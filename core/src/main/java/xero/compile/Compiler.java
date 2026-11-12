package xero.compile;

import xero.data.Data;
import xero.data.Names;
import xero.json.Json;
import xero.lang.Ast;
import xero.lang.Ast.Arg;
import xero.lang.Ast.Expr;
import xero.lang.Ast.Span;
import xero.lang.Diagnostic;
import xero.lang.Keywords;
import xero.lang.Parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import static xero.compile.Context.array;
import static xero.compile.Context.entry;
import static xero.compile.Context.enumValue;
import static xero.compile.Context.named;
import static xero.compile.Context.num;
import static xero.compile.Context.obj;
import static xero.compile.Context.text;

public final class Compiler {
    public static final String MODULE = "module:";
    public static final String CONNECT = "connect:";

    public record Declared(Ast.Func func, String file, String module) {}

    public record Origin(String file, int start, int end) {}

    public record Result(Map<String, Object> module, List<Diagnostic> diagnostics, List<Declared> declared, List<Object> connected,
                         List<List<Origin>> columns) {
        public List<Ast.Func> imported() {
            List<Ast.Func> out = new ArrayList<>();
            for (Declared d : declared) if (d.module() != null) out.add(d.func());
            return out;
        }

        public boolean ok() {
            for (Diagnostic d : diagnostics) if (d.error()) return false;
            return true;
        }
    }

    public interface Front {
        Ast.File parse(String file, String text, List<Diagnostic> diagnostics);

        default boolean source(String path) {
            return path.toLowerCase(Locale.ROOT).endsWith(".xero");
        }
    }

    public interface Loader {
        String load(String path);
    }

    record Unit(String file, Ast.File ast) {}

    private static final String WORLD_EVENTS = "Событие мира";

    private final Context c;
    private Front front;

    Compiler(Context c) {
        this.c = c;
    }

    public static Result compile(Data data, Names names, String text) {
        return compile(data, names, null, text, null);
    }

    public static Result compile(Data data, Names names, String name, String text, Loader loader) {
        return compile(data, names, name, text, loader, null);
    }

    public static Result compile(Data data, Names names, String name, String text, Loader loader, Front front) {
        try {
            return run(data, names, name, text, loader, front);
        } catch (StackOverflowError e) {
            return new Result(obj("handlers", new ArrayList<>()), List.of(Diagnostic.error("код вложен слишком глубоко — раздели его на части", 0, 1)),
                    List.of(), List.of(), List.of());
        }
    }

    private static Result run(Data data, Names names, String name, String text, Loader loader, Front front) {
        Context c = new Context(data, names);
        c.compiler.front = front;
        List<Unit> units = new ArrayList<>();
        c.compiler.load(units, new LinkedHashSet<>(), name, text, loader);
        Map<String, Object> module = c.compiler.module(units);
        List<String> order = new ArrayList<>();
        for (Unit u : units) order.add(u.file());
        List<Diagnostic> diagnostics = new ArrayList<>(c.diagnostics);
        diagnostics.sort((a, b) -> {
            int fa = order.indexOf(a.file()), fb = order.indexOf(b.file());
            return fa != fb ? Integer.compare(fa, fb) : Integer.compare(a.start(), b.start());
        });
        List<Declared> declared = new ArrayList<>();
        c.functions.forEach((n, f) -> declared.add(new Declared(f, c.origins.get(n), c.external.get(n))));
        c.processes.forEach((n, f) -> declared.add(new Declared(f, c.origins.get(n), c.external.get(n))));
        List<Object> connected = new ArrayList<>();
        for (Context.Imported m : c.modules) if (m.entry() != null) connected.add(m.entry());
        return new Result(module, diagnostics, declared, connected, columns(module, c.sources));
    }

    private static List<List<Origin>> columns(Map<String, Object> module, Map<Object, Origin> sources) {
        List<List<Origin>> out = new ArrayList<>();
        for (Object h : Json.array(module.get("handlers"))) {
            List<Origin> line = new ArrayList<>();
            if (Json.object(h).get("operations") instanceof List<?> ops) walk(Json.array(ops), sources, line);
            out.add(line);
        }
        return out;
    }

    private static void walk(List<Object> ops, Map<Object, Origin> sources, List<Origin> out) {
        for (Object op : ops) {
            Origin origin = sources.get(op);
            out.add(origin);
            if (!(op instanceof Map<?, ?> m) || !(m.get("operations") instanceof List<?> body)) continue;
            walk(Json.array(body), sources, out);
            out.add(origin);
        }
    }

    private void load(List<Unit> units, Set<String> seen, String name, String text, Loader loader) {
        seen.add(name == null ? "" : name);
        c.diagnostics.file = name;
        Ast.File ast = front == null ? Parser.parse(text, c.diagnostics) : front.parse(name, text, c.diagnostics);
        units.add(new Unit(name, ast));
        for (Ast.Item item : ast.items()) {
            if (!(item instanceof Ast.Import imp)) continue;
            boolean source = front == null ? imp.path().toLowerCase(Locale.ROOT).endsWith(".xero") : front.source(imp.path());
            if (!source) {
                module(imp, name, loader, seen);
                continue;
            }
            String path = resolve(name, imp.path());
            if (!seen.add(path)) continue;
            String loaded = loader == null ? null : loader.load(path);
            if (loaded == null) {
                c.diagnostics.file = name;
                c.error("нет файла «" + imp.path() + "»", imp.span(), loader == null ? "импорт работает при сборке файла с диска" : null);
                continue;
            }
            load(units, seen, path, loaded, loader);
        }
    }

    private void module(Ast.Import imp, String file, Loader loader, Set<String> seen) {
        String wanted = imp.path().strip();
        if (!seen.add(MODULE + wanted.toLowerCase(Locale.ROOT))) return;
        c.diagnostics.file = file;
        String text = loader == null ? null : loader.load(MODULE + (imp.connect() ? CONNECT : "") + wanted);
        if (text == null) {
            c.error("модуль «" + wanted + "» не получен", imp.span(), loader == null ? "импорт работает при сборке файла с диска" : null);
            return;
        }
        Map<String, Object> got;
        try {
            got = Json.object(Json.parse(text));
        } catch (Json.Error | IllegalArgumentException | ClassCastException e) {
            c.error("у модуля «" + wanted + "» непонятный код", imp.span());
            return;
        }
        if (got.get("error") instanceof String why) {
            c.error(why, imp.span(), got.get("hint") instanceof String hint ? hint : null);
            return;
        }
        if (got.get("note") instanceof String note) c.warning(note, imp.span());
        if (got.get("handlers") instanceof List<?> handlers)
            c.modules.add(new Context.Imported(wanted, file, imp.span(), Json.array(handlers), imp.connect() ? got.get("entry") : null));
    }

    static String resolve(String from, String path) {
        String p = path.replace('\\', '/');
        String base = from == null ? "" : from.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        String dir = p.startsWith("/") || slash < 0 ? "" : base.substring(0, slash + 1);
        List<String> parts = new ArrayList<>();
        for (String part : (dir + p).split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..") && !parts.isEmpty() && !parts.get(parts.size() - 1).equals("..")) parts.remove(parts.size() - 1);
            else parts.add(part);
        }
        String joined = String.join("/", parts);
        return (dir + p).startsWith("/") ? "/" + joined : joined;
    }

    private Map<String, Object> module(List<Unit> units) {
        Set<String> functions = new java.util.HashSet<>();
        for (Unit u : units) functions.addAll(Methods.functions(u.ast()));
        for (Context.Imported m : c.modules)
            for (Object h : m.handlers())
                if (h instanceof Map<?, ?> line && line.get("name") instanceof String name) functions.add(name);
        Methods methods = new Methods(c.names, functions::contains);
        for (int i = 0; i < units.size(); i++) units.set(i, new Unit(units.get(i).file(), methods.file(units.get(i).ast())));
        c.methodCalls.addAll(methods.made());
        c.declarations.collect(units);
        c.order.collect(units);
        List<Object> handlers = new ArrayList<>();
        int next = 0;
        for (Unit u : units) {
            c.diagnostics.file = u.file();
            for (Ast.Item item : u.ast().items()) {
                List<Ast.Decorator> marks = switch (item) {
                    case Ast.Event e -> e.decorators();
                    case Ast.Func f -> f.decorators();
                    case Ast.Global g -> null;
                    case Ast.Const k -> null;
                    case Ast.Import i -> null;
                };
                if (marks == null) continue;
                int position = c.declarations.line(marks, next);
                next = position + 1;
                try {
                    Map<String, Object> h = item instanceof Ast.Event e ? event(e, position) : function((Ast.Func) item, position);
                    if (h != null) {
                        handlers.add(h);
                        if (!Wrap.carries(h, c.data)) c.warning("строка шире " + Wrap.WIDEST + " блоков, а перенести её нельзя: сервер её не примет",
                                item instanceof Ast.Event e ? e.nameSpan() : ((Ast.Func) item).nameSpan(), "вынеси часть кода в функцию");
                    }
                } catch (Context.Stop ignored) {
                }
            }
        }
        for (Context.Imported m : c.modules) {
            if (m.entry() != null) continue;
            List<Map<String, Object>> lines = new ArrayList<>();
            for (Object h : m.handlers()) lines.add(new LinkedHashMap<>(Json.object(h)));
            lines.sort(java.util.Comparator.comparingInt(h -> h.get("position") instanceof Json.Num n ? n.intValue() : Integer.MAX_VALUE));
            for (Map<String, Object> line : lines) {
                line.put("position", next++);
                handlers.add(line);
            }
        }
        c.locals.report(units);
        c.lints.report();
        return obj("handlers", handlers);
    }

