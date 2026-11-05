package xero.dialect.jmcc;

import xero.compile.Wire;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;
import xero.lang.Ast;
import xero.lang.Ast.Span;
import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Lower {
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Stop() { super(null, null, false, false); }
    }

    private static final class Env {
        final Env up;
        final Map<String, String> scopes = new HashMap<>();
        final Map<String, Tree.Expr> inlines = new HashMap<>();

        Env(Env up) { this.up = up; }
    }

    private static final class Fn {
        final String name;
        final Set<String> params = new HashSet<>();
        final Map<String, String> kinds = new HashMap<>();

        Fn(String name) { this.name = name; }
    }

    private record Through(String kind, String label) {}

    private static final class Loop {
        final String label;
        final List<Through> through = new ArrayList<>();
        final Set<String> flags = new HashSet<>();

        Loop(String label) { this.label = label; }
    }

    private static final Map<String, String> PARTICLE_NAMES = Map.of("spread_x", "first_spread", "spread_y", "second_spread",
            "motion_x", "x_motion", "motion_y", "y_motion", "motion_z", "z_motion");
    private static final List<String> PARTICLE_ORDER = List.of("particle", "count", "spread_x", "spread_y", "motion_x",
            "motion_y", "motion_z", "material", "color", "size", "to_color");
    private static final List<String> ITEM_ORDER = List.of("id", "name", "count", "lore", "nbt", "custom_tags");
    private static final List<String> SOUND_ORDER = List.of("sound", "volume", "pitch", "variation", "source");
    private static final List<String> POTION_ORDER = List.of("potion", "amplifier", "duration");
    private static final List<String> LOCATION_ORDER = List.of("x", "y", "z", "yaw", "pitch");
    private static final List<String> VECTOR_ORDER = List.of("x", "y", "z");
    private static final Set<String> PASSED = Set.of("raw", "num", "enum", "len", "call", "literal");
    private static final String TEMP = "__jc";
    private static final int DEEPEST = 60;

    private final Project p;
    private final Table t;
    private final List<Diagnostic> out;
    private final boolean last;
    private final String file;
    private final List<Loop> loops = new ArrayList<>();
    private final Map<String, Ast.Global> globals = new LinkedHashMap<>();
    private final Set<String> told = new HashSet<>();
    private List<Ast.Item> hoisted = new ArrayList<>();
    private List<Ast.Stmt> pre = new ArrayList<>();
    private Env env = new Env(null);
    private Fn fn;
    private Span over;
    private int formula;
    private int temps;
    private int depth;

    Lower(Project p, List<Diagnostic> out, boolean last, String file) {
        this.p = p;
        this.t = p.table;
        this.out = out;
        this.last = last;
        this.file = file;
    }

    Ast.File unit(Tree.Unit u) {
        List<Ast.Item> items = new ArrayList<>();
        List<Tree.Stmt> world = new ArrayList<>();
        List<Tree.Stmt> declared = new ArrayList<>();
        for (Tree.Stmt s : u.items()) {
            switch (s) {
                case Tree.Import i -> items.add(new Ast.Import(p.importPath(file, i.path()), i.connect(), i.span()));
                case Tree.Event e -> declared.add(e);
                case Tree.Func f -> declared.add(f);
                case Tree.Enum e -> { }
                case Tree.Alias a -> { }
                case Tree.Foreign f -> foreign(f);
                case Tree.Var v when "inline".equals(v.scope()) -> { }
                case Tree.Var v when v.values().isEmpty() -> head(v);
                case Tree.Assign a when Project.inline(a) != null -> { }
                default -> world.add(s);
            }
        }
        if (!world.isEmpty()) {
            Span first = world.get(0).span();
            Span whole = first.to(world.get(world.size() - 1).span());
            List<Ast.Stmt> body = new ArrayList<>();
            for (Tree.Stmt s : world) statement(s, body);
            if (!body.isEmpty()) items.add(new Ast.Event("world_start", first, new Ast.Block(body, whole), List.of(), whole));
        }
        items.addAll(hoisted);
        hoisted = new ArrayList<>();
        for (Tree.Stmt s : declared) {
            guard(() -> {
                if (s instanceof Tree.Event e) event(e, items);
                else func((Tree.Func) s, items);
            });
            items.addAll(hoisted);
            hoisted = new ArrayList<>();
        }
        List<Ast.Item> all = new ArrayList<>(globals.values());
        all.addAll(items);
        return new Ast.File(all);
    }

    private void head(Tree.Var v) {
        List<Ast.Stmt> found = new ArrayList<>();
        statement(v, found);
        for (Ast.Stmt s : found) if (s instanceof Ast.Declare d) globals.put(d.name(), new Ast.Global(d.scope(), d.name(), d.span()));
    }

    private void guard(Runnable work) {
        try {
            work.run();
        } catch (Stop ignored) {
        }
    }

    private void foreign(Tree.Foreign f) {
        if (f.kind().equals("class"))
            error("классы не поддерживаются: «" + f.name() + "» — на сервере их нет", f.span(), "используй словарь и функции");
        else error("интерфейсы не поддерживаются: на сервере их нет", f.span());
    }

    private void error(String message, Span at) { error(message, at, null); }

    private void error(String message, Span at, String hint) {
        Span s = sp(at);
        out.add(Diagnostic.error(message, s.start(), s.end(), hint));
    }

    private void warning(String message, Span at) {
        Span s = sp(at);
        out.add(Diagnostic.warning(message, s.start(), s.end()));
    }

    private Stop stop(String message, Span at) { return stop(message, at, null); }

    private Stop stop(String message, Span at, String hint) {
        error(message, at, hint);
        return new Stop();
    }

    private Span sp(Span s) { return over != null ? over : s; }

    private void event(Tree.Event e, List<Ast.Item> items) {
        String name = t.eventId(e.name());
        if (t.staleEvent(name) && !t.data().events().containsKey(name)) {
            warning("событие «" + name + "» устарело: сервер его больше не знает — код внутри не попадёт в мир", e.nameSpan());
            return;
        }
        List<Ast.Decorator> marks = marks(e.marks());
        Env saved = env;
        Fn savedFn = fn;
        fn = null;
        env = new Env(env);
        List<Loop> outer = new ArrayList<>(loops);
        loops.clear();
        Ast.Block body = block(e.body(), false);
        loops.addAll(outer);
        env = saved;
        fn = savedFn;
        items.add(new Ast.Event(name, sp(e.nameSpan()), body, marks, sp(e.span())));
    }

    private static boolean overload(List<Tree.Mark> marks) {
        for (Tree.Mark m : marks) if (m.name().equals("overload")) return true;
        return false;
    }

    private void func(Tree.Func f, List<Ast.Item> items) {
        if (overload(f.marks())) {
            error("перегрузки не поддерживаются: у функции одно имя в мире — назови её иначе", f.nameSpan());
            return;
        }
        if (f.body() == null) {
            error("у «" + f.name() + "» нет тела", f.nameSpan());
            return;
        }
        Env saved = env;
        Fn savedFn = fn;
        List<Loop> outer = new ArrayList<>(loops);
        List<Ast.Stmt> savedPre = pre;
        loops.clear();
        pre = new ArrayList<>();
        env = new Env(env);
        fn = new Fn(f.name());
        List<Ast.Param> params = new ArrayList<>();
        for (Tree.Param x : f.params()) {
            if (x.star() > 0) {
                error((x.star() == 1 ? "*" : "**") + x.name() + " не поддерживается: у функции в мире постоянный список параметров",
                        x.span(), "передавай список или словарь обычным параметром");
                continue;
            }
            fn.params.add(x.name());
            params.add(param(f, x));
        }
        List<Ast.Decorator> marks = marks(f.marks());
        Ast.Block body = functionBody(f);
        env = saved;
        fn = savedFn;
        pre = savedPre;
        loops.addAll(outer);
        items.add(new Ast.Func(f.process(), f.name(), quoted(f.name()), sp(f.nameSpan()), params, body, marks, sp(f.span())));
    }

    private boolean quoted(String name) {
        return t.names().resolve(Names.Space.ACTION, name) != null || Wire.CALL_WORDS.contains(name);
    }

    private Ast.Param param(Tree.Func f, Tree.Param x) {
        String type = x.options() != null ? "enum" : paramType(x.type());
        List<String> options = x.options();
        if (x.options() == null && x.type() != null && p.enums.containsKey(x.type())) {
            type = "enum";
            options = p.enums.get(x.type());
        }
        if (type != null && !type.equals("enum")) fn.kinds.put(x.name(), type);
        shaped(x.name(), type);
        if (x.ref() || refParam(f.name(), x.name())) type = "variable";
        Ast.Expr fallback = null;
        if (x.fallback() != null) {
            try {
                fallback = value(x.fallback());
            } catch (Stop ignored) {
                fallback = new Ast.Blank(sp(x.span()));
            }
        }
        return new Ast.Param(x.name(), "any".equals(type) ? null : type, options, fallback, sp(x.span()));
    }

    private boolean refParam(String function, String param) {
        Set<String> assigned = p.assignedParams.get(function);
        if (assigned == null || !assigned.contains(param)) return false;
        Set<String> given = p.variableParams.get(function);
        Set<String> values = p.valueParams.get(function);
        return given != null && given.contains(param) && (values == null || !values.contains(param));
    }

    private String paramType(String type) {
        if (type == null) return null;
        String base = p.types.getOrDefault(type, type);
        String known = Words.type(base);
        return known == null ? "any" : known;
    }

    private Ast.Block functionBody(Tree.Func f) {
        List<Tree.Stmt> stmts = f.body().stmts();
        Tree.Stmt tail = stmts.isEmpty() ? null : stmts.get(stmts.size() - 1);
        boolean returning = f.result() != null && !f.process() && tail instanceof Tree.Run r && r.expr() instanceof Tree.Match;
        if (!returning) return block(f.body(), false);
        List<Ast.Stmt> body = new ArrayList<>();
        for (int i = 0; i + 1 < stmts.size(); i++) statement(stmts.get(i), body);
        List<Ast.Stmt> saved = pre;
        pre = new ArrayList<>();
        List<Ast.Stmt> mine = new ArrayList<>();
        try {
            matchStatement((Tree.Match) ((Tree.Run) tail).expr(), true, mine);
        } catch (Stop ignored) {
        }
        body.addAll(pre);
        body.addAll(mine);
        pre = saved;
        return new Ast.Block(body, sp(f.body().span()));
    }

    private List<Ast.Decorator> marks(List<Tree.Mark> marks) {
        List<Ast.Decorator> outMarks = new ArrayList<>();
        for (Tree.Mark m : marks) {
            Span s = sp(m.span());
            switch (m.name()) {
                case "hidden", "twin", "line", "values", "display_name", "display_description", "description", "icon" -> {
                    try {
                        outMarks.add(new Ast.Decorator(m.name(), args(m.args()), s));
                    } catch (Stop ignored) {
                    }
                }
                case "item" -> {
                    try {
                        Ast.Expr icon = item(m.args(), s, s);
                        outMarks.add(new Ast.Decorator("icon", List.of(new Ast.Arg(null, null, icon)), s));
                    } catch (Stop ignored) {
                    }
                }
                case "args", "returns", "return_var" -> warning("пометка @" + m.name() + " не попадает в код мира: сервер такого описания не хранит", s);
                case "alias", "алиас", "test", "ignore", "should_panic", "lang_item", "dict" -> { }
                case "overload", "getter", "setter" -> { }
                default -> error("нет пометки @" + m.name(), s);
            }
        }
        return outMarks;
    }

    private List<Ast.Arg> args(List<Tree.Arg> args) {
        List<Ast.Arg> list = new ArrayList<>();
        for (Tree.Arg a : args) list.add(new Ast.Arg(a.name(), a.nameSpan() == null ? null : sp(a.nameSpan()), value(a.value())));
        return list;
    }

    private Ast.Block block(Tree.Block b, boolean keep) {
        Env saved = env;
        if (!keep) env = new Env(env);
        List<Ast.Stmt> body = new ArrayList<>();
        for (Tree.Stmt s : b.stmts()) statement(s, body);
        env = saved;
        return new Ast.Block(body, sp(b.span()));
    }

    private void statement(Tree.Stmt s, List<Ast.Stmt> into) {
        List<Ast.Stmt> saved = pre;
        pre = new ArrayList<>();
        List<Ast.Stmt> mine = new ArrayList<>();
        try {
            if (++depth > DEEPEST) throw stop("слишком глубокая вложенность", s.span());
            statementInto(s, mine);
        } catch (Stop ignored) {
        } finally {
            depth--;
            into.addAll(pre);
            into.addAll(mine);
            pre = saved;
        }
    }

    private void statementInto(Tree.Stmt s, List<Ast.Stmt> into) {
        switch (s) {
            case Tree.Import i -> error("import пишут в начале файла", i.span());
            case Tree.Event e -> event(e, hoisted);
            case Tree.Func f -> func(f, hoisted);
            case Tree.Enum e -> { }
            case Tree.Alias a -> { }
            case Tree.Foreign f -> foreign(f);
            case Tree.Var v -> variable(v, into);
            case Tree.Assign a -> assignment(a, into);
            case Tree.Run r -> run(r, into);
            case Tree.If i -> ifStatement(i, 0, into);
            case Tree.While w -> whileLoop(w, into);
            case Tree.For f -> forLoop(f, into);
            case Tree.Try x -> tryStatement(x, into);
            case Tree.Throw x -> throwStatement(x, into);
            case Tree.Return r -> into.add(new Ast.Jump("return", r.value() == null ? null : value(r.value()), sp(r.span())));
            case Tree.Jump j -> jump(j, into);
        }
    }

    private void variable(Tree.Var v, List<Ast.Stmt> into) {
        String scope = v.scope();
        boolean inline = "inline".equals(scope);
        if (v.targets().size() > 1 && !inline && !v.values().isEmpty()) {
            for (Tree.Target x : v.targets()) if (x.target() instanceof Tree.Name n) declare(n, scope);
            if (v.values().size() == 1) multiple(v.targets(), v.values().get(0), into, v.span());
            else parallel(new Tree.Assign(v.targets(), "=", v.values(), v.span()), into);
            return;
        }
        for (int i = 0; i < v.targets().size(); i++) {
            Tree.Target x = v.targets().get(i);
            if (!(x.target() instanceof Tree.Name n)) continue;
            Tree.Expr given = i < v.values().size() ? v.values().get(i) : null;
            if (inline) {
                if (given != null) env.inlines.put(n.name(), given);
                continue;
            }
            if ("?".equals(scope)) {
                note(nameOf(n), x.type(), null);
                continue;
            }
            String own = declare(n, scope);
            String name = nameOf(n);
            note(name, x.type(), given);
            if (given == null) {
                if (!fnParam(name)) declaration(name, own, n.span(), into);
                continue;
            }
            into.add(assign(name, own, n.span(), "=", value(given), v.span()));
        }
    }

    private String declare(Tree.Name n, String scope) {
        String own = n.scope() != null ? n.scope() : scope != null ? scope : "local";
        if (n.parts() == null) {
            env.scopes.put(n.name(), own);
            env.inlines.remove(n.name());
        }
        return own;
    }

    private void declaration(String name, String scope, Span at, List<Ast.Stmt> into) {
        use(name, scope);
        p.written.add(name);
        if (!p.pinned(name)) into.add(new Ast.Declare(scope, name, sp(at)));
    }

    private void note(String name, String type, Tree.Expr given) {
        if (last || p.typed.contains(name)) return;
        if (type != null) {
            String kind = paramType(type);
            if (kind.equals("any")) {
                p.kinds.put(name, "?");
            } else {
                p.kinds.put(name, kind);
                p.typed.add(name);
            }
            return;
        }
        if (given == null) return;
        String kind = kindOf(given);
        String before = p.kinds.putIfAbsent(name, kind == null ? "?" : kind);
        if (before != null && !before.equals(kind)) p.kinds.put(name, "?");
        if (given instanceof Tree.ListLit) shaped(name, "array");
        if (given instanceof Tree.MapLit) shaped(name, "map");
        if (given instanceof Tree.Action a) {
            Table.Entry e = t.action(a.object(), a.name());
            if (e != null && e.id().equals("set_variable_create_list")) shaped(name, "array");
            if (e != null && e.id().equals("set_variable_create_map")) shaped(name, "map");
        }
    }

    private void shaped(String name, String kind) {
        if (last || !"array".equals(kind) && !"map".equals(kind)) return;
        String before = p.shapes.putIfAbsent(name, kind);
        if (before != null && !before.equals(kind)) p.shapes.put(name, "?");
    }

    private String shape(Tree.Expr e) {
        if (e instanceof Tree.Name n && n.parts() == null && inline(n.name()) == null && !(fn != null && fn.kinds.containsKey(n.name()))) {
            String known = p.shapes.get(n.name());
            if ("array".equals(known) || "map".equals(known)) return known;
        }
        return kindOf(e);
    }

    private boolean fnParam(String name) { return fn != null && fn.params.contains(name); }

    private void use(String name, String scope) {
        if (!last) p.used.computeIfAbsent(name, k -> new java.util.LinkedHashSet<>()).add(scope);
    }

    private void global(String name, String scope, Span s) {
        if (!p.pinned(name) && !scope.equals("local")) globals.putIfAbsent(name, new Ast.Global(scope, name, s));
    }

    private Ast.Var variableRef(String name, String scope, Span s) {
        use(name, scope);
        if (p.pinned(name)) return new Ast.Var(name, true, s, scope);
        global(name, scope, s);
        if (last && scope.equals("local") && !p.written.contains(name)) {
            globals.putIfAbsent(name, new Ast.Global("local", name, s));
            if (told.add(name)) out.add(Diagnostic.warning("переменная «" + name + "» нигде не задаётся — она будет пустой", s.start(), s.end()));
        }
        return new Ast.Var(name, true, s);
    }

    private Ast.Stmt assign(String name, String scope, Span nameSpan, String op, Ast.Expr value, Span span) {
        Span ns = sp(nameSpan);
        Span s = sp(span);
        use(name, scope);
        p.written.add(name);
        if (fnParam(name)) {
            if (!last) p.assignedParams.computeIfAbsent(fn.name, k -> new HashSet<>()).add(name);
            return new Ast.Assign(null, name, ns, op, value, s);
        }
        if (p.pinned(name)) return new Ast.Assign(scope, name, ns, op, value, s, true);
        global(name, scope, ns);
        return new Ast.Assign(null, name, ns, op, value, s);
    }

    private String lookup(String name) {
        for (Env e = env; e != null; e = e.up) {
            String s = e.scopes.get(name);
            if (s != null) return s;
        }
        String known = p.scopeOf(name);
        return known != null ? known : "local";
    }

    private Tree.Expr inline(String name) {
        for (Env e = env; e != null; e = e.up) {
            if (e.scopes.containsKey(name)) return null;
            Tree.Expr x = e.inlines.get(name);
            if (x != null) return x;
        }
        return p.inlines.get(name);
    }

    private String nameOf(Tree.Name n) {
        if (n.parts() == null) return n.name();
        StringBuilder b = new StringBuilder();
        for (Object part : n.parts()) {
            if (part instanceof String s) {
                b.append(s);
                continue;
            }
            b.append(placed((Tree.Expr) part));
        }
        return b.toString();
    }

    private String placed(Tree.Expr e) {
        Ast.Expr v = value(e);
        return switch (v) {
            case Ast.Str s when !s.interpolated() -> s.text();
            case Ast.Num n -> n.text();
            case Ast.Var x -> placeholder(x.name(), x.scope() != null ? x.scope() : scopeNow(x.name()));
            case Ast.Path x when x.parts().size() == 2 -> "%val(" + x.parts().get(1) + ")";
            default -> {
                String tmp = temp();
                Span s = sp(e.span());
                pre.add(new Ast.Assign("line", tmp, s, "=", v, s, true));
                yield placeholder(tmp, "line");
            }
        };
    }

    private String scopeNow(String name) { return fnParam(name) ? "line" : lookup(name); }

    private static String placeholder(String name, String scope) {
        return switch (scope) {
            case "save" -> "%var_save(" + name + ")";
            case "local" -> "%var_local(" + name + ")";
            case "line" -> "%var_line(" + name + ")";
            default -> "%var(" + name + ")";
        };
    }

    private String temp() { return TEMP + (++temps); }

    private Ast.Var tempVar(String name, Span s) { return new Ast.Var(name, true, s, "line"); }

    private void assignment(Tree.Assign a, List<Ast.Stmt> into) {
        if (a.targets().size() > 1) {
            if (a.values().size() == 1) {
                multiple(a.targets(), a.values().get(0), into, a.span());
                return;
            }
            if (a.values().size() != a.targets().size())
                throw stop("слева " + a.targets().size() + " переменных, а справа " + a.values().size() + " значений", a.span());
            parallel(a, into);
            return;
        }
        Tree.Target x = a.targets().get(0);
        Tree.Expr target = x.target();
        while (target instanceof Tree.Cast c) target = c.value();
        Tree.Expr given = a.values().get(0);
        switch (target) {
            case Tree.Name n -> assignName(n, x.type(), a.op(), given, a.span(), into);
            case Tree.Index i -> assignIndex(i, a.op(), given, a.span(), into);
            default -> throw stop("запись в поле бывает только у классов — их нет на сервере", target.span(), "используй словарь: x[\"поле\"] = …");
        }
    }

    private boolean inPlace(Tree.Method m, Tree.Name n, List<Ast.Stmt> into) {
        Table.Entry e = t.method(m.name());
        if (e == null) e = extra(m);
        if (e == null || e.origin() == null || !e.result().isEmpty() || !e.variable(e.origin())) return false;
        command(e, m.args(), m.receiver(), null, null, new Tree.Run(m, List.of(), null, m.span()), m.span(), into);
        if (!(m.receiver() instanceof Tree.Name r && r.name().equals(n.name()))) {
            String name = nameOf(n);
            into.add(assign(name, n.scope() != null ? n.scope() : scopeNow(name), n.span(), "=", value(m.receiver()), m.span()));
        }
        return true;
    }

    private void assignName(Tree.Name n, String type, String op, Tree.Expr given, Span span, List<Ast.Stmt> into) {
        if (given instanceof Tree.Method m && op.equals("=") && inPlace(m, n, into)) return;
        String name = nameOf(n);
        boolean bare = n.scope() == null || n.scope().equals("inline");
        if (n.parts() == null && "inline".equals(n.scope()) || bare && n.parts() == null && inline(name) != null) {
            if (op.equals("=")) {
                env.inlines.put(name, given);
                return;
            }
            throw stop("встроенную переменную меняют только присваиванием", n.span());
        }
        String scope = n.scope() != null ? n.scope() : scopeNow(name);
        if (op.equals("=")) note(name, type, given);
        if (n.scope() != null && n.parts() == null) env.scopes.put(name, scope);
        if (op.equals("^=")) {
            Ast.Expr self = variableOf(name, scope, sp(n.span()));
            into.add(assign(name, scope, n.span(), "=", power(self, value(given), span), span));
            return;
        }
        Ast.Expr v = value(given);
        if (!op.equals("=") && text(given) && !numeric(given)) {
            Ast.Expr self = variableOf(name, scope, sp(n.span()));
            if (op.equals("+=")) {
                into.add(assign(name, scope, n.span(), "=", joined(self, v, sp(span)), span));
                return;
            }
        }
        into.add(assign(name, scope, n.span(), op, v, span));
    }

    private static boolean numeric(Tree.Expr e) {
        if (!(e instanceof Tree.Str s) || s.parts() != null || s.text().isEmpty()) return false;
        for (int i = 0; i < s.text().length(); i++) if ("0123456789.-".indexOf(s.text().charAt(i)) < 0) return false;
        return true;
    }

    private Ast.Expr variableOf(String name, String scope, Span s) {
        if (fnParam(name)) return new Ast.Var(name, true, s);
        return variableRef(name, scope, s);
    }

    private Ast.Expr power(Ast.Expr left, Ast.Expr right, Span span) {
        Span s = sp(span);
        return new Ast.Call(null, "pow", false, s, List.of(new Ast.Arg(null, null, left), new Ast.Arg(null, null, right)), s);
    }

    private void assignIndex(Tree.Index i, String op, Tree.Expr given, Span span, List<Ast.Stmt> into) {
        if (i.to() != null) throw stop("запись в срез не поддерживается", i.span(), "запиши значения по одному индексу");
        Tree.Expr holder = i.target();
        while (holder instanceof Tree.Cast c) holder = c.value();
        if (!(holder instanceof Tree.Name || holder instanceof Tree.Index))
            throw stop("записывать по индексу можно в переменную", i.target().span());
        String kind = shape(holder);
        if ("text".equals(kind) && !(i.from() instanceof Tree.Str)) throw stop("запись в текст по индексу не поддерживается", i.span(), "собери новый текст");
        Ast.Expr target = value(holder);
        Ast.Expr key = value(i.from());
        Span s = sp(span);
        Ast.Expr v = value(given);
        boolean literal = key instanceof Ast.Num || key instanceof Ast.Str x && !x.interpolated();
        if (literal || kind == null && holder instanceof Tree.Index || !(target instanceof Ast.Var)) {
            into.add(new Ast.IndexAssign(new Ast.Index(target, key, sp(i.span())), op.equals("^=") ? "=" : op,
                    op.equals("^=") ? power(new Ast.Index(target, key, s), v, span) : v, s));
            return;
        }
        boolean map = "map".equals(kind);
        Ast.Expr whole = op.equals("=") ? v : op.equals("^=") ? power(read(target, key, map, s), v, span)
                : new Ast.Binary(op.substring(0, 1), read(target, key, map, s), v, s);
        Table.Entry e = t.byId(map ? "set_variable_set_map_value" : "set_variable_set_list_value");
        Ast.Expr cell = map && e.action().plural("value") && whole instanceof Ast.ListLit
                ? new Ast.ListLit(List.of(whole), s) : whole;
        List<Ast.Arg> args = new ArrayList<>(List.of(named("variable", target, s), named(map ? "map" : "list", target, s),
                named(map ? "key" : "number", key, s), named("value", cell, s)));
        into.add(new Ast.Run(call(e, args, s, s), null, s));
    }

    private Ast.Expr read(Ast.Expr target, Ast.Expr key, boolean map, Span s) {
        Table.Entry e = t.byId(map ? "set_variable_get_map_value_single" : "set_variable_get_list_value");
        List<Ast.Arg> args = new ArrayList<>(List.of(named(map ? "map" : "list", target, s), named(map ? "key" : "number", key, s)));
        return call(e, args, s, s);
    }

    private static Ast.Arg named(String name, Ast.Expr value, Span s) { return new Ast.Arg(name, s, value); }

    private Ast.Call call(Table.Entry e, List<Ast.Arg> args, Span nameSpan, Span span) {
        String q = e.xero().qualified();
        int dot = q.indexOf('.');
        return new Ast.Call(q.substring(0, dot), q.substring(dot + 1), false, nameSpan, args, span);
    }

    private void parallel(Tree.Assign a, List<Ast.Stmt> into) {
        List<String> names = new ArrayList<>();
        List<Span> spans = new ArrayList<>();
        boolean plain = true;
        for (Tree.Target x : a.targets()) {
            if (!(x.target() instanceof Tree.Name n)) throw stop("слева от «=» нужны переменные", x.target().span());
            String name = nameOf(n);
            String scope = n.scope() != null ? n.scope() : scopeNow(name);
            use(name, scope);
            p.written.add(name);
            if (p.pinned(name)) plain = false;
            else global(name, scope, sp(n.span()));
            names.add(name);
            spans.add(sp(n.span()));
        }
        List<Ast.Expr> values = new ArrayList<>();
        for (Tree.Expr e : a.values()) values.add(value(e));
        if (plain) {
            into.add(new Ast.MultiAssign(null, names, spans, values, sp(a.span())));
            return;
        }
        for (int i = 0; i < names.size(); i++) {
            Tree.Name n = (Tree.Name) a.targets().get(i).target();
            String scope = n.scope() != null ? n.scope() : scopeNow(names.get(i));
            into.add(assign(names.get(i), scope, n.span(), "=", values.get(i), a.span()));
        }
    }

    private void multiple(List<Tree.Target> targets, Tree.Expr source, List<Ast.Stmt> into, Span span) {
        Table.Entry e = null;
        List<Tree.Arg> args = List.of();
        Tree.Expr receiver = null;
        String selector = null;
        Span selectorSpan = null;
        if (source instanceof Tree.Action a) {
            e = entry(a);
            args = a.args();
            selector = a.selector();
            selectorSpan = a.selectorSpan();
        } else if (source instanceof Tree.Method m) {
            e = t.method(m.name());
            args = m.args();
            receiver = m.receiver();
        }
        if (e == null || e.result().size() < 2)
            throw stop("слева несколько переменных — справа нужны столько же значений или действие с несколькими результатами", source.span());
        Map<String, Tree.Expr> given = new LinkedHashMap<>();
        for (int i = 0; i < targets.size() && i < e.result().size(); i++) given.put(e.result().get(i), targets.get(i).target());
        Span s = sp(span);
        Ast.Expr call = call(e, bind(e, args, receiver, given, Set.of(), source.span()), s, s);
        if (selector != null) call = targeted(e, selector, selectorSpan, call, s);
        into.add(new Ast.Run(call, null, s));
    }

    private void run(Tree.Run r, List<Ast.Stmt> into) {
        Tree.Expr e = r.expr();
        while (e instanceof Tree.Cast c) e = c.value();
        switch (e) {
            case Tree.Action a -> actionStatement(a, r, into);
            case Tree.Method m -> methodStatement(m, r, into);
            case Tree.Call c -> callStatement(c, r, into);
            case Tree.Step st -> into.add(step(st));
            case Tree.Match m -> matchStatement(m, false, into);
            case Tree.Name n when n.name().equals("_") && !n.quoted() && inline("_") == null ->
                    into.add(new Ast.Run(new Ast.Blank(sp(n.span())), null, sp(n.span())));
            case Tree.Lambda l -> throw stop("лямбды не поддерживаются: функций-значений на сервере нет", l.span());
            default -> {
                value(e);
                throw stop("здесь нужна команда, например player::message(\"…\")", e.span());
            }
        }
    }

    private Ast.Stmt step(Tree.Step st) {
        if (!(st.target() instanceof Tree.Name n)) throw stop("++ и -- бывают только у переменной", st.span());
        String name = nameOf(n);
        String scope = n.scope() != null ? n.scope() : scopeNow(name);
        return assign(name, scope, n.span(), st.op().equals("++") ? "+=" : "-=", new Ast.Num("1", sp(st.span())), st.span());
    }

    private Table.Entry entry(Tree.Action a) {
        Table.Entry e = t.action(a.object(), a.name());
        if (e == null) {
            String canonical = Words.object(a.object());
            String where = canonical == null ? a.object() : canonical;
            throw stop("нет действия «" + where + "::" + a.name() + "»", a.nameSpan(), hint(where + "::" + a.name()));
        }
        return e;
    }

    private String hint(String label) {
        String best = null;
        int score = Integer.MAX_VALUE;
        for (String l : t.labels()) {
            int d = distance(label, l);
            if (d < score) {
                score = d;
                best = l;
            }
        }
        return best != null && score <= Math.max(2, label.length() / 4) ? "возможно, вы имели в виду «" + best + "»" : null;
    }

    private static int distance(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 6) return Integer.MAX_VALUE;
        int[] row = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) row[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int prev = row[0];
            row[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cur = row[j];
                row[j] = Math.min(Math.min(row[j] + 1, row[j - 1] + 1), prev + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                prev = cur;
            }
        }
        return row[b.length()];
    }

    private void actionStatement(Tree.Action a, Tree.Run r, List<Ast.Stmt> into) {
        Table.Entry e = entry(a);
        switch (e.id()) {
            case "call_function" -> {
                into.add(new Ast.Run(callFunction(a), null, sp(a.span())));
                return;
            }
            case "start_process" -> {
                into.add(startProcess(a));
                return;
            }
            case "else" -> throw stop("«else» — часть if", a.span());
            default -> { }
        }
        if (e.stale()) {
            warning("«" + e.label() + "» устарело: сервер его больше не знает — блок пропущен", a.nameSpan());
            return;
        }
        command(e, a.args(), null, a.selector(), a.selectorSpan(), r, a.span(), into);
    }

    private void methodStatement(Tree.Method m, Tree.Run r, List<Ast.Stmt> into) {
        Table.Entry e = methodEntry(m);
        if (e.stale()) {
            warning("«" + e.label() + "» устарело: сервер его больше не знает — блок пропущен", m.nameSpan());
            return;
        }
        command(e, m.args(), m.receiver(), null, null, r, m.span(), into);
    }

    private void command(Table.Entry e, List<Tree.Arg> args, Tree.Expr receiver, String selector, Span selectorSpan,
                         Tree.Run r, Span span, List<Ast.Stmt> into) {
        Span s = sp(span);
        if (Table.CONDITIONAL.contains(e.id())) {
            conditional(e, args, selector, selectorSpan, r, s, into);
            return;
        }
        if (e.bool()) {
            Ast.Expr test = conditionCall(e, args, receiver, selector, selectorSpan, s);
            if (r.body() == null) throw stop("условие на месте команды: напиши if (…) { … }", span);
            Ast.Block body = block(r.body(), false);
            into.add(new Ast.If(List.of(new Ast.Branch(test, body)), null, s));
            return;
        }
        boolean eachList = e.id().equals("repeat_for_each_in_list");
        boolean eachMap = e.id().equals("repeat_for_each_map_entry");
        if ((eachList || eachMap) && selector == null && r.body() != null && !r.lambda().isEmpty()) {
            forEachAction(e, args, r, s, eachMap, into);
            return;
        }
        Map<String, Tree.Expr> given = new LinkedHashMap<>();
        for (int i = 0; i < r.lambda().size(); i++) {
            if (i >= e.lambda().size()) throw stop("у «" + e.label() + "» меньше переменных цикла", r.lambda().get(i).span());
            given.put(e.lambda().get(i), r.lambda().get(i));
        }
        if (receiver instanceof Tree.Name && e.firstResult() != null && !e.firstResult().equals(e.origin()))
            given.putIfAbsent(e.firstResult(), receiver);
        Set<String> skip = !e.lambda().isEmpty() && r.lambda().isEmpty() && !namedFirst(args) ? Set.copyOf(e.lambda()) : Set.of();
        Ast.Expr call = call(e, bind(e, args, receiver, given, skip, span), s, s);
        if (selector != null) call = targeted(e, selector, selectorSpan, call, s);
        Ast.Block body = null;
        if (r.body() != null) {
            boolean loop = e.id().startsWith("repeat_");
            if (loop) {
                Loop l = new Loop(null);
                loops.add(l);
                body = block(r.body(), false);
                loops.remove(loops.size() - 1);
                finishLoop(l, into, new Ast.Run(call, body, s), s);
                return;
            }
            body = block(r.body(), false);
        }
        into.add(new Ast.Run(call, body, s));
    }

    private static boolean namedFirst(List<Tree.Arg> args) {
        for (Tree.Arg a : args) if (a.name() == null) return a.value() instanceof Tree.Name;
        return false;
    }

    private void forEachAction(Table.Entry e, List<Tree.Arg> args, Tree.Run r, Span s, boolean map, List<Ast.Stmt> into) {
        Tree.Expr source = null;
        for (Tree.Arg a : args) if (a.name() == null || a.name().equals("list") || a.name().equals("map")) source = a.value();
        if (source == null) throw stop("нужен список", r.span());
        String first = loopName(r.lambda().get(0));
        String second = r.lambda().size() > 1 ? loopName(r.lambda().get(1)) : null;
        if (second == null) second = loopTemp();
        if (source instanceof Tree.Name n) shaped(n.name(), map ? "map" : "array");
        eachLoop(first, sp(r.lambda().get(0).span()), second, sp(r.lambda().get(r.lambda().size() - 1).span()), map, value(source), r.body(), null, s, into);
    }

    private String loopName(Tree.Name n) {
        String name = nameOf(n);
        String scope = n.scope() != null ? n.scope() : scopeNow(name);
        use(name, scope);
        p.written.add(name);
        global(name, scope, sp(n.span()));
        return name;
    }

    private String loopTemp() {
        String name = temp();
        use(name, "local");
        p.written.add(name);
        return name;
    }

    private void eachLoop(String first, Span firstSpan, String second, Span secondSpan, boolean map, Ast.Expr source, Tree.Block body,
                          String label, Span s, List<Ast.Stmt> into) {
        Loop l = new Loop(label);
        loops.add(l);
        Ast.Block b = block(body, false);
        loops.remove(loops.size() - 1);
        finishLoop(l, into, new Ast.ForEach(first, first == null ? null : firstSpan, second, secondSpan, map, source, b, s), s);
    }

    private void conditional(Table.Entry e, List<Tree.Arg> args, String selector, Span selectorSpan, Tree.Run r, Span s, List<Ast.Stmt> into) {
        if (args.size() != 1) throw stop("«" + e.label() + "» нужно условие", s);
        Tree.Expr test = args.get(0).value();
        if (e.id().equals("repeat_while") && selector == null) {
            whileLoop(new Tree.While(null, test, r.body() == null ? new Tree.Block(List.of(), s) : r.body(), s), into);
            return;
        }
        List<Ast.Arg> list = List.of(new Ast.Arg(null, null, condition(test)));
        Ast.Expr call = call(e, list, s, s);
        if (selector != null) call = targeted(e, selector, selectorSpan, call, s);
        into.add(new Ast.Run(call, r.body() == null ? null : block(r.body(), false), s));
    }

    private Ast.Expr targeted(Table.Entry e, String selector, Span at, Ast.Expr call, Span s) {
        String word = selectorWord(selector, at);
        Span here = sp(at);
        String family = e.action() == null ? null : e.action().family();
        if (family == null) throw stop("у «" + e.label() + "» нет цели", at);
        String target = t.target(family, word);
        if (target == null) {
            List<String> all = new ArrayList<>(t.names().selectors(family).values());
            throw stop("нет цели <" + word + "> у «" + e.label() + "»", at, "есть: " + String.join(", ", all));
        }
        return new Ast.Targeted(target, here, call, s);
    }

    private String selectorWord(String selector, Span at) {
        if (!selector.startsWith("$")) return selector;
        String name = selector.substring(1);
        Tree.Expr x = inline(name);
        if (x instanceof Tree.Str s && s.parts() == null) return s.text();
        throw stop("в цели <$" + name + "> нужна встроенная переменная с текстом", at);
    }

    private void callStatement(Tree.Call c, Tree.Run r, List<Ast.Stmt> into) {
        String name = p.aliases.getOrDefault(c.name(), c.name());
        Tree.Func f = p.functions.get(name);
        Span s = sp(c.span());
        if (f != null && f.process()) {
            into.add(start(name, c.args(), f, c));
            return;
        }
        if (f == null && c.name().equals("raw") && r.body() != null) {
            into.add(new Ast.Run(value(c), block(r.body(), false), s));
            return;
        }
        if (f == null && (c.quoted() || c.name().equals("raw") || c.name().equals("call"))) {
            into.add(new Ast.Run(value(c), null, s));
            return;
        }
        if (f == null && p.foreign.contains(name)) return;
        if (f == null) throw stop("нет функции «" + name + "»", c.nameSpan(), functionHint(name));
        if (r.body() != null) throw stop("у вызова функции нет тела", r.body().span());
        into.add(new Ast.Run(functionCall(name, c), null, s));
    }

    private String functionHint(String name) {
        String best = null;
        int score = Integer.MAX_VALUE;
        for (String f : p.functions.keySet()) {
            int d = distance(name, f);
            if (d < score) {
                score = d;
                best = f;
            }
        }
        return best != null && score <= Math.max(2, name.length() / 3) ? "возможно, вы имели в виду «" + best + "»" : null;
    }

    private Ast.Stmt start(String name, List<Tree.Arg> args, Tree.Func f, Tree.Call c) {
        Span s = sp(c.span());
        List<Ast.Arg> list = callArgs(f, args);
        list.add(new Ast.Arg("local_variables_mode", s, new Ast.Var("copy", false, s)));
        list.add(new Ast.Arg("target_mode", s, new Ast.Var("current_target", false, s)));
        return new Ast.Start(name, quoted(name), sp(c.nameSpan()), list, s);
    }

    private List<Ast.Arg> callArgs(Tree.Func f, List<Tree.Arg> args) {
        List<Ast.Arg> list = new ArrayList<>();
        int positional = 0;
        for (Tree.Arg a : args) {
            if (a.star() > 0) throw stop("распаковка аргументов не поддерживается", a.value().span());
            String param = a.name();
            if (param == null && f != null && positional < f.params().size()) param = f.params().get(positional).name();
            if (a.name() == null) positional++;
            noteArgument(f, param, a.value());
            list.add(new Ast.Arg(a.name(), a.nameSpan() == null ? null : sp(a.nameSpan()), value(a.value())));
        }
        return list;
    }

    private void noteArgument(Tree.Func f, String param, Tree.Expr given) {
        if (last || f == null || param == null) return;
        boolean variable = given instanceof Tree.Name n && inline(n.name()) == null && !n.name().equals("_");
        (variable ? p.variableParams : p.valueParams).computeIfAbsent(f.name(), k -> new HashSet<>()).add(param);
        if (variable) p.written.add(((Tree.Name) given).name());
    }

    private Ast.Expr functionCall(String name, Tree.Call c) {
        Tree.Func f = p.functions.get(name);
        return new Ast.Call(null, name, quoted(name), sp(c.nameSpan()), callArgs(f, c.args()), sp(c.span()));
    }

    private Ast.Expr callFunction(Tree.Action a) {
        Table.Entry e = t.byId("call_function");
        Map<String, Tree.Expr> bound = plain(e, a.args());
        Tree.Expr name = bound.get("function_name");
        if (name == null) throw stop("нужно имя функции", a.span());
        Span s = sp(a.span());
        List<Ast.Arg> passed = passed(bound.get("args"));
        Ast.Expr shown = value(name);
        if (shown instanceof Ast.Str str && !str.interpolated() && str.prefix() == null) {
            String real = p.aliases.getOrDefault(str.text(), str.text());
            return new Ast.Call(null, real, true, sp(name.span()), passed, s);
        }
        List<Ast.Arg> args = new ArrayList<>();
        args.add(new Ast.Arg(null, null, shown));
        args.addAll(passed);
        return new Ast.Call(null, "call", false, s, args, s);
    }

    private Map<String, Tree.Expr> plain(Table.Entry e, List<Tree.Arg> args) {
        Map<String, Tree.Expr> bound = new LinkedHashMap<>();
        for (Tree.Arg a : args) if (a.name() != null) bound.put(a.name(), a.value());
        int slot = 0;
        for (Tree.Arg a : args) {
            if (a.name() != null) continue;
            while (slot < e.order().size() && bound.containsKey(e.order().get(slot))) slot++;
            if (slot >= e.order().size()) throw stop("лишний аргумент", a.value().span());
            bound.put(e.order().get(slot), a.value());
        }
        return bound;
    }

    private List<Ast.Arg> passed(Tree.Expr map) {
        List<Ast.Arg> list = new ArrayList<>();
        if (map == null) return list;
        if (!(map instanceof Tree.MapLit m)) throw stop("аргументы функции передают словарём: {\"имя\": значение}", map.span());
        for (int i = 0; i < m.keys().size(); i++) {
            if (!(m.keys().get(i) instanceof Tree.Str k) || k.parts() != null)
                throw stop("имя аргумента — текст в кавычках", m.keys().get(i).span());
            list.add(new Ast.Arg(k.text(), sp(k.span()), value(m.values().get(i))));
        }
        return list;
    }

    private Ast.Stmt startProcess(Tree.Action a) {
        Table.Entry e = t.byId("start_process");
        Map<String, Tree.Expr> bound = plain(e, a.args());
        Tree.Expr name = bound.get("process_name");
        if (name == null) throw stop("нужно имя процесса", a.span());
        Ast.Expr shown = value(name);
        if (!(shown instanceof Ast.Str str) || str.interpolated())
            throw stop("имя процесса — постоянный текст", name.span(), "сервер запускает процесс по имени из блока: передай его строкой");
        List<Ast.Arg> list = passed(bound.get("args"));
        Span s = sp(a.span());
        for (String key : List.of("local_variables_mode", "target_mode")) {
            Tree.Expr v = bound.get(key);
            if (v == null) continue;
            String word = word(v);
            if (word == null) throw stop("у " + key + " вариант пишется текстом", v.span());
            list.add(new Ast.Arg(key, sp(v.span()), new Ast.Var(word.toLowerCase(Locale.ROOT), false, sp(v.span()))));
        }
        return new Ast.Start(str.text(), true, sp(name.span()), list, s);
    }

    private String word(Tree.Expr e) { return word(e, 0); }

    private String word(Tree.Expr e, int hops) {
        return switch (e) {
            case Tree.Str s when s.parts() == null -> s.text();
            case Tree.Bool b -> b.value() ? "true" : "false";
            case Tree.Name n when n.parts() == null -> {
                Tree.Expr x = inline(n.name());
                yield x != null && hops < DEEPEST ? word(x, hops + 1) : n.quoted() ? null : n.name();
            }
            case Tree.Attr a when a.receiver() instanceof Tree.Name n && p.enums.containsKey(enumName(n.name())) -> a.name();
            default -> null;
        };
    }

    private String enumName(String name) { return p.aliases.getOrDefault(name, name); }

    private List<Ast.Arg> bind(Table.Entry e, List<Tree.Arg> args, Tree.Expr receiver, Map<String, Tree.Expr> given, Set<String> skip, Span at) {
        Map<String, Tree.Expr> bound = new LinkedHashMap<>(given);
        List<Tree.Expr> positional = new ArrayList<>();
        Map<String, Span> nameSpans = new HashMap<>();
        for (Tree.Arg a : args) {
            if (a.star() > 0) throw stop("распаковка аргументов не поддерживается", a.value().span());
            if (a.name() != null) {
                if (bound.containsKey(a.name())) throw stop("аргумент «" + a.name() + "» указан дважды", a.value().span());
                bound.put(a.name(), a.value());
                nameSpans.put(a.name(), a.nameSpan());
            } else {
                positional.add(a.value());
            }
        }
        if (receiver != null) {
            String origin = e.origin() != null ? e.origin() : e.firstResult();
            if (origin == null) origin = e.order().isEmpty() ? null : e.order().get(0);
            if (origin == null) throw stop("у «" + e.label() + "» нечему принять значение слева от точки", at);
            if (!positional.isEmpty() && plural(e, origin) && !bound.containsKey(origin)) {
                List<Tree.Expr> merged = new ArrayList<>(List.of(receiver));
                for (Tree.Expr x : positional) {
                    if (x instanceof Tree.ListLit l) merged.addAll(l.items());
                    else merged.add(x);
                }
                positional.clear();
                bound.put(origin, new Tree.ListLit(merged, receiver.span()));
            } else {
                bound.put(origin, receiver);
            }
        }
        List<String> slots = slots(e.order(), bound, skip);
        if (e.oldOrder() != null && positional.size() > 1) {
            List<String> old = slots(e.oldOrder(), bound, skip);
            if (misfits(e, slots, positional) > 0 && misfits(e, old, positional) == 0) {
                warning("порядок аргументов из старого JMCC — у «" + e.label() + "» теперь: " + String.join(", ", e.order()), at);
                slots = old;
            }
        }
        if (positional.size() > slots.size())
            throw stop("лишний аргумент: у «" + e.label() + "» свободно " + slots.size(), positional.get(slots.size()).span(),
                    "порядок: " + String.join(", ", e.order()));
        for (int i = 0; i < positional.size(); i++) bound.put(slots.get(i), positional.get(i));
        List<Ast.Arg> list = new ArrayList<>();
        for (Map.Entry<String, Tree.Expr> b : bound.entrySet()) {
            String id = b.getKey();
            Span nameSpan = sp(nameSpans.getOrDefault(id, b.getValue().span()));
            list.add(new Ast.Arg(id, nameSpan, argument(e, id, b.getValue())));
        }
        return list;
    }

    private static List<String> slots(List<String> order, Map<String, Tree.Expr> bound, Set<String> skip) {
        List<String> free = new ArrayList<>();
        for (String id : order) if (!bound.containsKey(id) && !skip.contains(id)) free.add(id);
        return free;
    }

    private static boolean plural(Table.Entry e, String arg) { return e.action() != null && e.action().plural(arg); }

    private int misfits(Table.Entry e, List<String> slots, List<Tree.Expr> values) {
        int count = 0;
        for (int i = 0; i < values.size() && i < slots.size(); i++) {
            Data.Arg a = e.action() == null ? null : e.action().arg(slots.get(i));
            if (a == null) {
                if (e.setting(slots.get(i)) && word(values.get(i)) == null) count++;
                continue;
            }
            String kind = kindOf(values.get(i));
            if (values.get(i) instanceof Tree.ListLit && plural(e, slots.get(i))) continue;
            if (!fits(a.kind(), kind)) count++;
        }
        return count;
    }

    private static boolean fits(String slot, String kind) {
        if (kind == null || slot == null || slot.equals(Data.ANY) || slot.equals("Текст")) return true;
        return switch (kind) {
            case "number" -> slot.equals("Число");
            case "text" -> !slot.equals("Местоположение") && !slot.equals("Список") && !slot.equals("Словарь") && !slot.equals(Data.VARIABLE);
            case "location" -> slot.equals("Местоположение");
            case "vector" -> slot.equals("Вектор");
            case "item" -> slot.equals("Предмет") || slot.equals("Блок");
            case "block" -> slot.equals("Блок");
            case "sound" -> slot.equals("Звук");
            case "particle" -> slot.equals("Эффект частиц");
            case "potion" -> slot.equals("Зелье");
            case "array" -> slot.equals("Список");
            case "map" -> slot.equals("Словарь");
            default -> true;
        };
    }

    private Ast.Expr argument(Table.Entry e, String id, Tree.Expr raw) {
        if (e.setting(id)) return marker(e, id, raw);
        if (e.loose().contains(id)) {
            String word = word(raw);
            if (word != null) return enumCall(word.toUpperCase(Locale.ROOT), null, sp(raw.span()));
        }
        Tree.Expr given = moved(e, id, raw);
        if (e.variable(id) && given instanceof Tree.Name n) {
            p.written.add(nameOf(n));
            if (n.scope() != null && n.parts() == null) env.scopes.put(n.name(), n.scope());
        }
        int saved = formula;
        formula = 0;
        try {
            return value(given);
        } finally {
            formula = saved;
        }
    }

    private Tree.Expr moved(Table.Entry e, String id, Tree.Expr given) {
        Data.Arg a = e.action() == null ? null : e.action().arg(id);
        if (a == null || !a.kind().equals("Местоположение") || !(given instanceof Tree.Value v)) return given;
        String now = t.movedValue(v.name());
        return now == null ? given : new Tree.Value(now, v.nameSpan(), v.selector(), v.selectorSpan(), v.span());
    }

    private Ast.Expr marker(Table.Entry e, String id, Tree.Expr given) {
        Span s = sp(given.span());
        if (given instanceof Tree.Call c && (c.name().equals("enum") || c.name().equals("raw") || c.name().equals("перечисление"))) return value(given);
        String word = word(given);
        if (word != null) {
            String option = t.option(e, id, word);
            if (option != null) return new Ast.Str(null, option, s);
            List<String> old = e.oldOptions().get(id);
            if (old != null && old.contains(word.toUpperCase(Locale.ROOT))) {
                warning("вариант «" + word + "» устарел: сервера он больше не знает", given.span());
                return enumCall(word.toUpperCase(Locale.ROOT), null, s);
            }
            if (!(given instanceof Tree.Name)) return new Ast.Str(null, word, s);
        }
        if (given instanceof Tree.Name n) {
            Data.Setting setting = e.action().setting(id);
            String first = setting.options().keySet().iterator().next().toUpperCase(Locale.ROOT);
            return enumCall(first, value(n), s);
        }
        throw stop("у «" + id + "» вариант пишется текстом: " + String.join(", ", e.action().setting(id).options().keySet()), given.span());
    }

    private Ast.Expr enumCall(String option, Ast.Expr variable, Span s) {
        List<Ast.Arg> args = new ArrayList<>();
        args.add(new Ast.Arg(null, null, new Ast.Str(null, option, s)));
        if (variable != null) args.add(new Ast.Arg(null, null, variable));
        return new Ast.Call(null, "enum", false, s, args, s);
    }

    private void ifStatement(Tree.If i, int from, List<Ast.Stmt> into) {
        List<Ast.Branch> branches = new ArrayList<>();
        Ast.Block otherwise = null;
        for (int n = from; n < i.branches().size(); n++) {
            Tree.Branch b = i.branches().get(n);
            List<Ast.Stmt> saved = pre;
            pre = new ArrayList<>();
            Ast.Expr test;
            try {
                test = condition(b.condition());
            } catch (Stop stopped) {
                pre = saved;
                block(b.body(), false);
                continue;
            }
            List<Ast.Stmt> head = pre;
            pre = saved;
            if (!head.isEmpty() && n > from) {
                List<Ast.Stmt> nested = new ArrayList<>(head);
                ifRest(i, n, test, nested);
                otherwise = new Ast.Block(nested, sp(b.body().span()));
                into.add(new Ast.If(branches, otherwise, sp(i.span())));
                return;
            }
            into.addAll(head);
            branches.add(new Ast.Branch(test, block(b.body(), false)));
        }
        if (i.otherwise() != null) otherwise = block(i.otherwise(), false);
        if (branches.isEmpty()) return;
        into.add(new Ast.If(branches, otherwise, sp(i.span())));
    }

    private void ifRest(Tree.If i, int at, Ast.Expr test, List<Ast.Stmt> into) {
        List<Ast.Branch> branches = new ArrayList<>();
        branches.add(new Ast.Branch(test, block(i.branches().get(at).body(), false)));
        for (int n = at + 1; n < i.branches().size(); n++) {
            Tree.Branch b = i.branches().get(n);
            branches.add(new Ast.Branch(condition(b.condition()), block(b.body(), false)));
        }
        Ast.Block otherwise = i.otherwise() == null ? null : block(i.otherwise(), false);
        into.add(new Ast.If(branches, otherwise, sp(i.span())));
    }

    private void whileLoop(Tree.While w, List<Ast.Stmt> into) {
        Span s = sp(w.span());
        Loop l = new Loop(w.label());
        loops.add(l);
        List<Ast.Stmt> saved = pre;
        pre = new ArrayList<>();
        Ast.Expr test;
        try {
            test = condition(w.condition());
        } catch (Stop stopped) {
            pre = saved;
            block(w.body(), false);
            loops.remove(loops.size() - 1);
            throw stopped;
        }
        List<Ast.Stmt> head = pre;
        pre = saved;
        Ast.Block body = block(w.body(), false);
        loops.remove(loops.size() - 1);
        Ast.Stmt loop;
        if (head.isEmpty()) {
            loop = new Ast.While(test, body, s);
        } else {
            List<Ast.Stmt> stmts = new ArrayList<>(head);
            Ast.Block leave = new Ast.Block(List.of(new Ast.Jump("break", null, s)), s);
            stmts.add(new Ast.If(List.of(new Ast.Branch(new Ast.Unary("not", test, s), leave)), null, s));
            stmts.addAll(body.stmts());
            loop = new Ast.Forever(new Ast.Block(stmts, s), s);
        }
        finishLoop(l, into, loop, s);
    }

    private void finishLoop(Loop l, List<Ast.Stmt> into, Ast.Stmt loop, Span s) {
        for (String flag : l.flags) into.add(new Ast.Assign("line", flag, s, "=", new Ast.Num("0", s), s, true));
        into.add(loop);
        Loop outer = loops.isEmpty() ? null : loops.get(loops.size() - 1);
        for (Through x : l.through) {
            String flag = flag(x.kind(), x.label());
            List<Ast.Stmt> body = new ArrayList<>();
            boolean here = outer != null && x.label().equals(outer.label);
            if (here && x.kind().equals("continue")) body.add(new Ast.Assign("line", flag, s, "=", new Ast.Num("0", s), s, true));
            body.add(new Ast.Jump(here ? x.kind() : "break", null, s));
            Ast.Expr test = new Ast.Binary("==", tempVar(flag, s), new Ast.Num("1", s), s);
            into.add(new Ast.If(List.of(new Ast.Branch(test, new Ast.Block(body, s))), null, s));
        }
    }

    private static String flag(String kind, String label) { return TEMP + "_" + kind + "_" + label; }

    private void jump(Tree.Jump j, List<Ast.Stmt> into) {
        Span s = sp(j.span());
        if (j.label() == null) {
            if (loops.isEmpty() && j.kind().equals("break")) {
                into.add(new Ast.Run(call(t.byId("control_end_thread"), List.of(), s, s), null, s));
                return;
            }
            into.add(new Ast.Jump(j.kind(), null, s));
            return;
        }
        int at = -1;
        for (int i = loops.size() - 1; i >= 0; i--) {
            if (j.label().equals(loops.get(i).label)) {
                at = i;
                break;
            }
        }
        if (at < 0) throw stop("нет цикла с меткой '" + j.label(), j.span());
        if (at == loops.size() - 1) {
            into.add(new Ast.Jump(j.kind(), null, s));
            return;
        }
        String flag = flag(j.kind(), j.label());
        loops.get(at).flags.add(flag);
        for (int i = at + 1; i < loops.size(); i++) {
            Through x = new Through(j.kind(), j.label());
            if (!loops.get(i).through.contains(x)) loops.get(i).through.add(x);
        }
        into.add(new Ast.Assign("line", flag, s, "=", new Ast.Num("1", s), s, true));
        into.add(new Ast.Jump("break", null, s));
    }

    private void forLoop(Tree.For f, List<Ast.Stmt> into) {
        Span s = sp(f.span());
        Tree.Expr source = f.source();
        Tree.Expr step = null;
        while (true) {
            if (source instanceof Tree.Cast c) {
                source = c.value();
            } else if (source instanceof Tree.Method m && (m.name().equals("iter") || m.name().equals("into_iter")) && m.args().isEmpty()) {
                source = m.receiver();
            } else if (source instanceof Tree.Method m && m.name().equals("step_by") && m.args().size() == 1) {
                step = m.args().get(0).value();
                source = m.receiver();
            } else {
                break;
            }
        }
        if (f.names().size() > 2) throw stop("в for бывает одна или две переменные", f.names().get(2).span());
        if (source instanceof Tree.Range r) {
            rangeLoop(f, r, step, s, into);
            return;
        }
        String first = name(f.names().get(0));
        String second = f.names().size() > 1 ? name(f.names().get(1)) : null;
        boolean map = "map".equals(shape(source));
        if (source instanceof Tree.Name n && f.names().size() > 1 && !map) shaped(n.name(), "array");
        Ast.Expr from = value(source);
        Span firstSpan = sp(f.names().get(0).span());
        Span lastSpan = sp(f.names().get(f.names().size() - 1).span());
        if (second == null && !map) {
            eachLoop(null, null, first, firstSpan, false, from, f.body(), f.label(), s, into);
            return;
        }
        if (second == null) second = loopTemp();
        eachLoop(first, firstSpan, second, lastSpan, map, from, f.body(), f.label(), s, into);
    }

    private String name(Tree.Name n) {
        if (n.name().equals("_") && !n.quoted()) return loopTemp();
        return loopName(n);
    }

    private void rangeLoop(Tree.For f, Tree.Range r, Tree.Expr step, Span s, List<Ast.Stmt> into) {
        Ast.Expr from = value(r.from());
        Ast.Expr to = value(r.to());
        if (!r.inclusive()) to = new Ast.Binary("-", to, new Ast.Num("1", s), s);
        Ast.Expr by = step == null ? null : value(step);
        Tree.Name counter = f.names().get(f.names().size() - 1);
        String name = name(counter);
        Loop l = new Loop(f.label());
        loops.add(l);
        Ast.Block body;
        if (f.names().size() == 2) {
            Tree.Name indexName = f.names().get(0);
            String index = name(indexName);
            List<Ast.Stmt> stmts = new ArrayList<>();
            Ast.Expr offset = new Ast.Binary("-", new Ast.Var(name, true, s), from, s);
            stmts.add(assign(index, "local", indexName.span(), "=", offset, f.span()));
            stmts.addAll(block(f.body(), false).stmts());
            body = new Ast.Block(stmts, sp(f.body().span()));
        } else {
            body = block(f.body(), false);
        }
        loops.remove(loops.size() - 1);
        finishLoop(l, into, new Ast.ForRange(name, sp(counter.span()), from, to, by, body, s), s);
    }

    private void tryStatement(Tree.Try x, List<Ast.Stmt> into) {
        Span s = sp(x.span());
        Ast.Expr holder;
        if (x.variable() != null) {
            holder = value(x.variable());
            p.written.add(nameOf(x.variable()));
        } else {
            holder = tempVar(temp(), s);
        }
        Table.Entry catcher = t.byId("controller_exception");
        Ast.Expr call = call(catcher, new ArrayList<>(List.of(named("variable", holder, s))), s, s);
        into.add(new Ast.Run(call, block(x.body(), false), s));
        if (x.handler() == null) return;
        Table.Entry exists = t.byId("if_variable_exists");
        Ast.Expr test = call(exists, new ArrayList<>(List.of(named("variable", holder, s))), s, s);
        into.add(new Ast.If(List.of(new Ast.Branch(test, block(x.handler(), false))), null, s));
    }

    private void throwStatement(Tree.Throw x, List<Ast.Stmt> into) {
        Span s = sp(x.span());
        Table.Entry e = t.byId("control_call_exception");
        List<Ast.Arg> args = new ArrayList<>();
        if (x.message() != null) args.add(named("message", value(x.message()), s));
        String option = t.option(e, "type", x.kind());
        if (option == null) throw stop("вид ошибки: WARNING, ERROR или FATAL", x.span());
        args.add(named("type", new Ast.Str(null, option, s), s));
        into.add(new Ast.Run(call(e, args, s, s), null, s));
    }

    private void matchStatement(Tree.Match m, boolean tail, List<Ast.Stmt> into) {
        Span s = sp(m.span());
        Ast.Expr subject = subject(m, into);
        List<Ast.Branch> branches = new ArrayList<>();
        Ast.Block otherwise = null;
        for (Tree.Arm arm : m.arms()) {
            Ast.Expr test = arm(subject, arm);
            Ast.Block body = armBody(arm, tail);
            if (test == null) {
                otherwise = body;
                break;
            }
            branches.add(new Ast.Branch(test, body));
        }
        if (branches.isEmpty()) {
            if (otherwise != null) into.addAll(otherwise.stmts());
            return;
        }
        into.add(new Ast.If(branches, otherwise, s));
    }

    private Ast.Expr subject(Tree.Match m, List<Ast.Stmt> into) {
        Ast.Expr subject = value(m.subject());
        if (subject instanceof Ast.Var || subject instanceof Ast.Num || subject instanceof Ast.Str) return subject;
        Span s = sp(m.subject().span());
        String tmp = temp();
        into.add(new Ast.Assign("line", tmp, s, "=", subject, s, true));
        return tempVar(tmp, s);
    }

    private Ast.Expr arm(Ast.Expr subject, Tree.Arm arm) {
        Ast.Expr any = null;
        boolean wild = false;
        for (Tree.Expr pattern : arm.patterns()) {
            if (pattern instanceof Tree.Name n && n.name().equals("_") && !n.quoted()) {
                wild = true;
                continue;
            }
            Span s = sp(pattern.span());
            Ast.Expr test = pattern instanceof Tree.Range r ? inRange(subject, r, s) : new Ast.Binary("==", subject, value(pattern), s);
            any = any == null ? test : new Ast.Binary("or", any, test, s);
        }
        Ast.Expr guard = arm.guard() == null ? null : condition(arm.guard());
        if (wild) return guard;
        if (guard == null) return any;
        return new Ast.Binary("and", any, guard, sp(arm.span()));
    }

    private Ast.Block armBody(Tree.Arm arm, boolean tail) {
        if (arm.body() != null) return block(arm.body(), false);
        List<Ast.Stmt> stmts = new ArrayList<>();
        Tree.Stmt single = arm.single();
        if (tail && single instanceof Tree.Run r && !(r.expr() instanceof Tree.Action a && actionOnly(a))) {
            statement(new Tree.Return(r.expr(), r.span()), stmts);
        } else if (single != null) {
            statement(single, stmts);
        }
        return new Ast.Block(stmts, sp(arm.span()));
    }

    private boolean actionOnly(Tree.Action a) {
        Table.Entry e = t.action(a.object(), a.name());
        return e != null && e.result().isEmpty();
    }

    private Ast.Expr matchValue(Tree.Match m) {
        Span s = sp(m.span());
        Ast.Expr subject = subject(m, pre);
        Ast.Expr tailValue = null;
        List<Tree.Arm> arms = new ArrayList<>(m.arms());
        for (int i = arms.size() - 1; i >= 0; i--) {
            Tree.Arm arm = arms.get(i);
            Ast.Expr result = armValue(arm);
            Ast.Expr test = arm(subject, arm);
            if (test == null) tailValue = result;
            else tailValue = new Ast.Ternary(test, result, tailValue == null ? new Ast.Num("0", s) : tailValue, s);
        }
        return tailValue == null ? new Ast.Num("0", s) : tailValue;
    }

    private Ast.Expr armValue(Tree.Arm arm) {
        if (arm.single() instanceof Tree.Run r) return value(r.expr());
        throw stop("здесь ветка match должна быть значением", arm.span());
    }

    private boolean text(Tree.Expr e) { return "text".equals(kindOf(e)); }

    private String kindOf(Tree.Expr e) {
        return switch (e) {
            case Tree.Num n -> "number";
            case Tree.Str s -> "text";
            case Tree.Bool b -> "number";
            case Tree.ListLit l -> "array";
            case Tree.MapLit m -> "map";
            case Tree.Cast c -> {
                String known = paramType(c.type());
                yield known == null || known.equals("any") ? kindOf(c.value()) : known;
            }
            case Tree.Name n -> kindOfName(n);
            case Tree.Value v -> {
                String id = t.valueId(v.name());
                yield id == null ? null : t.valueType(id);
            }
            case Tree.Action a -> {
                Table.Entry x = t.action(a.object(), a.name());
                yield x == null ? null : x.resultType();
            }
            case Tree.Method m -> {
                Table.Entry x = t.method(m.name());
                yield x == null ? null : x.resultType();
            }
            case Tree.Call c -> {
                String factory = Words.factory(c.name());
                if (factory != null && !p.functions.containsKey(c.name())) yield factory.equals("value") ? null : factory;
                Tree.Func f = p.functions.get(p.aliases.getOrDefault(c.name(), c.name()));
                yield f == null ? null : paramType(f.result());
            }
            case Tree.Binary b -> switch (b.op()) {
                case "+" -> text(b.left()) || text(b.right()) ? "text" : "number";
                case "-", "*", "/", "%", "^" -> "number";
                default -> "number";
            };
            case Tree.Ternary x -> kindOf(x.then());
            default -> null;
        };
    }

    private String kindOfName(Tree.Name n) {
        if (n.parts() != null) return null;
        Tree.Expr x = n.scope() == null && depth < DEEPEST ? inline(n.name()) : null;
        if (x != null) {
            depth++;
            try {
                return kindOf(x);
            } finally {
                depth--;
            }
        }
        if (fn != null && fn.kinds.containsKey(n.name())) return fn.kinds.get(n.name());
        String k = p.kinds.get(n.name());
        return "?".equals(k) ? null : k;
    }

    private Ast.Expr condition(Tree.Expr e) {
        return switch (e) {
            case Tree.Binary b when b.op().equals("and") || b.op().equals("or") ->
                    new Ast.Binary(b.op(), condition(b.left()), condition(b.right()), sp(b.span()));
            case Tree.Unary u when u.op().equals("not") -> new Ast.Unary("not", condition(u.operand()), sp(u.span()));
            case Tree.Binary b -> value(b);
            case Tree.Bool b -> value(b);
            case Tree.Name n -> {
                Tree.Expr x = n.scope() == null && n.parts() == null ? inline(n.name()) : null;
                if (x != null) yield inlined(x, sp(n.span()), true);
                yield value(n);
            }
            case Tree.Cast c -> condition(c.value());
            case Tree.Action a -> actionCondition(a);
            case Tree.Method m -> methodCondition(m);
            default -> {
                Span s = sp(e.span());
                yield new Ast.Binary("==", value(e), new Ast.Num("1", s), s);
            }
        };
    }

    private Ast.Expr actionCondition(Tree.Action a) {
        Table.Entry x = entry(a);
        if (!x.bool()) {
            Span s = sp(a.span());
            return new Ast.Binary("==", value(a), new Ast.Num("1", s), s);
        }
        if (x.stale()) {
            warning("«" + x.label() + "» устарело: сервер его больше не знает", a.nameSpan());
            return new Ast.Var("false", false, sp(a.span()));
        }
        return conditionCall(x, a.args(), null, a.selector(), a.selectorSpan(), sp(a.span()));
    }

    private Ast.Expr methodCondition(Tree.Method m) {
        Table.Entry x = methodEntry(m);
        Span s = sp(m.span());
        if (!x.bool()) return new Ast.Binary("==", value(m), new Ast.Num("1", s), s);
        return conditionCall(x, m.args(), m.receiver(), null, null, s);
    }

    private Ast.Expr conditionCall(Table.Entry given, List<Tree.Arg> args, Tree.Expr receiver, String selector, Span selectorSpan, Span s) {
        Table.Entry e = range(given, args, receiver);
        Ast.Expr call = call(e, bind(e, args, receiver, Map.of(), Set.of(), s), s, s);
        return selector == null ? call : targeted(e, selector, selectorSpan, call, s);
    }

    private Table.Entry range(Table.Entry e, List<Tree.Arg> args, Tree.Expr receiver) {
        if (!e.id().equals("if_variable_in_range")) return e;
        boolean places = receiver != null && "location".equals(kindOf(receiver));
        for (Tree.Arg a : args) if ("location".equals(kindOf(a.value()))) places = true;
        return places ? t.byId("if_variable_location_in_range") : e;
    }

    private Table.Entry methodEntry(Tree.Method m) {
        Table.Entry e = t.method(m.name());
        if (e == null) e = extra(m);
        if (e == null) e = t.action("variable", m.name());
        if (e == null) {
            if (m.receiver() instanceof Tree.Call c && p.foreign.contains(c.name())) throw new Stop();
            throw stop("нет метода «" + m.name() + "»", m.nameSpan(), "у значений есть только действия сервера: x.equals(y), x.get_coordinate(\"X\")");
        }
        return e;
    }

    private Table.Entry extra(Tree.Method m) {
        String kind = shape(m.receiver());
        return switch (m.name()) {
            case "len" -> t.byId("map".equals(kind) ? "set_variable_get_map_size"
                    : "text".equals(kind) ? "set_variable_text_length" : "set_variable_get_list_length");
            case "contains" -> t.byId("map".equals(kind) ? "if_variable_map_has_key"
                    : "text".equals(kind) ? "if_variable_text_contains" : "if_variable_list_contains_value");
            case "has_key" -> t.byId("if_variable_map_has_key");
            case "push", "append" -> t.byId("set_variable_append_value");
            case "keys" -> t.byId("set_variable_get_map_keys");
            case "values" -> t.byId("set_variable_get_map_values");
            default -> null;
        };
    }

    private Ast.Expr inlined(Tree.Expr e, Span s, boolean test) {
        if (depth > DEEPEST) throw stop("встроенные переменные ссылаются друг на друга по кругу", s);
        Span saved = over;
        if (over == null) over = s;
        depth++;
        try {
            return test ? condition(e) : value(e);
        } finally {
            depth--;
            over = saved;
        }
    }

    private Ast.Expr value(Tree.Expr e) {
        if (++depth > DEEPEST * 3) {
            depth--;
            throw stop("слишком глубокая вложенность", e.span());
        }
        try {
            return valueBody(e);
        } finally {
            depth--;
        }
    }

    private Ast.Expr valueBody(Tree.Expr e) {
        return switch (e) {
            case Tree.Num n -> new Ast.Num(n.text(), sp(n.span()));
            case Tree.Str s -> string(s);
            case Tree.Bool b -> new Ast.Var(b.value() ? "true" : "false", false, sp(b.span()));
            case Tree.Name n -> name(n, sp(n.span()));
            case Tree.ListLit l -> {
                List<Ast.Expr> items = new ArrayList<>();
                for (Tree.Expr x : l.items()) items.add(value(x));
                yield new Ast.ListLit(items, sp(l.span()));
            }
            case Tree.MapLit m -> {
                List<Ast.Expr> keys = new ArrayList<>();
                List<Ast.Expr> values = new ArrayList<>();
                for (int i = 0; i < m.keys().size(); i++) {
                    keys.add(value(m.keys().get(i)));
                    values.add(value(m.values().get(i)));
                }
                yield new Ast.MapLit(keys, values, sp(m.span()));
            }
            case Tree.Nbt n -> throw stop("m{…} бывает только в item(nbt = m{…})", n.span());
            case Tree.Action a -> action(a);
            case Tree.Value v -> gameValue(v);
            case Tree.Call c -> call(c);
            case Tree.Method m -> method(m);
            case Tree.Attr a -> attribute(a);
            case Tree.Index i -> index(i);
            case Tree.Unary u -> {
                if (u.op().equals("not")) yield new Ast.Unary("not", condition(u.operand()), sp(u.span()));
                yield new Ast.Unary("-", value(u.operand()), sp(u.span()));
            }
            case Tree.Binary b -> binary(b);
            case Tree.Ternary x -> new Ast.Ternary(condition(x.condition()), value(x.then()), value(x.otherwise()), sp(x.span()));
            case Tree.Step st -> stepValue(st);
            case Tree.Range r -> throw stop("диапазон бывает только в for и после in", r.span());
            case Tree.Cast c -> value(c.value());
            case Tree.Match m -> matchValue(m);
            case Tree.Lambda l -> throw stop("лямбды не поддерживаются: функций-значений на сервере нет", l.span());
        };
    }

    private Ast.Expr name(Tree.Name n, Span s) {
        String name = nameOf(n);
        String scope = n.scope();
        if (n.parts() == null) {
            if (scope == null || scope.equals("inline")) {
                Tree.Expr x = inline(name);
                if (x != null) return inlined(x, s, false);
                if ("inline".equals(scope)) throw stop("встроенная переменная «" + name + "» ещё не задана", n.span());
            }
            if (scope == null && !n.quoted()) {
                if (name.equals("_")) return new Ast.Blank(s);
                String real = enumName(name);
                if (p.enums.containsKey(real)) {
                    List<Ast.Expr> items = new ArrayList<>();
                    for (String m : p.enums.get(real)) items.add(new Ast.Str(null, m, s));
                    return new Ast.ListLit(items, s);
                }
            }
            if (scope == null && fnParam(name)) return new Ast.Var(name, true, s);
        }
        if (scope != null && n.parts() == null) env.scopes.putIfAbsent(name, scope);
        return variableRef(name, scope != null ? scope : scopeNow(name), s);
    }

    private Ast.Expr string(Tree.Str given) {
        Span s = sp(given.span());
        if (given.parts() == null) return new Ast.Str(given.prefix(), given.text(), s);
        List<Object> parts = new ArrayList<>();
        for (Object part : given.parts()) {
            if (part instanceof String text) {
                push(parts, text);
                continue;
            }
            Ast.Expr v = value((Tree.Expr) part);
            switch (v) {
                case Ast.Str x when !x.interpolated() -> push(parts, x.text());
                case Ast.Str x -> {
                    for (Object inner : x.parts()) {
                        if (inner instanceof String text) push(parts, text);
                        else parts.add(inner);
                    }
                }
                case Ast.Num n -> push(parts, n.text());
                default -> parts.add(v);
            }
        }
        if (parts.isEmpty()) return new Ast.Str(given.prefix(), "", s);
        if (parts.size() == 1 && parts.get(0) instanceof String only) return new Ast.Str(given.prefix(), only, s);
        return new Ast.Str(given.prefix(), "", parts, s);
    }

    private static void push(List<Object> parts, String text) {
        if (text.isEmpty()) return;
        int last = parts.size() - 1;
        if (last >= 0 && parts.get(last) instanceof String before) parts.set(last, before + text);
        else parts.add(text);
    }

    private Ast.Expr binary(Tree.Binary b) {
        Span s = sp(b.span());
        switch (b.op()) {
            case "and", "or" -> {
                return condition(b);
            }
            case "in" -> {
                return membership(b, s);
            }
            case "==", "!=", ">", ">=", "<", "<=" -> {
                return new Ast.Binary(b.op(), value(b.left()), value(b.right()), s);
            }
            case "+" -> {
                if (text(b.left()) || text(b.right())) return joined(value(b.left()), value(b.right()), s);
            }
            case "*" -> {
                if (text(b.left())) {
                    Table.Entry e = t.byId("set_variable_repeat_text");
                    List<Tree.Arg> args = List.of(new Tree.Arg(null, null, b.left(), 0), new Tree.Arg(null, null, b.right(), 0));
                    return call(e, bindValue(e, args, null, b.span()), s, s);
                }
            }
            default -> { }
        }
        formula++;
        try {
            Ast.Expr l = value(b.left());
            Ast.Expr r = value(b.right());
            return b.op().equals("^") ? power(l, r, b.span()) : new Ast.Binary(b.op(), l, r, s);
        } finally {
            formula--;
        }
    }

    private Ast.Expr joined(Ast.Expr left, Ast.Expr right, Span s) {
        List<Object> parts = new ArrayList<>();
        String prefix = null;
        for (Ast.Expr side : List.of(left, right)) {
            switch (side) {
                case Ast.Str x when !x.interpolated() -> {
                    if (prefix == null) prefix = x.prefix();
                    push(parts, x.text());
                }
                case Ast.Str x -> {
                    if (prefix == null) prefix = x.prefix();
                    for (Object part : x.parts()) {
                        if (part instanceof String text) push(parts, text);
                        else parts.add(part);
                    }
                }
                case Ast.Num n -> push(parts, n.text());
                default -> parts.add(side);
            }
        }
        if (parts.isEmpty()) return new Ast.Str(prefix, "", s);
        if (parts.size() == 1 && parts.get(0) instanceof String only) return new Ast.Str(prefix, only, s);
        return new Ast.Str(prefix, "", parts, s);
    }

    private Ast.Expr inRange(Ast.Expr left, Tree.Range r, Span s) {
        Ast.Expr from = value(r.from());
        Ast.Expr to = value(r.to());
        if (r.inclusive()) return new Ast.Binary("in", left, new Ast.Binary("..", from, to, sp(r.span())), s);
        return new Ast.Binary("and", new Ast.Binary(">=", left, from, s), new Ast.Binary("<", left, to, s), s);
    }

    private Ast.Expr membership(Tree.Binary b, Span s) {
        Tree.Expr right = b.right();
        Ast.Expr left = value(b.left());
        if (right instanceof Tree.Range r) return inRange(left, r, s);
        if (right instanceof Tree.ListLit || right instanceof Tree.MapLit) return new Ast.Binary("in", left, value(right), s);
        String kind = shape(right);
        Ast.Expr holder = value(right);
        if (holder instanceof Ast.ListLit) return new Ast.Binary("in", left, holder, s);
        Table.Entry e;
        List<Ast.Arg> args = new ArrayList<>();
        if ("map".equals(kind)) {
            e = t.byId("if_variable_map_has_key");
            args.add(named("map", holder, s));
            args.add(named("key", left, s));
        } else if ("text".equals(kind)) {
            e = t.byId("if_variable_text_contains");
            args.add(named("value", holder, s));
            args.add(named("compare", left, s));
        } else {
            e = t.byId("if_variable_list_contains_value");
            args.add(named("list", holder, s));
            args.add(named("values", left, s));
        }
        return call(e, args, s, s);
    }

    private Ast.Expr stepValue(Tree.Step st) {
        Span s = sp(st.span());
        if (!(st.target() instanceof Tree.Name n)) throw stop("++ и -- бывают только у переменной", st.span());
        Ast.Stmt change = step(st);
        if (st.prefix()) {
            pre.add(change);
            return value(n);
        }
        String tmp = temp();
        pre.add(new Ast.Assign("line", tmp, s, "=", value(n), s, true));
        pre.add(change);
        return tempVar(tmp, s);
    }

    private Ast.Expr gameValue(Tree.Value v) {
        Span s = sp(v.span());
        String id = t.valueId(v.name());
        if (id == null) {
            if (t.staleValue(v.name())) {
                warning("значение «" + v.name() + "» устарело: сервер его больше не знает", v.nameSpan());
                return new Ast.Blank(s);
            }
            throw stop("нет игрового значения «" + v.name() + "»", v.nameSpan(), valueHint(v.name()));
        }
        Ast.Path path = new Ast.Path(List.of("value", id), s);
        if (v.selector() == null) return path;
        String word = selectorWord(v.selector(), v.selectorSpan());
        String target = t.target("value", word);
        if (target == null) {
            if (word.equals("none")) return path;
            throw stop("нет цели <" + word + "> у игрового значения", v.selectorSpan(),
                    "есть: " + String.join(", ", t.names().selectors("value").values()));
        }
        return new Ast.Targeted(target, sp(v.selectorSpan()), path, s);
    }

    private String valueHint(String name) {
        String best = null;
        int score = Integer.MAX_VALUE;
        for (String id : t.data().gameValues().keySet()) {
            int d = distance(name, id);
            if (d < score) {
                score = d;
                best = id;
            }
        }
        return best != null && score <= Math.max(2, name.length() / 3) ? "возможно, вы имели в виду value::" + best : null;
    }

    private Ast.Expr action(Tree.Action a) {
        Table.Entry e = entry(a);
        if (e.id().equals("call_function") || e.id().equals("start_process"))
            throw stop("«" + e.label() + "» — команда, у неё нет значения", a.span());
        if (e.stale()) {
            warning("«" + e.label() + "» устарело: сервер его больше не знает", a.nameSpan());
            return new Ast.Blank(sp(a.span()));
        }
        Span s = sp(a.span());
        if (e.bool()) return booleanValue(conditionCall(e, a.args(), null, a.selector(), a.selectorSpan(), s), e, s);
        Ast.Expr call = call(e, bindValue(e, a.args(), null, a.span()), s, s);
        return a.selector() == null ? call : targeted(e, a.selector(), a.selectorSpan(), call, s);
    }

    private List<Ast.Arg> bindValue(Table.Entry e, List<Tree.Arg> args, Tree.Expr receiver, Span at) {
        if (receiver != null && e.origin() != null && e.result().contains(e.origin()))
            throw stop("«" + e.label() + "» меняет значение на месте и ничего не возвращает", at);
        Set<String> skip = new HashSet<>(e.result());
        Data.Arg result = e.action() == null ? null : e.action().result();
        if (result != null) skip.add(result.id());
        return bind(e, args, receiver, Map.of(), skip, at);
    }

    private Ast.Expr booleanValue(Ast.Expr test, Table.Entry e, Span s) {
        if (t.names().resolve(Names.Space.ACTION, e.xero().qualified()) == null) return test;
        return new Ast.Ternary(test, new Ast.Num("1", s), new Ast.Num("0", s), s);
    }

    private Ast.Expr method(Tree.Method m) {
        Table.Entry e = methodEntry(m);
        Span s = sp(m.span());
        if (e.stale()) {
            warning("«" + e.label() + "» устарело: сервер его больше не знает", m.nameSpan());
            return new Ast.Blank(s);
        }
        if (e.bool()) return booleanValue(conditionCall(e, m.args(), m.receiver(), null, null, s), e, s);
        return call(e, bindValue(e, m.args(), m.receiver(), m.span()), s, s);
    }

    private Ast.Expr attribute(Tree.Attr a) {
        if (a.receiver() instanceof Tree.Name n) {
            String real = enumName(n.name());
            List<String> members = p.enums.get(real);
            if (members != null) {
                if (members.contains(a.name())) return new Ast.Str(null, a.name(), sp(a.span()));
                throw stop("в «" + n.name() + "» нет варианта «" + a.name() + "»", a.nameSpan(), "есть: " + String.join(", ", members));
            }
        }
        throw stop("поля бывают только у классов — их нет на сервере", a.span(), "используй словарь: x[\"" + a.name() + "\"]");
    }

    private Ast.Expr index(Tree.Index i) {
        Span s = sp(i.span());
        String kind = shape(i.target());
        Ast.Expr target = value(i.target());
        Ast.Expr from = value(i.from());
        if (i.to() != null) {
            Ast.Expr to = value(i.to());
            boolean text = "text".equals(kind);
            Table.Entry e = t.byId(text ? "set_variable_trim_text" : "set_variable_trim_list");
            return call(e, new ArrayList<>(List.of(named(text ? "text" : "list", target, s), named("start", from, s), named("end", to, s))), s, s);
        }
        if ("text".equals(kind) && !(from instanceof Ast.Str)) {
            Table.Entry e = t.byId("set_variable_get_char_at");
            return call(e, new ArrayList<>(List.of(named("text", target, s), named("index", from, s))), s, s);
        }
        boolean literal = from instanceof Ast.Num || from instanceof Ast.Str x && !x.interpolated();
        if (literal) return new Ast.Index(target, from, s);
        return read(target, from, "map".equals(kind), s);
    }

    private Ast.Expr call(Tree.Call c) {
        String name = p.aliases.getOrDefault(c.name(), c.name());
        Span s = sp(c.span());
        Tree.Func f = p.functions.get(name);
        if (f != null) {
            if (f.process()) throw stop("процесс «" + name + "» идёт отдельно и значения не возвращает", c.span());
            return functionCall(name, c);
        }
        if (c.quoted()) {
            List<Ast.Arg> list = new ArrayList<>();
            for (Tree.Arg a : c.args()) list.add(new Ast.Arg(a.name(), a.nameSpan() == null ? null : sp(a.nameSpan()), value(a.value())));
            return new Ast.Call(null, name, true, sp(c.nameSpan()), list, s);
        }
        if (Table.MATH.contains(name)) return math(name, c, s);
        String factory = Words.factory(name);
        if (factory != null && !factory.equals("enum")) return factory(factory, c, s);
        if (PASSED.contains(name) || "enum".equals(factory)) {
            String own = "enum".equals(factory) ? "enum" : name;
            return new Ast.Call(null, own, false, s, args(c.args()), s);
        }
        if (p.foreign.contains(name)) throw new Stop();
        throw stop("нет функции «" + name + "»", c.nameSpan(), functionHint(name));
    }

    private Ast.Expr math(String name, Tree.Call c, Span s) {
        List<Ast.Arg> list = new ArrayList<>();
        boolean many = (name.equals("min") || name.equals("max")) && formula == 0 && c.args().size() > 1;
        if (many) {
            List<Ast.Expr> items = new ArrayList<>();
            for (Tree.Arg a : c.args()) items.add(value(a.value()));
            list.add(new Ast.Arg(null, null, new Ast.ListLit(items, s)));
        } else {
            for (Tree.Arg a : c.args()) list.add(new Ast.Arg(a.name(), a.nameSpan() == null ? null : sp(a.nameSpan()), value(a.value())));
        }
        return new Ast.Call(null, name, false, sp(c.nameSpan()), list, s);
    }

    private Map<String, Tree.Expr> factoryArgs(String what, List<String> order, List<Tree.Arg> args, Span at) {
        Map<String, Tree.Expr> bound = new LinkedHashMap<>();
        for (Tree.Arg a : args) if (a.name() != null) bound.put(a.name(), a.value());
        int slot = 0;
        for (Tree.Arg a : args) {
            if (a.name() != null) continue;
            while (slot < order.size() && bound.containsKey(order.get(slot))) slot++;
            if (slot >= order.size()) throw stop("лишний аргумент у " + what + "()", a.value().span());
            bound.put(order.get(slot), a.value());
        }
        return bound;
    }

    private Ast.Expr factory(String kind, Tree.Call c, Span s) {
        return switch (kind) {
            case "item" -> item(c.args(), s, sp(c.nameSpan()));
            case "location" -> coordinates(c, "loc", "set_variable_set_all_coordinates", LOCATION_ORDER, s);
            case "vector" -> coordinates(c, "vec", "set_variable_vector", VECTOR_ORDER, s);
            case "sound" -> sound(c, s);
            case "particle" -> particle(c, s);
            case "potion" -> potion(c, s);
            case "block" -> new Ast.Call(null, "block", false, sp(c.nameSpan()), args(c.args()), s);
            case "text" -> textFactory(c, s);
            case "array" -> {
                List<Ast.Expr> items = new ArrayList<>();
                for (Tree.Arg a : c.args()) items.add(value(a.value()));
                yield new Ast.ListLit(items, s);
            }
            case "map" -> mapFactory(c, s);
            case "value" -> valueFactory(c);
            default -> throw stop("нет значения «" + kind + "»", c.nameSpan());
        };
    }

    private Ast.Expr textFactory(Tree.Call c, Span s) {
        if (c.args().size() != 1) throw stop("text(значение): один аргумент", c.span());
        Ast.Expr v = value(c.args().get(0).value());
        if (v instanceof Ast.Str) return v;
        return new Ast.Str(null, "", new ArrayList<>(List.of((Object) v)), s);
    }

    private Ast.Expr mapFactory(Tree.Call c, Span s) {
        if (c.args().isEmpty()) return new Ast.MapLit(List.of(), List.of(), s);
        if (c.args().size() == 1) return value(c.args().get(0).value());
        List<Ast.Expr> keys = new ArrayList<>();
        List<Ast.Expr> values = new ArrayList<>();
        for (Tree.Arg a : c.args()) {
            if (a.name() == null) throw stop("map(ключ = значение, …)", a.value().span());
            keys.add(new Ast.Str(null, a.name(), sp(a.nameSpan())));
            values.add(value(a.value()));
        }
        return new Ast.MapLit(keys, values, s);
    }

    private Ast.Expr valueFactory(Tree.Call c) {
        if (c.args().isEmpty() || !(c.args().get(0).value() instanceof Tree.Str id) || id.parts() != null)
            throw stop("value(\"имя\"), или value::имя", c.span());
        String selector = null;
        Span at = null;
        if (c.args().size() > 1 && c.args().get(1).value() instanceof Tree.Str x && x.parts() == null) {
            selector = x.text();
            at = x.span();
        }
        return gameValue(new Tree.Value(id.text(), id.span(), selector, at, c.span()));
    }

    private Ast.Expr coordinates(Tree.Call c, String name, String action, List<String> order, Span s) {
        Map<String, Tree.Expr> bound = factoryArgs(name, order, c.args(), c.span());
        List<Ast.Expr> values = new ArrayList<>();
        boolean constant = true;
        for (String k : order) {
            Tree.Expr e = bound.get(k);
            Ast.Expr v = e == null ? null : value(e);
            values.add(v);
            if (v != null && !constant(v)) constant = false;
        }
        if (constant) {
            List<Ast.Arg> list = new ArrayList<>();
            int last = values.size();
            while (last > 3 && values.get(last - 1) == null) last--;
            for (int i = 0; i < last; i++)
                list.add(new Ast.Arg(null, null, values.get(i) != null ? values.get(i) : new Ast.Num("0", s)));
            return new Ast.Call(null, name, false, sp(c.nameSpan()), list, s);
        }
        List<Ast.Arg> list = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            if (values.get(i) != null) list.add(named(order.get(i), values.get(i), s));
            else if (i < 3) list.add(named(order.get(i), new Ast.Num("0", s), s));
        }
        return call(t.byId(action), list, s, s);
    }

    private static boolean constant(Ast.Expr e) {
        return switch (e) {
            case Ast.Num n -> true;
            case Ast.Unary u -> u.op().equals("-") && constant(u.operand());
            case Ast.Binary b -> "+-*/".contains(b.op()) && constant(b.left()) && constant(b.right());
            default -> false;
        };
    }

    private Ast.Expr item(List<Tree.Arg> args, Span s, Span nameSpan) {
        Map<String, Tree.Expr> bound = factoryArgs("item", ITEM_ORDER, args, s);
        Tree.Expr id = bound.get("id");
        if (id == null) throw stop("item(id): нужен id предмета", s);
        List<Ast.Arg> list = new ArrayList<>();
        list.add(new Ast.Arg(null, null, value(id)));
        for (Map.Entry<String, Tree.Expr> e : bound.entrySet()) {
            String key = e.getKey();
            if (key.equals("id")) continue;
            Tree.Expr v = e.getValue();
            Span at = sp(v.span());
            switch (key) {
                case "nbt" -> list.add(named("components", new Ast.Str(null, snbt(v), at), at));
                case "custom_tags" -> list.add(named("tags", value(v), at));
                default -> list.add(named(key, value(v), at));
            }
        }
        return new Ast.Call(null, "item", false, nameSpan, list, s);
    }

    private String snbt(Tree.Expr v) {
        if (v instanceof Tree.Nbt n) return n.text();
        if (v instanceof Tree.Str x && x.parts() == null) return x.text();
        Tree.Expr r = v instanceof Tree.Name n ? inline(n.name()) : null;
        if (r != null) return snbt(r);
        throw stop("nbt пишется m{…}", v.span());
    }

    private Ast.Expr sound(Tree.Call c, Span s) {
        Map<String, Tree.Expr> bound = factoryArgs("sound", SOUND_ORDER, c.args(), c.span());
        Tree.Expr id = bound.get("sound");
        if (id == null) throw stop("sound(id): нужен id звука", c.span());
        List<Ast.Arg> list = new ArrayList<>();
        Ast.Expr shown = value(id);
        list.add(new Ast.Arg(null, null, shown));
        Map<String, Ast.Expr> fields = new LinkedHashMap<>();
        for (String k : SOUND_ORDER) {
            if (k.equals("sound") || !bound.containsKey(k)) continue;
            Tree.Expr v = bound.get(k);
            Ast.Expr field = value(v);
            fields.put(k, field);
            list.add(named(k, field, sp(v.span())));
        }
        if (shown instanceof Ast.Str x && !x.interpolated()) {
            Ast.Expr own = ownSound(x.text(), fields, c, s);
            if (own != null) return own;
        }
        return new Ast.Call(null, "sound", false, sp(c.nameSpan()), list, s);
    }

    private Ast.Expr ownSound(String id, Map<String, Ast.Expr> fields, Tree.Call c, Span s) {
        String bare = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        Set<String> known = t.data().pickers().sounds();
        if (known.isEmpty() || known.contains(bare)) return null;
        Double volume = number(fields.get("volume"), 1);
        Double pitch = number(fields.get("pitch"), 1);
        String variation = fields.get("variation") instanceof Ast.Str x && !x.interpolated() ? x.text() : fields.containsKey("variation") ? null : "";
        String source = fields.get("source") instanceof Ast.Str x && !x.interpolated() ? x.text() : fields.containsKey("source") ? null : "MASTER";
        if (volume == null || pitch == null || variation == null || source == null) return null;
        warning("звука «" + bare + "» нет в данных сервера — уйдёт как есть", c.nameSpan());
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("type", "sound");
        raw.put("sound", id.contains(":") ? id : "minecraft:" + id);
        raw.put("pitch", new Json.Num(Double.toString(pitch)));
        raw.put("volume", new Json.Num(Double.toString(volume)));
        raw.put("variation", variation);
        raw.put("source", source.toUpperCase(Locale.ROOT));
        return new Ast.Call(null, "raw", false, s, List.of(new Ast.Arg(null, null, new Ast.Str(null, Json.write(raw), s))), s);
    }

    private static Double number(Ast.Expr e, double fallback) {
        if (e == null) return fallback;
        if (e instanceof Ast.Num n) return Double.parseDouble(n.text());
        if (e instanceof Ast.Unary u && u.op().equals("-") && u.operand() instanceof Ast.Num n) return -Double.parseDouble(n.text());
        return null;
    }

    private Ast.Expr potion(Tree.Call c, Span s) {
        Map<String, Tree.Expr> bound = factoryArgs("potion", POTION_ORDER, c.args(), c.span());
        Tree.Expr id = bound.get("potion");
        if (id == null) throw stop("potion(id): нужен id зелья", c.span());
        List<Ast.Arg> list = new ArrayList<>();
        list.add(new Ast.Arg(null, null, value(id)));
        for (String k : POTION_ORDER) {
            if (k.equals("potion") || !bound.containsKey(k)) continue;
            Tree.Expr v = bound.get(k);
            list.add(named(k, value(v), sp(v.span())));
        }
        return new Ast.Call(null, "potion", false, sp(c.nameSpan()), list, s);
    }

    private Ast.Expr particle(Tree.Call c, Span s) {
        Map<String, Tree.Expr> bound = factoryArgs("particle", PARTICLE_ORDER, c.args(), c.span());
        Tree.Expr id = bound.get("particle");
        if (id == null) throw stop("particle(id): нужен id частицы", c.span());
        Ast.Expr shown = value(id);
        if (shown instanceof Ast.Str x && x.text().startsWith("minecraft:"))
            shown = new Ast.Str(null, x.text().substring("minecraft:".length()), x.span());
        List<String> extras = shown instanceof Ast.Str x ? t.data().pickers().particles().get(x.text().toUpperCase(Locale.ROOT)) : null;
        List<Ast.Arg> list = new ArrayList<>();
        list.add(new Ast.Arg(null, null, shown));
        for (Map.Entry<String, Tree.Expr> e : bound.entrySet()) {
            String key = e.getKey();
            if (key.equals("particle")) continue;
            String real = PARTICLE_NAMES.getOrDefault(key, key);
            Ast.Expr v = value(e.getValue());
            if (extras != null && !accepted(real, extras) && empty(v)) continue;
            if (real.equals("material") && empty(v)) continue;
            list.add(named(real, v, sp(e.getValue().span())));
        }
        return new Ast.Call(null, "particle", false, sp(c.nameSpan()), list, s);
    }

    private static boolean accepted(String field, List<String> extras) {
        return switch (field) {
            case "x_motion", "y_motion", "z_motion" -> extras.contains("Движение");
            case "color" -> extras.contains("Цвет");
            case "to_color" -> extras.contains("Цвет перехода");
            case "size" -> extras.contains("Размер");
            case "material" -> extras.contains("Материал");
            default -> true;
        };
    }

    private static boolean empty(Ast.Expr v) {
        return v instanceof Ast.Num n && Double.parseDouble(n.text()) == 0 || v instanceof Ast.Str x && !x.interpolated() && x.text().isEmpty();
    }
}
