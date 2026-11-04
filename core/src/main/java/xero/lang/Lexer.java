package xero.lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Lexer {
    private static final List<String> SYMBOLS = List.of(
            "==", "!=", ">=", "<=", "+=", "-=", "*=", "/=", "%=", "..",
            "{", "}", "(", ")", "[", "]", ",", ":", ".", "=", "@", ">", "<", "+", "-", "*", "/",
            "%", ";", "?");

    private static final Set<String> STRING_PREFIXES = Set.of("mm", "plain", "json", "legacy");

    public static final String TIME = "time";

    private static final Map<String, Double> UNITS = new LinkedHashMap<>();

    static {
        for (String u : List.of("t", "т", "тик")) UNITS.put(u, 50.0);
        for (String u : List.of("ms", "мс")) UNITS.put(u, 1.0);
        for (String u : List.of("s", "с", "сек")) UNITS.put(u, 1000.0);
        for (String u : List.of("m", "м", "мин")) UNITS.put(u, 60_000.0);
        for (String u : List.of("h", "ч", "час")) UNITS.put(u, 3_600_000.0);
        for (String u : List.of("d", "д")) UNITS.put(u, 86_400_000.0);
    }

    private final String text;
    private final int end;
    private final List<Diagnostic> diagnostics;
    private final List<Token> tokens = new ArrayList<>();
    private List<Comment> comments;
    private int pos;
    private boolean newline = true;

    private Lexer(String text, int from, int to, List<Diagnostic> diagnostics) {
        this.text = text;
        this.pos = from;
        this.end = to;
        this.diagnostics = diagnostics;
    }

    public static List<Token> tokens(String text, List<Diagnostic> diagnostics) {
        return tokens(text, 0, text.length(), diagnostics);
    }

    public static List<Token> tokens(String text, int from, int to, List<Diagnostic> diagnostics) {
        Lexer lexer = new Lexer(text, from, to, diagnostics);
        lexer.run();
        return lexer.tokens;
    }

    public record Comment(int start, int end) {}

    public static List<Token> tokens(String text, List<Diagnostic> diagnostics, List<Comment> comments) {
        Lexer lexer = new Lexer(text, 0, text.length(), diagnostics);
        lexer.comments = comments;
        lexer.run();
        return lexer.tokens;
    }

    private void run() {
        while (true) {
            skipSpace();
            if (pos >= end) {
                tokens.add(new Token(Token.Kind.EOF, "", null, end, end, true));
                return;
            }
            char c = text.charAt(pos);
            int start = pos;
            if (c == '"') { string(null, start); continue; }
            if (c == '`') { name(start); continue; }
            if (Character.isDigit(c)) { number(start); continue; }
            if (identStart(c)) {
                while (pos < end && identPart(text.charAt(pos))) pos++;
                String word = text.substring(start, pos);
                if (pos < end && text.charAt(pos) == '"' && STRING_PREFIXES.contains(word)) {
                    string(word, start);
                    continue;
                }
                add(Token.Kind.IDENT, word, null, start, null);
                continue;
            }
            String symbol = symbol();
            if (symbol != null) {
                pos += symbol.length();
                add(Token.Kind.SYMBOL, symbol, null, start, null);
                continue;
            }
            pos++;
            diagnostics.add(Diagnostic.error("непонятный символ «" + c + "»", start, pos));
        }
    }

    private void add(Token.Kind kind, String value, String prefix, int start, List<Object> parts) {
        tokens.add(new Token(kind, value, prefix, start, pos, newline, parts));
        newline = false;
    }

    private void skipSpace() {
        while (pos < end) {
            char c = text.charAt(pos);
            if (c == '\n') { newline = true; pos++; continue; }
            if (c == ' ' || c == '\t' || c == '\r' || c == '﻿') { pos++; continue; }
            if (c == '/' && pos + 1 < end && text.charAt(pos + 1) == '/') {
                int start = pos;
                while (pos < end && text.charAt(pos) != '\n') pos++;
                if (comments != null) comments.add(new Comment(start, pos));
                continue;
            }
            if (c == '/' && pos + 1 < end && text.charAt(pos + 1) == '*') {
                int start = pos;
                int close = text.indexOf("*/", pos + 2);
                if (close < 0 || close + 2 > end) {
                    diagnostics.add(Diagnostic.error("комментарий /* не закрыт", start, start + 2));
                    pos = end;
                    return;
                }
                for (int i = pos; i < close; i++) if (text.charAt(i) == '\n') newline = true;
                pos = close + 2;
                if (comments != null) comments.add(new Comment(start, pos));
                continue;
            }
            return;
        }
    }

    private String symbol() {
        for (String s : SYMBOLS) if (text.startsWith(s, pos) && pos + s.length() <= end) return s;
        return null;
    }

    private void digits() {
        while (pos < end && Character.isDigit(text.charAt(pos))) pos++;
        if (pos + 1 < end && text.charAt(pos) == '.' && Character.isDigit(text.charAt(pos + 1))) {
            pos++;
            while (pos < end && Character.isDigit(text.charAt(pos))) pos++;
        }
    }

    private int unitEnd(int at) {
        int i = at;
        while (i < end && Character.isLetter(text.charAt(i))) i++;
        return i > at && UNITS.containsKey(text.substring(at, i)) ? i : -1;
    }

    private void number(int start) {
        digits();
        int unit = unitEnd(pos);
        if (unit < 0) {
            add(Token.Kind.NUMBER, text.substring(start, pos), null, start, null);
            return;
        }
        pos = unit;
        while (pos < end && Character.isDigit(text.charAt(pos))) {
            int part = pos;
            digits();
            int next = unitEnd(pos);
            if (next < 0) {
                pos = part;
                break;
            }
            pos = next;
        }
        add(Token.Kind.NUMBER, text.substring(start, pos), TIME, start, null);
    }

    public static double millis(String literal) {
        double total = 0;
        int i = 0;
        while (i < literal.length()) {
            int from = i;
            while (i < literal.length() && !Character.isLetter(literal.charAt(i))) i++;
            int split = i;
            while (i < literal.length() && Character.isLetter(literal.charAt(i))) i++;
            total += Double.parseDouble(literal.substring(from, split)) * UNITS.get(literal.substring(split, i));
        }
        return total;
    }

    private void string(String prefix, int start) {
        pos++;
        StringBuilder literal = new StringBuilder();
        StringBuilder whole = new StringBuilder();
        List<Object> parts = new ArrayList<>();
        while (true) {
            if (pos >= end || text.charAt(pos) == '\n') {
                diagnostics.add(Diagnostic.error("строка не закрыта кавычкой", start, pos));
                break;
            }
            char c = text.charAt(pos++);
            if (c == '"') break;
            if (c == '$' && pos < end && text.charAt(pos) == '{') {
                int open = pos;
                int close = closingBrace(open);
                if (close < 0) {
                    diagnostics.add(Diagnostic.error("подстановка ${ не закрыта", open - 1, open + 1));
                    pos = end;
                    break;
                }
                if (!literal.isEmpty()) parts.add(literal.toString());
                literal.setLength(0);
                parts.add(new Token.Code(open + 1, close));
                whole.append(text, open - 1, close + 1);
                pos = close + 1;
                continue;
            }
            if (c != '\\') {
                literal.append(c);
                whole.append(c);
                continue;
            }
            if (pos >= end) continue;
            char e = text.charAt(pos++);
            switch (e) {
                case '"' -> { literal.append('"'); whole.append('"'); }
                case '\\' -> { literal.append('\\'); whole.append('\\'); }
                case '$' -> { literal.append('$'); whole.append('$'); }
                case 'u' -> {
                    if (pos + 4 <= end && text.substring(pos, pos + 4).matches("[0-9a-fA-F]{4}")) {
                        char u = (char) Integer.parseInt(text.substring(pos, pos + 4), 16);
                        literal.append(u);
                        whole.append(u);
                        pos += 4;
                    } else {
                        diagnostics.add(Diagnostic.error("после \\u нужны четыре шестнадцатеричные цифры", pos - 2, pos));
                    }
                }
                default -> {
                    literal.append('\\').append(e);
                    whole.append('\\').append(e);
                }
            }
        }
        if (!literal.isEmpty()) parts.add(literal.toString());
        boolean code = parts.stream().anyMatch(p -> p instanceof Token.Code);
        add(Token.Kind.STRING, code ? whole.toString() : (parts.isEmpty() ? "" : (String) parts.get(0)), prefix, start,
                code ? parts : null);
    }

    private int closingBrace(int open) {
        int depth = 0;
        int i = open;
        while (i < end) {
            char c = text.charAt(i);
            if (c == '\n') return -1;
            if (c == '"') {
                i++;
                while (i < end && text.charAt(i) != '"' && text.charAt(i) != '\n') {
                    if (text.charAt(i) == '\\') i++;
                    i++;
                }
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) return i;
            }
            i++;
        }
        return -1;
    }

    private void name(int start) {
        pos++;
        int close = text.indexOf('`', pos);
        int lineEnd = text.indexOf('\n', pos);
        if (close < 0 || close >= end || (lineEnd >= 0 && lineEnd < close)) {
            pos = lineEnd < 0 || lineEnd > end ? end : lineEnd;
            diagnostics.add(Diagnostic.error("имя в `…` не закрыто", start, pos));
            add(Token.Kind.NAME, text.substring(start + 1, pos), null, start, null);
            return;
        }
        String value = text.substring(pos, close);
        pos = close + 1;
        if (value.isEmpty()) diagnostics.add(Diagnostic.error("пустое имя в ``", start, pos));
        add(Token.Kind.NAME, value, null, start, null);
    }

    public static boolean identifier(String s) {
        if (s.isEmpty() || !identStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) if (!identPart(s.charAt(i))) return false;
        return true;
    }

    private static boolean identStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean identPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