    private Map<String, Object> event(Ast.Event e, int position) {
        for (Ast.Decorator d : e.decorators())
            if (!d.name().equals(Declarations.LINE) && !d.name().equals(Declarations.CUSTOM_DATA))
                c.error("у события бывают только пометки @line и @custom_data", d.span());
        c.current = null;
        c.params = c.declarations.lineScoped(e);
        c.lineKinds = c.declarations.lineKinds(e);
        c.declarations.enter(e);
        c.locals.enter(e);
        Data.Event known = c.data.events().get(e.name());
        c.happening = known;
        c.world = known != null && WORLD_EVENTS.equals(known.group());
        c.selected = false;
        c.flags = 0;
        List<Object> ops = block(e.body());
        c.world = false;
        c.happening = null;
        c.params = Map.of();
        c.lineKinds = Map.of();
        c.declarations.leave();
        c.locals.leave();
        if (!c.data.events().containsKey(e.name())) {
            String best = Suggest.best(e.name(), c.data.events().keySet());
            Hints.Found found = best != null ? new Hints.Found(Suggest.meant(best), List.of(best))
                    : c.hints.russianEvents(e.name());
            c.error("нет события «" + e.name() + "»", e.nameSpan(), found.hint(), Hints.replace(e.nameSpan(), found.names()));
            return null;
        }
        return c.declarations.customData(e.decorators(), obj("type", "event", "event", e.name(), "position", position, "operations", ops));
    }

    private Map<String, Object> function(Ast.Func f, int position) {
        c.current = f;
        c.locals.enter(f);
        c.lints.enter();
        c.flags = 0;
        Map<String, String> own = new LinkedHashMap<>(c.declarations.lineScoped(f));
        Ast.Func declared = (f.process() ? c.processes : c.functions).getOrDefault(f.name(), f);
        for (Ast.Param p : declared.params()) own.put(p.name(), "line");
        c.params = own;
        c.paramKinds = Declarations.paramKinds(declared);
        c.lineKinds = c.declarations.lineKinds(f);
        c.declarations.enter(f);
        List<Object> values = c.declarations.values(f);
        List<Object> ops = new ArrayList<>();
        for (String name : c.declarations.settled()) {
            Object param = obj("type", "variable", "variable", name, "scope", "line");
            ops.add(setValue(param, param));
        }
        ops.addAll(block(f.body()));
        c.lints.leave(f);
        if (c.returning.contains(f.name()) && !Declarations.alwaysReturns(f.body()))
            c.warning("«" + f.name() + "» может дойти до конца без return — тогда вызов получит прошлое значение",
                    f.nameSpan(), "допиши return … в конце");
        c.current = null;
        c.params = Map.of();
        c.paramKinds = Map.of();
        c.lineKinds = Map.of();
        c.declarations.leave();
        c.locals.leave();
        return c.declarations.customData(f.decorators(), obj("type", f.process() ? "process" : "function", "name", f.name(), "values", values,
                "position", position, "operations", ops));
    }

    List<Object> block(Ast.Block block) {
        List<Object> ops = new ArrayList<>();
        if (block == null) return ops;
        boolean ended = false, told = false;
        for (Ast.Stmt s : block.stmts()) {
            if (ended && !told) {
                c.warning("этот код никогда не выполнится", s.span());
                told = true;
            }
            try {
                statement(s, ops);
            } catch (Context.Stop ignored) {
            }
            if (s instanceof Ast.Jump) ended = true;
        }
        return ops;
    }

    private void statement(Ast.Stmt s, List<Object> ops) {
        List<Object> saved = c.prelude;
        Map<String, Integer> savedCounts = c.callCounts;
        Map<Ast.Var, Ast.Var> savedSnapshots = c.snapshots;
        c.prelude = new ArrayList<>();
        c.callCounts = c.calls.count(s);
        c.snapshots = new java.util.IdentityHashMap<>();
        List<Object> out = new ArrayList<>();
        try {
            c.order.plan(s);
            statementInto(s, out);
        } finally {
            Origin origin = new Origin(c.diagnostics.file, s.span().start(), s.span().end());
            for (Object op : c.prelude) c.sources.putIfAbsent(op, origin);
            for (Object op : out) c.sources.putIfAbsent(op, origin);
            ops.addAll(c.prelude);
            ops.addAll(out);
            c.prelude = saved;
            c.callCounts = savedCounts;
            c.snapshots = savedSnapshots;
        }
    }

    private void statementInto(Ast.Stmt s, List<Object> ops) {
        switch (s) {
            case Ast.Run r -> ops.add(run(r));
            case Ast.Assign a -> assign(a, ops);
            case Ast.IndexAssign a -> {
                Ast.Index target = onceIndexed(a.target(), ops);
                Expr value = a.op().equals("=") ? a.value()
                        : new Ast.Binary(a.op().substring(0, 1), target, a.value(), a.span());
                write(target, value, ops);
            }
            case Ast.Declare d -> c.lints.name(d.name());
            case Ast.MultiAssign m -> {
                if (m.values().size() == 1 && m.names().size() > 1) {
                    unpack(m, ops);
                    return;
                }
                if (m.names().size() != m.values().size()) {
                    c.error("слева " + m.names().size() + " переменных, а справа " + m.values().size() + " значений", m.span());
                    return;
                }
                List<Expr> vars = new ArrayList<>();
                for (int n = 0; n < m.names().size(); n++) vars.add(new Ast.Var(m.names().get(n), false, m.nameSpans().get(n)));
                Data.Action action = c.data.actions().get("set_variable_multiple");
                ops.add(obj("action", action.id(), "values", bind(action, List.of(
                        named("variables", new Ast.ListLit(vars, m.span())),
                        named("values", new Ast.ListLit(m.values(), m.span()))), null, m.span())));
            }
            case Ast.If i -> c.conditions.ifChain(i.branches(), i.otherwise(), ops);
            case Ast.Jump j -> jump(j, ops);
            case Ast.Start st -> ops.add(c.calls.start(st));
            case Ast.Repeat r -> ops.add(loop("repeat_multi_times", List.of(named("amount", r.count())), r.body()));
            case Ast.ForRange f -> {
                List<Arg> args = new ArrayList<>(List.of(named("variable", new Ast.Var(f.name(), false, f.nameSpan())),
                        named("start", f.from()), named("end", f.to())));
                if (f.step() != null) args.add(named("interval", f.step()));
                ops.add(loop("repeat_on_range", args, f.body()));
            }
            case Ast.ForEach f -> {
                List<Arg> args = new ArrayList<>();
                if (f.map()) {
                    args.add(named("key_variable", new Ast.Var(f.first(), false, f.firstSpan())));
                    args.add(named("value_variable", new Ast.Var(f.second(), false, f.secondSpan())));
                    args.add(named("map", f.source()));
                    ops.add(loop("repeat_for_each_map_entry", args, f.body()));
                } else {
                    if (f.first() != null) args.add(named("index_variable", new Ast.Var(f.first(), false, f.firstSpan())));
                    args.add(named("value_variable", new Ast.Var(f.second(), false, f.secondSpan())));
                    args.add(named("list", f.source()));
                    ops.add(loop("repeat_for_each_in_list", args, f.body()));
                }
            }
            case Ast.While w -> ops.add(c.conditions.whileLoop(w.condition(), w.body()));
            case Ast.Forever f -> {
                if (!c.lints.pauses(f.body())) c.warning("в forever нет wait: сервер остановит цикл за слишком много итераций", f.span(),
                        "допиши wait(1) или выход через break");
                ops.add(loop("repeat_forever", List.of(), f.body()));
            }
        }
    }

    Map<String, Object> forever(Ast.Block body) {
        return loop("repeat_forever", List.of(), body);
    }

    private Map<String, Object> loop(String id, List<Arg> args, Ast.Block body) {
        Data.Action action = c.data.actions().get(id);
        List<Object> values = bind(action, args, null, body.span());
        c.loops++;
        List<Object> ops = block(body);
        c.loops--;
        return obj("action", action.id(), "values", values, "operations", ops);
    }

