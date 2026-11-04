package xero.compile;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Nbt {
    public static final byte END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6,
            BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    public sealed interface Tag permits Num, Text, Array, ListTag, Compound {
        byte type();
    }

    public record Num(byte type, long bits) implements Tag {
        public static Num ofInt(int v) { return new Num(INT, v); }
        public static Num ofByte(int v) { return new Num(BYTE, (byte) v); }
        public static Num ofFloat(float v) { return new Num(FLOAT, Float.floatToRawIntBits(v)); }
        public static Num ofDouble(double v) { return new Num(DOUBLE, Double.doubleToRawLongBits(v)); }
        public float asFloat() { return Float.intBitsToFloat((int) bits); }
        public double asDouble() { return Double.longBitsToDouble(bits); }
    }

    public record Text(String value) implements Tag {
        public byte type() { return STRING; }
    }

    public record Array(byte type, long[] values) implements Tag {}

    public record ListTag(byte element, List<Tag> items) implements Tag {
        public byte type() { return LIST; }
    }

    public record Compound(Map<String, Tag> entries) implements Tag {
        public Compound() { this(new LinkedHashMap<>()); }
        public byte type() { return COMPOUND; }
        public Compound put(String key, Tag value) {
            entries.put(key, value);
            return this;
        }
        public Tag get(String key) { return entries.get(key); }
    }

    public static final class Error extends Exception {
        private static final long serialVersionUID = 1L;
        public final int offset;

        Error(String message, int offset) {
            super(message, null, false, false);
            this.offset = offset;
        }
    }

    public static boolean same(Tag a, Tag b) {
        return Arrays.equals(bytes(a), bytes(b));
    }

    public static byte[] bytes(Tag t) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, t);
        return out.toByteArray();
    }

    public static byte[] root(Compound c) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(COMPOUND);
        utf(out, "");
        write(out, c);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, Tag t) {
        switch (t) {
            case Num n -> {
                int size = switch (n.type()) {
                    case BYTE -> 1;
                    case SHORT -> 2;
                    case INT, FLOAT -> 4;
                    default -> 8;
                };
                for (int i = size - 1; i >= 0; i--) out.write((int) (n.bits() >>> (8 * i)) & 0xff);
            }
            case Text s -> utf(out, s.value());
            case Array a -> {
                i32(out, a.values().length);
                int size = a.type() == BYTE_ARRAY ? 1 : a.type() == INT_ARRAY ? 4 : 8;
                for (long v : a.values()) for (int i = size - 1; i >= 0; i--) out.write((int) (v >>> (8 * i)) & 0xff);
            }
            case ListTag l -> {
                out.write(l.items().isEmpty() && l.element() == END ? END : l.element());
                i32(out, l.items().size());
                for (Tag item : l.items()) write(out, item);
            }
            case Compound c -> {
                for (Map.Entry<String, Tag> e : c.entries().entrySet()) {
                    out.write(e.getValue().type());
                    utf(out, e.getKey());
                    write(out, e.getValue());
                }
                out.write(END);
            }
        }
    }

    private static void i32(ByteArrayOutputStream out, int v) {
        for (int i = 3; i >= 0; i--) out.write((v >>> (8 * i)) & 0xff);
    }

    private static void utf(ByteArrayOutputStream out, String s) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 1 && c <= 0x7f) {
                body.write(c);
            } else if (c <= 0x7ff) {
                body.write(0xc0 | (c >> 6));
                body.write(0x80 | (c & 0x3f));
            } else {
                body.write(0xe0 | (c >> 12));
                body.write(0x80 | ((c >> 6) & 0x3f));
                body.write(0x80 | (c & 0x3f));
            }
        }
        if (body.size() > 0xffff) throw new IllegalArgumentException("строка длиннее 65535 байт");
        out.write(body.size() >> 8);
        out.write(body.size() & 0xff);
        out.write(body.toByteArray(), 0, body.size());
    }

    public static Compound readRoot(byte[] data) {
        Reader r = new Reader(data);
        if (r.u8() != COMPOUND) throw new IllegalArgumentException("не compound");
        r.utf();
        Compound c = (Compound) r.tag(COMPOUND, 0);
        if (r.pos != data.length) throw new IllegalArgumentException("лишние байты");
        return c;
    }

    private static final class Reader {
        final byte[] data;
        int pos;

        Reader(byte[] data) { this.data = data; }

        int u8() {
            if (pos >= data.length) throw new IllegalArgumentException("NBT оборван");
            return data[pos++] & 0xff;
        }

        long number(int size) {
            long v = 0;
            for (int i = 0; i < size; i++) v = (v << 8) | u8();
            int shift = 64 - 8 * size;
            return size == 8 ? v : (v << shift) >> shift;
        }

        String utf() {
            int length = (int) number(2) & 0xffff;
            if (pos + length > data.length) throw new IllegalArgumentException("NBT оборван");
            StringBuilder out = new StringBuilder();
            int end = pos + length;
            while (pos < end) {
                int a = u8();
                if (a < 0x80) out.append((char) a);
                else if ((a & 0xe0) == 0xc0) out.append((char) (((a & 0x1f) << 6) | (u8() & 0x3f)));
                else if ((a & 0xf0) == 0xe0) out.append((char) (((a & 0x0f) << 12) | ((u8() & 0x3f) << 6) | (u8() & 0x3f)));
                else throw new IllegalArgumentException("битая строка NBT");
            }
            if (pos != end) throw new IllegalArgumentException("битая строка NBT");
            return out.toString();
        }

        Tag tag(int type, int depth) {
            if (depth > 512) throw new IllegalArgumentException("NBT слишком глубокий");
            return switch (type) {
                case BYTE -> new Num(BYTE, number(1));
                case SHORT -> new Num(SHORT, number(2));
                case INT -> new Num(INT, number(4));
                case LONG -> new Num(LONG, number(8));
                case FLOAT -> new Num(FLOAT, number(4));
                case DOUBLE -> new Num(DOUBLE, number(8));
                case STRING -> new Text(utf());
                case BYTE_ARRAY, INT_ARRAY, LONG_ARRAY -> {
                    int n = (int) number(4);
                    int size = type == BYTE_ARRAY ? 1 : type == INT_ARRAY ? 4 : 8;
                    if (n < 0 || (long) n * size > data.length - pos) throw new IllegalArgumentException("NBT оборван");
                    long[] values = new long[n];
                    for (int i = 0; i < n; i++) values[i] = number(size);
                    yield new Array((byte) type, values);
                }
                case LIST -> {
                    int element = u8();
                    int n = (int) number(4);
                    if (n < 0 || n > data.length - pos) throw new IllegalArgumentException("NBT оборван");
                    List<Tag> items = new ArrayList<>();
                    for (int i = 0; i < n; i++) items.add(tag(element, depth + 1));
                    yield new ListTag((byte) element, items);
                }
                case COMPOUND -> {
                    Compound c = new Compound();
                    while (true) {
                        int t = u8();
                        if (t == END) break;
                        String key = utf();
                        if (c.entries().containsKey(key)) throw new IllegalArgumentException("ключ повторяется");
                        c.put(key, tag(t, depth + 1));
                    }
                    yield c;
                }
                default -> throw new IllegalArgumentException("неизвестный тег NBT " + type);
            };
        }
    }

    public static ListTag list(List<Tag> items) {
        if (items.isEmpty()) return new ListTag(END, items);
        byte first = items.get(0).type();
        boolean mixed = false;
        for (Tag t : items) if (t.type() != first) mixed = true;
        if (!mixed) return new ListTag(first, items);
        List<Tag> wrapped = new ArrayList<>();
        for (Tag t : items) wrapped.add(t instanceof Compound ? t : new Compound().put("", t));
        return new ListTag(COMPOUND, wrapped);
    }

    public static String print(Tag t) {
        StringBuilder out = new StringBuilder();
        print(out, t);
        return out.toString();
    }

    private static void print(StringBuilder out, Tag t) {
        switch (t) {
            case Num n -> out.append(number(n));
            case Text s -> quote(out, s.value());
            case Array a -> {
                out.append(a.type() == BYTE_ARRAY ? "[B;" : a.type() == INT_ARRAY ? "[I;" : "[L;");
                String suffix = a.type() == BYTE_ARRAY ? "b" : a.type() == INT_ARRAY ? "" : "L";
                for (int i = 0; i < a.values().length; i++) {
                    if (i > 0) out.append(',');
                    out.append(a.values()[i]).append(suffix);
                }
                out.append(']');
            }
            case ListTag l -> {
                boolean unwrap = false, plain = false;
                if (l.element() == COMPOUND) {
                    for (Tag item : l.items()) {
                        if (wrapped((Compound) item) != null) unwrap = true;
                        else plain = true;
                    }
                }
                unwrap = unwrap && plain;
                out.append('[');
                for (int i = 0; i < l.items().size(); i++) {
                    if (i > 0) out.append(',');
                    Tag item = l.items().get(i);
                    Tag inner = unwrap ? wrapped((Compound) item) : null;
                    print(out, inner != null ? inner : item);
                }
                out.append(']');
            }
            case Compound c -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, Tag> e : c.entries().entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    if (bareKey(e.getKey())) out.append(e.getKey());
                    else quote(out, e.getKey());
                    out.append(':');
                    print(out, e.getValue());
                }
                out.append('}');
            }
        }
    }

    private static Tag wrapped(Compound c) {
        if (c.entries().size() != 1 || !c.entries().containsKey("")) return null;
        Tag inner = c.get("");
        return inner instanceof Compound ? null : inner;
    }

    private static String number(Num n) {
        return switch (n.type()) {
            case BYTE -> n.bits() + "b";
            case SHORT -> n.bits() + "s";
            case INT -> Long.toString(n.bits());
            case LONG -> n.bits() + "L";
            case FLOAT -> {
                float f = n.asFloat();
                if (Float.isNaN(f) || Float.isInfinite(f)) throw new IllegalArgumentException("не число");
                String s = Float.toString(f);
                if (Float.floatToRawIntBits(Float.parseFloat(s)) != (int) n.bits()) throw new IllegalArgumentException("неточно");
                yield s + "f";
            }
            default -> {
                double d = n.asDouble();
                if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("не число");
                String s = Double.toString(d);
                if (Double.doubleToRawLongBits(Double.parseDouble(s)) != n.bits()) throw new IllegalArgumentException("неточно");
                yield s + "d";
            }
        };
    }

    private static boolean bareKey(String key) {
        if (key.isEmpty()) return false;
        for (int i = 0; i < key.length(); i++) if (!bareChar(key.charAt(i))) return false;
        return true;
    }

    private static boolean bareChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.' || c == '+';
    }

    private static void quote(StringBuilder out, String s) {
        out.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\'' -> out.append("\\'");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        String hex = Integer.toHexString(c);
                        out.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('\'');
    }

    public static Tag parse(String text) throws Error {
        Snbt p = new Snbt(text);
        p.space();
        Tag t = p.value(0);
        p.space();
        if (p.pos < text.length()) throw new Error("лишнее после значения", p.pos);
        return t;
    }

    private static final class Snbt {
        final String s;
        int pos;

        Snbt(String s) { this.s = s; }

        void space() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        boolean at(char c) { return pos < s.length() && s.charAt(pos) == c; }

        void expect(char c, String message) throws Error {
            space();
            if (!at(c)) throw new Error(message, pos);
            pos++;
        }

        Tag value(int depth) throws Error {
            if (depth > 512) throw new Error("слишком глубоко", pos);
            space();
            if (pos >= s.length()) throw new Error("ожидалось значение", pos);
            char c = s.charAt(pos);
            if (c == '{') return compound(depth);
            if (c == '[') return list(depth);
            if (c == '"' || c == '\'') return new Text(quoted());
            int start = pos;
            while (pos < s.length() && bareChar(s.charAt(pos))) pos++;
            if (pos == start) throw new Error("непонятный знак «" + c + "»", pos);
            return word(s.substring(start, pos), start);
        }

        Compound compound(int depth) throws Error {
            pos++;
            Compound c = new Compound();
            space();
            if (at('}')) {
                pos++;
                return c;
            }
            while (true) {
                space();
                int at = pos;
                String key;
                if (at('"') || at('\'')) key = quoted();
                else {
                    while (pos < s.length() && bareChar(s.charAt(pos))) pos++;
                    if (pos == at) throw new Error("ожидался ключ", pos);
                    key = s.substring(at, pos);
                }
                if (c.entries().containsKey(key)) throw new Error("ключ «" + key + "» повторяется", at);
                expect(':', "после ключа нужно «:»");
                c.put(key, value(depth + 1));
                space();
                if (at(',')) {
                    pos++;
                    space();
                    if (at('}')) {
                        pos++;
                        return c;
                    }
                    continue;
                }
                if (at('}')) {
                    pos++;
                    return c;
                }
                throw new Error("ожидалась «,» или «}»", pos);
            }
        }

        Tag list(int depth) throws Error {
            pos++;
            space();
            if (pos + 1 < s.length() && s.charAt(pos + 1) == ';' && "BIL".indexOf(s.charAt(pos)) >= 0) {
                char kind = s.charAt(pos);
                pos += 2;
                byte type = kind == 'B' ? BYTE_ARRAY : kind == 'I' ? INT_ARRAY : LONG_ARRAY;
                byte want = kind == 'B' ? BYTE : kind == 'I' ? INT : LONG;
                List<Long> values = new ArrayList<>();
                space();
                while (!at(']')) {
                    int at = pos;
                    Tag v = value(depth + 1);
                    if (!(v instanceof Num n) || n.type() != want)
                        throw new Error(kind == 'B' ? "в [B;…] — только байты: 1b" : kind == 'I' ? "в [I;…] — только целые" : "в [L;…] — только 1L", at);
                    values.add(n.bits());
                    space();
                    if (at(',')) { pos++; space(); continue; }
                    if (!at(']')) throw new Error("ожидалась «,» или «]»", pos);
                }
                pos++;
                long[] out = new long[values.size()];
                for (int i = 0; i < out.length; i++) out[i] = values.get(i);
                return new Array(type, out);
            }
            List<Tag> items = new ArrayList<>();
            while (!at(']')) {
                items.add(value(depth + 1));
                space();
                if (at(',')) { pos++; space(); continue; }
                if (!at(']')) throw new Error("ожидалась «,» или «]»", pos);
            }
            pos++;
            return Nbt.list(items);
        }

        String quoted() throws Error {
            char q = s.charAt(pos);
            int start = pos++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw new Error("строка не закрыта", start);
                char c = s.charAt(pos++);
                if (c == q) return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= s.length()) throw new Error("строка не закрыта", start);
                char e = s.charAt(pos++);
                switch (e) {
                    case '\\', '\'', '"' -> out.append(e);
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 's' -> out.append(' ');
                    case 'u', 'x' -> {
                        int n = e == 'u' ? 4 : 2;
                        if (pos + n > s.length()) throw new Error("неполный код символа", pos - 2);
                        try {
                            out.append((char) Integer.parseInt(s.substring(pos, pos + n), 16));
                        } catch (NumberFormatException ex) {
                            throw new Error("неполный код символа", pos - 2);
                        }
                        pos += n;
                    }
                    default -> throw new Error("неизвестное экранирование \\" + e, pos - 2);
                }
            }
        }

        Tag word(String w, int at) throws Error {
            if (w.equals("true")) return Num.ofByte(1);
            if (w.equals("false")) return Num.ofByte(0);
            String body = w.replace("_", "");
            char last = Character.toLowerCase(body.charAt(body.length() - 1));
            boolean integer = body.matches("[-+]?\\d+[bBsSlL]?");
            boolean decimal = body.matches("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?[fFdD]?");
            if (!integer && !decimal) {
                if (Character.isDigit(w.charAt(0)) || w.charAt(0) == '-' || w.charAt(0) == '+' || w.charAt(0) == '.')
                    throw new Error("непонятное число «" + w + "»", at);
                return new Text(w);
            }
            try {
                if (integer) {
                    boolean suffix = Character.isLetter(last);
                    String digits = suffix ? body.substring(0, body.length() - 1) : body;
                    long v = Long.parseLong(digits.startsWith("+") ? digits.substring(1) : digits);
                    byte type = !suffix ? INT : last == 'b' ? BYTE : last == 's' ? SHORT : LONG;
                    long min = type == BYTE ? Byte.MIN_VALUE : type == SHORT ? Short.MIN_VALUE : type == INT ? Integer.MIN_VALUE : Long.MIN_VALUE;
                    long max = type == BYTE ? Byte.MAX_VALUE : type == SHORT ? Short.MAX_VALUE : type == INT ? Integer.MAX_VALUE : Long.MAX_VALUE;
                    if (v < min || v > max) throw new Error("число «" + w + "» не помещается в свой вид", at);
                    return new Num(type, v);
                }
                boolean suffix = last == 'f' || last == 'd';
                String digits = suffix ? body.substring(0, body.length() - 1) : body;
                if (last == 'f') {
                    float f = Float.parseFloat(digits);
                    if (Float.isInfinite(f)) throw new Error("число «" + w + "» слишком большое", at);
                    return Num.ofFloat(f);
                }
                double d = Double.parseDouble(digits);
                if (Double.isInfinite(d)) throw new Error("число «" + w + "» слишком большое", at);
                return Num.ofDouble(d);
            } catch (NumberFormatException e) {
                throw new Error("непонятное число «" + w + "»", at);
            }
        }
    }

    private Nbt() {}
}
