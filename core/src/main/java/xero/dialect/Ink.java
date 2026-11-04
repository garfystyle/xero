package xero.dialect;

import java.util.Map;

public final class Ink {
    public static final byte PLAIN = 0, KEYWORD = 1, LITERAL = 2, NUMBER = 3, STRING = 4, ESCAPE = 5, PLACEHOLDER = 6,
            COMMENT = 7, OPERATOR = 8, PUNCT = 9, ACTION = 10, CONDITION = 11, FUNCTION = 12, EVENT = 13, VALUE = 14,
            VARIABLE = 15, TARGET = 16, TYPE = 17, DECORATOR = 18;

    public static final int NORMAL = 0;
    private static final int COMMENT_BASE = 16, STRING_BASE = 1;

    private Ink() {}

    public static boolean inComment(int state) { return state >= COMMENT_BASE; }

    public static boolean inString(int state) { return state >= STRING_BASE && state < COMMENT_BASE; }

    public static boolean wordStart(char c) { return c == '_' || Character.isLetter(c); }

    public static boolean wordPart(char c) { return c == '_' || Character.isLetterOrDigit(c); }

    public static int scan(Syntax s, Map<String, Byte> lexicon, String line, int state, byte[] out) {
        return new Run(s, lexicon, line, out).go(state);
    }

    private static final class Run {
        final Syntax s;
        final Map<String, Byte> lexicon;
        final String t;
        final byte[] out;
        final int n;
        int i;

        Run(Syntax s, Map<String, Byte> lexicon, String t, byte[] out) {
            this.s = s;
            this.lexicon = lexicon;
            this.t = t;
            this.out = out;
            this.n = t.length();
        }

        void paint(int from, int to, byte kind) {
            for (int k = from; k < to && k < out.length; k++) out[k] = kind;
        }

        int go(int state) {
            if (state >= COMMENT_BASE) {
                int left = comment(state - COMMENT_BASE + 1, 0);
                if (left != NORMAL) return left;
            } else if (state >= STRING_BASE) {
                char quote = s.quotes().charAt(state - STRING_BASE);
                if (!string(quote)) return state;
            }
            return code(n);
        }

        int code(int end) {
            while (i < end) {
                char c = t.charAt(i);
                if (c == ' ' || c == '\t') {
                    paint(i, i + 1, PLAIN);
                    i++;
                    continue;
                }
                if (starts(s.lineComment()) || starts(s.otherComment())) {
                    paint(i, n, COMMENT);
                    i = n;
                    return NORMAL;
                }
                if (starts(s.blockOpen())) {
                    int left = comment(1, s.blockOpen().length());
                    if (left != NORMAL) return left;
                    continue;
                }
                if (s.quotes().indexOf(c) >= 0) {
                    int q = s.quotes().indexOf(c);
                    paint(i, i + 1, STRING);
                    i++;
                    if (!string(c)) return s.multiline() ? STRING_BASE + q : NORMAL;
                    continue;
                }
                if (s.backtick() && c == '`') {
                    backtick(i);
                    continue;
                }
                boolean leadingDot = c == '.' && i + 1 < n && Character.isDigit(t.charAt(i + 1))
                        && (i == 0 || !wordPart(t.charAt(i - 1)));
                if (Character.isDigit(c) || leadingDot) {
                    number();
                    continue;
                }
                if (wordStart(c)) {
                    word();
                    continue;
                }
                if (s.decorator() != 0 && c == s.decorator()) {
                    decorator();
                    continue;
                }
                if (c == '%' && placeholder()) continue;
                paint(i, i + 1, "(){}[];,.".indexOf(c) >= 0 ? PUNCT : OPERATOR);
                i++;
            }
            return NORMAL;
        }

        boolean starts(String prefix) { return prefix != null && !prefix.isEmpty() && t.startsWith(prefix, i); }

        int comment(int depth, int skip) {
            int from = i;
            i += skip;
            while (i < n) {
                if (s.nested() && starts(s.blockOpen())) {
                    depth++;
                    i += s.blockOpen().length();
                    continue;
                }
                if (starts(s.blockClose())) {
                    i += s.blockClose().length();
                    if (--depth == 0) {
                        paint(from, i, COMMENT);
                        return NORMAL;
                    }
                    continue;
                }
                i++;
            }
            paint(from, n, COMMENT);
            return COMMENT_BASE + depth - 1;
        }