    private Map<String, Object> run(Ast.Run r) {
        String target = null;
        Span targetSpan = null;
        Expr inner = r.call();
        if (inner instanceof Ast.Targeted t) {
            target = t.target();
            targetSpan = t.targetSpan();
            inner = t.inner();
        }
        if (inner instanceof Ast.Blank) {
            if (target != null || r.body() != null) c.error("пустой блок _ пишется один", r.span());
            return obj("action", "empty", "values", new ArrayList<>());
        }
        if (!(inner instanceof Ast.Call call)) {
            throw c.stop("здесь нужна команда, например send_message(\"…\")", r.call().span());
        }
        if (call.qualifier() == null && !call.quoted() && call.name().equals("raw")) {
            if (target != null) c.error("у raw цель пишется внутри JSON", targetSpan);
            Object raw = c.rawJson(call.args(), call.span());
            if (!(raw instanceof Map<?, ?>) || !Json.object(raw).containsKey("action"))
                throw c.stop("raw-команда — это JSON-объект с полем action", call.span());
            c.locals.raw(raw);
            Map<String, Object> op = new LinkedHashMap<>(Json.object(raw));
            if (r.body() != null) op.put("operations", block(r.body()));
            return op;
        }
        if (call.qualifier() == null && !call.quoted() && call.name().equals("call")) {
            if (target != null) c.error("у вызова функции нет цели", targetSpan);
            if (r.body() != null) c.error("у вызова функции нет тела", r.body().span());
            return c.calls.dynamic(call);
        }
        if (c.userCall(call)) {
            if (target != null) c.error("у вызова функции нет цели", targetSpan);
            if (r.body() != null) c.error("у вызова функции нет тела", r.body().span());
            return c.calls.invoke(call);
        }
        Data.Action action = action(call);
        if (target == null && r.body() == null && inPlace(action, call)) {
            Ast.Var receiver = (Ast.Var) call.args().get(0).value();
            c.lints.read(receiver.name());
            return obj("action", action.id(), "values", bind(action, call.args(), receiver, call.span()));
        }
        Map<String, Object> op = obj("action", action.id());
        if (Wire.CONDITIONAL.contains(action.canonical())) {
            if (call.args().isEmpty() || call.args().get(0).name() != null)
                throw c.stop("«" + call.shown() + "» нужно условие: " + call.shown() + "(x == 1)", call.span());
            Expr condition = call.args().get(0).value();
            boolean loop = action.canonical().equals("repeat_while") && target == null;
            for (int i = 1; i < call.args().size(); i++) c.error("лишний аргумент", call.args().get(i).value().span());
            Ast.Block body = r.body() == null ? new Ast.Block(List.of(), call.span()) : r.body();
            if (!(c.conditions.tree(condition, false) instanceof Conditions.Leaf leaf)) {
                if (loop) return c.conditions.whileLoop(condition, body);
                throw c.stop("в «" + call.shown() + "» and/or нельзя — сервер проверяет условие у каждой цели отдельно",
                        condition.span(), "над одной переменной пиши x in [1, 2], иначе посчитай условие заранее в переменную");
            }
            Context.Captured<Conditions.Cond> captured = c.capture(() -> c.conditions.leafCond(leaf));
            if (!captured.pre().isEmpty()) {
                if (loop) return c.conditions.whileLoop(condition, body);
                throw c.stop("в «" + call.shown() + "» вызовы, индексы и формулы-условия нельзя — сервер проверяет условие сам",
                        condition.span(), "посчитай значение заранее в переменную");
            }
            Conditions.Cond cond = captured.value();
            candidate(action, condition);
            op.put("values", cond.values());
            op.put("conditional", obj("action", cond.id(), "is_inverted", cond.inverted()));
            if (cond.selection() != null) op.put("selection", cond.selection());
        } else {
            op.put("values", bind(action, call.args(), null, call.span()));
        }
        if (target != null) op.put("selection", selection(action, target, targetSpan));
        else noTarget(action, call.nameSpan());
        if (action.category() == xero.data.Category.SELECT) c.selected = true;
        if (action.id().equals("game_cancel_event") && c.happening != null && !c.happening.description().contains("Отменяемое"))
            c.warning("событие «" + c.happening.id() + "» нельзя отменить: сервер ответит «Данное событие нельзя отменить»", call.nameSpan());
        if (action.container()) {
            boolean loop = action.id().startsWith("repeat_");
            if (loop) c.loops++;
            op.put("operations", block(r.body()));
            if (loop) c.loops--;
        } else if (r.body() != null) {
            c.error("у «" + call.shown() + "» нет тела в фигурных скобках", r.body().span());
        }
        return op;
    }

    private boolean inPlace(Data.Action action, Ast.Call call) {
        Data.Arg result = action.result();
        List<Arg> args = call.args();
        if (result == null || args.isEmpty() || args.get(0).name() != null || !(args.get(0).value() instanceof Ast.Var)) return false;
        List<Data.Arg> rest = new ArrayList<>(action.args());
        rest.remove(result);
        if (Wire.IN_PLACE.contains(action.canonical())) {
            rest.removeIf(a -> Data.VARIABLE.equals(a.kind()));
            boolean fullForm = args.size() > 1 && args.get(1).name() == null && rest.get(0).kind().equals(kindOf(args.get(1).value()));
            return !fullForm && args.stream().filter(a -> a.name() == null).count() <= rest.size();
        }
        if (args.size() < 2 || args.get(1).name() != null) return false;
        if (rest.isEmpty() || !Kinds.ITEM.equals(rest.get(0).kind())) return false;
        Expr second = c.resolved(args.get(1).value());
        return second instanceof Ast.Str s ? !itemId(s) : Kinds.NUMBER.equals(kindOf(second));
    }

    private boolean itemId(Ast.Str s) {
        return !s.interpolated() && c.data.minecraft().items().contains("minecraft:" + s.text().toLowerCase(Locale.ROOT));
    }

    private void candidate(Data.Action action, Expr condition) {
        if (!action.id().contains("entity")) return;
        Expr inner = condition instanceof Ast.Unary u ? u.operand() : condition;
        if (!(inner instanceof Ast.Binary b)) return;
        for (Expr side : List.of(b.left(), b.right())) {
            if (side instanceof Ast.Targeted t && t.target().equals("current") && t.inner() instanceof Ast.Path)
                c.warning("в условии выборки сущностей @current не видит кандидата: сравнение идёт не с ним", t.span(),
                        "проверяй условием сущности, например entity.name_equals(\"имя\")");
        }
    }

    Data.Action action(Ast.Call call) {
        if (call.qualifier() == null && Wire.VALUES.contains(call.name()))
            throw c.stop("«" + call.name() + "(…)» — это значение, его передают в аргумент", call.nameSpan());
        Names.Entry e = c.names.resolve(Names.Space.ACTION, call.shown());
        if (e == null) throw c.hints.unknownCall(call, Names.Space.ACTION);
        c.hints.spare(e, call.nameSpan());
        return e.action();
    }

    void noTarget(Data.Action action, Span at) {
        if (!c.world || c.selected || !"p".equals(action.family())) return;
        c.warning("в событии мира у игрока нет цели по умолчанию — «" + Names.shortName(action.id())
                + "» ни на кого не подействует", at,
                "допиши цель: @all " + Names.shortName(action.id()) + "(…), или сначала выбери игроков действием select");
    }

    Map<String, Object> selection(Data.Action action, String target, Span at) {
        if (action.family() == null) {
            c.error("у «" + Names.shortName(action.id()) + "» нет цели", at);
            return obj("type", "current");
        }
        Map<String, String> known = c.names.selectors(action.family());
        String id = known.get(target);
        if (id == null) {
            String best = Suggest.best(target, known.keySet());
            c.error("нет цели @" + target, at, Suggest.meant(best), Hints.replace(at, best));
            return obj("type", "current");
        }
        return obj("type", id);
    }

