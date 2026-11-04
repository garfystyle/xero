package xero.dialect.justice;

import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.List;

final class Lexer {
    enum Kind { WORD, NAME, STRING, NUMBER, PLACEHOLDER, COLOR, ENUM, SYMBOL, EOF }

    record Token(Kind kind, String text, int start, int end) {
        boolean is(String symbol) { return kind == Kind.SYMBOL && text.equals(symbol); }

        boolean word(String w) { return kind == Kind.WORD && text.equals(w); }

        boolean named() { return kind == Kind.WORD || kind == Kind.NAME; }
    }

    private static final String SYMBOLS = "{}()<>,=;@";

    private final String text;
    private final List<Diagnostic> diagnostics;
    private int pos;
    private Token pending;

    Lexer(String text, List<Diagnostic> diagnostics) {
        this.text = text;
        this.diagnostics = diagnostics;
    }

    static boolean wordStart(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_';
    }

    static boolean wordPart(char c) {
        return wordStart(c) || c >= '0' && c <= '9';
    }

    static boolean word(String s) {
        if (s.isEmpty() || !wordStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) if (!wordPart(s.charAt(i))) return false;
        return true;
    }

    Token peek() {
        if (pending == null) pending = read(diagnostics);
        return pending;
    }

    Token next() {
        Token t = peek();
        pending = null;
        return t;
    }

    Token after() {
        Token first = peek();
        int saved = pos;
        Token second = read(new ArrayList<>());
        pos = saved;
        pending = first;
        return second;
    }

    String compound() {
        Token open = peek();
        pending = null;
        int depth = 0;
        int i = open.start();
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                i++;
                while (i < text.length() && text.charAt(i) != c) {
                    if (text.charAt(i) == '\\') i++;
                    i++;
                }
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                pos = i + 1;
                return text.substring(open.start(), pos);
            }
            i++;
        }
        pos = text.length();
        return null;
    }

    private Token read(List<Diagnostic> problems) {
        while (true) {
            skip(problems);
            if (pos >= text.length()) return new Token(Kind.EOF, "", text.length(), text.length());
            char c = text.charAt(pos);
            int start = pos;
            if (SYMBOLS.indexOf(c) >= 0) {
                pos++;
                return new Token(Kind.SYMBOL, String.valueOf(c), start, pos);
            }
            if (wordStart(c)) {
                while (pos < text.length() && wordPart(text.charAt(pos))) pos++;
                return new Token(Kind.WORD, text.substring(start, pos), start, pos);
            }
            if (c >= '0' && c <= '9' || c == '-' || c == '+' || c == '.') return number(start);
            if (c == '"') return new Token(Kind.STRING, enclosed('"', "строка не закрыта", problems), start, pos);
            if (c == '`') return new Token(Kind.NAME, enclosed('`', "имя в `…` не закрыто", problems), start, pos);
            if (c == '\'') return new Token(Kind.ENUM, enclosed('\'', "маркер не закрыт", problems), start, pos);
            if (c == '%') return placeholder(start, problems);
            if (c == '#') return color(start, problems);
            pos += Character.isHighSurrogate(c) && pos + 1 < text.length() ? 2 : 1;
            problems.add(Diagnostic.error("непонятный символ «" + text.substring(start, pos) + "»", start, pos));
        }
    }

    private void skip(List<Diagnostic> problems) {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '﻿') {
                pos++;
            } else if (text.startsWith("//", pos)) {
                while (pos < text.length() && text.charAt(pos) != '\n') pos++;
            } else if (text.startsWith("/*", pos)) {
                int close = text.indexOf("*/", pos + 2);
                if (close < 0) {
                    problems.add(Diagnostic.error("комментарий /* не закрыт", pos, pos + 2));
                    pos = text.length();
                    return;
                }
                pos = close + 2;
            } else {
                return;
            }
        }
    }

    private Token number(int start) {
        if (text.charAt(pos) == '-' || text.charAt(pos) == '+') pos++;
        boolean dot = false, exponent = false;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '.' && !dot && !exponent) {
                dot = true;
            } else if ((c == 'e' || c == 'E') && !exponent) {
                exponent = true;
                if (pos + 1 < text.length() && (text.charAt(pos + 1) == '-' || text.charAt(pos + 1) == '+')) pos++;
            } else if (c < '0' || c > '9') {
                break;
            }
            pos++;
        }
        return new Token(Kind.NUMBER, text.substring(start, pos), start, pos);
    }

    private String enclosed(char close, String unclosed, List<Diagnostic> problems) {
        int start = pos++;
        StringBuilder out = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == close) {
                pos++;
                return out.toString();
            }
            if (c == '\n') break;
            pos++;
            if (c != '\\') {
                out.append(c);
            } else if (pos < text.length()) {
                escape(out, close, problems);
            }
        }
        problems.add(Diagnostic.error(unclosed, start, Math.max(pos, start + 1)));
        return out.toString();
    }

    private void escape(StringBuilder out, char close, List<Diagnostic> problems) {
        int at = pos - 1;
        char e = text.charAt(pos++);
        switch (e) {
            case '\n' -> { }
            case 'n' -> out.append('\n');
            case 't' -> out.append('\t');
            case 'r' -> out.append('\r');
            case 'u' -> {
                int code = hex(pos, pos + 4);
                if (code < 0) {
                    problems.add(Diagnostic.error("после \\u нужны четыре шестнадцатеричные цифры", at, pos));
                } else {
                    out.append((char) code);
                    pos += 4;
                }
            }
            default -> {
                if (e == close || e == '\\') out.append(e);
                else problems.add(Diagnostic.error("неизвестное экранирование \\" + e, at, pos));
            }
        }
    }

    private int hex(int from, int to) {
        if (to > text.length()) return -1;
        int value = 0;
        for (int i = from; i < to; i++) {
            int d = Character.digit(text.charAt(i), 16);
            if (d < 0) return -1;
            value = value * 16 + d;
        }
        return value;
    }

    private Token placeholder(int start, List<Diagnostic> problems) {
        int depth = 0;
        pos++;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '\n') break;
            if (c == '(') depth++;
            if (c == ')') depth--;
            pos++;
            if (depth < 0) break;
            if ((c == ')' || c == '%') && depth == 0) return new Token(Kind.PLACEHOLDER, text.substring(start, pos), start, pos);
        }
        problems.add(Diagnostic.error("плейсхолдер не закрыт", start, Math.max(pos, start + 1)));
        return new Token(Kind.PLACEHOLDER, text.substring(start, pos), start, pos);
    }

    private Token color(int start, List<Diagnostic> problems) {
        pos++;
        while (pos < text.length() && Character.digit(text.charAt(pos), 16) >= 0) pos++;
        if (pos - start != 7) problems.add(Diagnostic.error("цвет пишется #RRGGBB", start, Math.max(pos, start + 1)));
        return new Token(Kind.COLOR, text.substring(start, pos), start, pos);
    }
}
