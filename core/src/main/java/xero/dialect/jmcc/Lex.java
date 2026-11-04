package xero.dialect.jmcc;

import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Lex {
    enum Kind { WORD, NUMBER, STRING, NAME, SNBT, LABEL, SYMBOL, EOF }

    record Code(int start, int end) {}

    record Ref(String name, int start, int end) {}

    record Tok(Kind kind, String text, String prefix, int start, int end, boolean newline, List<Object> parts) {
        boolean is(String symbol) { return kind == Kind.SYMBOL && text.equals(symbol); }

        boolean means(String meaning) { return kind == Kind.WORD && Words.means(text, meaning); }

        boolean name() { return kind == Kind.WORD || kind == Kind.NAME; }
    }

    private static final List<String> SYMBOLS = List.of(
            "..=", "::", "->", "=>", "==", "!=", ">=", "<=", "+=", "-=", "*=", "/=", "%=", "^=", "++", "--", "**", "&&", "||",
            "..", "{", "}", "(", ")", "[", "]", ",", ":", ".", "=", "@", ">", "<", "+", "-", "*", "/", "%", "^", ";", "?",
            "!", "&", "|", "$");

    private static final List<String> STRINGS = List.of("plain", "minimessage", "legacy", "json");
    private static final Map<String, String> STRING_WORDS = Map.of("простой", "plain", "устаревший", "legacy",
            "минисообщение", "minimessage", "джсон", "json", "mm", "minimessage");
    private static final Map<String, String> PARSING = Map.of("plain", "plain", "minimessage", "mm", "json", "json", "legacy", "legacy");
    private static final List<String> NAME_SCOPES = List.of("bracket", "inline", "local", "game", "save", "jmcc", "line");
    private static final Map<String, String> SCOPE_WORDS = Map.of("l", "local", "lo", "local", "li", "line",
            "игра", "game", "сохранение", "save", "локальный", "local", "строка", "line", "встраиваемый", "inline");
    private static final Set<String> SNBT_WORDS = Set.of("m", "n", "nbt", "minecraft_nbt");

    private final String text;
    private final int end;
    private final List<Diagnostic> diagnostics;
    private final List<Tok> tokens = new ArrayList<>();
    private int pos;
    private boolean newline = true;

    private Lex(String text, int from, int to, List<Diagnostic> diagnostics) {
        this.text = text;
        this.pos = from;
        this.end = to;
        this.diagnostics = diagnostics;
    }

    static List<Tok> tokens(String text, int from, int to, List<Diagnostic> diagnostics) {
        Lex lex = new Lex(text, from, to, diagnostics);
        lex.run();
        return lex.tokens;
    }

    private void run() {
        while (true) {
            skip();
            if (pos >= end) {
                tokens.add(new Tok(Kind.EOF, "", null, end, end, true, null));
                return;
            }
            char c = text.charAt(pos);
            int start = pos;
            if (c == '"' || c == '\'') {
                if (c == '\'' && label(start)) continue;
                string(c, null, start);
                continue;
            }
            if (c == '`') {
                name(null, start);
                continue;
            }
            if (digit(c) || c == '.' && pos + 1 < end && digit(text.charAt(pos + 1)) && !afterValue()) {
                number(start);
                continue;
            }
            if (wordStart(c)) {
                word(start);
                continue;
            }
            if (c == '%') {
                int stop = placeholderEnd(pos);
                if (stop > 0) {
                    pos = stop;
                    add(Kind.WORD, text.substring(start, stop), null, start, null);
                    continue;
                }
            }
            String symbol = symbol();
            if (symbol != null) {
                pos += symbol.length();
                add(Kind.SYMBOL, symbol, null, start, null);
                continue;
            }
            pos++;
            diagnostics.add(Diagnostic.error("непонятный символ «" + c + "»", start, pos));
        }
    }

    private void add(Kind kind, String value, String prefix, int start, List<Object> parts) {
        tokens.add(new Tok(kind, value, prefix, start, pos, newline, parts));
        newline = false;
    }

    private boolean afterValue() {
        if (tokens.isEmpty()) return false;
        Tok last = tokens.get(tokens.size() - 1);
        return last.end() == pos && (last.kind() != Kind.SYMBOL || last.is(")") || last.is("]"));
    }

    private void skip() {
        while (pos < end) {
            char c = text.charAt(pos);
            if (c == '\n') {
                newline = true;
                pos++;
            } else if (c == ' ' || c == '\t' || c == '\r' || c == '﻿') {
                pos++;
            } else if (c == '#' || c == '/' && pos + 1 < end && text.charAt(pos + 1) == '/') {
                while (pos < end && text.charAt(pos) != '\n') pos++;
            } else if (c == '/' && pos + 1 < end && text.charAt(pos + 1) == '*') {
                comment();
            } else if (c != '\\' || !lineJoin()) {
                return;
            }
        }
    }

    private boolean lineJoin() {
        int i = pos + 1;
        while (i < end && (text.charAt(i) == ' ' || text.charAt(i) == '\t' || text.charAt(i) == '\r')) i++;
        if (i >= end || text.charAt(i) != '\n') return false;
        pos = i + 1;
        return true;
    }

    private void comment() {
        int start = pos;
        int depth = 0;
        while (pos < end) {
            if (text.startsWith("/*", pos)) {
                depth++;
                pos += 2;
            } else if (text.startsWith("*/", pos)) {
                pos += 2;
                if (--depth == 0) return;
            } else {
                if (text.charAt(pos) == '\n') newline = true;
                pos++;
            }
        }
        diagnostics.add(Diagnostic.error("комментарий /* не закрыт", start, Math.min(end, start + 2)));
    }

    private String symbol() {
        for (String s : SYMBOLS) if (pos + s.length() <= end && text.startsWith(s, pos)) return s;
        return null;
    }

    private int placeholderEnd(int at) {
        int i = at + 1;
        if (i >= end || !wordStart(text.charAt(i))) return -1;
        while (i < end && wordPart(text.charAt(i))) i++;
        if (i >= end || text.charAt(i) != '%') return -1;
        i++;
        while (i < end) {
            char c = text.charAt(i);
            if (wordPart(c)) {
                i++;
            } else if (c == '%') {
                int next = placeholderEnd(i);
                if (next < 0) break;
                i = next;
            } else {
                break;
            }
        }
        return i;
    }

    private boolean label(int start) {
        int i = start + 1;
        if (i >= end || !wordStart(text.charAt(i))) return false;
        while (i < end && wordPart(text.charAt(i))) i++;
        Tok last = tokens.isEmpty() ? null : tokens.get(tokens.size() - 1);
        boolean jump = !newline && last != null && (last.means("break") || last.means("continue"));
        boolean head = i < end && text.charAt(i) == ':' && (i + 1 >= end || text.charAt(i + 1) != ':') && headWord(i + 1);
        if (!jump && !head) return false;
        if (i < end && text.charAt(i) == '\'') return false;
        String name = text.substring(start + 1, i);
        pos = head ? i + 1 : i;
        add(Kind.LABEL, name, null, start, null);
        return true;
    }

    private boolean headWord(int at) {
        int i = at;
        while (i < end && " \t\r\n".indexOf(text.charAt(i)) >= 0) i++;
        int from = i;
        while (i < end && wordPart(text.charAt(i))) i++;
        String word = text.substring(from, i);
        return Words.means(word, "while") || Words.means(word, "for") || word.equals("repeat");
    }

    private void word(int start) {
        while (pos < end && wordPart(text.charAt(pos))) pos++;
        String word = text.substring(start, pos);
        char next = pos < end ? text.charAt(pos) : '\0';
        if (next == '"' || next == '\'') {
            String parsing = stringPrefix(word);
            if (parsing != null) {
                string(next, PARSING.get(parsing), start);
                return;
            }
        }
        if (next == '`') {
            String scope = nameScope(word);
            if (scope != null) {
                name(scope, start);
                return;
            }
        }
        if (next == '{' && SNBT_WORDS.contains(word) && !afterDeclaration()) {
            snbt(start);
            return;
        }
        add(Kind.WORD, word, null, start, null);
    }

    private boolean afterDeclaration() {
        if (tokens.isEmpty()) return false;
        Tok last = tokens.get(tokens.size() - 1);
        return last.means("function") || last.means("process") || last.means("class");
    }

    private static String stringPrefix(String word) {
        String known = STRING_WORDS.get(word);
        if (known != null) return known;
        return unique(word, STRINGS);
    }

    private static String nameScope(String word) {
        String known = SCOPE_WORDS.get(word);
        if (known != null) return known;
        return unique(word, NAME_SCOPES);
    }

    private static String unique(String word, List<String> options) {
        String found = null;
        for (String s : options) {
            if (s.equals(word)) return s;
            if (s.startsWith(word)) {
                if (found != null) return null;
                found = s;
            }
        }
        return found;
    }

    private void snbt(int start) {
        int open = pos;
        int depth = 0;
        char quote = 0;
        for (int i = open; i < end; i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                pos = i + 1;
                add(Kind.SNBT, text.substring(open, pos), null, start, null);
                return;
            }
        }
        pos = end;
        diagnostics.add(Diagnostic.error("данные m{…} не закрыты", start, Math.min(end, start + 2)));
        add(Kind.SNBT, "{}", null, start, null);
    }

    private void number(int start) {
        StringBuilder out = new StringBuilder();
        if (text.charAt(pos) == '.') out.append('0');
        digits(out);
        if (pos + 1 < end && text.charAt(pos) == '.' && digit(text.charAt(pos + 1))) {
            out.append('.');
            pos++;
            digits(out);
        }
        if (pos < end && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
            int i = pos + 1;
            if (i < end && (text.charAt(i) == '+' || text.charAt(i) == '-')) i++;
            if (i < end && digit(text.charAt(i))) {
                out.append(text, pos, i);
                pos = i;
                digits(out);
            }
        }
        add(Kind.NUMBER, out.toString(), null, start, null);
    }

    private void digits(StringBuilder out) {
        while (pos < end && (digit(text.charAt(pos)) || text.charAt(pos) == '_')) {
            if (text.charAt(pos) != '_') out.append(text.charAt(pos));
            pos++;
        }
    }

    private int lineEnd(int from) {
        int line = text.indexOf('\n', from);
        return line < 0 || line > end ? end : line;
    }

    private void string(char quote, String prefix, int start) {
        int open = pos;
        pos++;
        List<Object> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        if (!body(quote, literal, parts)) {
            int stop = lineEnd(open);
            diagnostics.add(Diagnostic.error("строка не закрыта кавычкой", start, Math.max(start + 1, stop)));
            parts.clear();
            literal.setLength(0);
            new Lex(text, open + 1, stop, new ArrayList<>()).body(quote, literal, parts);
            pos = stop;
        }
        finish(Kind.STRING, prefix, start, literal, parts);
    }

    private void finish(Kind kind, String prefix, int start, StringBuilder literal, List<Object> parts) {
        if (parts.isEmpty()) {
            add(kind, literal.toString(), prefix, start, null);
            return;
        }
        if (literal.length() > 0) parts.add(literal.toString());
        add(kind, "", prefix, start, parts);
    }

    private boolean body(char quote, StringBuilder literal, List<Object> parts) {
        while (pos < end) {
            char c = text.charAt(pos);
            if (c == quote) {
                pos++;
                return true;
            }
            if (c == '\n') {
                if (quote == '`') return false;
                literal.append("\\n");
                pos++;
            } else if (c == '\r') {
                pos++;
            } else if (c == '\\') {
                escape(quote, literal);
            } else if (c == '$') {
                dollar(literal, parts);
            } else {
                literal.append(c);
                pos++;
            }
        }
        return false;
    }

    private void escape(char quote, StringBuilder literal) {
        pos++;
        if (pos >= end) {
            literal.append('\\');
            return;
        }
        char e = text.charAt(pos++);
        if (e == quote || e == '\\' || e == '$' || e == '"' || e == '\'' || e == '`') {
            literal.append(e);
        } else if (e == 'u' && pos + 4 <= end && hex(pos)) {
            literal.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
            pos += 4;
        } else if (e == '\r') {
            if (pos < end && text.charAt(pos) == '\n') pos++;
        } else if (e != '\n') {
            literal.append('\\').append(e);
        }
    }

    private boolean hex(int at) {
        for (int i = at; i < at + 4; i++) if (Character.digit(text.charAt(i), 16) < 0) return false;
        return true;
    }

    private void dollar(StringBuilder literal, List<Object> parts) {
        int at = pos;
        char next = at + 1 < end ? text.charAt(at + 1) : '\0';
        if (next == '{') {
            int close = closing(at + 1);
            if (close < 0) {
                diagnostics.add(Diagnostic.error("подстановка ${ не закрыта", at, at + 2));
                literal.append("${");
                pos = at + 2;
                return;
            }
            flush(literal, parts);
            parts.add(new Code(at + 2, close));
            pos = close + 1;
            return;
        }
        int stop = -1;
        if (next == '%') {
            stop = placeholderEnd(at + 1);
        } else if (wordStart(next)) {
            stop = at + 1;
            while (stop < end && wordPart(text.charAt(stop))) stop++;
        }
        if (stop < 0) {
            literal.append('$');
            pos = at + 1;
            return;
        }
        flush(literal, parts);
        parts.add(new Ref(text.substring(at + 1, stop), at, stop));
        pos = stop;
    }

    private static void flush(StringBuilder literal, List<Object> parts) {
        if (literal.length() == 0) return;
        parts.add(literal.toString());
        literal.setLength(0);
    }

    private int closing(int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < end; i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'' || c == '`') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private void name(String scope, int start) {
        int open = pos;
        pos++;
        List<Object> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        if (!body('`', literal, parts)) {
            int stop = lineEnd(open);
            diagnostics.add(Diagnostic.error("имя в `…` не закрыто", start, Math.max(start + 1, stop)));
            parts.clear();
            literal.setLength(0);
            literal.append(text, Math.min(open + 1, stop), stop);
            pos = stop;
        }
        finish(Kind.NAME, scope, start, literal, parts);
    }

    static boolean wordStart(char c) { return c == '_' || Character.isLetter(c); }

    static boolean wordPart(char c) { return c == '_' || Character.isLetterOrDigit(c); }

    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
}