    List<Object> bind(Data.Action action, List<Arg> given, Ast.Var into, Span at) {
        Map<String, Expr> bound = new LinkedHashMap<>();
        List<Arg> args = given;
        List<Data.Arg> list = action.args();
        List<Data.Arg> order = new ArrayList<>(list);
        List<Data.Setting> settings = action.settings();
        int positional = 0;
        if (into != null) {
            Data.Arg result = action.result();
            if (result == null)
                throw c.stop("«" + Names.shortName(action.id()) + "» ничего не записывает в переменную", at);
            bound.put(result.id(), into);
            order.remove(result);
            if (repeatsTarget(action, args, into)) args = args.subList(1, args.size());
            else if (skipsExtra(action, order, args, into)) order.remove(0);
        }
        for (Arg a : args) {
            String key;
            if (a.name() == null) {
                Data.Setting word = wordFor(a.value(), settings, bound.keySet(), c::known);
                if (word != null) {
                    key = word.id();
                    if (positional >= order.size()) positional++;
                } else if (positional < order.size()) key = order.get(positional++).id();
                else if (positional - order.size() < settings.size()) key = settings.get(positional++ - order.size()).id();
                else {
                    c.error("лишний аргумент: у «" + Names.shortName(action.id()) + "» их " + order.size()
                            + (settings.isEmpty() ? "" : " и " + settings.size() + " настроек"), a.value().span(),
                            action.plural().isEmpty() ? null : "несколько значений пишут списком: [a, b]");
                    continue;
                }
            } else {
                key = a.name().toLowerCase(Locale.ROOT);
                if (action.arg(key) == null && action.setting(key) == null) {
                    List<String> all = new ArrayList<>();
                    for (Data.Arg x : list) all.add(x.id());
                    for (Data.Setting x : settings) all.add(x.id());
                    String close = Suggest.best(a.name(), all);
                    if (!(a.value() instanceof Ast.Call raw && raw.qualifier() == null
                            && ("raw".equals(raw.name()) || "enum".equals(raw.name())))) {
                        c.error("у «" + Names.shortName(action.id()) + "» нет аргумента «" + a.name() + "»",
                                a.nameSpan(), all.isEmpty() ? "у него нет аргументов"
                                        : close != null ? Suggest.meant(close)
                                        : "есть: " + String.join(", ", all), Hints.replace(a.nameSpan(), close));
                        continue;
                    }
                    c.warning("аргумента «" + a.name() + "» нет в данных — уйдёт как есть", a.nameSpan());
                }
            }
            if (bound.containsKey(key)) {
                c.error("аргумент «" + key + "» указан дважды", a.value().span());
                continue;
            }
            bound.put(key, a.value());
        }
        swapped(action, order, args);
        List<Object> values = new ArrayList<>();
        Ast.Var wasWriting = c.writing;
        double wasUnit = c.unit;
        if (into != null) c.writing = into;
        for (Data.Arg arg : list) {
            Expr e = bound.get(arg.id());
            if (e == null) {
                if (arg.slotted() && Wire.WHOLE_GRID.contains(action.id())) values.add(entry(arg.id(), array(grid(new ArrayList<>(), arg))));
                continue;
            }
            c.unit = unit(arg, bound.get("time_unit") == null ? null : c.resolved(bound.get("time_unit")));
            Object v = c.guard(() -> argument(action, arg, e));
            if (v != null) values.add(entry(arg.id(), v));
        }
        c.unit = wasUnit;
        c.writing = wasWriting;
        for (Data.Setting s : settings) {
            Expr e = bound.get(s.id());
            if (e == null) continue;
            Object v = c.guard(() -> marker(s, e));
            if (v != null) values.add(entry(s.id(), v));
        }
        for (Map.Entry<String, Expr> extra : bound.entrySet()) {
            if (action.arg(extra.getKey()) != null || action.setting(extra.getKey()) != null) continue;
            Object v = c.guard(() -> value(extra.getValue(), null));
            if (v != null) values.add(entry(extra.getKey(), v));
        }
        return values;
    }

    private static boolean same(Expr e, Ast.Var v) {
        return e instanceof Ast.Var x && x.name().equals(v.name()) && Objects.equals(x.scope(), v.scope());
    }

    private static boolean repeatsTarget(Data.Action action, List<Arg> args, Ast.Var into) {
        return action.canonical().equals(Wire.APPEND) && args.size() > 1 && args.get(0).name() == null && same(args.get(0).value(), into);
    }

    private boolean skipsExtra(Data.Action action, List<Data.Arg> order, List<Arg> args, Ast.Var into) {
        if (!Wire.IN_PLACE.contains(action.canonical()) || !Data.VARIABLE.equals(order.get(0).kind())) return false;
        if (args.size() < 2 || args.size() >= order.size() || args.get(0).name() != null || args.get(1).name() != null) return false;
        Expr second = args.get(1).value();
        if (same(args.get(0).value(), into)) return !same(second, into);
        Kinds.Problem misfit = Kinds.check(order.get(1).kind(), Kinds.slot(kindOf(second)), null);
        return misfit != null && misfit.error();
    }

    public static Data.Setting wordFor(Expr value, List<Data.Setting> settings, Set<String> taken,
                                       Predicate<String> variable) {
        if (!(value instanceof Ast.Var v) || v.quoted() || v.scope() != null || variable.test(v.name())) return null;
        String word = v.name().toLowerCase(Locale.ROOT);
        for (Data.Setting s : settings) if (!taken.contains(s.id()) && s.options().containsKey(word)) return s;
        return null;
    }

