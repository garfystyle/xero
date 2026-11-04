package xero.compile;

import xero.data.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

final class Texts {
    record Problem(boolean error, String message, String hint, int offset) {}

    private record Arity(int min, int max) {}

    private static final Map<String, Arity> MATH = Map.ofEntries(
            Map.entry("abs", new Arity(1, 1)), Map.entry("sqrt", new Arity(1, 1)),
            Map.entry("cbrt", new Arity(1, 1)), Map.entry("ceil", new Arity(1, 2)),
            Map.entry("floor", new Arity(1, 2)), Map.entry("sin", new Arity(1, 1)),
            Map.entry("cos", new Arity(1, 1)), Map.entry("round", new Arity(1, 2)),
            Map.entry("pow", new Arity(2, 2)), Map.entry("min", new Arity(2, 2)),
            Map.entry("max", new Arity(2, 2)), Map.entry("sign", new Arity(1, 1)),
            Map.entry("atan2", new Arity(2, 2)), Map.entry("root", new Arity(2, 2)),
            Map.entry("clamp", new Arity(3, 3)), Map.entry("wrap", new Arity(3, 3)),
            Map.entry("sinr", new Arity(1, 1)), Map.entry("cosr", new Arity(1, 1)),
            Map.entry("asin", new Arity(1, 1)), Map.entry("acos", new Arity(1, 1)));

    private static final Set<String> CONSTANTS = Set.of("PI", "E");
    private static final Set<String> TWO_PARTS = Set.of(
            "index", "index_local", "index_save", "index_line", "entry", "entry_local", "entry_save", "entry_line");

    private final Data data;
    private final Predicate<String> knownVariable;
    private final List<Problem> problems = new ArrayList<>();

    private Texts(Data data, Predicate<String> knownVariable) {
        this.data = data;
        this.knownVariable = knownVariable;
    }

    static List<Problem> check(Data data, String text, Predicate<String> knownVariable) {
        Texts t = new Texts(data, knownVariable);
        t.scan(text, 0);
        return t.problems;
    }

    static boolean mathFunction(String name) { return MATH.containsKey(name); }

    static boolean mathArity(String name, int count) {
        Arity a = MATH.get(name);
        return a != null && count >= a.min() && count <= a.max();
    }

    static boolean mathWord(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (MATH.containsKey(lower)) return true;
        for (String k : CONSTANTS) if (k.toLowerCase(Locale.ROOT).equals(lower)) return true;
        return false;
    }