        boolean string(char quote) {
            while (i < n) {
                char c = t.charAt(i);
                if (c == '\\' && i + 1 < n) {
                    paint(i, i + 2, ESCAPE);
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    paint(i, i + 1, STRING);
                    i++;
                    return true;
                }
                if (c == '%' && placeholder()) continue;
                if (s.dollar() && c == '$' && i + 1 < n) {
                    char d = t.charAt(i + 1);
                    if (d == '{') {
                        interpolation();
                        continue;
                    }
                    if (wordStart(d)) {
                        int from = i++;
                        while (i < n && wordPart(t.charAt(i))) i++;
                        paint(from, i, PLACEHOLDER);
                        continue;
                    }
                }
                paint(i, i + 1, STRING);
                i++;
            }
            return false;
        }

        void interpolation() {
            int close = closing(i + 1, '{', '}');
            paint(i, i + 2, ESCAPE);
            i += 2;
            if (close < 0) {
                paint(i, n, PLAIN);
                i = n;
                return;
            }
            code(close);
            paint(close, close + 1, ESCAPE);
            i = close + 1;
        }

        int closing(int from, char open, char close) {
            int depth = 0;
            for (int k = from; k < n; k++) {
                char c = t.charAt(k);
                if (c == open) depth++;
                else if (c == close && --depth == 0) return k;
            }
            return -1;
        }

        boolean placeholder() {
            int k = i + 1;
            if (k >= n || !wordStart(t.charAt(k))) return false;
            while (k < n && wordPart(t.charAt(k))) k++;
            if (k < n && t.charAt(k) == '%') {
                paint(i, k + 1, PLACEHOLDER);
                i = k + 1;
                return true;
            }
            if (k < n && t.charAt(k) == '(') {
                int end = closing(k, '(', ')');
                if (end < 0) end = n - 1;
                paint(i, end + 1, PLACEHOLDER);
                i = end + 1;
                return true;
            }
            return false;
        }

        void backtick(int from) {
            int close = t.indexOf('`', i + 1);
            int end = close < 0 ? n : close + 1;
            paint(from, end, VARIABLE);
            i = end;
        }

        void number() {
            int from = i;
            boolean hex = t.startsWith("0x", i) || t.startsWith("0X", i);
            if (hex) i += 2;
            while (i < n) {
                char c = t.charAt(i);
                boolean exponent = !hex && (c == 'e' || c == 'E') && i + 1 < n
                        && (Character.isDigit(t.charAt(i + 1)) || "+-".indexOf(t.charAt(i + 1)) >= 0);
                if (exponent) {
                    i += 2;
                    continue;
                }
                if (c == '.' && i + 1 < n && t.charAt(i + 1) == '.') break;
                if (Character.isLetterOrDigit(c) || c == '.' || c == '_') {
                    i++;
                    continue;
                }
                break;
            }
            paint(from, i, NUMBER);
        }

        void decorator() {
            int from = i++;
            while (i < n && wordPart(t.charAt(i))) i++;
            paint(from, i, DECORATOR);
        }

        void word() {
            int from = i;
            while (i < n && wordPart(t.charAt(i))) i++;
            int first = i;
            if (i < n && i - from == 1 && s.prefixes().indexOf(t.charAt(from)) >= 0
                    && s.quotes().indexOf(t.charAt(i)) >= 0) {
                paint(from, i, ESCAPE);
                return;
            }
            if (s.backtick() && i < n && t.charAt(i) == '`'
                    && (" " + s.backtickPrefixes() + " ").contains(" " + t.substring(from, i) + " ")) {
                backtick(from);
                return;
            }
            String joiner = s.joiner();
            if (joiner != null) {
                while (t.startsWith(joiner, i) && i + joiner.length() < n && wordStart(t.charAt(i + joiner.length()))) {
                    i += joiner.length();
                    while (i < n && wordPart(t.charAt(i))) i++;
                }
            }
            String whole = t.substring(from, i);
            Byte known = lexicon.get(whole);
            if (known == null && i != first) {
                int tail = whole.lastIndexOf(joiner) + joiner.length();
                paint(from, from + tail, PLAIN);
                paint(from + tail, i, called(i) ? FUNCTION : PLAIN);
                return;
            }
            paint(from, i, known != null ? known : plain(whole));
        }

        byte plain(String word) {
            if (s.keywords().contains(word)) return KEYWORD;
            if (s.literals().contains(word)) return LITERAL;
            if (s.types().contains(word)) return TYPE;
            return called(i) ? FUNCTION : PLAIN;
        }

        boolean called(int at) {
            int k = at;
            while (k < n && t.charAt(k) == ' ') k++;
            return k < n && t.charAt(k) == '(';
        }
    }
}
