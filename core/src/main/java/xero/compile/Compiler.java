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

    public record Result(Map<String, Object> module, List<Diagnostic> diagnostics, List<Declared> declared, List<Object> connected) {
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
        List<Object> connected = new ArrayList<>();
        for (Context.Imported m : c.modules) if (m.entry() != null) connected.add(m.entry());
        return new Result(module, diagnostics, declared, connected);
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
        c.declarations.collect(units);
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
                    if (h != null) handlers.add(h);
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
            if (!d.name().equals(Declarations.LINE)) c.error("у события бывает только пометка @line", d.span());
        c.current = null;
        c.params = Map.of();
        c.locals.enter(e);
        Data.Event known = c.data.events().get(e.name());
        c.happening = known;
        c.world = known != null && WORLD_EVENTS.equals(known.group());
        c.selected = false;
        c.flags = 0;
        List<Object> ops = block(e.body());
        c.world = false;
        c.happening = null;
        c.locals.leave();
        if (!c.data.events().containsKey(e.name())) {
            String best = Suggest.best(e.name(), c.data.events().keySet());
            Hints.Found found = best != null ? new Hints.Found(Suggest.meant(best), List.of(best))
                    : c.hints.russianEvents(e.name());
            c.error("нет события «" + e.name() + "»", e.nameSpan(), found.hint(), Hints.replace(e.nameSpan(), found.names()));
            return null;
        }
        return obj("type", "event", "event", e.name(), "position", position, "operations", ops);
    }

    private Map<String, Object> function(Ast.Func f, int position) {
        c.current = f;
        c.locals.enter(f);
        c.lints.enter();
        c.flags = 0;
        Map<String, String> own = new LinkedHashMap<>();
        Ast.Func declared = c.functions.getOrDefault(f.name(), f);
        for (Ast.Param p : declared.params()) own.put(p.name(), "line");
        c.params = own;
        c.paramKinds = Declarations.paramKinds(declared);
        List<Object> values = c.declarations.values(f);
        List<Object> ops = block(f.body());
        c.lints.leave(f);
        if (c.returning.contains(f.name()) && !Declarations.alwaysReturns(f.body()))
            c.warning("«" + f.name() + "» может дойти до конца без return — тогда вызов получит прошлое значение",
                    f.nameSpan(), "допиши return … в конце");
        c.current = null;
        c.params = Map.of();
        c.paramKinds = Map.of();
        c.locals.leave();
        return obj("type", f.process() ? "process" : "function", "name", f.name(), "values", values,
                "position", position, "operations", ops);
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
        c.prelude = new ArrayList<>();
        c.callCounts = c.calls.count(s);
        List<Object> out = new ArrayList<>();
        try {
            statementInto(s, out);
        } finally {
            ops.addAll(c.prelude);
            ops.addAll(out);
            c.prelude = saved;
            c.callCounts = savedCounts;
        }
    }

    private void statementInto(Ast.Stmt s, List<Object> ops) {
        switch (s) {
            case Ast.Run r -> ops.add(run(r));
            case Ast.Assign a -> assign(a, ops);
            case Ast.IndexAssign a -> {
                Expr value = a.op().equals("=") ? a.value()
                        : new Ast.Binary(a.op().substring(0, 1), a.target(), a.value(), a.span());
                write(a.target(), value, ops);
            }
            case Ast.Declare d -> { }
            case Ast.MultiAssign m -> {
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
            case Ast.Forever f -> ops.add(loop("repeat_forever", List.of(), f.body()));
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
        if (call.qualifier() == null && (c.functions.containsKey(call.name()) || call.quoted())) {
            if (target != null) c.error("у вызова функции нет цели", targetSpan);
            if (r.body() != null) c.error("у вызова функции нет тела", r.body().span());
            return c.calls.invoke(call);
        }
        Data.Action action = action(call);
        Map<String, Object> op = obj("action", action.id());
        if (Wire.CONDITIONAL.contains(action.canonical())) {
            if (call.args().isEmpty() || call.args().get(0).name() != null)
                throw c.stop("«" + call.shown() + "» нужно условие: " + call.shown() + "(x == 1)", call.span());
            Expr condition = call.args().get(0).value();
            boolean loop = action.canonical().equals("repeat_while") && target == null;
            for (int i = 1; i < call.args().size(); i++) c.error("лишний аргумент", call.args().get(i).value().span());
            Ast.Block body = r.body() == null ? new Ast.Block(List.of(), call.span()) : r.body();
            if (!(c.conditions.tree(condition, false) instanceof Conditions.Leaf)) {
                if (loop) return c.conditions.whileLoop(condition, body);
                throw c.stop("в «" + call.shown() + "» and/or нельзя — сервер проверяет условие у каждой цели отдельно",
                        condition.span());
            }
            Context.Captured<Conditions.Cond> captured = c.capture(() -> c.conditions.condition(condition));
            if (!captured.pre().isEmpty()) {
                if (loop) return c.conditions.whileLoop(condition, body);
                throw c.stop("в «" + call.shown() + "» вызовы, индексы и формулы-условия нельзя — сервер проверяет условие сам",
                        condition.span(), "посчитай значение заранее в переменную");
            }
            Conditions.Cond cond = captured.value();
            op.put("values", cond.values());
            op.put("conditional", obj("action", cond.id(), "is_inverted", cond.inverted()));
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

    List<Object> bind(Data.Action action, List<Arg> args, Ast.Var into, Span at) {
        Map<String, Expr> bound = new LinkedHashMap<>();
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
        }
        for (Arg a : args) {
            String key;
            if (a.name() == null) {
                if (positional < order.size()) key = order.get(positional).id();
                else if (positional - order.size() < settings.size()) key = settings.get(positional - order.size()).id();
                else {
                    c.error("лишний аргумент: у «" + Names.shortName(action.id()) + "» их " + order.size()
                            + (settings.isEmpty() ? "" : " и " + settings.size() + " настроек"), a.value().span());
                    continue;
                }
                positional++;
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
            if (e == null) continue;
            c.unit = unit(arg, bound);
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

    private static double unit(Data.Arg arg, Map<String, Expr> bound) {
        String label = arg.label().toLowerCase(Locale.ROOT);
        if (label.contains("миллисекунд")) return 1;
        if (label.contains("секунд")) return 1000;
        if (label.contains("минут")) return 60_000;
        if (bound.get("time_unit") instanceof Ast.Var v) {
            if (v.name().equals("seconds")) return 1000;
            if (v.name().equals("minutes")) return 60_000;
        }
        return 50;
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
        Expr inner = e instanceof Ast.Targeted t ? t.inner() : e;
        return switch (inner) {
            case Ast.Num n -> n.millis() == null && (n.text().equals("0") || n.text().equals("1")) ? Kinds.BOOL : Kinds.NUMBER;
            case Ast.Str s -> Kinds.TEXT;
            case Ast.ListLit l -> Kinds.LIST;
            case Ast.MapLit m -> Kinds.MAP;
            case Ast.Path p when p.parts().size() == 2 && p.parts().get(0).equals("value") -> Kinds.ofGameValue(c.data, p.parts().get(1));
            case Ast.Var v -> {
                Expr k = c.constant(v);
                if (k != null) yield kindOf(k);
                if (!v.quoted() && !c.known(v.name()) && Context.truth(v.name()) != null) yield Kinds.BOOL;
                yield c.kind(v);
            }
            case Ast.Call call when call.qualifier() == null && !call.quoted() && !c.functions.containsKey(call.name()) -> switch (call.name()) {
                case "loc" -> Kinds.LOCATION;
                case "vec" -> Kinds.VECTOR;
                case "item" -> Kinds.ITEM;
                case "block" -> Kinds.BLOCK;
                case "sound" -> Kinds.SOUND;
                case "particle" -> Kinds.PARTICLE;
                case "potion" -> Kinds.POTION;
                case "num", "len" -> Kinds.NUMBER;
                default -> c.formula.mathCall(call) ? Kinds.NUMBER : c.conditions.conditionName(call) ? Kinds.BOOL : null;
            };
            case Ast.Unary u when u.op().equals("not") -> Kinds.BOOL;
            case Ast.Unary u -> Kinds.VECTOR.equals(kindOf(u.operand())) ? null : Kinds.NUMBER;
            case Ast.Binary b when Formula.ARITHMETIC.contains(b.op()) -> {
                String l = kindOf(b.left()), r = kindOf(b.right());
                if (Kinds.VECTOR.equals(l) || Kinds.VECTOR.equals(r))
                    yield Kinds.LOCATION.equals(l) ? Kinds.LOCATION : Kinds.VECTOR;
                yield Kinds.NUMBER;
            }
            case Ast.Binary b -> Kinds.BOOL;
            case Ast.Ternary t -> {
                String a = kindOf(t.then()), b = kindOf(t.otherwise());
                yield a != null && a.equals(b) ? a : null;
            }
            default -> null;
        };
    }

    private record Op(Data.Action action, List<Arg> args) {}

    private Op vectorOp(Ast.Binary b) {
        String l = kindOf(b.left()), r = kindOf(b.right());
        boolean lv = Kinds.VECTOR.equals(l), rv = Kinds.VECTOR.equals(r);
        boolean ln = l == null || Kinds.NUMBER.equals(Kinds.slot(l)), rn = r == null || Kinds.NUMBER.equals(Kinds.slot(r));
        Ast.ListLit both = new Ast.ListLit(List.of(b.left(), b.right()), b.span());
        if (Kinds.LOCATION.equals(l) && rv && b.op().equals("+"))
            return op("set_variable_shift_location_on_vector", named("location", b.left()), named("vector", b.right()));
        if ((lv && (rv || r == null) || rv && l == null) && b.op().equals("+")) return op("set_variable_add_vectors", named("vectors", both));
        if ((lv && (rv || r == null) || rv && l == null) && b.op().equals("-")) return op("set_variable_subtract_vectors", named("vectors", both));
        if (lv && rn && b.op().equals("*")) return op("set_variable_multiply_vector", named("vector", b.left()), named("multiplier", b.right()));
        if (rv && ln && b.op().equals("*")) return op("set_variable_multiply_vector", named("vector", b.right()), named("multiplier", b.left()));
        if (lv && rv && b.op().equals("/")) return op("set_variable_divide_vector", named("vector", b.left()), named("divider", b.right()));
        return null;
    }

    private Op op(String id, Arg... args) {
        return new Op(c.data.actions().get(id), List.of(args));
    }

    private Object argument(Data.Action action, Data.Arg arg, Expr e) {
        if (!action.plural(arg.id())) return e instanceof Ast.Blank ? null : value(e, arg.kind());
        List<Object> cells = new ArrayList<>();
        List<Expr> items = e instanceof Ast.ListLit l ? l.items() : List.of(e);
        for (Expr item : items) {
            Object v = item instanceof Ast.Blank ? null : c.guard(() -> value(item, arg.kind()));
            if (v == null) cells.add(arg.slotted() ? obj("type", "item", "item", Items.EMPTY) : obj());
            else cells.add(v);
        }
        if (arg.capacity() > 0 && cells.size() > arg.capacity())
            c.error("в «" + arg.id() + "» помещается " + arg.capacity() + ", а передано " + cells.size(), e.span());
        return array(cells);
    }

    private Object marker(Data.Setting s, Expr e) {
        if (e instanceof Ast.Call call && call.qualifier() == null && call.name().equals("enum")) return value(call, null);
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
        return enumValue(option.toUpperCase(Locale.ROOT));
    }

    private void flow(Ast.Var v, String slot) {
        String known = Kinds.slot(c.kind(v));
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
                Op op = vectorOp(b);
                if (op == null) yield c.formula.math(b);
                Ast.Var tmp = c.temp(b.span());
                c.need(b.span()).add(obj("action", op.action().id(), "values", bind(op.action(), op.args(), tmp, b.span())));
                yield variable(tmp, null);
            }
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
                        || c.functions.containsKey(call.name()) || call.quoted() || c.formula.mathCall(call))))
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
        return obj("type", "variable", "variable", v.name(), "scope", scope);
    }

    private Object list(Ast.ListLit l) {
        List<Object> cells = new ArrayList<>();
        for (Expr item : l.items()) {
            Object v = item instanceof Ast.Blank ? null : c.guard(() -> value(item, null));
            cells.add(v == null ? obj() : v);
        }
        while (!cells.isEmpty() && cells.get(cells.size() - 1) instanceof Map<?, ?> m && m.isEmpty())
            cells.remove(cells.size() - 1);
        return obj("type", "array", "values", cells);
    }

    private Object map(Ast.MapLit m) {
        Map<String, Object> entries = new LinkedHashMap<>();
        for (int i = 0; i < m.keys().size(); i++) {
            Expr k = m.keys().get(i);
            Object key = c.guard(() -> value(k, null));
            if (key == null) {
                c.error("у пары словаря нужен ключ", k.span());
                continue;
            }
            Expr ve = m.values().get(i);
            Object v = ve instanceof Ast.Blank ? null : c.guard(() -> value(ve, null));
            String field = Json.write(key);
            if (entries.containsKey(field)) c.error("ключ повторяется", k.span());
            entries.put(field, v == null ? obj() : v);
        }
        return obj("type", "map", "values", entries);
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
        Op vector = whole instanceof Ast.Binary w && Formula.ARITHMETIC.contains(w.op()) ? vectorOp(w) : null;
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
                && !c.conditions.isCondition(b.right())) {
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
        Expr value = a.value();
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
        Expr inner = value instanceof Ast.Targeted t ? t.inner() : value;
        if (inner instanceof Ast.Call call && !(call.qualifier() == null
                && (Wire.VALUES.contains(call.name()) || c.functions.containsKey(call.name()) || call.quoted()
                || c.formula.mathCall(call)))) {
            Data.Action action = action(call);
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
            String s = c.shapes.get(v.name());
            if ("list".equals(s) || "map".equals(s)) return s;
        }
        Expr idx = i.index();
        if (idx instanceof Ast.Num || (idx instanceof Ast.Unary u && u.op().equals("-"))
                || (idx instanceof Ast.Binary b && Formula.ARITHMETIC.contains(b.op()))) return "list";
        if (idx instanceof Ast.Str) return "map";
        String name = i.target() instanceof Ast.Var v ? v.name() : "это значение";
        throw c.stop("не понятно, «" + name + "» — список или словарь", i.span(),
                "задай его где-нибудь в коде: " + name + " = [] или " + name + " = {} — или пиши get_list_value / get_map_value");
    }

    private Map<String, Object> indexRead(Ast.Index i, Ast.Var into) {
        boolean list = shapeOf(i).equals("list");
        if (list && i.index() instanceof Ast.Unary u && u.op().equals("-") && u.operand() instanceof Ast.Num)
            c.warning("индекс списка считается с нуля — как сервер поймёт отрицательный, не проверено", i.index().span());
        Data.Action action = c.data.actions().get(list ? "set_variable_get_list_value" : "set_variable_get_map_value_single");
        List<Arg> args = list ? List.of(named("list", i.target()), named("number", i.index()))
                : List.of(named("map", i.target()), named("key", i.index()));
        return obj("action", action.id(), "values", bind(action, args, into, i.span()));
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
                } else if (c.current == null) {
                    c.error("return бывает только в функции", j.span());
                } else if (c.returning.contains(c.current.name())) {
                    c.error("«" + c.current.name() + "» возвращает значение — напиши return …", j.span(),
                            "если вернуть нечего, верни 0");
                }
                out.add(obj("action", "control_return_function", "values", new ArrayList<>()));
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
