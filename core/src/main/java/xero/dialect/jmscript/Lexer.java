package xero.dialect.jmscript;

import xero.lang.Diagnostic;
import xero.lang.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class Lexer {
    private static final List<String> SYMBOLS = List.of(
            "..<", "..=", "==", "!=", ">=", "<=", "+=", "-=", "*=", "/=", "%=", "&&", "||", "->", ":=", "::",
            "{", "}", "(", ")", "[", "]", ",", ":", ";", ".", "=", "@", ">", "<", "+", "-", "*", "/", "%", "!", "?");

    private static final Set<String> STRING_PREFIXES = Set.of("mm", "plain", "json", "legacy");

    private final String text;
    private final int end;
    private final List<Diagnostic> diagnostics;
    private final List<Token> tokens = new ArrayList<>();
    private int pos;
    private boolean newline = true;

    private Lexer(String text, int from, int to, List<Diagnostic> diagnostics) {
        this.text = text;
        this.pos = from;
        this.end = to;
        this.diagnostics = diagnostics;
    }

    static List<Token> tokens(String text, List<Diagnostic> diagnostics) {
        return tokens(text, 0, text.length(), diagnostics);
    }

    static List<Token> tokens(String text, int from, int to, List<Diagnostic> diagnostics) {
        Lexer lexer = new Lexer(text, from, to, diagnostics);
        lexer.run();
        return lexer.tokens;
    }

    static boolean identifier(String s) {
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

    private void run() {
        while (true) {
            skipSpace();
            if (pos >= end) {
                tokens.add(new Token(Token.Kind.EOF, "", null, end, end, true));
                return;
            }
            char c = text.charAt(pos);
            int start = pos;
            if (c == '"' || c == '\'') {
                string(null, start);
            } else if (c == '`') {
                name(start);
            } else if (Character.isDigit(c)) {
                number(start);
            } else if (identStart(c)) {
                word(start);
            } else if (text.startsWith("..", pos) && !text.startsWith("..<", pos) && !text.startsWith("..=", pos)) {
                pos += 2;
                diagnostics.add(Diagnostic.error("диапазон пишется ..= (с концом) или ..< (без конца)", start, pos));
                add(Token.Kind.SYMBOL, "..=", null, start, null);
            } else {
                symbol(start, c);
            }
        }
    }

    private void word(int start) {
        while (pos < end && identPart(text.charAt(pos))) pos++;
        String word = text.substring(start, pos);
        if (pos < end && (text.charAt(pos) == '"' || text.charAt(pos) == '\'') && STRING_PREFIXES.contains(word)) {
            string(word, start);
            return;
        }
        add(Token.Kind.IDENT, word, null, start, null);
    }

    private void symbol(int start, char c) {
        for (String s : SYMBOLS) {
            if (!text.startsWith(s, pos) || pos + s.length() > end) continue;
            pos += s.length();
            add(Token.Kind.SYMBOL, s, null, start, null);
            return;
        }
        pos += Character.isHighSurrogate(c) && pos + 1 < end ? 2 : 1;
        diagnostics.add(Diagnostic.error("непонятный символ «" + text.substring(start, pos) + "»", start, pos));
    }

    private void add(Token.Kind kind, String value, String prefix, int start, List<Object> parts) {
        tokens.add(new Token(kind, value, prefix, start, pos, newline, parts));
        newline = false;
    }

    private void skipSpace() {
        while (pos < end) {
            char c = text.charAt(pos);
            if (c == '\n') {
                newline = true;
                pos++;
            } else if (c == ' ' || c == '\t' || c == '\r' || c == '﻿') {
                pos++;
            } else if (text.startsWith("//", pos) || text.startsWith("#+", pos)) {
                while (pos < end && text.charAt(pos) != '\n') pos++;
            } else if (text.startsWith("/*", pos)) {
                int close = text.indexOf("*/", pos + 2);
                if (close < 0 || close + 2 > end) {
                    diagnostics.add(Diagnostic.error("комментарий /* не закрыт", pos, Math.min(end, pos + 2)));
                    pos = end;
                    return;
                }
                for (int i = pos; i < close; i++) if (text.charAt(i) == '\n') newline = true;
                pos = close + 2;
            } else {
                return;
            }
        }
    }

    private void number(int start) {
        while (pos < end && Character.isDigit(text.charAt(pos))) pos++;
        if (pos + 1 < end && text.charAt(pos) == '.' && Character.isDigit(text.charAt(pos + 1))) {
            pos++;
            while (pos < end && Character.isDigit(text.charAt(pos))) pos++;
        }
        add(Token.Kind.NUMBER, text.substring(start, pos), null, start, null);
    }

    private void string(String prefix, int start) {
        char quote = text.charAt(pos++);
        StringBuilder literal = new StringBuilder();
        StringBuilder whole = new StringBuilder();
        List<Object> parts = new ArrayList<>();
        while (true) {
            if (pos >= end || text.charAt(pos) == '\n') {
                diagnostics.add(Diagnostic.error("строка не закрыта кавычкой", start, Math.max(pos, start + 1)));
                break;
            }
            char c = text.charAt(pos++);
            if (c == quote) break;
            if (c == '$' && pos < end && text.charAt(pos) == '{') {
                int open = pos;
                int close = closingBrace(open);
                if (close < 0) {
                    diagnostics.add(Diagnostic.error("подстановка ${ не закрыта", open - 1, open + 1));
                    pos = lineEnd(open);
                    break;
                }
                if (!literal.isEmpty()) parts.add(literal.toString());
                literal.setLength(0);
                parts.add(new Token.Code(open + 1, close));
                whole.append(text, open - 1, close + 1);
                pos = close + 1;
            } else if (c != '\\') {
                literal.append(c);
                whole.append(c);
            } else if (pos < end) {
                escape(literal, whole);
            }
        }
        if (!literal.isEmpty()) parts.add(literal.toString());
        boolean code = false;
        for (Object p : parts) if (p instanceof Token.Code) code = true;
        add(Token.Kind.STRING, code ? whole.toString() : parts.isEmpty() ? "" : (String) parts.get(0), prefix, start,
                code ? parts : null);
    }

    private void escape(StringBuilder literal, StringBuilder whole) {
        char e = text.charAt(pos++);
        if (e == '"' || e == '\'' || e == '\\' || e == '$') {
            literal.append(e);
            whole.append(e);
        } else if (e == 'u') {
            int code = hex(pos, pos + 4);
            if (code < 0) {
                diagnostics.add(Diagnostic.error("после \\u нужны четыре шестнадцатеричные цифры", pos - 2, pos));
                return;
            }
            literal.append((char) code);
            whole.append((char) code);
            pos += 4;
        } else {
            literal.append('\\').append(e);
            whole.append('\\').append(e);
        }
    }

    private int hex(int from, int to) {
        if (to > end) return -1;
        int value = 0;
        for (int i = from; i < to; i++) {
            int d = Character.digit(text.charAt(i), 16);
            if (d < 0) return -1;
            value = value * 16 + d;
        }
        return value;
    }

    private int lineEnd(int from) {
        int nl = text.indexOf('\n', from);
        return nl < 0 || nl > end ? end : nl;
    }

    private int closingBrace(int open) {
        int depth = 0;
        int i = open;
        while (i < end) {
            char c = text.charAt(i);
            if (c == '\n') return -1;
            if (c == '"' || c == '\'') {
                i++;
                while (i < end && text.charAt(i) != c && text.charAt(i) != '\n') {
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
        int stop = lineEnd(pos);
        if (close < 0 || close >= stop) {
            pos = stop;
            diagnostics.add(Diagnostic.error("имя в `…` не закрыто", start, Math.max(pos, start + 1)));
            add(Token.Kind.NAME, text.substring(start + 1, pos), null, start, null);
            return;
        }
        String value = text.substring(pos, close);
        pos = close + 1;
        if (value.isEmpty()) diagnostics.add(Diagnostic.error("пустое имя в ``", start, pos));
        add(Token.Kind.NAME, value, null, start, null);
    }
}
