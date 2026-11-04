package xero.compile;

import xero.data.Data;
import xero.json.Json;
import xero.lang.Ast;
import xero.lang.Ast.Arg;
import xero.lang.Ast.Expr;
import xero.lang.Ast.Span;
import xero.lang.Keywords;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static xero.compile.Context.entry;
import static xero.compile.Context.enumValue;
import static xero.compile.Context.obj;
import static xero.compile.Context.text;

final class Calls {
    private final Context c;

    Calls(Context c) {
        this.c = c;
    }

    Map<String, Integer> count(Ast.Stmt s) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Expr> roots = new ArrayList<>();
        switch (s) {
            case Ast.Run r -> roots.add(r.call());
            case Ast.Assign a -> roots.add(a.value());
            case Ast.IndexAssign a -> {
                roots.add(a.target());
                roots.add(a.value());
            }
            case Ast.MultiAssign m -> roots.addAll(m.values());
            case Ast.If i -> {
                for (Ast.Branch b : i.branches()) roots.add(b.condition());
            }
            case Ast.Jump j -> {
                if (j.value() != null) roots.add(j.value());
            }
            case Ast.Start st -> {
                for (Arg a : st.args()) roots.add(a.value());
            }
            case Ast.Repeat r -> roots.add(r.count());
            case Ast.ForRange f -> {
                roots.add(f.from());
                roots.add(f.to());
                if (f.step() != null) roots.add(f.step());
            }
            case Ast.ForEach f -> roots.add(f.source());
            case Ast.While w -> roots.add(w.condition());
            default -> { }
        }
        for (Expr e : roots) count(e, counts);
        return counts;
    }

    private void count(Expr e, Map<String, Integer> counts) {
        if (e == null) return;
        switch (e) {
            case Ast.Call call -> {
                if (call.qualifier() == null && c.returning.contains(call.name())) counts.merge(call.name(), 1, Integer::sum);
                for (Arg a : call.args()) count(a.value(), counts);
            }
            case Ast.Str str -> {
                if (str.parts() != null) for (Object part : str.parts()) if (part instanceof Expr x) count(x, counts);
            }
            case Ast.ListLit l -> l.items().forEach(x -> count(x, counts));
            case Ast.MapLit m -> {
                m.keys().forEach(x -> count(x, counts));
                m.values().forEach(x -> count(x, counts));
            }
            case Ast.Targeted t -> count(t.inner(), counts);
            case Ast.Unary u -> count(u.operand(), counts);
            case Ast.Binary b -> {
                count(b.left(), counts);
                count(b.right(), counts);
            }
            case Ast.Index i -> {
                count(i.target(), counts);
                count(i.index(), counts);
            }
            case Ast.Ternary t -> {
                count(t.condition(), counts);
                count(t.then(), counts);
                count(t.otherwise(), counts);
            }
            default -> { }
        }
    }

    Object value(Ast.Call call) {
        if (call.qualifier() == null && Wire.VALUES.contains(call.name()) && !call.quoted()) return c.values.factory(call);
        if (call.qualifier() == null && c.functions.containsKey(call.name())) {
            if (c.returning.contains(call.name())) return result(call);
            Ast.Func f = c.functions.get(call.name());
            throw c.stop((f.process() ? "процесс" : "функция") + " «" + call.name() + "» ничего не возвращает", call.span(),
                    f.process() ? "процесс запускают отдельно: start " + call.name() + "()"
                            : c.external.containsKey(call.name()) ? "она из модуля: результат смотри в её описании"
                            : "допиши в неё return значение");
        }
        if (call.quoted()) throw c.stop("«" + call.name() + "» нет в этом файле — неизвестно, что она возвращает", call.span());
        if (c.conditions.conditionName(call)) return c.conditions.conditionValue(call);
        return action(call, null, null);
    }

    private Object result(Ast.Call call) {
        List<Object> pre = c.need(call.span());
        Map<String, Object> op = invoke(call);
        c.called++;
        pre.add(op);
        Ast.Var result = new Ast.Var(Wire.RESULT + call.name(), false, call.span());
        if (c.callCounts.getOrDefault(call.name(), 0) > 1) {
            Ast.Var tmp = c.temp(call.span());
            Data.Action set = c.data.actions().get("set_variable_value");
            pre.add(obj("action", set.id(), "values", c.compiler.bind(set, List.of(new Arg(null, null, result)), tmp, call.span())));
            return c.compiler.variable(tmp, null);
        }
        return c.compiler.variable(result, null);
    }

    Object action(Ast.Call call, String target, Span targetSpan) {
        Data.Action action = c.compiler.action(call);
        if (action.container()) throw c.stop("у «" + call.shown() + "» есть тело — его не подставить как значение", call.span());
        Ast.Var tmp = c.temp(call.span());
        Map<String, Object> op = obj("action", action.id(), "values", c.compiler.bind(action, call.args(), tmp, call.span()));
        if (target != null) op.put("selection", c.compiler.selection(action, target, targetSpan));
        c.need(call.span()).add(op);
        return c.compiler.variable(tmp, null);
    }

    Map<String, Object> invoke(Ast.Call call) {
        Ast.Func f = c.functions.get(call.name());
        c.locals.call(call.name(), f != null && !f.process() && !c.external.containsKey(call.name()));
        List<Object> values = new ArrayList<>();
        values.add(entry("function_name", text(call.name(), "legacy")));
        Map<String, Object> passed = passed(call.name(), call.args(), call.span(), call.nameSpan(), false, call.quoted());
        if (!passed.isEmpty()) values.add(entry("args", obj("type", "map", "values", passed)));
        return obj("action", "call_function", "values", values);
    }

    Map<String, Object> dynamic(Ast.Call call) {
        if (call.args().isEmpty() || call.args().get(0).name() != null)
            throw c.stop("call(имя, аргумент: значение, …) — первым идёт имя функции", call.span());
        c.locals.opaque();
        List<Object> values = new ArrayList<>();
        Object name = c.compiler.value(call.args().get(0).value(), null);
        if (name == null) throw c.stop("нужно имя функции", call.args().get(0).value().span());
        values.add(entry("function_name", name));
        Map<String, Object> passed = new LinkedHashMap<>();
        for (Arg a : call.args().subList(1, call.args().size())) {
            if (a.name() == null) {
                c.error("у call аргументы пишутся по имени: имя: значение", a.value().span());
                continue;
            }
            Object v = c.guard(() -> c.compiler.value(a.value(), null));
            if (v != null) passed.put(Json.write(text(a.name(), "plain")), v);
        }
        if (!passed.isEmpty()) values.add(entry("args", obj("type", "map", "values", passed)));
        return obj("action", "call_function", "values", values);
    }

    Map<String, Object> start(Ast.Start s) {
        List<Object> values = new ArrayList<>();
        values.add(entry("process_name", text(s.name(), "legacy")));
        List<Arg> args = new ArrayList<>();
        Map<String, Object> settings = new LinkedHashMap<>();
        for (Arg a : s.args()) {
            List<String> options = a.name() == null ? null : Wire.START_SETTINGS.get(a.name());
            if (options == null) {
                args.add(a);
                continue;
            }
            String word = a.value() instanceof Ast.Var v && !v.quoted() ? v.name().toLowerCase(Locale.ROOT) : null;
            if (word == null || !options.contains(word)) {
                String best = word == null ? null : Suggest.best(word, options);
                c.error("у " + a.name() + " варианты: " + String.join(", ", options), a.value().span(),
                        Suggest.meant(best), Hints.replace(a.value().span(), best));
                continue;
            }
            settings.put(a.name(), enumValue(word.toUpperCase(Locale.ROOT)));
        }
        for (String key : Wire.START_SETTINGS.keySet())
            if (settings.containsKey(key)) values.add(entry(key, settings.get(key)));
        Object mode = settings.get("local_variables_mode");
        c.locals.start(s.name(), mode == null ? "dont_copy" : Json.string(Json.object(mode).get("enum")).toLowerCase(Locale.ROOT));
        Map<String, Object> passed = passed(s.name(), args, s.span(), s.nameSpan(), true, s.quoted());
        if (!passed.isEmpty()) values.add(entry("args", obj("type", "map", "values", passed)));
        return obj("action", "start_process", "values", values);
    }

    private Map<String, Object> passed(String name, List<Arg> args, Span at, Span nameAt, boolean process, boolean quoted) {
        Ast.Func f = c.functions.get(name);
        Map<String, Object> out = new LinkedHashMap<>();
        if (f == null) {
            if (quoted) {
                String close = c.external.isEmpty() ? null : Suggest.best(name, c.external.keySet());
                c.warning("«" + name + "» нет в этом файле — вызов уйдёт как есть", at, Suggest.meant(close),
                        Hints.replace(nameAt, close == null ? null : Keywords.function(close)));
            } else {
                Set<String> known = c.functions.keySet();
                String best = Suggest.best(name, known);
                throw c.stop("нет " + (process ? "процесса" : "функции") + " «" + name + "»", at, Suggest.meant(best),
                        Hints.replace(nameAt, best == null ? null : Keywords.function(best)));
            }
            for (Arg a : args) {
                if (a.name() == null) {
                    c.error("у функции не из этого файла аргументы пишутся по имени: имя: значение", a.value().span());
                    continue;
                }
                Object v = c.guard(() -> c.compiler.value(a.value(), null));
                if (v != null) out.put(Json.write(text(a.name(), "plain")), v);
            }
            return out;
        }
        if (f.process() != process)
            c.error("«" + name + "» — " + (f.process() ? "процесс: запускай start " + name + "()" : "функция: вызывай " + name + "()"), at);
        Map<String, Expr> bound = new LinkedHashMap<>();
        int positional = 0;
        for (Arg a : args) {
            String key;
            if (a.name() == null) {
                if (positional >= f.params().size()) {
                    c.error("лишний аргумент: у «" + name + "» параметров " + f.params().size(), a.value().span());
                    continue;
                }
                key = f.params().get(positional++).name();
            } else {
                key = a.name();
                boolean exists = false;
                for (Ast.Param p : f.params()) if (p.name().equals(key)) exists = true;
                if (!exists) {
                    List<String> all = new ArrayList<>();
                    for (Ast.Param p : f.params()) all.add(p.name());
                    String best = Suggest.best(key, all);
                    c.error("у «" + name + "» нет параметра «" + key + "»", a.nameSpan(), Suggest.meant(best),
                            Hints.replace(a.nameSpan(), best));
                    continue;
                }
            }
            if (bound.put(key, a.value()) != null) c.error("параметр «" + key + "» указан дважды", a.value().span());
        }
        for (Ast.Param p : f.params()) {
            Expr e = bound.get(p.name());
            if (e == null) {
                String key = Json.write(text(p.name(), "plain"));
                if (p.options() != null) out.put(key, text(markerDefault(p), "plain"));
                else if (p.fallback() != null) out.put(key, new LinkedHashMap<String, Object>());
                else c.error("не передан параметр «" + p.name() + "»", at);
                continue;
            }
            if (p.options() != null) {
                Object v = c.guard(() -> marker(p, e));
                if (v != null) out.put(Json.write(text(p.name(), "plain")), v);
                continue;
            }
            String kind = p.type() == null ? null : Kinds.ofParam(Declarations.PARAM_TYPES.get(p.type().toLowerCase(Locale.ROOT)));
            Object v = c.guard(() -> c.compiler.value(e, kind));
            if (v != null) out.put(Json.write(text(p.name(), "plain")), v);
        }
        return out;
    }

    private static String markerDefault(Ast.Param p) {
        return p.fallback() instanceof Ast.Str s ? s.text() : p.options().get(0);
    }

    private Object marker(Ast.Param p, Expr e) {
        Expr r = c.resolved(e);
        if (r instanceof Ast.ListLit) return c.compiler.value(r, null);
        if (r instanceof Ast.Var v) {
            return Context.array(new ArrayList<>(List.of(c.compiler.variable(v, null), text(markerDefault(p), "plain"))));
        }
        if (!(r instanceof Ast.Str s) || s.interpolated())
            throw c.stop("у «" + p.name() + "» варианты: " + String.join(", ", p.options()), e.span());
        if (!p.options().contains(s.text())) {
            String best = Suggest.best(s.text(), p.options());
            throw c.stop("у «" + p.name() + "» нет варианта «" + s.text() + "»", e.span(),
                    best != null ? Suggest.meant(best) : "есть: " + String.join(", ", p.options()));
        }
        return text(s.text(), "plain");
    }
}
