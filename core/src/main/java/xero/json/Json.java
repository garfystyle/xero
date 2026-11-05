package xero.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    public record Num(String text) {
        public double value() { return Double.parseDouble(text); }
        public int intValue() { return (int) value(); }
        @Override public String toString() { return text; }
    }

    public static final class Error extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int position;
        Error(String message, int position) {
            super(message + " (символ " + position + ")");
            this.position = position;
        }
    }

    public static Object parse(String text) {
        Reader reader = new Reader(text);
        reader.space();
        Object value = reader.value();
        reader.space();
        if (reader.pos < text.length()) throw new Error("лишнее после конца JSON", reader.pos);
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IllegalArgumentException("ожидался объект JSON, а не " + kind(value));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        if (value instanceof List<?> list) return (List<Object>) list;
        throw new IllegalArgumentException("ожидался массив JSON, а не " + kind(value));
    }

    public static String string(Object value) {
        if (value instanceof String s) return s;
        throw new IllegalArgumentException("ожидалась строка JSON, а не " + kind(value));
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, -1, 0);
        return out.toString();
    }

    public static String pretty(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 2, 0);
        return out.toString();
    }

    private static String kind(Object value) {
        if (value == null) return "null";
        if (value instanceof Map) return "объект";
        if (value instanceof List) return "массив";
        if (value instanceof String) return "строка";
        if (value instanceof Num) return "число";
        if (value instanceof Boolean) return "логическое";
        return value.getClass().getSimpleName();
    }

    private static void write(StringBuilder out, Object value, int indent, int depth) {
        switch (value) {
            case null -> out.append("null");
            case String s -> quote(out, s);
            case Num n -> out.append(n.text());
            case Boolean b -> out.append(b);
            case Integer i -> out.append(i);
            case Long l -> out.append(l);
            case Double d -> out.append(d);
            case Map<?, ?> map -> {
                if (map.isEmpty()) { out.append("{}"); return; }
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    newline(out, indent, depth + 1);
                    quote(out, String.valueOf(e.getKey()));
                    out.append(indent < 0 ? ":" : ": ");
                    write(out, e.getValue(), indent, depth + 1);
                }
                newline(out, indent, depth);
                out.append('}');
            }
            case List<?> list -> {
                if (list.isEmpty()) { out.append("[]"); return; }
                out.append('[');
                boolean first = true;
                for (Object item : list) {
                    if (!first) out.append(',');
                    first = false;
                    newline(out, indent, depth + 1);
                    write(out, item, indent, depth + 1);
                }
                newline(out, indent, depth);
                out.append(']');
            }
            default -> throw new IllegalArgumentException("не JSON: " + value.getClass().getName());
        }
    }

    private static void newline(StringBuilder out, int indent, int depth) {
        if (indent < 0) return;
        out.append('\n');
        out.append(" ".repeat(indent * depth));
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static final class Reader {
        private final String text;
        private int pos;

        Reader(String text) { this.text = text; }

        void space() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t' && c != '﻿') return;
                pos++;
            }
        }

        Object value() {
            if (pos >= text.length()) throw new Error("JSON оборвался", pos);
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                case 'I', 'N' -> notFinite();
                default -> {
                    if (c == '-' && text.startsWith("-Infinity", pos)) yield notFinite();
                    if (c == '-' || (c >= '0' && c <= '9')) yield number();
                    throw new Error("неожиданный символ «" + c + "»", pos);
                }
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            space();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                space();
                if (peek() != '"') throw new Error("ожидалось имя поля в кавычках", pos);
                String key = string();
                space();
                expect(':');
                space();
                map.put(key, value());
                space();
                char c = next();
                if (c == '}') return map;
                if (c != ',') throw new Error("ожидалась «,» или «}»", pos - 1);
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++;
            space();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                space();
                list.add(value());
                space();
                char c = next();
                if (c == ']') return list;
                if (c != ',') throw new Error("ожидалась «,» или «]»", pos - 1);
            }
        }

        private String string() {
            int start = pos;
            pos++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= text.length()) throw new Error("строка не закрыта", start);
                char c = text.charAt(pos++);
                if (c == '"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                if (pos >= text.length()) throw new Error("строка не закрыта", start);
                char e = text.charAt(pos++);
                switch (e) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) throw new Error("обрывок \\u", pos);
                        try {
                            out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new Error("плохой \\u", pos);
                        }
                        pos += 4;
                    }
                    default -> throw new Error("неизвестное экранирование \\" + e, pos - 1);
                }
            }
        }

        private Num number() {
            int start = pos;
            if (peek() == '-') pos++;
            digits(start);
            if (peek() == '.') { pos++; digits(start); }
            if (peek() == 'e' || peek() == 'E') {
                pos++;
                if (peek() == '+' || peek() == '-') pos++;
                digits(start);
            }
            return new Num(text.substring(start, pos));
        }

        private Num notFinite() {
            for (String word : List.of("-Infinity", "Infinity", "NaN")) {
                if (!text.startsWith(word, pos)) continue;
                pos += word.length();
                return new Num(word);
            }
            throw new Error("неожиданный символ «" + peek() + "»", pos);
        }

        private void digits(int start) {
            int from = pos;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            if (pos == from) throw new Error("плохое число", start);
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, pos)) throw new Error("ожидалось " + word, pos);
            pos += word.length();
            return value;
        }

        private char peek() { return pos < text.length() ? text.charAt(pos) : '\0'; }

        private char next() {
            if (pos >= text.length()) throw new Error("JSON оборвался", pos);
            return text.charAt(pos++);
        }

        private void expect(char c) {
            if (next() != c) throw new Error("ожидалось «" + c + "»", pos - 1);
        }
    }

    private Json() {}
}
