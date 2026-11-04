package xero.compile;

import xero.data.Names;
import xero.json.Json;
import xero.lang.Ast;
import xero.lang.Ast.Arg;
import xero.lang.Ast.Expr;
import xero.lang.Ast.Span;
import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static xero.compile.Context.num;
import static xero.compile.Context.obj;

final class Formula {
    static final Set<String> ARITHMETIC = Set.of("+", "-", "*", "/", "%");

    private static final List<String> MATH_NAMES = List.of("abs", "sqrt", "cbrt", "ceil", "floor", "sin", "cos",
            "round", "pow", "min", "max", "sign", "atan2", "root", "clamp", "wrap", "sinr", "cosr", "asin", "acos");

    private final Context c;

    Formula(Context c) {
        this.c = c;
    }

    boolean mathCall(Ast.Call call) {
        return call.qualifier() == null && !call.quoted() && Texts.mathFunction(call.name())
                && !Wire.VALUES.contains(call.name())
                && c.names.resolve(Names.Space.ACTION, call.name()) == null && !c.functions.containsKey(call.name());
    }

    Object math(Expr e) {
        Double folded = fold(e);
        if (folded != null) return obj("type", "number", "number", num(c.finite(folded, e.span())));
        return obj("type", "number", "number", "%math(" + mathText(e, 0) + ")");
    }

    private static boolean len(Ast.Call call) {
        return call.qualifier() == null && !call.quoted() && call.name().equals("len");
    }

    String length(Ast.Call call) {
        if (call.args().size() != 1 || call.args().get(0).name() != null || !(call.args().get(0).value() instanceof Ast.Var v))
            throw c.stop("len(список): в скобках переменная со списком или словарём", call.span());
        if (v.name().matches(".*[,()].*")) throw c.stop("такое имя в %length не подставить", v.span(), "пиши get_list_length");
        String scope = c.scope(v);
        if (scope == null) {
            c.compiler.variable(v, null);
            return "0";
        }
        String kind = Kinds.slot(c.kind(v));
        if (kind != null && !kind.equals(Kinds.LIST) && !kind.equals(Kinds.MAP))
            c.warning("«" + v.name() + "» — " + Kinds.lower(kind) + ": len считает списки и словари", v.span(),
                    kind.equals(Kinds.TEXT) ? "длину текста даёт text_length" : null);
        if (scope.equals("local")) c.locals.use(v.name(), v.span(), false);
        c.lints.read(v.name());
        String suffix = switch (scope) {
            case "save" -> "_save";
            case "local" -> "_local";
            case "line" -> "_line";
            default -> "";
        };
        return "%length" + suffix + "(" + v.name() + ")";
    }

    Double fold(Expr raw) {
        Expr e = c.resolved(raw);
        return switch (e) {
            case Ast.Num n -> c.number(n);
            case Ast.Unary u when u.op().equals("-") -> {
                Double v = fold(u.operand());
                yield v == null ? null : -v;
            }
            case Ast.Binary b when ARITHMETIC.contains(b.op()) && !b.op().equals("%") -> {
                Double l = fold(b.left()), r = fold(b.right());
                if (l == null || r == null) yield null;
                yield switch (b.op()) {
                    case "+" -> l + r;
                    case "-" -> l - r;
                    case "*" -> l * r;
                    default -> r == 0 ? null : l / r;
                };
            }
            default -> null;
        };
    }

    private static int precedence(String op) {
        return op.equals("+") || op.equals("-") ? 1 : 2;
    }

    private String mathText(Expr raw, int parent) {
        Expr e = c.resolved(raw);
        return switch (e) {
            case Ast.Num n -> {
                c.finite(c.number(n), n.span());
                yield c.numberText(n);
            }
            case Ast.Unary u when u.op().equals("-") -> "-" + mathText(u.operand(), 3);
            case Ast.Binary b when ARITHMETIC.contains(b.op()) -> {
                Double divisor = b.op().equals("/") || b.op().equals("%") ? fold(b.right()) : null;
                if (divisor != null && divisor == 0)
                    c.warning("деление на ноль — сервер молча даст 0", b.span(), "проверь делитель: на 0 делить нельзя");
                int p = precedence(b.op());
                String left = mathText(b.left(), p);
                String right = mathText(b.right(), b.op().equals("-") || b.op().equals("/") || b.op().equals("%") ? p + 1 : p);
                String s = left + b.op() + right;
                yield p < parent ? "(" + s + ")" : s;
            }
            case Ast.Var v -> mathVariable(v);
            case Ast.Path p when p.parts().size() == 2 && p.parts().get(0).equals("value") -> {
                String id = c.gameValue(p.parts().get(1), p.span());
                String kind = Kinds.ofGameValue(c.data, id);
                if (kind != null && !kind.equals(Kinds.NUMBER))
                    c.error("«" + id + "» — " + Kinds.lower(kind) + ", а не число", p.span());
                yield "%val(" + id + ")";
            }
            case Ast.Call call when len(call) -> "(" + length(call) + ")";
            case Ast.Call call when call.qualifier() == null && call.name().equals("num") && call.args().size() == 1
                    && call.args().get(0).value() instanceof Ast.Str s -> "(" + s.text() + ")";
            case Ast.Call call when call.qualifier() == null && !call.quoted() && c.functions.containsKey(call.name()) ->
                    mathName(c.compiler.plainValue(call, null));
            case Ast.Index i -> {
                String placeholder = c.compiler.indexText(i);
                yield placeholder != null ? "(" + placeholder + ")" : mathName(c.compiler.indexValue(i));
            }
            case Ast.Ternary t -> mathName(c.conditions.ternaryValue(t));
            case Ast.Call call when call.qualifier() == null && Texts.mathFunction(call.name()) -> {
                List<String> args = new ArrayList<>();
                for (Arg a : call.args()) {
                    if (a.name() != null) c.error("у функций формулы аргументы без имён", a.nameSpan());
                    args.add(mathText(a.value(), 0));
                }
                if (!Texts.mathArity(call.name(), args.size()))
                    c.error("у " + call.name() + "() другое число аргументов", call.span());
                yield call.name() + "(" + String.join(",", args) + ")";
            }
            case Ast.Call call when !Texts.mathFunction(call.name()) && (c.conditions.conditionName(call)
                    || c.names.resolve(Names.Space.ACTION, call.shown()) != null) -> mathName(c.compiler.plainValue(call, null));
            case Ast.Call call -> {
                String best = Suggest.best(call.name(), MATH_NAMES);
                throw c.stop(Texts.mathFunction(call.name()) ? "в формуле функции пишутся без категории"
                        : "в формуле нет функции «" + call.name() + "»", call.nameSpan(), Suggest.meant(best),
                        Hints.replace(new Span(call.span().start(), call.nameSpan().end()), best));
            }
            case Ast.Binary b when Wire.COMPARE.containsKey(b.op()) || Wire.LOGIC.contains(b.op()) ->
                    mathName(c.conditions.conditionValue(b));
            case Ast.Unary u when u.op().equals("not") -> mathName(c.conditions.conditionValue(u));
            case Ast.Str s -> throw c.stop("текст в формуле не складывают", s.span(), "для склейки пиши \"…${x}…\"");
            case Ast.Targeted t -> throw c.stop("в формуле игровое значение берётся без цели", t.span());
            default -> throw c.stop("это не число для формулы", e.span());
        };
    }

