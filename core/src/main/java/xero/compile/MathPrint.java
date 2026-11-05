package xero.compile;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

final class MathPrint {
    sealed interface Node permits Num, Bare, Placed, Value, Length, Cell, Call, Neg, Bin {}

    record Num(String text) implements Node {}

    record Bare(String name) implements Node {}

    record Placed(String name, String scope) implements Node {}

    record Value(String id) implements Node {}

    record Length(String name, String scope) implements Node {}

    record Cell(String name, String scope, String key, boolean list) implements Node {}

    record Call(String name, List<Node> args) implements Node {}

    record Neg(Node operand) implements Node {}

    record Bin(String op, Node left, Node right) implements Node {}

    private static final class Fail extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Fail() { super(null, null, false, false); }
    }

    private final String text;
    private int pos;

    private MathPrint(String text) {
        this.text = text;
    }

    static Node parse(String inner) {
        String text = spaceless(inner);
        MathPrint p = new MathPrint(text);
        try {
            Node n = p.sum();
            return p.pos == text.length() ? n : null;
        } catch (Fail f) {
            return null;
        }
    }

    private static String spaceless(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') continue;
            out.append(c);
            if (c != '%') continue;
            int open = i + 1;
            while (open < s.length() && (Character.isLetter(s.charAt(open)) || s.charAt(open) == '_')) open++;
            if (open >= s.length() || s.charAt(open) != '(') continue;
            int close = s.indexOf(')', open);
            if (close < 0) continue;
            out.append(s, i + 1, close + 1);
            i = close;
        }
        return out.toString();
    }

    static String canonical(String number) {
        if (!number.startsWith("%math(") || !number.endsWith(")")) return number;
        Node n = parse(number.substring("%math(".length(), number.length() - 1));
        if (n == null) return number;
        if (n instanceof Length x) return "%length" + suffix(x.scope()) + "(" + x.name() + ")";
        return "%math(" + math(bare(n), 0) + ")";
    }

    private static Node bare(Node n) {
        return switch (n) {
            case Placed x when x.scope().equals("local") && x.name().matches("[A-Za-z_][A-Za-z0-9_]*") && !Texts.mathWord(x.name()) ->
                    new Bare(x.name());
            case Neg x -> new Neg(bare(x.operand()));
            case Bin x -> new Bin(x.op(), bare(x.left()), bare(x.right()));
            case Call x -> {
                List<Node> args = new ArrayList<>();
                for (Node a : x.args()) args.add(bare(a));
                yield new Call(x.name(), args);
            }
            default -> n;
        };
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private Node sum() {
        Node left = product();
        while (peek() == '+' || peek() == '-') {
            String op = String.valueOf(text.charAt(pos++));
            left = new Bin(op, left, product());
        }
        return left;
    }

    private Node product() {
        Node left = unary();
        while (peek() == '*' || peek() == '/' || peek() == '%' && !placeholder()) {
            String op = String.valueOf(text.charAt(pos++));
            left = new Bin(op, left, unary());
        }
        return left;
    }

    private boolean placeholder() {
        int i = pos + 1;
        while (i < text.length() && (Character.isLetter(text.charAt(i)) || text.charAt(i) == '_')) i++;
        return i > pos + 1 && i < text.length() && (text.charAt(i) == '(' || text.charAt(i) == '%');
    }

    private Node unary() {
        if (peek() == '-') {
            pos++;
            return new Neg(unary());
        }
        return atom();
    }

    private Node atom() {
        char c = peek();
        if (c == '(') {
            pos++;
            Node inner = sum();
            if (peek() != ')') throw new Fail();
            pos++;
            return inner;
        }
        if (c == '%') return placed();
        if (Character.isDigit(c)) return number();
        if (Character.isLetter(c) || c == '_') return word();
        throw new Fail();
    }

    private Node number() {
        int start = pos;
        while (Character.isDigit(peek())) pos++;
        if (peek() == '.' && pos + 1 < text.length() && Character.isDigit(text.charAt(pos + 1))) {
            pos++;
            while (Character.isDigit(peek())) pos++;
        }
        if (Character.isLetter(peek())) throw new Fail();
        return new Num(text.substring(start, pos));
    }

    private Node word() {
        int start = pos;
        while (Character.isLetterOrDigit(peek()) || peek() == '_') pos++;
        String name = text.substring(start, pos);
        if (peek() != '(') return new Bare(name);
        pos++;
        List<Node> args = new ArrayList<>();
        if (peek() == ')') throw new Fail();
        while (true) {
            args.add(sum());
            if (peek() != ',') break;
            pos++;
        }
        if (peek() != ')') throw new Fail();
        pos++;
        return new Call(name, args);
    }

    private Node placed() {
        int open = text.indexOf('(', pos);
        if (open < 0) throw new Fail();
        String head = text.substring(pos + 1, open);
        int close = text.indexOf(')', open);
        if (close < 0) throw new Fail();
        String inner = text.substring(open + 1, close);
        if (inner.isEmpty() || inner.indexOf('(') >= 0 || inner.indexOf('%') >= 0) throw new Fail();
        pos = close + 1;
        boolean list = head.startsWith("index"), map = head.startsWith("entry");
        if (list || map) {
            int comma = inner.indexOf(',');
            if (comma <= 0 || comma != inner.lastIndexOf(',')) throw new Fail();
            String key = inner.substring(comma + 1);
            if (key.isEmpty() || key.indexOf(' ') >= 0 || list && !key.matches("\\d+")) throw new Fail();
            return new Cell(inner.substring(0, comma), scope(head.substring("index".length())), key, list);
        }
        if (inner.indexOf(',') >= 0) throw new Fail();
        if (head.equals("val")) return new Value(inner);
        boolean length = head.startsWith("length");
        if (!length && !head.startsWith("var")) throw new Fail();
        String scope = scope(head.substring(length ? "length".length() : "var".length()));
        return length ? new Length(inner, scope) : new Placed(inner, scope);
    }

    private static String scope(String suffix) {
        return switch (suffix) {
            case "" -> "game";
            case "_local" -> "local";
            case "_save" -> "save";
            case "_line" -> "line";
            default -> throw new Fail();
        };
    }

    private static int precedence(String op) {
        return op.equals("+") || op.equals("-") ? 1 : 2;
    }

    private static String suffix(String scope) {
        return switch (scope) {
            case "local" -> "_local";
            case "save" -> "_save";
            case "line" -> "_line";
            default -> "";
        };
    }

    static String math(Node n, int parent) {
        return switch (n) {
            case Num x -> x.text();
            case Bare x -> x.name();
            case Placed x -> "(%var" + suffix(x.scope()) + "(" + x.name() + "))";
            case Value x -> "%val(" + x.id() + ")";
            case Length x -> "(%length" + suffix(x.scope()) + "(" + x.name() + "))";
            case Cell x -> "(%" + (x.list() ? "index" : "entry") + suffix(x.scope()) + "(" + x.name() + "," + x.key() + "))";
            case Call x -> {
                List<String> args = new ArrayList<>();
                for (Node a : x.args()) args.add(math(a, 0));
                yield x.name() + "(" + String.join(",", args) + ")";
            }
            case Neg x -> "-" + math(x.operand(), 3);
            case Bin x -> {
                int p = precedence(x.op());
                String left = math(x.left(), p), right = math(x.right(), p + 1);
                if (x.op().equals("%") && left.matches("(?s).*%[A-Za-z_][A-Za-z0-9_]*")) left = "(" + left + ")";
                if (x.op().equals("%") && right.matches("(?s)[A-Za-z_][A-Za-z0-9_]*[(].*")) right = "(" + right + ")";
                String s = left + x.op() + right;
                yield p < parent ? "(" + s + ")" : s;
            }
        };
    }

    static boolean moving(Node n) {
        return switch (n) {
            case Num x -> false;
            case Neg x -> moving(x.operand());
            case Bin x -> moving(x.left()) || moving(x.right());
            case Call x -> {
                boolean any = false;
                for (Node a : x.args()) any |= moving(a);
                yield any;
            }
            default -> true;
        };
    }

    static String xero(Node n, int parent, BiFunction<String, String, String> variable, BiFunction<String, String, String> value) {
        return switch (n) {
            case Num x -> x.text();
            case Bare x -> require(variable.apply(x.name(), null));
            case Placed x -> require(variable.apply(x.name(), x.scope()));
            case Value x -> require(value.apply(x.id(), null));
            case Length x -> "len(" + require(variable.apply(x.name(), "#" + x.scope())) + ")";
            case Cell x -> require(variable.apply(x.name(), "#" + x.scope())) + "[" + (x.list() ? x.key() : Decompiler.quote(x.key(), null)) + "]";
            case Call x -> {
                if (!Texts.mathFunction(x.name()) || !Texts.mathArity(x.name(), x.args().size())) throw new Fail();
                List<String> args = new ArrayList<>();
                for (Node a : x.args()) args.add(xero(a, 0, variable, value));
                yield x.name() + "(" + String.join(", ", args) + ")";
            }
            case Neg x -> "-" + xero(x.operand(), 3, variable, value);
            case Bin x -> {
                int p = precedence(x.op());
                String s = xero(x.left(), p, variable, value) + " " + x.op() + " " + xero(x.right(), p + 1, variable, value);
                yield p < parent ? "(" + s + ")" : s;
            }
        };
    }

    private static String require(String printed) {
        if (printed == null) throw new Fail();
        return printed;
    }

    static String printed(Node n, BiFunction<String, String, String> variable, BiFunction<String, String, String> value) {
        try {
            return xero(n, 0, variable, value);
        } catch (Fail f) {
            return null;
        }
    }
}