    private static double unit(Data.Arg arg, Expr timeUnit) {
        String label = arg.label().toLowerCase(Locale.ROOT);
        if (label.contains("миллисекунд")) return 1;
        if (label.contains("секунд")) return 1000;
        if (label.contains("минут")) return 60_000;
        String word = timeUnit instanceof Ast.Var v ? v.name() : timeUnit instanceof Ast.Str s ? s.text() : "";
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "seconds" -> 1000;
            case "minutes" -> 60_000;
            default -> 50;
        };
    }

    private void swapped(Data.Action action, List<Data.Arg> list, List<Arg> args) {
        for (int i = 0; i + 1 < args.size() && i + 1 < list.size(); i++) {
            Arg a = args.get(i), b = args.get(i + 1);
            if (a.name() != null || b.name() != null) break;
            Data.Arg x = list.get(i), y = list.get(i + 1);
            if (a.value() instanceof Ast.ListLit || b.value() instanceof Ast.ListLit) continue;
            String ka = Kinds.slot(kindOf(a.value())), kb = Kinds.slot(kindOf(b.value()));
            if (ka == null || kb == null) continue;
            Kinds.Problem p1 = Kinds.check(x.kind(), ka, null), p2 = Kinds.check(y.kind(), kb, null);
            if (p1 == null || p2 == null || !p1.error() || !p2.error()) continue;
            if (Kinds.check(x.kind(), kb, null) != null || Kinds.check(y.kind(), ka, null) != null) continue;
            c.diagnostics.add(new Diagnostic(true, "похоже, аргументы перепутаны местами: у «"
                    + Names.shortName(action.id()) + "» сначала идёт «" + x.id() + "», потом «" + y.id() + "»",
                    a.value().span().start(), b.value().span().end(),
                    "поменяй местами или подпиши: " + Names.shortName(action.id()) + "(" + x.id() + ": …, " + y.id() + ": …)"));
        }
    }

    String kindOf(Expr e) {
        return kindOf(e, false);
    }

    String likelyKind(Expr e) {
        return kindOf(e, true);
    }

    boolean unsure(Expr e) {
        return e instanceof Ast.Binary b && b.op().equals("+") && (likelyKind(b.left()) == null || likelyKind(b.right()) == null);
    }

    private String returned(Ast.Call call) {
        if (c.userCall(call)) {
            String kind = c.likely.get(Wire.RESULT + call.shown());
            return "?".equals(kind) ? null : kind;
        }
        Names.Entry e = c.names.resolve(Names.Space.ACTION, call.shown());
        return e == null ? null : c.data.returned(e.action());
    }

    private String kindOf(Expr e, boolean likely) {
        Expr inner = e instanceof Ast.Targeted t ? t.inner() : e;
        return switch (inner) {
            case Ast.Num n -> n.millis() == null && (n.text().equals("0") || n.text().equals("1")) ? Kinds.BOOL : Kinds.NUMBER;
            case Ast.Str s -> Kinds.TEXT;
            case Ast.ListLit l -> Kinds.LIST;
            case Ast.MapLit m -> Kinds.MAP;
            case Ast.Path p when p.parts().size() == 2 && p.parts().get(0).equals("value") -> Kinds.ofGameValue(c.data, p.parts().get(1));
            case Ast.Var v -> {
                Expr k = c.constant(v);
                if (k != null) yield kindOf(k, likely);
                if (!v.quoted() && !c.known(v.name()) && Context.truth(v.name()) != null) yield Kinds.BOOL;
                yield likely ? c.likely(v) : c.kind(v);
            }
            case Ast.Call call when call.qualifier() == null && !c.userCall(call) -> switch (call.name()) {
                case "loc" -> Kinds.LOCATION;
                case "vec" -> Kinds.VECTOR;
                case "item" -> Kinds.ITEM;
                case "block" -> Kinds.BLOCK;
                case "cell" -> Kinds.CELL;
                case "sound" -> Kinds.SOUND;
                case "particle" -> Kinds.PARTICLE;
                case "potion" -> Kinds.POTION;
                case "num", "len" -> Kinds.NUMBER;
                case "literal" -> call.args().size() == 1 ? kindOf(call.args().get(0).value(), likely) : null;
                default -> c.formula.mathCall(call) ? Kinds.NUMBER : c.conditions.conditionName(call) ? Kinds.BOOL
                        : likely ? returned(call) : null;
            };
            case Ast.Call call when likely -> returned(call);
            case Ast.Index i when likely -> elementKind(i.target());
            case Ast.Unary u when u.op().equals("not") -> Kinds.BOOL;
            case Ast.Unary u -> Kinds.VECTOR.equals(kindOf(u.operand(), likely)) ? null : Kinds.NUMBER;
            case Ast.Binary b when Formula.ARITHMETIC.contains(b.op()) -> {
                String l = kindOf(b.left(), likely), r = kindOf(b.right(), likely);
                if (likely && Kinds.LOCATION.equals(r) && Kinds.VECTOR.equals(l)) yield Kinds.LOCATION;
                if (likely && Kinds.LOCATION.equals(l) && r == null) yield Kinds.LOCATION;
                if (Kinds.VECTOR.equals(l) || Kinds.VECTOR.equals(r))
                    yield Kinds.LOCATION.equals(l) ? Kinds.LOCATION : Kinds.VECTOR;
                yield Kinds.NUMBER;
            }
            case Ast.Binary b -> Kinds.BOOL;
            case Ast.Ternary t -> {
                String a = kindOf(t.then(), likely), b = kindOf(t.otherwise(), likely);
                yield a != null && a.equals(b) ? a : null;
            }
            default -> null;
        };
    }

    private String elementKind(Expr target) {
        if (!(target instanceof Ast.Var v)) return null;
        Expr fixed = c.constant(v);
        if (fixed == null) return c.element(v);
        String element = elementOf(v.name(), fixed);
        return "?".equals(element) ? null : element;
    }

    String elementOf(String name, Expr value) {
        return switch (value) {
            case Ast.ListLit l -> commonKind(l.items());
            case Ast.MapLit m -> commonKind(m.values());
            case Ast.Var v when c.constant(v) != null -> elementOf(name, c.constant(v));
            case Ast.Var v -> {
                String copied = c.element(v);
                yield copied == null ? "?" : copied;
            }
            case Ast.Call call when c.declarations.emptyCollection(call) -> null;
            case Ast.Call call when !c.userCall(call) -> elementAfter(name, call);
            default -> "?";
        };
    }

    boolean writesInPlace(Ast.Call call) {
        Names.Entry e = c.names.resolve(Names.Space.ACTION, call.shown());
        return e != null && e.action().result() != null;
    }

    private String elementAfter(String name, Ast.Call call) {
        Names.Entry e = c.names.resolve(Names.Space.ACTION, call.shown());
        if (e == null) return "?";
        String id = e.action().id();
        if (Wire.KEEPS_ELEMENTS.contains(id)) return null;
        List<Expr> added = new ArrayList<>();
        for (Arg a : call.args())
            if (!(a.value() instanceof Ast.Var v && v.name().equals(name))) added.add(a.value());
        if (id.equals("set_variable_append_value")) return commonKind(added);
        if (Wire.LAST_IS_ELEMENT.contains(id) && !added.isEmpty()) return commonKind(List.of(added.get(added.size() - 1)));
        return "?";
    }

    private String commonKind(List<Expr> items) {
        String common = null;
        for (Expr item : items) {
            if (item instanceof Ast.Blank) continue;
            String kind = likelyKind(item);
            if (!Kinds.spatial(kind) || common != null && !common.equals(kind)) return "?";
            common = kind;
        }
        return common;
    }

    private record Op(Data.Action action, List<Arg> args) {}

    boolean vectorMath(Ast.Binary b) {
        return vectorOp(b, likelyKind(b.left()), likelyKind(b.right()), null) != null;
    }

    private Op vectorOp(Ast.Binary b, String wanted) {
        Op op = vectorOp(b, likelyKind(b.left()), likelyKind(b.right()), wanted);
        if (op == null) return vectorOp(b, kindOf(b.left()), kindOf(b.right()), null);
        if (op.action().id().equals("set_variable_add_vectors") && !Kinds.VECTOR.equals(wanted)) warnUnsure(b);
        return op;
    }

    private void warnUnsure(Ast.Binary b) {
        Expr side = likelyKind(b.left()) == null ? b.left() : likelyKind(b.right()) == null ? b.right() : null;
        if (!(side instanceof Ast.Var v) || c.constant(v) != null) return;
        c.warning("не понятно, «" + v.name() + "» — место или вектор: собрано как сумма векторов", v.span(),
                c.params.containsKey(v.name()) ? "допиши вид параметра: " + v.name() + ": location" : "объяви вид: save " + v.name() + ": location");
    }

    private Op shifted(Expr location, Expr vector) {
        return op("set_variable_shift_location_on_vector", named("location", location), named("vector", vector));
    }

    private Op vectorOp(Ast.Binary b, String l, String r, String wanted) {
        boolean lv = Kinds.VECTOR.equals(l), rv = Kinds.VECTOR.equals(r);
        boolean ll = Kinds.LOCATION.equals(l), rl = Kinds.LOCATION.equals(r), place = Kinds.LOCATION.equals(wanted);
        boolean ln = l == null || Kinds.NUMBER.equals(Kinds.slot(l)), rn = r == null || Kinds.NUMBER.equals(Kinds.slot(r));
        Ast.ListLit both = new Ast.ListLit(List.of(b.left(), b.right()), b.span());
        if (b.op().equals("+")) {
            if (ll && (rv || r == null) || rv && l == null && place) return shifted(b.left(), b.right());
            if ((lv || l == null) && rl || lv && r == null && place) return shifted(b.right(), b.left());
        }
        if (ll && (rv || r == null) && b.op().equals("-"))
            return shifted(b.left(), new Ast.Binary("*", b.right(), new Ast.Num("-1", b.span()), b.span()));
        if (lv && Kinds.NUMBER.equals(Kinds.slot(r)) && b.op().equals("/"))
            return op("set_variable_multiply_vector", named("vector", b.left()),
                    named("multiplier", new Ast.Binary("/", new Ast.Num("1", b.span()), b.right(), b.span())));
        if ((lv && (rv || r == null) || rv && l == null) && b.op().equals("+")) return op("set_variable_add_vectors", named("vectors", both));
        if ((lv && (rv || r == null) || rv && l == null) && b.op().equals("-")) return op("set_variable_subtract_vectors", named("vectors", both));
        if (lv && rn && b.op().equals("*")) return op("set_variable_multiply_vector", named("vector", b.left()), named("multiplier", b.right()));
        if (rv && ln && b.op().equals("*")) return op("set_variable_multiply_vector", named("vector", b.right()), named("multiplier", b.left()));
        if ((lv || l == null) && rv && b.op().equals("/")) return op("set_variable_divide_vector", named("vector", b.left()), named("divider", b.right()));
        if (l == null && Kinds.VECTOR.equals(wanted) && rn && r != null && b.op().equals("*"))
            return op("set_variable_multiply_vector", named("vector", b.left()), named("multiplier", b.right()));
        return null;
    }

    private Op op(String id, Arg... args) {
        return new Op(c.data.actions().get(id), List.of(args));
    }

    private Object argument(Data.Action action, Data.Arg arg, Expr e) {
        if (!action.plural(arg.id())) return e instanceof Ast.Blank ? null : value(e, arg.kind());
        List<Object> cells = new ArrayList<>();
        List<Expr> items = c.resolved(e) instanceof Ast.ListLit l ? l.items() : List.of(e);
        for (Expr item : items) {
            Object v = item instanceof Ast.Blank ? null : c.guard(() -> value(item, arg.kind()));
            if (v == null) cells.add(arg.slotted() ? obj("type", "item", "item", Items.EMPTY) : obj());
            else cells.add(v);
        }
        if (arg.capacity() > 0 && cells.size() > arg.capacity())
            c.error("в «" + arg.id() + "» помещается " + arg.capacity() + ", а передано " + cells.size(), e.span());
        if (arg.slotted()) {
            List<Object> grid = grid(cells, arg);
            boolean blank = grid.stream().allMatch(Compiler::emptyItem);
            return blank && !Wire.WHOLE_GRID.contains(action.id()) ? null : array(grid);
        }
        while (!cells.isEmpty() && Wire.hole(cells.get(cells.size() - 1))) cells.remove(cells.size() - 1);
        return array(cells);
    }

    private static List<Object> grid(List<Object> cells, Data.Arg arg) {
        while (!cells.isEmpty() && emptyItem(cells.get(cells.size() - 1))) cells.remove(cells.size() - 1);
        while (cells.size() < arg.capacity()) cells.add(obj("type", "item", "item", Items.EMPTY));
        return cells;
    }

    private static boolean emptyItem(Object cell) {
        return cell instanceof Map<?, ?> m && Items.EMPTY.equals(m.get("item"));
    }

    Object boundMarker(String option, Ast.Var v) {
        Map<String, Object> variable = Json.object(variable(v, null));
        Map<String, Object> marker = enumValue(option.toUpperCase(Locale.ROOT));
        marker.put("variable", variable.get("variable"));
        marker.put("scope", variable.get("scope"));
        return marker;
    }

    boolean markerVariable(Set<String> options, Expr e) {
        return e instanceof Ast.Var v && c.constant(v) == null && (v.scope() != null || c.known(v.name()))
                && !(!v.quoted() && options.contains(v.name().toLowerCase(Locale.ROOT)));
    }

    private Object marker(Data.Setting s, Expr e) {
        if (e instanceof Ast.Call call && call.qualifier() == null && call.name().equals("enum")) return value(call, null);
        if (markerVariable(s.options().keySet(), e)) return boundMarker(s.fallback(), (Ast.Var) e);
        String word = switch (e) {
            case Ast.Var v when c.constant(v) instanceof Ast.Str str -> str.text();
            case Ast.Var v when !v.quoted() -> v.name();
            case Ast.Str str -> str.text();
            default -> null;
        };
        if (word == null) throw c.stop("у «" + s.id() + "» вариант пишется словом: " + String.join(", ", s.options().keySet()), e.span());
        String option = word.toLowerCase(Locale.ROOT);
        if (!s.options().containsKey(option)) {
            String best = Suggest.best(word, s.options().keySet());
            throw c.stop("у «" + s.id() + "» нет варианта «" + word + "»", e.span(),
                    best != null ? Suggest.meant(best) : "есть: " + String.join(", ", s.options().keySet()),
                    Hints.replace(e.span(), best == null ? null : Keywords.plainName(best) ? best : Decompiler.quote(best, null)));
        }
        if (s.options().containsKey("generic_" + option) && Wire.ATTRIBUTE_SETTING.equals(s.id()))
            c.warning("«" + option + "» собирается, но сервер остановит строку: Enum " + option.toUpperCase(Locale.ROOT) + " value not found", e.span(),
                    "пиши с приставкой", Hints.replace(e.span(), "generic_" + option));
        return enumValue(option.toUpperCase(Locale.ROOT));
    }

    private void flow(Ast.Var v, String slot) {
        String known = Kinds.slot(c.shown(v));
        if (known == null || known.equals(Kinds.LIST) || slot.equals(Data.VARIABLE)) return;
        Kinds.Problem p = Kinds.check(slot, known, null);
        if (p == null) return;
        c.warning(p.error() ? "«" + v.name() + "» — " + Kinds.lower(known) + ", а здесь нужно «" + Kinds.lower(slot) + "»" : p.message(),
                v.span(), p.hint());
    }

    Object value(Expr raw, String kind) {
        Expr e = c.resolved(raw);
        Object v = plainValue(e, kind);
        if (kind != null && e instanceof Ast.Var var) flow(var, kind);
        if (v instanceof Map<?, ?> m && kind != null && !(e instanceof Ast.Call call && call.name().equals("raw"))) {
            String valueKind = m.get("type") instanceof String t
                    ? (t.equals("game_value") && m.get("game_value") instanceof String id ? Kinds.ofGameValue(c.data, id) : Kinds.ofType(t))
                    : null;
            Kinds.Problem p = Kinds.check(kind, valueKind, e instanceof Ast.Str ? String.valueOf(m.get("text")) : null);
            if (p != null) {
                if (p.error()) c.error(p.message(), e.span(), p.hint());
                else c.warning(p.message(), e.span(), p.hint());
            }
        }
        return v;
    }

    Object plainValue(Expr e, String kind) {
        return switch (e) {
            case Ast.Num n -> {
                double d = c.number(n);
                if (n.millis() != null && c.unit <= 50 && d != Math.rint(d))
                    c.error("это не целое число " + (c.unit == 1 ? "миллисекунд" : "тиков") + ": " + d, n.span());
                yield obj("type", "number", "number", num(c.finite(d, n.span())));
            }
            case Ast.Unary u when u.op().equals("-") && u.operand() instanceof Ast.Num n ->
                    obj("type", "number", "number", num(c.finite(-c.number(n), u.span())));
            case Ast.Str s when s.interpolated() -> {
                String built = c.formula.interpolate(s);
                c.formula.texts(built, s.span(), 0);
                yield text(built, Context.parsing(s.prefix()));
            }
            case Ast.Str s -> {
                c.formula.texts(s.text(), s.span(), 1 + (s.prefix() == null ? 0 : s.prefix().length()));
                yield text(s.text(), Context.parsing(s.prefix()));
            }
            case Ast.Binary b when Formula.ARITHMETIC.contains(b.op()) -> {
                Op op = vectorOp(b, kind);
                if (op == null) yield c.formula.math(b);
                Ast.Var tmp = c.temp(b.span());
                c.need(b.span()).add(obj("action", op.action().id(), "values", bind(op.action(), op.args(), tmp, b.span())));
                yield variable(tmp, null);
            }
            case Ast.Unary u when u.op().equals("-") && Kinds.VECTOR.equals(likelyKind(u.operand())) ->
                    plainValue(new Ast.Binary("*", u.operand(), new Ast.Num("-1", u.span()), u.span()), kind);
            case Ast.Unary u when u.op().equals("-") && !(u.operand() instanceof Ast.Num) -> c.formula.math(u);
            case Ast.Call call when c.formula.mathCall(call) -> c.formula.math(call);
            case Ast.Var v -> variable(v, kind);
            case Ast.Blank b -> null;
            case Ast.ListLit l -> list(l);
            case Ast.MapLit m -> map(m);
            case Ast.Call call -> c.calls.value(call);
            case Ast.Path p -> path(p, null, null);
            case Ast.Targeted t -> {
                if (t.inner() instanceof Ast.Path p) yield path(p, t.target(), t.targetSpan());
                if (t.inner() instanceof Ast.Call call && c.conditions.conditionName(call)) yield c.conditions.conditionValue(t);
                if (t.inner() instanceof Ast.Call call && (call.qualifier() != null || !(Wire.VALUES.contains(call.name())
                        || c.userCall(call) || c.formula.mathCall(call))))
                    yield c.calls.action(call, t.target(), t.targetSpan());
                throw c.stop("цель @" + t.target() + " у значения бывает у игрового значения, условия и действия: @"
                        + t.target() + " value.current_health", t.span());
            }
            case Ast.Index i -> indexValue(i);
            case Ast.Ternary t -> c.conditions.ternaryValue(t);
            case Ast.Unary u -> c.conditions.conditionValue(u);
            case Ast.Binary b -> c.conditions.conditionValue(b);
        };
    }

    Object variable(Ast.Var v, String kind) {
        String scope = c.scope(v);
        if (scope == null) {
            if (Data.VARIABLE.equals(kind)) {
                c.variables.put(v.name(), "local");
                scope = "local";
            } else {
                Set<String> known = new LinkedHashSet<>(c.variables.keySet());
                known.addAll(c.params.keySet());
                String best = Suggest.best(v.name(), known);
                throw c.stop("переменная «" + v.name() + "» нигде не задана", v.span(),
                        best != null ? Suggest.meant(best) : "задай её: " + v.name() + " = …  или объяви: save " + v.name(),
                        Hints.replace(v.span(), best == null ? null : Keywords.name(best)));
            }
        }
        if (scope.equals("local")) c.locals.use(v.name(), v.span(), Data.VARIABLE.equals(kind));
        if (v != c.writing) c.lints.read(v.name());
        else c.lints.wrote(v.name());
        c.lints.name(v.name());
        c.lints.parameterRead(v.name(), v.span(), true);
        return obj("type", "variable", "variable", v.name(), "scope", scope);
    }

    private List<Object> cells(Ast.ListLit l) {
        List<Object> cells = new ArrayList<>();
        for (Expr item : l.items()) {
            Object v = item instanceof Ast.Blank ? null : c.guard(() -> value(item, null));
            cells.add(v == null ? obj() : v);
        }
        while (!cells.isEmpty() && cells.get(cells.size() - 1) instanceof Map<?, ?> m && m.isEmpty())
            cells.remove(cells.size() - 1);
        return cells;
    }

    private boolean literal() {
        return c.storing || c.prelude == null;
    }

    Object plainList(Ast.ListLit l) {
        return array(cells(l));
    }

    Object stored(Expr e) {
        boolean was = c.storing;
        c.storing = true;
        try {
            return value(e, null);
        } finally {
            c.storing = was;
        }
    }

    private Object list(Ast.ListLit l) {
        List<Object> cells = cells(l);
        if (literal() || cells.stream().noneMatch(Wire::dynamic)) return array(cells);
        Ast.Var tmp = c.temp(l.span());
        c.need(l.span()).addAll(builtList(variable(tmp, null), cells));
        return variable(tmp, null);
    }

    private List<Object> builtList(Object into, List<Object> cells) {
        Data.Action create = c.data.actions().get("set_variable_create_list");
        Data.Action append = c.data.actions().get("set_variable_append_value");
        int room = create.arg("values").capacity();
        if (room <= 0) room = Math.max(cells.size(), 1);
        List<Object> ops = new ArrayList<>();
        for (int from = 0; from == 0 || from < cells.size(); from += room) {
            List<Object> part = new ArrayList<>(cells.subList(from, Math.min(cells.size(), from + room)));
            ops.add(obj("action", (from == 0 ? create : append).id(), "values",
                    new ArrayList<>(List.of(entry("variable", into), entry("values", array(part))))));
        }
        return ops;
    }

    private record Pairs(List<Object> keys, List<Object> values) {
        boolean dynamic() {
            return keys.stream().anyMatch(Wire::dynamic) || values.stream().anyMatch(Wire::dynamic);
        }
    }

    private Pairs pairs(Ast.MapLit m) {
        Pairs pairs = new Pairs(new ArrayList<>(), new ArrayList<>());
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < m.keys().size(); i++) {
            Expr k = m.keys().get(i);
            Object key = c.guard(() -> value(k, null));
            if (key == null) {
                c.error("у пары словаря нужен ключ", k.span());
                continue;
            }
            Expr ve = m.values().get(i);
            Object v = ve instanceof Ast.Blank ? null : c.guard(() -> value(ve, null));
            if (!seen.add(Json.write(key))) c.error("ключ повторяется", k.span());
            pairs.keys().add(key);
            pairs.values().add(v == null ? obj() : v);
        }
        return pairs;
    }

    private static Object plainMap(Pairs pairs) {
        Map<String, Object> entries = new LinkedHashMap<>();
        for (int i = 0; i < pairs.keys().size(); i++) entries.put(Json.write(pairs.keys().get(i)), pairs.values().get(i));
        return obj("type", "map", "values", entries);
    }

    private static Object builtMap(Object into, Pairs pairs) {
        List<Object> values = new ArrayList<>(pairs.values());
        while (!values.isEmpty() && Wire.hole(values.get(values.size() - 1))) values.remove(values.size() - 1);
        return obj("action", "set_variable_create_map_from_values", "values", new ArrayList<>(List.of(entry("variable", into),
                entry("keys", array(pairs.keys())), entry("values", array(values)))));
    }

    private Object map(Ast.MapLit m) {
        Pairs pairs = pairs(m);
        if (literal() || !pairs.dynamic()) return plainMap(pairs);
        Ast.Var tmp = c.temp(m.span());
        c.need(m.span()).add(builtMap(variable(tmp, null), pairs));
        return variable(tmp, null);
    }

    private Object receiver(Ast.Var target) {
        Ast.Var wasWriting = c.writing;
        c.writing = target;
        Object into = variable(target, Data.VARIABLE);
        c.writing = wasWriting;
        return into;
    }

    private static Map<String, Object> setValue(Object into, Object value) {
        return obj("action", "set_variable_value", "values", new ArrayList<>(List.of(entry("variable", into), entry("value", value))));
    }

    void snapshot(Ast.Var v) {
        if (c.snapshots.containsKey(v)) return;
        Object value = variable(v, null);
        Ast.Var tmp = c.temp(v.span());
        String kind = c.kind(v);
        if (kind != null) c.kinds.put(tmp.name(), kind);
        c.prelude.add(setValue(variable(tmp, null), value));
        c.snapshots.put(v, tmp);
    }

    Object copied(Ast.Var v) {
        Object value = variable(v, null);
        Ast.Var tmp = c.temp(v.span());
        c.need(v.span()).add(setValue(variable(tmp, null), value));
        return variable(tmp, null);
    }

    Object settled(Object v, Span at) {
        if (!Wire.lazy(v)) return v;
        Ast.Var tmp = c.temp(at);
        c.need(at).add(setValue(variable(tmp, null), v));
        return variable(tmp, null);
    }

    Object held(Expr e) {
        Object v = plainValue(e, null);
        if (v instanceof Map<?, ?> m && "variable".equals(m.get("type"))) return v;
        Ast.Var tmp = c.temp(e.span());
        c.need(e.span()).add(setValue(variable(tmp, null), v));
        return variable(tmp, null);
    }

    private Object path(Ast.Path p, String target, Span targetSpan) {
        List<String> parts = p.parts();
        if (!parts.get(0).equals("value") || parts.size() != 2)
            throw c.stop("непонятное значение «" + p.joined() + "»", p.span(),
                    "игровое значение пишется value.имя, например value.current_health");
        String id = c.gameValue(parts.get(1), p.span());
        if (target == null && Wire.NO_TARGET.contains(c.data.gameValues().get(id).group()))
            return obj("type", "game_value", "game_value", id, "selection", "null");
        String selector = "default";
        if (target != null) {
            Map<String, String> known = c.names.selectors("value");
            selector = known.get(target);
            if (selector == null) {
                String best = Suggest.best(target, known.keySet());
                c.error("нет цели @" + target + " у игрового значения", targetSpan, Suggest.meant(best),
                        Hints.replace(targetSpan, best));
                selector = "default";
            }
        }
        return obj("type", "game_value", "game_value", id, "selection", "{\"type\":\"" + selector + "\"}");
    }

    void assign(Ast.Assign a, List<Object> out) {
        Ast.Var target = new Ast.Var(a.name(), false, a.nameSpan(), a.pinned() ? a.scope() : null);
        if (!a.pinned() && c.constant(target) != null) return;
        Expr whole = a.op().equals("=") ? a.value() : a.op().equals("%=") ? null
                : new Ast.Binary(a.op().substring(0, 1), target, a.value(), a.span());
        Op vector = whole instanceof Ast.Binary w && Formula.ARITHMETIC.contains(w.op()) ? vectorOp(w, c.slot(a.name())) : null;
        if (vector != null) {
            out.add(obj("action", vector.action().id(), "values", bind(vector.action(), vector.args(), target, a.span())));
            return;
        }
        if (a.op().equals("%=")) {
            Data.Action action = c.data.actions().get("set_variable_remainder");
            out.add(obj("action", action.id(), "values",
                    bind(action, List.of(named("dividend", target), named("divisor", a.value())), target, a.span())));
            return;
        }
        if (a.op().equals("=") && a.value() instanceof Ast.Binary b && Wire.COMPOUND.containsKey(b.op() + "=")
                && b.left() instanceof Ast.Var v && v.name().equals(a.name()) && Objects.equals(v.scope(), target.scope())
                && !c.snapshots.containsKey(v) && !c.conditions.isCondition(b.right())) {
            assign(new Ast.Assign(a.scope(), a.name(), a.nameSpan(), b.op() + "=", b.right(), a.span(), a.pinned()), out);
            return;
        }
        if (!a.op().equals("=")) {
            Data.Action action = c.data.actions().get(Wire.COMPOUND.get(a.op()));
            Ast.ListLit both = new Ast.ListLit(List.of(target, a.value()), a.value().span());
            out.add(obj("action", action.id(), "values",
                    bind(action, List.of(new Arg(null, null, both)), target, a.span())));
            return;
        }
        Expr value = a.value() instanceof Ast.Index i && c.item(i) != null ? c.resolved(i) : a.value();
        if (c.conditions.isCondition(value)) {
            Ast.Var wasWriting = c.writing;
            c.writing = target;
            Map<String, Object> into = Json.object(variable(target, Data.VARIABLE));
            c.writing = wasWriting;
            out.add(Conditions.setVar(into, 0));
            out.addAll(c.conditions.compute(c.conditions.tree(value, false), into));
            return;
        }
        if (value instanceof Ast.Ternary t) {
            c.conditions.ternary(t, target, out);
            return;
        }
        if (value instanceof Ast.Index i) {
            out.add(indexRead(i, target));
            return;
        }
        if (value instanceof Ast.ListLit l) {
            List<Object> cells = cells(l);
            Object into = receiver(target);
            if (!c.storing && cells.stream().anyMatch(Wire::dynamic)) out.addAll(builtList(into, cells));
            else out.add(setValue(into, array(cells)));
            return;
        }
        if (value instanceof Ast.MapLit m) {
            Pairs pairs = pairs(m);
            Object into = receiver(target);
            out.add(!c.storing && pairs.dynamic() ? builtMap(into, pairs) : setValue(into, plainMap(pairs)));
            return;
        }
        if (value instanceof Ast.Call call && c.values.built(call)) {
            out.add(c.values.build(call, target));
            return;
        }
        Expr inner = value instanceof Ast.Targeted t ? t.inner() : value;
        if (inner instanceof Ast.Call call && !c.userCall(call) && !(call.qualifier() == null
                && (Wire.VALUES.contains(call.name()) || c.formula.mathCall(call)))) {
            Data.Action action = action(call);
            Ast.Call folded = value instanceof Ast.Targeted ? null : c.values.foldedItem(action, call);
            if (folded != null) {
                Data.Action set = c.data.actions().get("set_variable_value");
                out.add(obj("action", set.id(), "values", bind(set, List.of(new Arg(null, null, folded)), target, a.span())));
                return;
            }
            Map<String, Object> op = obj("action", action.id(), "values", bind(action, call.args(), target, call.span()));
            if (value instanceof Ast.Targeted t) op.put("selection", selection(action, t.target(), t.targetSpan()));
            if (action.container()) op.put("operations", new ArrayList<>());
            out.add(op);
            return;
        }
        Data.Action set = c.data.actions().get("set_variable_value");
        out.add(obj("action", set.id(), "values", bind(set, List.of(new Arg(null, null, value)), target, a.span())));
    }

    private String shapeOf(Ast.Index i) {
        if (i.target() instanceof Ast.Var v) {
            String s = c.shape(v.name());
            if ("list".equals(s) || "map".equals(s)) return s;
            if (c.constant(v) != null) throw c.stop("«" + v.name() + "» — постоянная, в ней нет значений по индексу", i.span(),
                    "индекс берут у const со списком или словарём: const " + v.name() + " = [1, 2]");
        }
        Expr idx = i.index();
        if (idx instanceof Ast.Num || (idx instanceof Ast.Unary u && u.op().equals("-"))
                || (idx instanceof Ast.Binary b && Formula.ARITHMETIC.contains(b.op()))) return "list";
        if (idx instanceof Ast.Str) return "map";
        String key = Kinds.slot(kindOf(idx));
        if (Kinds.TEXT.equals(key)) return "map";
        if (Kinds.NUMBER.equals(key)) return "list";
        String name = i.target() instanceof Ast.Var v ? v.name() : "это значение";
        throw c.stop("не понятно, «" + name + "» — список или словарь", i.span(), declareShape(i.target(), name));
    }

    private String declareShape(Expr target, String name) {
        if (target instanceof Ast.Var v && c.params.containsKey(v.name()))
            return "скажи это в параметре: " + name + ": array или " + name + ": map";
        String scope = target instanceof Ast.Var v ? c.scope(v) : null;
        if ("game".equals(scope) || "save".equals(scope))
            return "скажи это при объявлении: " + scope + " " + name + ": map или " + scope + " " + name + ": list";
        return "скажи это строкой в теле: line " + name + ": map или line " + name + ": list";
    }

    static boolean picked(Ast.Index i) {
        return i.index() instanceof Ast.ListLit l && !l.items().isEmpty() && l.span().start() == l.items().get(0).span().start();
    }

    private void unpack(Ast.MultiAssign m, List<Object> ops) {
        List<Expr> vars = new ArrayList<>();
        for (int n = 0; n < m.names().size(); n++) vars.add(new Ast.Var(m.names().get(n), false, m.nameSpans().get(n)));
        Ast.ListLit into = new Ast.ListLit(vars, m.span());
        Expr value = m.values().get(0);
        if (value instanceof Ast.Index i && picked(i)) {
            List<Expr> keys = ((Ast.ListLit) i.index()).items();
            if (keys.size() != vars.size()) {
                c.error("слева " + vars.size() + " переменных, а индексов " + keys.size(), m.span());
                return;
            }
            boolean list = shapeOf(new Ast.Index(i.target(), keys.get(0), i.span())).equals("list");
            Data.Action action = c.data.actions().get(list ? "set_variable_get_list_value_multiple" : "set_variable_get_map_value");
            List<Arg> args = list
                    ? List.of(named("variable", into), named("list", i.target()), named("number", i.index()))
                    : List.of(named("variable", into), named("map", i.target()), named("key", i.index()));
            ops.add(obj("action", action.id(), "values", bind(action, args, null, m.span())));
            return;
        }
        String kind = Kinds.slot(kindOf(value));
        if (kind != null && !Kinds.LIST.equals(kind)) {
            c.error("слева " + vars.size() + " переменных, а справа 1 значение, и это не список: разложить можно только список", value.span(),
                    "или по значению на переменную: a, b = 1, 2");
            return;
        }
        Data.Action action = c.data.actions().get("set_variable_destructure_list");
        ops.add(obj("action", action.id(), "values", bind(action, List.of(named("variables", into), named("list", value)), null, m.span())));
    }

    private Map<String, Object> indexRead(Ast.Index i, Ast.Var into) {
        if (picked(i)) throw c.stop("несколько индексов бывают только при разложении: x, y = l[0, 1]", i.span());
        boolean list = shapeOf(i).equals("list");
        if (list && i.index() instanceof Ast.Unary u && u.op().equals("-") && u.operand() instanceof Ast.Num)
            c.warning("индекс списка считается с нуля — как сервер поймёт отрицательный, не проверено", i.index().span());
        Data.Action action = c.data.actions().get(list ? "set_variable_get_list_value" : "set_variable_get_map_value_single");
        List<Arg> args = list ? List.of(named("list", i.target()), named("number", i.index()))
                : List.of(named("map", i.target()), named("key", i.index()));
        return obj("action", action.id(), "values", bind(action, args, into, i.span()));
    }

    private Ast.Index onceIndexed(Ast.Index i, List<Object> out) {
        Expr from = i.target() instanceof Ast.Index inner ? onceIndexed(inner, out) : i.target();
        Expr index = i.index();
        if (!c.calls.pure(index)) {
            Ast.Var tmp = c.temp(index.span());
            String kind = kindOf(index);
            if (kind != null) c.kinds.put(tmp.name(), kind);
            assign(new Ast.Assign(null, tmp.name(), index.span(), "=", index, index.span()), out);
            index = tmp;
        }
        return from == i.target() && index == i.index() ? i : new Ast.Index(from, index, i.span());
    }

    private void write(Ast.Index target, Expr value, List<Object> out) {
        if (target.target() instanceof Ast.Var v) {
            if (c.constant(v) != null) throw c.stop("«" + v.name() + "» — постоянная, её не меняют", v.span());
            boolean list = shapeOf(target).equals("list");
            Data.Action action = c.data.actions().get(list ? "set_variable_set_list_value" : "set_variable_set_map_value");
            Expr cell = action.plural("value") && value instanceof Ast.ListLit
                    ? new Ast.ListLit(List.of(value), value.span()) : value;
            List<Arg> args = list
                    ? List.of(named("list", v), named("number", target.index()), named("value", value))
                    : List.of(named("map", v), named("key", target.index()), named("value", cell));
            out.add(obj("action", action.id(), "values", bind(action, args, v, target.span())));
            return;
        }
        if (target.target() instanceof Ast.Index inner) {
            Ast.Var tmp = c.temp(inner.span());
            out.add(indexRead(inner, tmp));
            write(new Ast.Index(tmp, target.index(), target.span()), value, out);
            write(inner, tmp, out);
            return;
        }
        throw c.stop("записывать по индексу можно в переменную: l[0] = …", target.span());
    }

    Object indexValue(Ast.Index i) {
        Ast.Var tmp = c.temp(i.span());
        c.need(i.span()).add(indexRead(i, tmp));
        return variable(tmp, null);
    }

    String indexText(Ast.Index i) {
        if (!(i.target() instanceof Ast.Var v) || v.name().matches(".*[,()%].*")) return null;
        String scope = c.scope(v);
        if (scope == null) return null;
        if (scope.equals("local")) c.locals.use(v.name(), v.span(), false);
        c.lints.read(v.name());
        boolean list = shapeOf(i).equals("list");
        String key = switch (i.index()) {
            case Ast.Num n when list && n.text().matches("\\d+") -> n.text();
            case Ast.Str s when !list && !s.interpolated() && s.text().matches("[^,()%\\\\]+") -> s.text();
            default -> null;
        };
        if (key == null) return null;
        String suffix = switch (scope) {
            case "save" -> "_save";
            case "local" -> "_local";
            case "line" -> "_line";
            default -> "";
        };
        return "%" + (list ? "index" : "entry") + suffix + "(" + v.name() + "," + key + ")";
    }

    private void jump(Ast.Jump j, List<Object> out) {
        switch (j.kind()) {
            case "return" -> {
                if (j.value() != null) {
                    if (c.current == null || c.current.process())
                        c.error("значение возвращает только функция — из " + (c.current == null ? "события" : "процесса")
                                + " его некому вернуть", j.span());
                    else assign(new Ast.Assign(null, Wire.RESULT + c.current.name(), j.span(), "=", j.value(), j.span()), out);
                } else if (c.current != null && !c.current.process() && c.returning.contains(c.current.name())) {
                    c.error("«" + c.current.name() + "» возвращает значение — напиши return …", j.span(),
                            "если вернуть нечего, верни 0");
                }
                boolean function = c.current != null && !c.current.process();
                out.add(obj("action", function ? "control_return_function" : "control_end_thread", "values", new ArrayList<>()));
            }
            case "break" -> {
                if (c.loops == 0) c.error("break бывает только внутри повторения", j.span());
                out.add(obj("action", "control_stop_repeat", "values", new ArrayList<>()));
            }
            default -> {
                if (c.loops == 0) c.error("continue бывает только внутри повторения", j.span());
                out.add(obj("action", "control_skip_iteration", "values", new ArrayList<>()));
            }
        }
    }

}
