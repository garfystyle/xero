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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

final class Context {
    static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Stop() { super(null, null, false, false); }
    }

    static final class Tagged extends ArrayList<Diagnostic> {
        private static final long serialVersionUID = 1L;
        String file;

        @Override
        public boolean add(Diagnostic d) {
            return super.add(d.file() == null && file != null ? d.in(file) : d);
        }
    }

    record Captured<T>(T value, List<Object> pre, boolean calls) {}

    record Imported(String name, String file, Span at, List<Object> handlers, Object entry) {}

    final Data data;
    final Names names;
    final Tagged diagnostics = new Tagged();
    final Map<String, String> variables = new LinkedHashMap<>();
    final Set<String> declared = new LinkedHashSet<>();
    final Set<String> pinned = new LinkedHashSet<>();
    final Set<String> reserved = new LinkedHashSet<>();
    final Map<String, Expr> consts = new LinkedHashMap<>();
    final List<Imported> modules = new ArrayList<>();
    final Map<String, String> external = new LinkedHashMap<>();
    final Map<String, String> origins = new LinkedHashMap<>();
    final Map<String, String> kinds = new LinkedHashMap<>();
    final Map<String, String> likely = new LinkedHashMap<>();
    final Map<String, String> slots = new LinkedHashMap<>();
    final Map<String, Ast.Func> functions = new LinkedHashMap<>();
    final Map<String, Ast.Func> processes = new LinkedHashMap<>();
    final Set<String> returning = new LinkedHashSet<>();
    final Map<String, String> shapes = new LinkedHashMap<>();
    Map<String, String> params = Map.of();
    Map<String, String> paramKinds = Map.of();
    Map<String, String> lineKinds = Map.of();
    Map<String, String> ownKinds = Map.of();
    Map<String, String> ownLikely = Map.of();
    Map<String, String> ownShapes = Map.of();
    Map<String, String> ownSlots = Map.of();
    boolean storing;
    Ast.Var writing;
    Data.Event happening;
    double unit = 50;
    Ast.Func current;
    int loops;
    boolean world;
    boolean selected;
    int flags;
    List<Object> prelude;
    Map<String, Integer> callCounts = Map.of();
    Map<Ast.Var, Ast.Var> snapshots = new java.util.IdentityHashMap<>();
    int called;

    final Compiler compiler;
    final Declarations declarations;
    final Conditions conditions;
    final Formula formula;
    final Values values;
    final Calls calls;
    final Hints hints;
    final Locals locals;
    final Lints lints;
    final Order order;

    Context(Data data, Names names) {
        this.data = data;
        this.names = names;
        compiler = new Compiler(this);
        declarations = new Declarations(this);
        conditions = new Conditions(this);
        formula = new Formula(this);
        values = new Values(this);
        calls = new Calls(this);
        hints = new Hints(this);
        locals = new Locals(this);
        lints = new Lints(this);
        order = new Order(this);
    }

    static Boolean truth(String word) {
        return switch (word) {
            case "true", "истина" -> Boolean.TRUE;
            case "false", "ложь" -> Boolean.FALSE;
            default -> null;
        };
    }

    static String mathConstant(String word) {
        return switch (word) {
            case "PI" -> Double.toString(Math.PI);
            case "E" -> Double.toString(Math.E);
            default -> null;
        };
    }

    Ast.Func line(String name) {
        Ast.Func f = functions.get(name);
        return f != null ? f : processes.get(name);
    }

    boolean userCall(Ast.Call call) {
        if (call.qualifier() != null) return line(call.shown()) != null && !builtin(call.shown());
        if (call.quoted()) return true;
        Ast.Func f = line(call.name());
        return f != null && !(f.quoted() && builtin(call.name()));
    }

    private boolean builtin(String name) {
        return names.resolve(Names.Space.ACTION, name) != null || names.resolve(Names.Space.CONDITION, name) != null
                || Wire.CALL_WORDS.contains(name);
    }

    Expr constant(Ast.Var v) {
        if (v.scope() != null || params.containsKey(v.name())) return null;
        if (v.quoted() && Keywords.plainName(v.name())) return null;
        return consts.get(v.name());
    }

    Expr item(Ast.Index i) {
        Expr from = resolved(i.target());
        if (from instanceof Ast.ListLit l) {
            Double n = formula.fold(i.index());
            if (n == null || n != Math.rint(n) || n < 0 || n >= l.items().size()) return null;
            Expr found = l.items().get(n.intValue());
            return found instanceof Ast.Blank ? null : found;
        }
        if (from instanceof Ast.MapLit m && resolved(i.index()) instanceof Ast.Str key && !key.interpolated()) {
            for (int n = 0; n < m.keys().size(); n++)
                if (resolved(m.keys().get(n)) instanceof Ast.Str s && !s.interpolated() && s.text().equals(key.text())
                        && !(m.values().get(n) instanceof Ast.Blank)) return m.values().get(n);
        }
        return null;
    }

    Expr resolved(Expr e) {
        if (e instanceof Ast.Var taken && snapshots.containsKey(taken)) return snapshots.get(taken);
        if (e instanceof Ast.Index i) {
            Expr found = item(i);
            return found == null ? e : resolved(found);
        }
        if (!(e instanceof Ast.Var v)) return e;
        Expr k = constant(v);
        if (k != null) return resolved(k);
        if (v.quoted() || known(v.name())) return e;
        Boolean truth = truth(v.name());
        if (truth != null) return new Ast.Num(truth ? "1" : "0", v.span());
        String number = mathConstant(v.name());
        return number == null ? e : new Ast.Num(number, v.span());
    }

    private boolean own(Map<String, String> table, String name) {
        String scope = variables.get(name);
        return table.containsKey(name) && ("local".equals(scope) || "line".equals(scope));
    }

    String slot(String name) {
        return own(ownKinds, name) ? ownSlots.get(name) : slots.get(name);
    }

    String shape(String name) {
        return own(ownShapes, name) ? ownShapes.get(name) : shapes.get(name);
    }

    String kind(Ast.Var v) {
        if (v.scope() != null) return null;
        if (params.containsKey(v.name())) return paramKinds.get(v.name());
        String k = own(ownKinds, v.name()) ? ownKinds.get(v.name()) : kinds.get(v.name());
        return "?".equals(k) ? null : k;
    }

    String likely(Ast.Var v) {
        if (v.scope() != null) return null;
        if (params.containsKey(v.name())) {
            String own = paramKinds.get(v.name());
            if (own == null) own = lineKinds.get(v.name());
            return "?".equals(own) ? null : own;
        }
        String soft = own(ownLikely, v.name()) ? ownLikely.get(v.name()) : own(ownKinds, v.name()) ? null : likely.get(v.name());
        if (soft != null) return "?".equals(soft) ? null : soft;
        String firm = kind(v);
        if (firm != null) return firm;
        String used = slot(v.name());
        return "?".equals(used) ? null : used;
    }

    String shown(Ast.Var v) {
        if (declared.contains(v.name()) && kind(v) == null) return null;
        return likely.containsKey(v.name()) || own(ownLikely, v.name()) || params.containsKey(v.name()) ? likely(v) : kind(v);
    }

    double number(Ast.Num n) {
        return n.millis() == null ? Double.parseDouble(n.text()) : n.millis() / unit;
    }

    String numberText(Ast.Num n) {
        if (n.millis() == null) return n.text();
        double d = number(n);
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }

    String scope(String name) {
        String scope = params.get(name);
        return scope != null ? scope : variables.get(name);
    }

    String scope(Ast.Var v) {
        if (v.scope() == null) return scope(v.name());
        pinned.add(v.name());
        return v.scope();
    }

    boolean known(String name) {
        return scope(name) != null || pinned.contains(name);
    }

    List<Object> need(Span at) {
        if (prelude == null) throw stop("здесь можно только постоянное значение — вызов, индекс или условие сюда не подставить", at);
        return prelude;
    }

    <T> Captured<T> capture(Supplier<T> work) {
        List<Object> saved = prelude;
        int before = called;
        List<Object> pre = new ArrayList<>();
        prelude = pre;
        try {
            T value = work.get();
            return new Captured<>(value, pre, called > before);
        } finally {
            prelude = saved;
        }
    }

    String flag() {
        String name;
        do name = Wire.TEMP + (++flags); while (reserved.contains(name));
        return name;
    }

    Ast.Var temp(Span at) {
        String name = flag();
        variables.put(name, "line");
        return new Ast.Var(name, false, at);
    }

    <T> T guard(Supplier<T> work) {
        try {
            return work.get();
        } catch (Stop s) {
            return null;
        }
    }

    Object rawJson(List<Arg> args, Span at) {
        if (args.size() != 1 || args.get(0).name() != null || !(args.get(0).value() instanceof Ast.Str str))
            throw stop("raw(\"…JSON…\") — одна строка с JSON", at);
        try {
            lints.text(str.text());
            return Json.parse(str.text());
        } catch (Json.Error e) {
            throw stop("в raw не JSON: " + e.getMessage(), str.span());
        }
    }

    double finite(double d, Span at) {
        if (!Double.isFinite(d))
            throw stop("число слишком большое — в код мира его не записать", at, "больше примерно 1,8·10³⁰⁸ чисел не бывает");
        return d;
    }

    String gameValue(String id, Span at) {
        if (!data.gameValues().containsKey(id)) {
            String best = Suggest.best(id, data.gameValues().keySet());
            throw stop("нет игрового значения «" + id + "»", at, Suggest.meant(best),
                    Hints.replace(at, best == null ? null : "value." + best));
        }
        return id;
    }

    void error(String message, Span at) {
        error(message, at, null);
    }

    void error(String message, Span at, String hint) {
        error(message, at, hint, List.of());
    }

    void error(String message, Span at, String hint, List<Diagnostic.Fix> fixes) {
        int start = at == null ? 0 : at.start();
        int end = at == null ? 1 : at.end();
        diagnostics.add(new Diagnostic(true, message, start, end, hint, fixes));
    }

    void warning(String message, Span at) {
        warning(message, at, null);
    }

    void warning(String message, Span at, String hint) {
        warning(message, at, hint, List.of());
    }

    void warning(String message, Span at, String hint, List<Diagnostic.Fix> fixes) {
        diagnostics.add(new Diagnostic(false, message, at.start(), at.end(), hint, fixes));
    }

    Stop stop(String message, Span at) {
        return stop(message, at, null);
    }

    Stop stop(String message, Span at, String hint) {
        error(message, at, hint);
        return new Stop();
    }

    Stop stop(String message, Span at, String hint, List<Diagnostic.Fix> fixes) {
        error(message, at, hint, fixes);
        return new Stop();
    }

    static Arg named(String name, Expr value) {
        return new Arg(name, value.span(), value);
    }

    static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static Map<String, Object> entry(String name, Object value) {
        return obj("name", name, "value", value);
    }

    static Map<String, Object> enumValue(String id) {
        return obj("type", "enum", "enum", id);
    }

    static Map<String, Object> array(List<Object> cells) {
        return obj("type", "array", "values", cells);
    }

    static Map<String, Object> text(String s, String parsing) {
        return obj("type", "text", "text", s, "parsing", parsing);
    }

    static String parsing(String prefix) {
        if (prefix == null) return "legacy";
        return switch (prefix) {
            case "mm" -> "minimessage";
            case "plain" -> "plain";
            case "json" -> "json";
            default -> "legacy";
        };
    }

    static Json.Num num(double d) {
        return new Json.Num(Double.toString(d));
    }
}