    static boolean numeric(String text) {
        String t = text.trim();
        if (t.isEmpty()) return false;
        try {
            Double.parseDouble(t);
            return !t.endsWith("d") && !t.endsWith("D") && !t.endsWith("f") && !t.endsWith("F");
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void scan(String text, int base) {
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) != '%') { i++; continue; }
            int nameEnd = i + 1;
            while (nameEnd < text.length() && (Character.isLetterOrDigit(text.charAt(nameEnd)) || text.charAt(nameEnd) == '_'))
                nameEnd++;
            String name = text.substring(i + 1, nameEnd);
            if (name.isEmpty() || nameEnd >= text.length()) { i++; continue; }
            char after = text.charAt(nameEnd);
            if (after == '(') {
                int close = closing(text, nameEnd);
                if (!data.placeholders().functions().contains(name)) {
                    problems.add(new Problem(true, "нет плейсхолдера %" + name + "(…)",
                            Suggest.hint(name, data.placeholders().functions()), base + i));
                }
                if (close < 0) {
                    problems.add(new Problem(true, "у %" + name + "( не закрыта скобка", null, base + i));
                    return;
                }
                String inner = text.substring(nameEnd + 1, close);
                if (name.equals("math")) {
                    new Math(inner, base + nameEnd + 1).run();
                } else {
                    if (inner.isBlank()) problems.add(new Problem(true, "пустые скобки у %" + name + "()", null, base + i));
                    if (TWO_PARTS.contains(name) && topLevelCommas(inner) != 1)
                        problems.add(new Problem(true, "%" + name + "(…) пишется через запятую: %" + name + "(имя, ключ)",
                                null, base + i));
                    scan(inner, base + nameEnd + 1);
                }
                i = close + 1;
                continue;
            }
            if (after == '%') {
                if (!data.placeholders().plain().contains(name) && name.chars().allMatch(c -> Character.isLowerCase(c) || c == '_')
                        && Suggest.best(name, data.placeholders().plain()) != null) {
                    problems.add(new Problem(false, "нет плейсхолдера %" + name + "% — останется как есть",
                            Suggest.hint("%" + name + "%", plainForms()), base + i));
                }
                i = nameEnd + 1;
                continue;
            }
            i++;
        }
    }

    private List<String> plainForms() {
        List<String> out = new ArrayList<>();
        for (String p : data.placeholders().plain()) out.add("%" + p + "%");
        return out;
    }

    private static int closing(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static int topLevelCommas(String text) {
        int depth = 0, commas = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) commas++;
        }
        return commas;
    }

    private final class Math {
        private final String text;
        private final int base;
        private int pos;
        private boolean failed;

        Math(String text, int base) {
            this.text = text;
            this.base = base;
        }

        void run() {
            if (text.isBlank()) {
                fail("пустая формула в %math()", 0);
                return;
            }
            expression();
            space();
            if (!failed && pos < text.length())
                fail(text.charAt(pos) == ')' ? "в %math лишняя «)»" : "в %math непонятно «" + text.substring(pos, java.lang.Math.min(text.length(), pos + 8)) + "»", pos);
        }

        private void expression() {
            term();
            while (!failed) {
                space();
                if (peek() == '+' || peek() == '-') { pos++; term(); } else return;
            }
        }

        private void term() {
            factor();
            while (!failed) {
                space();
                char c = peek();
                if ((c == '*' || c == '/' || (c == '%' && !placeholderAt(pos)))) { pos++; factor(); } else return;
            }
        }

        private void factor() {
            unary();
            space();
            if (!failed && peek() == '^') { pos++; factor(); }
        }

        private void unary() {
            space();
            if (peek() == '-' || peek() == '+') { pos++; unary(); return; }
            atom();
        }

        private void atom() {
            space();
            if (pos >= text.length()) { fail("формула %math оборвалась — не хватает числа или переменной", pos); return; }
            char c = text.charAt(pos);
            if (c == '(') {
                pos++;
                expression();
                space();
                if (failed) return;
                if (peek() != ')') { fail("в %math не хватает «)»", pos); return; }
                pos++;
                return;
            }
            if (c == '%' && placeholderAt(pos)) { placeholder(); return; }
            if (Character.isDigit(c) || (c == '.' && pos + 1 < text.length() && Character.isDigit(text.charAt(pos + 1)))) { number(); return; }
            if (Character.isLetter(c) || c == '_' || c == '$') { identifier(); return; }
            fail("в %math ожидалось число или переменная, а не «" + c + "»", pos);
        }

        private boolean placeholderAt(int at) {
            int end = at + 1;
            while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) end++;
            return end > at + 1 && end < text.length() && (text.charAt(end) == '(' || text.charAt(end) == '%');
        }

        private void placeholder() {
            int start = pos;
            int end = pos + 1;
            while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) end++;
            if (text.charAt(end) == '%') {
                pos = end + 1;
                Texts.this.scan(text.substring(start, pos), base + start);
                return;
            }
            int close = closing(text, end);
            if (close < 0) { fail("у " + text.substring(start, end) + "( не закрыта скобка", start); return; }
            pos = close + 1;
            Texts.this.scan(text.substring(start, pos), base + start);
        }

        private void number() {
            if (text.startsWith("0x", pos) || text.startsWith("0X", pos) || text.startsWith("0b", pos)
                    || text.startsWith("0B", pos) || text.startsWith("0o", pos) || text.startsWith("0O", pos)) {
                pos += 2;
                int start = pos;
                while (pos < text.length() && Character.isLetterOrDigit(text.charAt(pos))) pos++;
                if (pos == start) fail("в %math неполное число", start);
                return;
            }
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) pos++;
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            }
        }

        private void identifier() {
            int start = pos;
            while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_' || text.charAt(pos) == '$')) pos++;
            String name = text.substring(start, pos);
            space();
            if (peek() == '(') {
                Arity arity = MATH.get(name);
                if (arity == null) {
                    String close = Suggest.best(name, MATH.keySet());
                    problems.add(new Problem(true, "в %math нет функции " + name + "()",
                            close != null ? "возможно, вы имели в виду «" + close + "»"
                                    : "есть: " + String.join(", ", new java.util.TreeSet<>(MATH.keySet())), base + start));
                }
                pos++;
                int args = 0;
                space();
                if (peek() != ')') {
                    while (!failed) {
                        expression();
                        args++;
                        space();
                        if (peek() == ',') { pos++; continue; }
                        break;
                    }
                }
                if (failed) return;
                if (peek() != ')') { fail("в %math у " + name + "( не хватает «)»", pos); return; }
                pos++;
                if (arity != null && (args < arity.min() || args > arity.max()))
                    problems.add(new Problem(true, "у " + name + "() в %math " + plural(arity) + ", а передано " + args,
                            null, base + start));
                return;
            }
            if (CONSTANTS.contains(name)) return;
            if (!knownVariable.test(name))
                problems.add(new Problem(false, "в %math переменная «" + name + "» нигде не задана — сервер подставит 0",
                        null, base + start));
        }

        private String plural(Arity a) {
            return a.min() == a.max() ? "аргументов " + a.min() : "аргументов " + a.min() + "–" + a.max();
        }

        private char peek() { return pos < text.length() ? text.charAt(pos) : '\0'; }

        private void space() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        private void fail(String message, int at) {
            if (failed) return;
            failed = true;
            problems.add(new Problem(true, message, null, base + at));
        }
    }
}
