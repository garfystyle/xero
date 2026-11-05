package xero.dialect.jmscript;

import xero.data.Data;
import xero.data.Names;
import xero.lang.Ast;
import xero.lang.Ast.Arg;
import xero.lang.Ast.Expr;
import xero.lang.Ast.Stmt;
import xero.lang.Keywords;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class Printer {
    private enum Mode { VALUE, CONDITION, MATH }

    private static final int WIDTH = 100;
    private static final String STEP = "    ";
    private static final int FLAT = Integer.MIN_VALUE / 2;
    private static final int HANDLER = 3;
    private static final int TERNARY = 0, OR = 1, AND = 2, COMPARE = 3, ADD = 4, MUL = 5, UNARY = 6, ATOM = 7;

    private static final Set<String> RESERVED = Set.of("package", "func", "event", "for", "in", "if", "else", "defer",
            "return", "continue", "break", "true", "false", "_");
    private static final Set<String> MATH = Set.of("abs", "sqrt", "cbrt", "ceil", "floor", "sin", "cos", "round", "pow",
            "min", "max", "sign", "atan2", "root", "clamp", "wrap", "sinr", "cosr", "asin", "acos");
    private static final Set<String> CONDITIONAL = Set.of("repeat_while", "select_player_by_conditional",
            "select_entity_by_conditional", "select_add_player_by_conditional", "select_add_entity_by_conditional",
            "select_filter_by_conditional");
    private static final Set<String> START_SETTINGS = Set.of("local_variables_mode", "target_mode");
    private static final Set<String> FACTORIES = Set.of("loc", "vec", "num", "item", "block", "sound", "particle",
            "potion", "raw", "enum", "len", "call", "literal");
    private static final Set<String> IDENTIFIED = Set.of("num", "item", "block", "sound", "particle", "potion", "raw", "enum");
    private static final Map<String, String> TYPES = Map.of("vector", "vec3", "vec", "vec3", "loc", "location",
            "map", "dict", "list", "array");
    private static final Map<String, String> FACTORY_NAMES = Map.of("loc", "location", "vec", "vec3", "num", "number");
    private static final Map<String, String> MARKS = Map.of("display_name", "name", "display_description", "desc");

    private final Names names;
    private final Ast.File file;
    private final Set<String> functions = new HashSet<>();
    private final Set<String> constants = new HashSet<>();
    private final Set<String> globals = new HashSet<>();
    private final Set<String> seen = new HashSet<>();
    private final Set<String> textParams = new HashSet<>();
    private final StringBuilder out = new StringBuilder();

    private Printer(Names names, Ast.File file) {
        this.names = names;
        this.file = file;
    }

    static String print(Names names, Ast.File file) {
        return new Printer(names, file).run();
    }

    private String run() {
        for (Ast.Item item : file.items()) {
            if (item instanceof Ast.Func f) functions.add(f.name());
            if (item instanceof Ast.Const k) constants.add(k.name());
            if (item instanceof Ast.Global g) globals.add(g.name());
        }
        out.append("package main\n");
        int last = -1;
        for (Ast.Item item : file.items()) {
            int group = group(item);
            if (group == HANDLER || group != last) out.append('\n');
            last = group;
            switch (item) {
                case Ast.Const k -> out.append(name(k.name())).append(" :: ").append(expr(k.value(), TERNARY, 0, Mode.VALUE)).append('\n');
                case Ast.Global g -> out.append(g.scope()).append(' ').append(name(g.name())).append('\n');
                case Ast.Import i -> out.append("import ").append(quoted(i.path())).append(i.connect() ? " connect\n" : "\n");
                case Ast.Event e -> event(e);
                case Ast.Func f -> function(f);
            }
        }
        return out.toString();
    }

    private static int group(Ast.Item item) {
        return switch (item) {
            case Ast.Const k -> 0;
            case Ast.Global g -> 1;
            case Ast.Import i -> 2;
            default -> HANDLER;
        };
    }

    private void event(Ast.Event e) {
        marks(e.decorators());
        seen.clear();
        textParams.clear();
        seen.addAll(globals);
        out.append("event ").append(name(e.name())).append("() ");
        block(e.body(), 0);
        out.append('\n');
    }

    private void function(Ast.Func f) {
        marks(f.decorators());
        seen.clear();
        textParams.clear();
        seen.addAll(globals);
        List<String> params = new ArrayList<>();
        for (Ast.Param p : f.params()) {
            params.add(param(p));
            seen.add(p.name());
            if ("text".equals(p.type())) textParams.add(p.name());
        }
        out.append(f.process() ? "process " : "func ").append(f.quoted() ? quotedName(f.name()) : functionName(f.name()))
                .append('(').append(String.join(", ", params)).append(") ");
        block(f.body(), 0);
        out.append('\n');
    }

    private String param(Ast.Param p) {
        StringBuilder s = new StringBuilder(name(p.name()));
        if (p.type() != null) {
            s.append(": ").append(TYPES.getOrDefault(p.type(), p.type()));
            if (p.options() != null) {
                List<String> options = new ArrayList<>();
                for (String o : p.options()) options.add(quoted(o));
                s.append('(').append(String.join(", ", options)).append(')');
            }
        }
        if (p.fallback() != null) s.append(" = ").append(expr(p.fallback(), TERNARY, 0, Mode.VALUE));
        return s.toString();
    }

    private void marks(List<Ast.Decorator> decorators) {
        for (Ast.Decorator d : decorators) {
            out.append('@').append(MARKS.getOrDefault(d.name(), d.name()));
            if (!d.args().isEmpty()) {
                List<String> parts = new ArrayList<>();
                for (Arg a : d.args()) parts.add(argument(a, 0));
                out.append(wrap("(", parts, ")", 0));
            }
            out.append('\n');
        }
    }

    private void block(Ast.Block b, int level) {
        out.append("{\n");
        for (Stmt s : b.stmts()) statement(s, level + 1);
        indent(level);
        out.append('}');
    }

    private void indent(int level) {
        out.append(STEP.repeat(level));
    }

    private void statement(Stmt s, int level) {
        indent(level);
        switch (s) {
            case Ast.Run r -> {
                out.append(expr(r.call(), TERNARY, level, Mode.VALUE));
                if (r.body() != null) {
                    out.append(' ');
                    block(r.body(), level);
                }
            }
            case Ast.Assign a -> out.append(assign(a, level));
            case Ast.MultiAssign m -> {
                List<String> targets = new ArrayList<>();
                List<String> values = new ArrayList<>();
                for (String n : m.names()) {
                    targets.add(name(n));
                    seen.add(n);
                }
                for (Expr e : m.values()) values.add(expr(e, TERNARY, level, Mode.VALUE));
                if (m.scope() != null) out.append(m.scope()).append(' ');
                out.append(String.join(", ", targets)).append(" = ").append(String.join(", ", values));
            }
            case Ast.Declare d -> {
                seen.add(d.name());
                out.append(d.scope()).append(' ').append(name(d.name()));
            }
            case Ast.If i -> conditional(i, level);
            case Ast.Jump j -> {
                out.append(j.kind());
                if (j.value() != null) out.append(' ').append(expr(j.value(), TERNARY, level, Mode.VALUE));
            }
            case Ast.Start st -> {
                List<String> parts = new ArrayList<>();
                for (Arg a : st.args()) {
                    if (a.name() != null && START_SETTINGS.contains(a.name()))
                        parts.add(a.name() + "=" + marker(a.value(), level + 1));
                    else parts.add(argument(a, level + 1));
                }
                out.append("start ").append(wrap((st.quoted() ? quotedName(st.name()) : functionName(st.name())) + "(", parts, ")", level));
            }
            case Ast.Repeat r -> {
                out.append("repeat_multi_times(amount=").append(expr(r.count(), TERNARY, level, Mode.VALUE)).append(") ");
                block(r.body(), level);
            }
            case Ast.ForRange f -> {
                seen.add(f.name());
                out.append("for ").append(name(f.name())).append(" in ").append(expr(f.from(), ADD, level, Mode.VALUE)).append("..=")
                        .append(expr(f.to(), ADD, level, Mode.VALUE));
                if (f.step() != null) out.append(" step ").append(expr(f.step(), TERNARY, level, Mode.VALUE));
                out.append(' ');
                block(f.body(), level);
            }
            case Ast.ForEach f -> {
                seen.add(f.second());
                if (f.first() != null) seen.add(f.first());
                out.append("for ");
                if (f.map()) out.append(name(f.first())).append(": ").append(name(f.second()));
                else if (f.first() == null) out.append(name(f.second()));
                else out.append(name(f.second())).append(", ").append(name(f.first()));
                out.append(" in ").append(expr(f.source(), ADD, level, Mode.VALUE)).append(' ');
                block(f.body(), level);
            }
            case Ast.While w -> {
                String condition = expr(w.condition(), TERNARY, level, Mode.CONDITION);
                boolean in = w.condition() instanceof Ast.Binary b && b.op().equals("in");
                out.append("for ").append(in ? "(" + condition + ")" : condition).append(' ');
                block(w.body(), level);
            }
            case Ast.Forever f -> {
                out.append("for ");
                block(f.body(), level);
            }
            case Ast.IndexAssign a -> out.append(expr(a.target(), ATOM, level, Mode.VALUE)).append(' ').append(a.op()).append(' ')
                    .append(expr(a.value(), TERNARY, level, Mode.VALUE));
        }
        out.append('\n');
    }

    private void conditional(Ast.If i, int level) {
        boolean first = true;
        for (Ast.Branch b : i.branches()) {
            out.append(first ? "if " : " else if ").append(expr(b.condition(), TERNARY, level, Mode.CONDITION)).append(' ');
            block(b.body(), level);
            first = false;
        }
        if (i.otherwise() != null) {
            out.append(" else ");
            block(i.otherwise(), level);
        }
    }

    private String assign(Ast.Assign a, int level) {
        String target = name(a.name());
        String value = expr(a.value(), TERNARY, level, Mode.VALUE);
        if (a.pinned()) return a.scope() + "." + target + " " + a.op() + " " + value;
        if (a.scope() != null) {
            seen.add(a.name());
            return a.scope() + " " + target + " " + a.op() + " " + value;
        }
        if (a.op().equals("+=") && textParams.contains(a.name()))
            return target + " = set_variable_add([" + target + ", " + value + "])";
        boolean fresh = a.op().equals("=") && seen.add(a.name()) && !textual(a.value());
        return target + (fresh ? " := " : " " + a.op() + " ") + value;
    }

    private static boolean textual(Expr e) {
        return e instanceof Ast.Str || e instanceof Ast.Call c && "var".equals(c.qualifier()) && c.name().equals("text");
    }

    private static int precedence(Expr e) {
        return switch (e) {
            case Ast.Ternary t -> TERNARY;
            case Ast.Unary u -> UNARY;
            case Ast.Binary b -> switch (b.op()) {
                case "or" -> OR;
                case "and" -> AND;
                case "+", "-" -> ADD;
                case "*", "/", "%" -> MUL;
                default -> COMPARE;
            };
            default -> ATOM;
        };
    }

    private String expr(Expr e, int parent, int level, Mode mode) {
        String s = switch (e) {
            case Ast.Num n -> n.text();
            case Ast.Str str -> string(str);
            case Ast.Var v -> variable(v);
            case Ast.Blank b -> "_";
            case Ast.ListLit l -> {
                List<String> parts = new ArrayList<>();
                for (Expr item : l.items()) parts.add(expr(item, TERNARY, level + 1, Mode.VALUE));
                yield wrap("[", parts, "]", level);
            }
            case Ast.MapLit m -> {
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < m.keys().size(); i++)
                    parts.add(expr(m.keys().get(i), ADD, level + 1, Mode.VALUE) + ": " + expr(m.values().get(i), TERNARY, level + 1, Mode.VALUE));
                yield wrap("{", parts, "}", level);
            }
            case Ast.Call c -> call(c, null, level, mode);
            case Ast.Path p -> path(p, null);
            case Ast.Targeted t -> {
                if (t.inner() instanceof Ast.Path p) yield path(p, t.target());
                if (t.inner() instanceof Ast.Call c) yield call(c, t.target(), level, mode);
                yield expr(t.inner(), parent, level, mode);
            }
            case Ast.Unary u -> {
                if (u.op().equals("not")) yield "!" + expr(u.operand(), UNARY, level, Mode.CONDITION);
                yield "-" + expr(u.operand(), UNARY, level, Mode.MATH);
            }
            case Ast.Binary b -> binary(b, level);
            case Ast.Index i -> expr(i.target(), ATOM, level, Mode.VALUE) + "[" + expr(i.index(), TERNARY, level, Mode.VALUE) + "]";
            case Ast.Ternary t -> expr(t.condition(), OR, level, Mode.CONDITION) + " ? " + expr(t.then(), TERNARY, level, Mode.VALUE)
                    + " : " + expr(t.otherwise(), TERNARY, level, Mode.VALUE);
        };
        return precedence(e) < parent ? "(" + s + ")" : s;
    }

    private String binary(Ast.Binary b, int level) {
        return switch (b.op()) {
            case "or" -> expr(b.left(), OR, level, Mode.CONDITION) + " || " + expr(b.right(), AND, level, Mode.CONDITION);
            case "and" -> expr(b.left(), AND, level, Mode.CONDITION) + " && " + expr(b.right(), COMPARE, level, Mode.CONDITION);
            case "+", "-" -> expr(b.left(), ADD, level, Mode.MATH) + " " + b.op() + " " + expr(b.right(), MUL, level, Mode.MATH);
            case "*", "/", "%" -> expr(b.left(), MUL, level, Mode.MATH) + " " + b.op() + " " + expr(b.right(), UNARY, level, Mode.MATH);
            case "in" -> {
                String left = expr(b.left(), ADD, level, Mode.VALUE);
                if (b.right() instanceof Ast.Binary r && r.op().equals(".."))
                    yield left + " in " + expr(r.left(), ADD, level, Mode.VALUE) + "..=" + expr(r.right(), ADD, level, Mode.VALUE);
                yield left + " in " + expr(b.right(), ADD, level, Mode.VALUE);
            }
            default -> expr(b.left(), ADD, level, Mode.VALUE) + " " + b.op() + " " + expr(b.right(), ADD, level, Mode.VALUE);
        };
    }

    private String path(Ast.Path p, String target) {
        boolean value = p.parts().size() == 2 && p.parts().get(0).equals("value");
        if (!value) return p.joined();
        String id = quoted(p.parts().get(1));
        return "game_value(" + id + (target == null ? "" : ", " + quoted(target)) + ")";
    }

    private String call(Ast.Call c, String target, int level, Mode mode) {
        if (c.quoted()) return invoke(quotedName(c.name()), c.args(), level);
        if (c.qualifier() == null) {
            if (functions.contains(c.name())) return invoke(functionName(c.name()), c.args(), level);
            if (FACTORIES.contains(c.name())) return factory(c, level);
            if (MATH.contains(c.name()) && (mode == Mode.MATH || names.resolve(Names.Space.ACTION, c.name()) == null)) {
                List<String> parts = new ArrayList<>();
                for (Arg a : c.args()) parts.add(expr(a.value(), TERNARY, level + 1, Mode.MATH));
                return wrap(c.name() + "(", parts, ")", level);
            }
        }
        Names.Entry e = entry(c.shown(), mode);
        String head = e == null ? c.shown() : functions.contains(e.action().id()) ? e.qualified() : e.action().id();
        List<String> parts = e == null ? plain(c.args(), level) : arguments(e.action(), c.args(), level);
        if (target != null) parts.add("selection=" + quoted(target));
        return wrap(head + "(", parts, ")", level);
    }

    private Names.Entry entry(String shown, Mode mode) {
        Names.Space first = mode == Mode.CONDITION ? Names.Space.CONDITION : Names.Space.ACTION;
        Names.Space second = mode == Mode.CONDITION ? Names.Space.ACTION : Names.Space.CONDITION;
        Names.Entry e = names.resolve(first, shown);
        return e != null ? e : names.resolve(second, shown);
    }

    private List<String> arguments(Data.Action a, List<Arg> args, int level) {
        List<String> parts = new ArrayList<>();
        boolean condition = CONDITIONAL.contains(a.canonical());
        int positional = 0;
        for (int i = 0; i < args.size(); i++) {
            Arg x = args.get(i);
            if (x.name() != null) {
                boolean setting = a.setting(x.name().toLowerCase(Locale.ROOT)) != null;
                parts.add(x.name() + "=" + (setting ? marker(x.value(), level + 1) : expr(x.value(), TERNARY, level + 1, Mode.VALUE)));
            } else if (a.category().condition && positional >= a.args().size()) {
                parts.add(marker(x.value(), level + 1));
            } else {
                parts.add(expr(x.value(), TERNARY, level + 1, condition && i == 0 ? Mode.CONDITION : Mode.VALUE));
                positional++;
            }
        }
        return parts;
    }

    private String marker(Expr value, int level) {
        if (value instanceof Ast.Var v && !v.quoted() && v.scope() == null && !constants.contains(v.name()))
            return quoted(v.name().toUpperCase(Locale.ROOT));
        if (value instanceof Ast.Str s && !s.interpolated()) return quoted(s.text());
        return expr(value, TERNARY, level, Mode.VALUE);
    }

    private List<String> plain(List<Arg> args, int level) {
        List<String> parts = new ArrayList<>();
        for (Arg a : args) parts.add(argument(a, level + 1));
        return parts;
    }

    private String argument(Arg a, int level) {
        String value = expr(a.value(), TERNARY, level, Mode.VALUE);
        return a.name() == null ? value : a.name() + "=" + value;
    }

    private String invoke(String head, List<Arg> args, int level) {
        return wrap(head + "(", plain(args, level), ")", level);
    }

    private String factory(Ast.Call c, int level) {
        List<String> parts = new ArrayList<>();
        boolean first = true;
        for (Arg a : c.args()) {
            boolean text = first && a.name() == null && IDENTIFIED.contains(c.name()) || literal(c.name(), a.name());
            String value = text ? identifier(a.value(), level + 1) : expr(a.value(), TERNARY, level + 1, Mode.VALUE);
            parts.add(a.name() == null ? value : a.name() + "=" + value);
            first = false;
        }
        return wrap(FACTORY_NAMES.getOrDefault(c.name(), c.name()) + "(", parts, ")", level);
    }

    private static boolean literal(String factory, String field) {
        if (field == null) return false;
        return switch (factory) {
            case "item" -> field.equals("components");
            case "sound" -> field.equals("source") || field.equals("variation");
            case "particle" -> field.equals("material") || field.equals("color") || field.equals("to_color");
            default -> false;
        };
    }

    private String identifier(Expr e, int level) {
        return switch (e) {
            case Ast.Var v when !constants.contains(v.name()) && v.scope() == null -> quoted(v.name());
            case Ast.Path p -> quoted(p.joined());
            case Ast.Str s when !s.interpolated() -> quoted(s.text());
            default -> expr(e, TERNARY, level, Mode.VALUE);
        };
    }

    private String variable(Ast.Var v) {
        if (v.scope() != null) return v.scope() + "." + name(v.name());
        if (v.quoted()) return quotedName(v.name());
        return v.name().equals("true") || v.name().equals("false") ? v.name() : name(v.name());
    }

    private static boolean plainName(String s) {
        return Lexer.identifier(s) && !RESERVED.contains(s);
    }

    private static String name(String s) {
        return plainName(s) ? s : quotedName(s);
    }

    private static String quotedName(String s) {
        return "`" + s + "`";
    }

    private static String functionName(String s) {
        return plainName(s) || Keywords.dotted(s) ? s : quotedName(s);
    }

    private static String wrap(String open, List<String> parts, String close, int level) {
        String flat = open + String.join(", ", parts) + close;
        if (level < 0 || flat.indexOf('\n') < 0 && level * STEP.length() + flat.length() <= WIDTH) return flat;
        String pad = STEP.repeat(level + 1);
        StringBuilder lines = new StringBuilder(open).append('\n');
        StringBuilder line = new StringBuilder(pad);
        for (String part : parts) {
            boolean tall = part.indexOf('\n') >= 0;
            if (line.length() > pad.length() && (tall || line.length() + part.length() + 1 > WIDTH)) {
                lines.append(line.toString().stripTrailing()).append('\n');
                line = new StringBuilder(pad);
            }
            line.append(part).append(", ");
            if (tall) {
                lines.append(line.toString().stripTrailing()).append('\n');
                line = new StringBuilder(pad);
            }
        }
        if (line.length() > pad.length()) lines.append(line.toString().stripTrailing()).append('\n');
        return lines.append(STEP.repeat(level)).append(close).toString();
    }

    private String string(Ast.Str s) {
        List<Object> parts = s.parts() != null ? s.parts() : List.of(s.text());
        char quote = quoteFor(parts);
        StringBuilder body = new StringBuilder();
        StringBuilder whole = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof String literal) {
                body.append(escaped(literal, quote));
                whole.append(literal);
            } else {
                String code = "${" + expr((Expr) part, TERNARY, FLAT, Mode.VALUE) + "}";
                body.append(code);
                whole.append(code);
            }
        }
        String wanted = "legacy".equals(s.prefix()) ? null : s.prefix();
        String detected = Markup.detect(whole.toString());
        String prefix = Objects.equals(wanted, detected) ? "" : wanted == null ? "legacy" : wanted;
        return prefix + quote + body + quote;
    }

    private static char quoteFor(List<Object> parts) {
        boolean doubled = false, single = false;
        for (Object part : parts) {
            if (!(part instanceof String literal)) continue;
            doubled |= literal.indexOf('"') >= 0;
            single |= literal.indexOf('\'') >= 0;
        }
        return doubled && !single ? '\'' : '"';
    }

    private static String quoted(String s) {
        char quote = quoteFor(List.of(s));
        return quote + escaped(s, quote) + quote;
    }

    private static String escaped(String s, char quote) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
            if (c == quote) {
                body.append('\\').append(c);
            } else if (c == '$' && next == '{') {
                body.append("\\$");
            } else if (c == '\\') {
                boolean special = next == '"' || next == '\'' || next == '\\' || next == 'u' || next == '$' || next == '\0';
                body.append(special ? "\\\\" : "\\");
            } else if (c < 0x20 || c == 0x7F) {
                String hex = Integer.toHexString(c);
                body.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
            } else {
                body.append(c);
            }
        }
        return body.toString();
    }
}
