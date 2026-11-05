package xero.dialect.jmcc;

import xero.data.Data;
import xero.data.Names;
import xero.lang.Ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Print {
    private static final Map<String, String> PARTICLE_NAMES = Map.of("first_spread", "spread_x", "second_spread", "spread_y",
            "x_motion", "motion_x", "y_motion", "motion_y", "z_motion", "motion_z");
    private static final Set<String> COMPARE = Set.of("==", "!=", ">", ">=", "<", "<=", "in");
    private static final String STEP = "    ";
    private static final String HEX = "0123456789abcdef";

    private final Table t;
    private final Map<String, Ast.Func> functions = new HashMap<>();
    private final StringBuilder out = new StringBuilder();
    private int formula;

    private Print(Table t) { this.t = t; }

    static String file(Table t, Ast.File f) {
        Print p = new Print(t);
        for (Ast.Item i : f.items()) if (i instanceof Ast.Func x) p.functions.put(x.name(), x);
        p.items(f.items());
        return p.out.toString();
    }

    private void items(List<Ast.Item> items) {
        boolean plain = false;
        boolean first = true;
        for (Ast.Item i : items) {
            boolean declaration = i instanceof Ast.Global || i instanceof Ast.Const || i instanceof Ast.Import;
            if (!first && !(declaration && plain)) out.append('\n');
            plain = declaration;
            first = false;
            switch (i) {
                case Ast.Global g -> out.append(g.scope()).append(" var ").append(name(g.name())).append(";\n");
                case Ast.Const c -> out.append("const ").append(name(c.name())).append(" = ").append(expr(c.value(), 0)).append(";\n");
                case Ast.Import m -> out.append("import ").append(quote(m.path())).append(m.connect() ? " connect" : "").append(";\n");
                case Ast.Event e -> {
                    marks(e.decorators());
                    out.append("event<").append(e.name()).append("> ");
                    block(e.body(), 0);
                    out.append('\n');
                }
                case Ast.Func x -> {
                    marks(x.decorators());
                    out.append(x.process() ? "process " : "function ").append(function(x.name())).append('(');
                    List<String> params = new ArrayList<>();
                    for (Ast.Param p : x.params()) params.add(param(p));
                    out.append(String.join(", ", params)).append(") ");
                    block(x.body(), 0);
                    out.append('\n');
                }
            }
        }
    }

    private void marks(List<Ast.Decorator> marks) {
        for (Ast.Decorator d : marks) {
            out.append('@').append(d.name());
            if (!d.args().isEmpty()) {
                List<String> args = new ArrayList<>();
                for (Ast.Arg a : d.args()) args.add(a.name() == null ? expr(a.value(), 0) : a.name() + " = " + expr(a.value(), 0));
                out.append('(').append(String.join(", ", args)).append(')');
            }
            out.append('\n');
        }
    }

    private String param(Ast.Param p) {
        StringBuilder b = new StringBuilder(name(p.name()));
        if (p.options() != null) {
            List<String> options = new ArrayList<>();
            for (String o : p.options()) options.add(quote(o));
            b.append(": enum(").append(String.join(", ", options)).append(')');
        } else if (p.type() != null) {
            b.append(": ").append(p.type());
        }
        if (p.fallback() != null) b.append(" = ").append(expr(p.fallback(), 0));
        return b.toString();
    }

    private void block(Ast.Block b, int depth) {
        if (b.stmts().isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        for (Ast.Stmt s : b.stmts()) {
            out.append(STEP.repeat(depth + 1));
            statement(s, depth + 1);
            out.append('\n');
        }
        out.append(STEP.repeat(depth)).append('}');
    }

    private void statement(Ast.Stmt s, int depth) {
        switch (s) {
            case Ast.Run r -> run(r, depth);
            case Ast.Assign a -> out.append(assignTarget(a)).append(' ').append(a.op()).append(' ').append(expr(a.value(), 0)).append(';');
            case Ast.IndexAssign a -> out.append(expr(a.target(), 0)).append(' ').append(a.op()).append(' ').append(expr(a.value(), 0)).append(';');
            case Ast.MultiAssign m -> {
                List<String> names = new ArrayList<>();
                for (String n : m.names()) names.add(name(n));
                List<String> values = new ArrayList<>();
                for (Ast.Expr v : m.values()) values.add(expr(v, 0));
                if (m.scope() != null) out.append(m.scope()).append(" var ");
                out.append(String.join(", ", names)).append(" = ").append(String.join(", ", values)).append(';');
            }
            case Ast.Declare d -> out.append(d.scope()).append(" var ").append(name(d.name())).append(';');
            case Ast.If i -> {
                boolean first = true;
                for (Ast.Branch b : i.branches()) {
                    out.append(first ? "if " : " else if ").append(condition(b.condition(), 0)).append(' ');
                    block(b.body(), depth);
                    first = false;
                }
                if (i.otherwise() != null) {
                    out.append(" else ");
                    block(i.otherwise(), depth);
                }
            }
            case Ast.Jump j -> {
                out.append(j.kind());
                if (j.value() != null) out.append(' ').append(expr(j.value(), 0));
                out.append(';');
            }
            case Ast.Start st -> start(st);
            case Ast.Repeat r -> {
                out.append("repeat::multi_times(amount = ").append(expr(r.count(), 0)).append(") ");
                block(r.body(), depth);
            }
            case Ast.ForRange f -> {
                String range = expr(f.from(), 5) + "..=" + expr(f.to(), 5);
                out.append("for ").append(name(f.name())).append(" in ");
                out.append(f.step() == null ? range : "(" + range + ").step_by(" + expr(f.step(), 0) + ")").append(' ');
                block(f.body(), depth);
            }
            case Ast.ForEach f -> forEach(f, depth);
            case Ast.While w -> {
                out.append("while ").append(condition(w.condition(), 0)).append(' ');
                block(w.body(), depth);
            }
            case Ast.Forever f -> {
                out.append("repeat::forever() ");
                block(f.body(), depth);
            }
        }
    }

    private void forEach(Ast.ForEach f, int depth) {
        if (f.first() == null && !f.map()) {
            out.append("for ").append(name(f.second())).append(" in ").append(expr(f.source(), 0)).append(' ');
            block(f.body(), depth);
            return;
        }
        out.append(f.map() ? "repeat::for_each_map_entry(" : "repeat::for_each_in_list(").append(expr(f.source(), 0)).append(") { ");
        out.append(name(f.first())).append(", ").append(name(f.second())).append(" ->\n");
        for (Ast.Stmt s : f.body().stmts()) {
            out.append(STEP.repeat(depth + 1));
            statement(s, depth + 1);
            out.append('\n');
        }
        out.append(STEP.repeat(depth)).append('}');
    }

    private String assignTarget(Ast.Assign a) {
        if (a.pinned()) return pinned(a.name(), a.scope());
        return (a.scope() == null ? "" : a.scope() + " var ") + name(a.name());
    }

    private void run(Ast.Run r, int depth) {
        Ast.Expr e = r.call();
        if (e instanceof Ast.Blank) {
            out.append("_;");
            return;
        }
        String target = null;
        if (e instanceof Ast.Targeted x) {
            target = x.target();
            e = x.inner();
        }
        if (!(e instanceof Ast.Call c)) {
            out.append(expr(e, 0)).append(';');
            return;
        }
        if (c.qualifier() == null && c.name().equals("call") && !c.quoted()) {
            out.append(dynamicCall(c)).append(';');
            return;
        }
        out.append(call(c, target, false, false));
        if (r.body() != null) {
            out.append(' ');
            block(r.body(), depth);
        } else if (needsBody(c)) {
            out.append(" {}");
        } else {
            out.append(';');
        }
    }

    private boolean needsBody(Ast.Call c) {
        Names.Entry e = action(c, false);
        return e != null && e.action().container();
    }

    private String dynamicCall(Ast.Call c) {
        StringBuilder b = new StringBuilder("code::call_function(");
        List<String> pairs = new ArrayList<>();
        boolean first = true;
        for (Ast.Arg a : c.args()) {
            if (first) b.append(expr(a.value(), 0));
            else pairs.add(quote(a.name()) + ": " + expr(a.value(), 0));
            first = false;
        }
        if (!pairs.isEmpty()) b.append(", args = {").append(String.join(", ", pairs)).append('}');
        return b.append(')').toString();
    }

    private void start(Ast.Start s) {
        String mode = null;
        String target = null;
        List<Ast.Arg> params = new ArrayList<>();
        for (Ast.Arg a : s.args()) {
            String word = a.value() instanceof Ast.Var v ? v.name() : null;
            if ("local_variables_mode".equals(a.name())) mode = word;
            else if ("target_mode".equals(a.name())) target = word;
            else params.add(a);
        }
        Ast.Func f = functions.get(s.name());
        if (f != null && f.process() && "copy".equals(mode) && "current_target".equals(target)) {
            List<String> args = new ArrayList<>();
            for (Ast.Arg a : params) args.add(a.name() == null ? expr(a.value(), 0) : a.name() + " = " + expr(a.value(), 0));
            out.append(function(s.name())).append('(').append(String.join(", ", args)).append(");");
            return;
        }
        List<String> parts = new ArrayList<>();
        parts.add(quote(s.name()));
        if (mode != null) parts.add("local_variables_mode = " + quote(mode.toUpperCase(Locale.ROOT)));
        if (target != null) parts.add("target_mode = " + quote(target.toUpperCase(Locale.ROOT)));
        if (!params.isEmpty()) {
            List<String> pairs = new ArrayList<>();
            for (int i = 0; i < params.size(); i++) {
                Ast.Arg a = params.get(i);
                String key = a.name() != null ? a.name() : f != null && i < f.params().size() ? f.params().get(i).name() : String.valueOf(i);
                pairs.add(quote(key) + ": " + expr(a.value(), 0));
            }
            parts.add("args = {" + String.join(", ", pairs) + "}");
        }
        out.append("code::start_process(").append(String.join(", ", parts)).append(");");
    }

    private Names.Entry action(Ast.Call c, boolean conditionFirst) {
        Names names = t.names();
        String shown = c.shown();
        Names.Entry first = names.resolve(conditionFirst ? Names.Space.CONDITION : Names.Space.ACTION, shown);
        return first != null ? first : names.resolve(conditionFirst ? Names.Space.ACTION : Names.Space.CONDITION, shown);
    }

    private String call(Ast.Call c, String target, boolean test, boolean skipResult) {
        if (c.qualifier() == null) {
            if (c.quoted() || functions.containsKey(c.name())) return userCall(c);
            String factory = factory(c);
            if (factory != null) return factory;
            if (Table.MATH.contains(c.name()) && (formula > 0 || action(c, false) == null)) return math(c);
        }
        Names.Entry e = action(c, test);
        if (e == null) return userCall(c);
        Data.Action a = e.action();
        Table.Entry x = t.byId(a.id());
        boolean result = skipResult && !a.category().condition;
        StringBuilder b = new StringBuilder(x.label());
        if (target != null) b.append('<').append(t.selector(a.family(), target)).append('>');
        b.append('(');
        if (Table.CONDITIONAL.contains(a.id()) && !c.args().isEmpty()) b.append(condition(c.args().get(0).value(), 0));
        else b.append(arguments(x, a, c.args(), result));
        return b.append(')').toString();
    }

    private String math(Ast.Call c) {
        List<String> args = new ArrayList<>();
        int saved = formula;
        formula = 1;
        for (Ast.Arg a : c.args()) args.add(a.name() == null ? expr(a.value(), 0) : a.name() + " = " + expr(a.value(), 0));
        formula = saved;
        return c.name() + "(" + String.join(", ", args) + ")";
    }

    private String userCall(Ast.Call c) {
        List<String> args = new ArrayList<>();
        int saved = formula;
        formula = 0;
        for (Ast.Arg a : c.args()) args.add(a.name() == null ? expr(a.value(), 0) : a.name() + " = " + expr(a.value(), 0));
        formula = saved;
        return function(c.name()) + "(" + String.join(", ", args) + ")";
    }

    private String arguments(Table.Entry x, Data.Action a, List<Ast.Arg> args, boolean skipResult) {
        Data.Arg result = skipResult ? a.result() : null;
        List<String> order = new ArrayList<>();
        for (Data.Arg arg : a.args()) if (arg != result) order.add(arg.id());
        for (Data.Setting s : a.settings()) order.add(s.id());
        Map<String, Ast.Expr> bound = new LinkedHashMap<>();
        int positional = 0;
        for (Ast.Arg g : args) {
            String key = g.name() != null ? g.name().toLowerCase(Locale.ROOT) : positional < order.size() ? order.get(positional) : null;
            if (g.name() == null) positional++;
            if (key != null) bound.put(key, g.value());
        }
        List<String> parts = new ArrayList<>();
        boolean inOrder = true;
        int saved = formula;
        formula = 0;
        for (String id : x.order()) {
            if (skipResult && (x.result().contains(id) || result != null && result.id().equals(id))) continue;
            Ast.Expr v = bound.remove(id);
            if (v == null) {
                inOrder = false;
                continue;
            }
            String printed = argument(x, id, v);
            parts.add(inOrder ? printed : id + " = " + printed);
        }
        for (Map.Entry<String, Ast.Expr> rest : bound.entrySet()) parts.add(rest.getKey() + " = " + argument(x, rest.getKey(), rest.getValue()));
        formula = saved;
        return String.join(", ", parts);
    }

    private String argument(Table.Entry x, String id, Ast.Expr v) {
        if (x.setting(id)) {
            if (v instanceof Ast.Var w) return quote(w.name().toUpperCase(Locale.ROOT));
            if (v instanceof Ast.Str s && !s.interpolated()) return quote(s.text().toUpperCase(Locale.ROOT));
        }
        return expr(v, 0);
    }

    private String factory(Ast.Call c) {
        return switch (c.name()) {
            case "item" -> item(c);
            case "loc" -> plainFactory("location", c);
            case "vec" -> plainFactory("vector", c);
            case "block" -> "block(" + quote(id(c.args().get(0).value())) + ")";
            case "sound", "potion" -> fielded(c, Map.of());
            case "particle" -> fielded(c, PARTICLE_NAMES);
            case "num", "raw", "enum", "len", "literal" -> plainFactory(c.name(), c);
            default -> null;
        };
    }

    private String plainFactory(String name, Ast.Call c) {
        List<String> args = new ArrayList<>();
        int saved = formula;
        formula = 0;
        for (Ast.Arg a : c.args()) args.add(a.name() == null ? expr(a.value(), 0) : a.name() + " = " + expr(a.value(), 0));
        formula = saved;
        return name + "(" + String.join(", ", args) + ")";
    }

    private static String id(Ast.Expr e) {
        return switch (e) {
            case Ast.Var v -> v.name();
            case Ast.Path p -> p.joined();
            case Ast.Str s -> s.text();
            default -> "";
        };
    }

    private String fielded(Ast.Call c, Map<String, String> names) {
        List<String> args = new ArrayList<>();
        boolean first = true;
        for (Ast.Arg a : c.args()) {
            if (first) {
                args.add(quote(id(a.value())));
                first = false;
                continue;
            }
            String key = a.name() == null ? null : names.getOrDefault(a.name(), a.name());
            args.add(key + " = " + field(a.name(), a.value()));
        }
        return c.name() + "(" + String.join(", ", args) + ")";
    }

    private String field(String name, Ast.Expr v) {
        if ("source".equals(name)) return quote(id(v).toUpperCase(Locale.ROOT));
        if ("material".equals(name) && !(v instanceof Ast.Str)) return quote(id(v));
        return expr(v, 0);
    }

    private String item(Ast.Call c) {
        List<String> args = new ArrayList<>();
        int positional = 0;
        for (Ast.Arg a : c.args()) {
            if (a.name() == null) {
                args.add(positional == 0 ? quote(id(a.value())) : "count = " + expr(a.value(), 0));
                positional++;
                continue;
            }
            String key = a.name().equals("tags") ? "custom_tags" : a.name();
            args.add(key + " = " + itemField(a.value()));
        }
        return "item(" + String.join(", ", args) + ")";
    }

    private String itemField(Ast.Expr v) {
        if (!(v instanceof Ast.MapLit m)) return expr(v, 0);
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < m.keys().size(); i++) {
            Ast.Expr k = m.keys().get(i);
            String key = k instanceof Ast.Str || k instanceof Ast.Var || k instanceof Ast.Path ? quote(id(k)) : expr(k, 0);
            pairs.add(key + ": " + expr(m.values().get(i), 0));
        }
        return "{" + String.join(", ", pairs) + "}";
    }

    private String condition(Ast.Expr e, int parent) {
        return switch (e) {
            case Ast.Binary b when b.op().equals("and") || b.op().equals("or") -> {
                int own = b.op().equals("or") ? 1 : 2;
                String s = condition(b.left(), own) + " " + b.op() + " " + condition(b.right(), own + 1);
                yield own < parent ? "(" + s + ")" : s;
            }
            case Ast.Unary u when u.op().equals("not") -> {
                String s = "not " + condition(u.operand(), 4);
                yield parent > 3 ? "(" + s + ")" : s;
            }
            case Ast.Call c -> call(c, null, true, true);
            case Ast.Targeted x when x.inner() instanceof Ast.Call c -> call(c, x.target(), true, true);
            default -> expr(e, parent);
        };
    }

    private String expr(Ast.Expr e, int parent) {
        return switch (e) {
            case Ast.Num n -> n.text();
            case Ast.Str s -> string(s);
            case Ast.Var v -> variable(v);
            case Ast.Blank b -> "_";
            case Ast.ListLit l -> {
                List<String> items = new ArrayList<>();
                int saved = formula;
                formula = 0;
                for (Ast.Expr x : l.items()) items.add(expr(x, 0));
                formula = saved;
                yield "[" + String.join(", ", items) + "]";
            }
            case Ast.MapLit m -> {
                List<String> pairs = new ArrayList<>();
                int saved = formula;
                formula = 0;
                for (int i = 0; i < m.keys().size(); i++) pairs.add(expr(m.keys().get(i), 5) + ": " + expr(m.values().get(i), 0));
                formula = saved;
                yield "{" + String.join(", ", pairs) + "}";
            }
            case Ast.Call c -> call(c, null, false, true);
            case Ast.Path p -> "value::" + p.parts().get(p.parts().size() - 1);
            case Ast.Targeted x -> targeted(x);
            case Ast.Unary u when u.op().equals("-") -> {
                String s = "-" + expr(u.operand(), 7);
                yield parent > 7 ? "(" + s + ")" : s;
            }
            case Ast.Unary u -> condition(u, parent);
            case Ast.Binary b -> binary(b, parent);
            case Ast.Index i -> expr(i.target(), 8) + "[" + expr(i.index(), 0) + "]";
            case Ast.Ternary x -> {
                String s = condition(x.condition(), 1) + " ? " + expr(x.then(), 1) + " : " + expr(x.otherwise(), 1);
                yield parent > 0 ? "(" + s + ")" : s;
            }
        };
    }

    private String targeted(Ast.Targeted x) {
        if (x.inner() instanceof Ast.Path p) {
            return "value::" + p.parts().get(p.parts().size() - 1) + "<" + t.selector("value", x.target()) + ">";
        }
        if (x.inner() instanceof Ast.Call c) return call(c, x.target(), false, true);
        return expr(x.inner(), 8);
    }

    private String binary(Ast.Binary b, int parent) {
        String op = b.op();
        if (op.equals("and") || op.equals("or")) return condition(b, parent);
        if (op.equals("..")) return expr(b.left(), 5) + "..=" + expr(b.right(), 5);
        if (COMPARE.contains(op)) {
            String s = expr(b.left(), 4) + " " + op + " " + expr(b.right(), 4);
            return parent > 3 ? "(" + s + ")" : s;
        }
        int own = op.equals("+") || op.equals("-") ? 5 : 6;
        formula++;
        String s = expr(b.left(), own) + " " + op + " " + expr(b.right(), own + 1);
        formula--;
        return own < parent ? "(" + s + ")" : s;
    }

    private String variable(Ast.Var v) {
        if (v.scope() != null) return pinned(v.name(), v.scope());
        if (!v.quoted() && (Words.TRUE.contains(v.name()) || Words.FALSE.contains(v.name()))) return v.name();
        return name(v.name());
    }

    private static String pinned(String name, String scope) {
        String prefix = switch (scope) {
            case "game" -> "g";
            case "save" -> "s";
            case "line" -> "line";
            default -> "l";
        };
        return prefix + "`" + escaped(name, '`') + "`";
    }

    private static String name(String name) {
        if (plain(name)) return name;
        return "`" + escaped(name, '`') + "`";
    }

    private static boolean plain(String name) {
        if (name.isEmpty() || !Lex.wordStart(name.charAt(0)) || Words.keyword(name) || name.equals("_")) return false;
        for (int i = 1; i < name.length(); i++) if (!Lex.wordPart(name.charAt(i))) return false;
        return true;
    }

    private String function(String name) { return name(name); }

    private String string(Ast.Str s) {
        String prefix = s.prefix() == null ? "" : switch (s.prefix()) {
            case "mm" -> "m";
            case "plain" -> "p";
            case "json" -> "j";
            default -> "l";
        };
        if (!s.interpolated()) return prefix + "\"" + escaped(s.text(), '"') + "\"";
        StringBuilder b = new StringBuilder(prefix).append('"');
        List<Object> parts = s.parts();
        for (int i = 0; i < parts.size(); i++) {
            Object part = parts.get(i);
            if (part instanceof String text) {
                b.append(escaped(text, '"'));
                continue;
            }
            int saved = formula;
            formula = 0;
            b.append("${").append(expr((Ast.Expr) part, 0)).append('}');
            formula = saved;
        }
        return b.append('"').toString();
    }

    private static String quote(String s) { return "\"" + escaped(s, '"') + "\""; }

    private static String escaped(String s, char quote) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
            if (c == quote) {
                b.append('\\').append(c);
            } else if (c == '\\') {
                boolean known = next == '"' || next == '\'' || next == '`' || next == '\\' || next == '$' || next == 'u' || next == '\0';
                b.append(known ? "\\\\" : "\\");
            } else if (c == '$' && (next == '{' || next == '%' || Lex.wordStart(next))) {
                b.append("\\$");
            } else if (c < 0x20 || c == 0x7F) {
                b.append("\\u00").append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 15));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