    private String mathName(Object value) {
        Map<String, Object> v = Json.object(value);
        return mathVariable(new Ast.Var(Json.string(v.get("variable")), false, null));
    }

    private String mathVariable(Ast.Var v) {
        String scope = c.scope(v);
        if (scope == null) {
            c.compiler.variable(v, null);
            return "0";
        }
        if (scope.equals("local")) c.locals.use(v.name(), v.span(), false);
        c.lints.read(v.name());
        String kind = Kinds.slot(c.kind(v));
        if (kind != null && !kind.equals(Kinds.NUMBER) && v.span() != null) {
            if (!kind.equals(Kinds.TEXT)) throw c.stop("«" + v.name() + "» — " + Kinds.lower(kind) + ", а в формуле только числа", v.span());
            c.warning("«" + v.name() + "» — текст: в формуле сервер поймёт его, только если там число", v.span());
        }
        boolean plain = v.scope() == null && v.name().matches("[A-Za-z_][A-Za-z0-9_]*") && !Texts.mathWord(v.name());
        return plain ? v.name() : "(" + placeholder(v.name(), scope) + ")";
    }

    static String placeholder(String name, String scope) {
        String fn = switch (scope) {
            case "save" -> "var_save";
            case "local" -> "var_local";
            case "line" -> "var_line";
            default -> "var";
        };
        return "%" + fn + "(" + name + ")";
    }

    String interpolate(Ast.Str s) {
        StringBuilder out = new StringBuilder();
        for (Object part : s.parts()) {
            if (part instanceof String literal) {
                out.append(literal);
                continue;
            }
            Expr e = c.resolved((Expr) part);
            try {
                out.append(switch (e) {
                    case Ast.Blank b -> "";
                    case Ast.Num n -> c.numberText(n);
                    case Ast.Str inner -> inner.interpolated() ? interpolate(inner) : inner.text();
                    case Ast.Call call when len(call) -> length(call);
                    case Ast.Var v -> {
                        String scope = c.scope(v);
                        if (scope == null) {
                            c.compiler.variable(v, null);
                            yield "";
                        }
                        if (scope.equals("local")) c.locals.use(v.name(), v.span(), false);
                        c.lints.read(v.name());
                        yield placeholder(v.name(), scope);
                    }
                    case Ast.Path p when p.parts().size() == 2 && p.parts().get(0).equals("value") ->
                            "%val(" + c.gameValue(p.parts().get(1), p.span()) + ")";
                    case Ast.Index i -> {
                        String placeholder = c.compiler.indexText(i);
                        yield placeholder != null ? placeholder : textOf(c.compiler.indexValue(i));
                    }
                    case Ast.Ternary t -> textOf(c.conditions.ternaryValue(t));
                    case Ast.Call call when !mathCall(call) && !(call.qualifier() == null && Wire.VALUES.contains(call.name())) ->
                            textOf(c.compiler.plainValue(call, null));
                    case Ast.Targeted t -> textOf(c.compiler.plainValue(t, null));
                    case Ast.Binary b when Wire.COMPARE.containsKey(b.op()) || Wire.LOGIC.contains(b.op()) ->
                            textOf(c.conditions.conditionValue(b));
                    case Ast.Unary u when u.op().equals("not") -> textOf(c.conditions.conditionValue(u));
                    default -> "%math(" + mathText(e, 0) + ")";
                });
            } catch (Context.Stop ignored) {
            }
        }
        return out.toString();
    }

    private static String textOf(Object value) {
        Map<String, Object> v = Json.object(value);
        return placeholder(Json.string(v.get("variable")), Json.string(v.get("scope")));
    }

    void texts(String text, Span at, int shift) {
        c.lints.text(text);
        for (Texts.Problem p : Texts.check(c.data, text, c::known)) {
            int start = Math.min(at.end() - 1, at.start() + shift + p.offset());
            c.diagnostics.add(new Diagnostic(false, p.message(), Math.max(at.start(), start), at.end(), p.hint()));
        }
    }
}
