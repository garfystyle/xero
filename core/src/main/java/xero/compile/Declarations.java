package xero.compile;

import xero.data.Data;
import xero.data.Names;
import xero.json.Json;
import xero.lang.Ast;
import xero.lang.Ast.Arg;
import xero.lang.Ast.Expr;
import xero.lang.Ast.Span;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static xero.compile.Context.entry;
import static xero.compile.Context.enumValue;
import static xero.compile.Context.obj;

final class Declarations {
    static final String LINE = "line";

    static final Map<String, String> PARAM_TYPES = Map.ofEntries(
            Map.entry("any", "any"), Map.entry("text", "text"), Map.entry("number", "number"),
            Map.entry("location", "location"), Map.entry("loc", "location"),
            Map.entry("vector", "vector"), Map.entry("vec", "vector"), Map.entry("block", "block"),
            Map.entry("item", "item"), Map.entry("sound", "sound"), Map.entry("particle", "particle"),
            Map.entry("potion", "potion"), Map.entry("array", "array"), Map.entry("list", "array"),
            Map.entry("map", "map"), Map.entry("variable", "variable"));

    private static final String PARAM_TYPE_LIST =
            "any, text, number, location, vector, block, item, sound, particle, potion, array, map, variable, enum(…)";

    private record Assigned(String name, Expr value, Map<String, String> params, Map<String, String> paramKinds,
                            String returned, boolean declared) {}

    static final String CUSTOM_DATA = "custom_data";
    static final int CUSTOM_DATA_LIMIT = 256;

    private static final List<String> DECORATORS = List.of(
            LINE, CUSTOM_DATA, "hidden", "icon", "display_name", "display_description", "description", "values", "twin");

    private final Context c;
    private final List<Assigned> assigned = new ArrayList<>();
    private final List<Assigned> results = new ArrayList<>();
    private final List<Assigned> inside = new ArrayList<>();
    private final Map<String, String> stated = new LinkedHashMap<>();
    private final Map<Ast.Item, Map<String, String>> lineScoped = new java.util.IdentityHashMap<>();
    private final Map<Map<String, String>, Map<String, String>> lineKinds = new java.util.IdentityHashMap<>();
    private final Map<Map<String, String>, Map<String, String>> ownKinds2 = new java.util.IdentityHashMap<>();
    private final Map<Map<String, String>, Map<String, String>> ownLikely = new java.util.IdentityHashMap<>();
    private final Map<Map<String, String>, Map<String, String>> ownShapes = new java.util.IdentityHashMap<>();
    private final Map<Map<String, String>, Map<String, String>> ownSlots = new java.util.IdentityHashMap<>();
    private Map<String, String> own;
    private Map<String, String> ownKinds = Map.of();
    private List<String> settled = new ArrayList<>();
    private String function;

    Declarations(Context c) {
        this.c = c;
    }

    Map<String, String> lineScoped(Ast.Item item) {
        return lineScoped.getOrDefault(item, Map.of());
    }

    Map<String, String> lineKinds(Ast.Item item) {
        return lineKinds.getOrDefault(lineScoped.get(item), Map.of());
    }

    void enter(Ast.Item item) {
        enter(lineScoped.get(item));
    }

    void leave() {
        c.ownKinds = Map.of();
        c.ownLikely = Map.of();
        c.ownShapes = Map.of();
        c.ownSlots = Map.of();
    }

    private void enter(Map<String, String> key) {
        c.ownKinds = ownKinds2.getOrDefault(key, Map.of());
        c.ownLikely = ownLikely.getOrDefault(key, Map.of());
        c.ownShapes = ownShapes.getOrDefault(key, Map.of());
        c.ownSlots = ownSlots.getOrDefault(key, Map.of());
    }

    private static void merge(Map<String, String> table, String name, String kind) {
        String before = table.get(name);
        if (before == null) table.put(name, kind == null ? "?" : kind);
        else if (!before.equals(kind)) {
            boolean numbers = Kinds.NUMBER.equals(Kinds.slot(before)) && Kinds.NUMBER.equals(Kinds.slot(kind));
            table.put(name, numbers ? Kinds.NUMBER : "?");
        }
    }

    Map<String, Object> customData(List<Ast.Decorator> marks, Map<String, Object> handler) {
        Ast.Decorator found = null;
        for (Ast.Decorator d : marks) {
            if (!d.name().equals(CUSTOM_DATA)) continue;
            if (found != null) c.error("пометка @custom_data повторяется", d.span());
            else found = d;
        }
        if (found == null) return handler;
        if (found.args().size() != 1 || found.args().get(0).name() != null
                || !(found.args().get(0).value() instanceof Ast.Str s) || s.parts() != null) {
            c.error("@custom_data(\"текст\") — обычная строка, без ${…}", found.span());
            return handler;
        }
        if (s.text().length() > CUSTOM_DATA_LIMIT)
            c.error("в custom_data " + s.text().length() + " символов, можно " + CUSTOM_DATA_LIMIT + ": сервер молча обрежет лишнее", found.span());
        else handler.put(CUSTOM_DATA, s.text());
        return handler;
    }

    int line(List<Ast.Decorator> marks, int next) {
        Ast.Decorator found = null;
        for (Ast.Decorator d : marks) {
            if (!d.name().equals(LINE)) continue;
            if (found != null) {
                c.error("пометка @line повторяется", d.span());
                continue;
            }
            found = d;
        }
        if (found == null) return next;
        if (found.args().size() != 1 || found.args().get(0).name() != null
                || !(found.args().get(0).value() instanceof Ast.Num n) || !n.text().matches("\\d{1,6}")) {
            c.error("@line(номер): номер строки — целое число от 0", found.span(), "строки в /dev считаются с нуля: @line(0) — первая");
            return next;
        }
        int at = Integer.parseInt(n.text());
        if (at < next) {
            c.error("строка " + at + " уже позади: номера строк идут по порядку, предыдущая — " + (next - 1), found.span(),
                    "поставь номер больше " + (next - 1) + " или убери @line");
            return next;
        }
        return at;
    }

    static Map<String, String> paramKinds(Ast.Func f) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Ast.Param p : f.params()) {
            String type = p.many() ? "array" : p.type() == null ? null : PARAM_TYPES.get(p.type().toLowerCase(Locale.ROOT));
            if (type != null) out.put(p.name(), Kinds.ofParam(type));
        }
        return out;
    }

    private void constants(List<Compiler.Unit> units) {
        for (Compiler.Unit u : units) {
            c.diagnostics.file = u.file();
            for (Ast.Item item : u.ast().items()) {
                if (!(item instanceof Ast.Const k)) continue;
                if (c.consts.containsKey(k.name())) {
                    c.error("постоянная «" + k.name() + "» уже объявлена", k.nameSpan());
                    continue;
                }
                c.consts.put(k.name(), k.value());
                if (k.value() instanceof Ast.ListLit) shape(k.name(), "list");
                if (k.value() instanceof Ast.MapLit) shape(k.name(), "map");
            }
        }
        for (Compiler.Unit u : units) {
            c.diagnostics.file = u.file();
            for (Ast.Item item : u.ast().items()) {
                if (!(item instanceof Ast.Const k)) continue;
                Set<String> path = new LinkedHashSet<>(List.of(k.name()));
                if (!constant(k.value(), path)) {
                    c.error("в const только постоянное значение: число, текст, item(…), loc(…), список", k.value().span());
                    c.consts.remove(k.name());
                }
            }
        }
    }

    private boolean constant(Expr e, Set<String> path) {
        return switch (e) {
            case Ast.Num n -> true;
            case Ast.Blank b -> true;
            case Ast.Str s -> {
                if (s.parts() != null)
                    for (Object p : s.parts()) if (p instanceof Expr x && !constant(x, path)) yield false;
                yield true;
            }
            case Ast.Var v -> {
                Expr k = c.constant(v);
                if (k == null) yield !v.quoted() && Context.truth(v.name()) != null;
                if (!path.add(v.name())) yield false;
                boolean ok = constant(k, path);
                path.remove(v.name());
                yield ok;
            }
            case Ast.Unary u -> constant(u.operand(), path);
            case Ast.Binary b -> constant(b.left(), path) && constant(b.right(), path);
            case Ast.ListLit l -> l.items().stream().allMatch(x -> constant(x, path));
            case Ast.MapLit m -> m.keys().stream().allMatch(x -> constant(x, path))
                    && m.values().stream().allMatch(x -> constant(x, path));
            case Ast.Call call -> call.qualifier() == null && Wire.VALUES.contains(call.name()) && (!call.name().equals("len")
                    || call.args().size() == 1 && constant(call.args().get(0).value(), path));
            case Ast.Index i -> constant(i.target(), path) && constant(i.index(), path);
            default -> false;
        };
    }

    private static Ast.Func withValues(Ast.Func f) {
        for (Ast.Decorator d : f.decorators()) {
            if (!d.name().equals("values") || d.args().size() != 1 || !(d.args().get(0).value() instanceof Ast.Str s)) continue;
            try {
                List<Ast.Param> params = importedParams(obj("values", Json.parse(s.text())), f.nameSpan());
                return new Ast.Func(f.process(), f.name(), f.quoted(), f.nameSpan(), params, f.body(), f.decorators(), f.span());
            } catch (RuntimeException e) {
                return f;
            }
        }
        return f;
    }

    private static boolean twin(Ast.Func f) {
        for (Ast.Decorator d : f.decorators()) if (d.name().equals("twin")) return true;
        return false;
    }

    private void kinds() {
        for (int round = 0; round < 4; round++) {
            Map<String, String> next = new LinkedHashMap<>();
            Map<Map<String, String>, Map<String, String>> nextOwn = new java.util.IdentityHashMap<>();
            for (Assigned a : assigned) {
                c.params = a.params();
                c.paramKinds = a.paramKinds();
                enter(a.params());
                String kind = a.value() == null ? null : c.compiler.kindOf(a.value());
                merge(next, a.name(), kind);
                if (!a.declared()) merge(nextOwn.computeIfAbsent(a.params(), k -> new LinkedHashMap<>()), a.name(), kind);
            }
            c.params = Map.of();
            c.paramKinds = Map.of();
            leave();
            next.putAll(stated);
            boolean same = next.equals(c.kinds) && nextOwn.equals(ownKinds2);
            c.kinds.clear();
            c.kinds.putAll(next);
            ownKinds2.clear();
            ownKinds2.putAll(nextOwn);
            if (same) break;
        }
    }

    private void likely() {
        for (int round = 0; round < 4; round++) {
            Map<String, String> next = new LinkedHashMap<>();
            Map<Map<String, String>, Map<String, String>> nextLine = new java.util.IdentityHashMap<>();
            Map<Map<String, String>, Map<String, String>> nextOwn = new java.util.IdentityHashMap<>();
            for (Assigned a : assigned) {
                note(next, a);
                if (!a.declared()) note(nextOwn.computeIfAbsent(a.params(), k -> new LinkedHashMap<>()), a);
            }
            for (Assigned a : results) note(next, a);
            for (Assigned a : inside) note(nextLine.computeIfAbsent(a.params(), k -> new LinkedHashMap<>()), a);
            c.params = Map.of();
            c.paramKinds = Map.of();
            c.lineKinds = Map.of();
            leave();
            next.putAll(stated);
            c.likely.clear();
            c.likely.putAll(next);
            lineKinds.clear();
            lineKinds.putAll(nextLine);
            ownLikely.clear();
            ownLikely.putAll(nextOwn);
        }
        c.likely.values().removeIf(k -> !Kinds.spatial(k));
        for (Map<String, String> line : lineKinds.values()) line.values().removeIf(k -> !Kinds.spatial(k));
        for (Map<String, String> table : ownLikely.values()) table.values().removeIf(k -> !Kinds.spatial(k));
    }

    private void note(Map<String, String> next, Assigned a) {
        if (a.declared()) return;
        c.params = a.params();
        c.paramKinds = a.paramKinds();
        c.lineKinds = lineKinds.getOrDefault(a.params(), Map.of());
        enter(a.params());
        String kind = a.returned() != null ? a.returned() : a.value() == null ? null : c.compiler.likelyKind(a.value());
        if (Kinds.VECTOR.equals(kind) && Kinds.LOCATION.equals(c.slot(a.name())) && c.compiler.unsure(a.value())) kind = Kinds.LOCATION;
        String before = next.get(a.name());
        if (before == null) next.put(a.name(), kind == null ? "?" : kind);
        else if (!before.equals(kind)) next.put(a.name(), "?");
    }

    private void assign(String name, Expr value) {
        assign(name, value, null, false);
    }

    private void assign(String name, Expr value, String returned, boolean declared) {
        (own.containsKey(name) ? inside : assigned).add(new Assigned(name, value, own, ownKinds, returned, declared));
    }

    private void state(String name, String shape) {
        if (shape == null) return;
        if (shape.equals("list") || shape.equals("map")) {
            shape(name, shape);
            return;
        }
        String kind = Kinds.ofParam(shape);
        if (own.containsKey(name)) {
            assign(name, null, kind, false);
            return;
        }
        String before = stated.putIfAbsent(name, kind);
        if (before != null && !before.equals(kind)) stated.put(name, "?");
    }

    private void slot(String name, String kind) {
        String before = c.slots.putIfAbsent(name, kind);
        if (before != null && !before.equals(kind)) c.slots.put(name, "?");
        Map<String, String> table = ownSlots.computeIfAbsent(own, k -> new LinkedHashMap<>());
        before = table.putIfAbsent(name, kind);
        if (before != null && !before.equals(kind)) table.put(name, "?");
    }

    private void slots(Expr call, boolean into) {
        Expr inner = call instanceof Ast.Targeted t ? t.inner() : call;
        if (!(inner instanceof Ast.Call cl) || c.userCall(cl)) return;
        Names.Entry e = c.names.resolve(Names.Space.ACTION, cl.shown());
        if (e == null) return;
        List<Data.Arg> order = new ArrayList<>(e.action().args());
        if (into) order.remove(e.action().result());
        int positional = 0;
        for (Arg a : cl.args()) {
            Data.Arg target = a.name() != null ? e.action().arg(a.name().toLowerCase(Locale.ROOT))
                    : positional < order.size() ? order.get(positional) : null;
            if (a.name() == null) positional++;
            if (target == null || !Kinds.spatial(target.kind())) continue;
            List<Expr> items = a.value() instanceof Ast.ListLit l ? l.items()
                    : e.action().plural(target.id()) ? List.of() : List.of(a.value());
            for (Expr item : items)
                if (item instanceof Ast.Var v && v.scope() == null && !own.containsKey(v.name())) slot(v.name(), target.kind());
        }
    }

    private void imported() {
        for (Context.Imported m : c.modules) {
            c.diagnostics.file = m.file();
            for (Object raw : m.handlers()) {
                Map<String, Object> h = Json.object(raw);
                boolean process = "process".equals(h.get("type"));
                if (!process && !"function".equals(h.get("type")) || !(h.get("name") instanceof String name)) continue;
                if (c.external.containsKey(name)) {
                    c.error("«" + name + "» есть и в модуле «" + c.external.get(name) + "», и в модуле «" + m.name() + "»", m.at());
                    continue;
                }
                c.external.put(name, m.name());
                c.origins.put(name, m.file());
                (process ? c.processes : c.functions).put(name, new Ast.Func(process, name, true, m.at(), importedParams(h, m.at()),
                        new Ast.Block(List.of(), m.at()), List.of(), m.at()));
            }
        }
    }

    private static List<Ast.Param> importedParams(Map<String, Object> handler, Span at) {
        List<Ast.Param> out = new ArrayList<>();
        if (!(handler.get("values") instanceof List<?> values)) return out;
        for (Object raw : values) {
            Map<String, Object> entry = Json.object(raw);
            if (!"parameters".equals(entry.get("name")) || !(entry.get("value") instanceof Map<?, ?> array)
                    || !(array.get("values") instanceof List<?> list)) continue;
            for (Object p : list) {
                Map<String, Object> param = Json.object(p);
                if (param.get("name") instanceof String name) out.add(importedParam(name, param, at));
            }
        }
        return out;
    }

    private static Ast.Param importedParam(String name, Map<String, Object> p, Span at) {
        if ("enum".equals(p.get("type_key")) && p.get("elements") instanceof String elements) {
            List<String> options = new ArrayList<>();
            for (Object element : Json.array(Json.parse(elements)))
                options.add(Json.string(Json.object(Json.parse(Json.string(element))).get("name")));
            String fallback = p.get("default_element") instanceof String d ? d : options.get(0);
            return new Ast.Param(name, "enum", options, new Ast.Str(null, fallback, at), at);
        }
        boolean plural = "plural".equals(p.get("type_key"));
        String type = p.get("value_type") instanceof String t && PARAM_TYPES.containsKey(t) && !t.equals("any") ? t : null;
        Expr fallback = !plural && "true".equals(p.get("is_required")) ? null : new Ast.Blank(at);
        return new Ast.Param(name, type, null, fallback, at, null, plural ? slotCount(p) : 0);
    }

    private static int slotCount(Map<String, Object> plural) {
        return plural.get("slots") instanceof String slots ? Math.max(1, Json.array(Json.parse(slots)).size()) : 1;
    }

    void collect(List<Compiler.Unit> units) {
        constants(units);
        imported();
        own = new LinkedHashMap<>();
        for (Compiler.Unit u : units) {
            c.diagnostics.file = u.file();
            for (Ast.Item item : u.ast().items()) {
                if (!(item instanceof Ast.Func f)) continue;
                if (c.external.containsKey(f.name())) {
                    c.error("«" + f.name() + "» уже есть в модуле «" + c.external.get(f.name()) + "»", f.nameSpan());
                    continue;
                }
                Map<String, Ast.Func> same = f.process() ? c.processes : c.functions;
                if (same.containsKey(f.name())) {
                    if (!twin(f)) c.error("«" + f.name() + "» уже объявлена выше", f.nameSpan(),
                            "если она и правда задвоена в мире, сверху ставят @twin");
                    continue;
                }
                c.origins.put(f.name(), u.file());
                if (!f.quoted() && (c.names.resolve(Names.Space.ACTION, f.name()) != null
                        || Wire.CALL_WORDS.contains(f.name()))) {
                    c.error("имя «" + f.name() + "» занято действием — назови функцию иначе", f.nameSpan());
                }
                same.put(f.name(), withValues(f));
                if (!f.process() && returnsValue(f.body())) c.returning.add(f.name());
            }
        }
        Set<String> plain = new LinkedHashSet<>();
        for (Compiler.Unit u : units) {
            c.diagnostics.file = u.file();
            for (Ast.Item item : u.ast().items())
                if (item instanceof Ast.Global g) {
                    declare(g.name(), g.scope(), g.span());
                    assign(g.name(), null, null, true);
                    state(g.name(), g.shape());
                }
        }
        ownShapes.clear();
        for (Compiler.Unit u : units) {
            c.diagnostics.file = u.file();
            for (Ast.Item item : u.ast().items()) {
                switch (item) {
                    case Ast.Event e -> {
                        own = new LinkedHashMap<>();
                        collect(e.body(), plain);
                        lineScoped.put(e, own);
                    }
                    case Ast.Func f -> {
                        own = new LinkedHashMap<>();
                        for (Ast.Param p : f.params()) {
                            own.put(p.name(), "line");
                            String type = p.type() == null ? null : PARAM_TYPES.get(p.type().toLowerCase(Locale.ROOT));
                            if ("array".equals(type) || p.many()) shape(p.name(), "list");
                            if ("map".equals(type) && !p.many()) shape(p.name(), "map");
                        }
                        ownKinds = paramKinds(f);
                        for (Ast.Param p : f.params()) assign(p.name(), null);
                        function = f.process() ? null : f.name();
                        collect(f.body(), plain);
                        function = null;
                        lineScoped.put(f, own);
                        ownKinds = Map.of();
                    }
                    case Ast.Global g -> { }
                    case Ast.Const k -> { }
                    case Ast.Import i -> { }
                }
            }
        }
        for (String name : plain) c.variables.putIfAbsent(name, "local");
        for (String name : c.variables.keySet()) if (name.startsWith(Wire.TEMP)) c.reserved.add(name);
        for (Map<String, String> table : lineScoped.values())
            for (String name : table.keySet()) if (name.startsWith(Wire.TEMP)) c.reserved.add(name);
        c.reserved.addAll(c.pinned);
        for (String f : c.returning) c.variables.putIfAbsent(Wire.RESULT + f, "local");
        kinds();
        likely();
    }

    void shape(String name, String kind) {
        String before = c.shapes.putIfAbsent(name, kind);
        if (before != null && !before.equals(kind)) c.shapes.put(name, "?");
        if (own == null) return;
        Map<String, String> table = ownShapes.computeIfAbsent(own, k -> new LinkedHashMap<>());
        before = table.putIfAbsent(name, kind);
        if (before != null && !before.equals(kind)) table.put(name, "?");
    }

    private static boolean returnsValue(Ast.Block block) {
        if (block == null) return false;
        for (Ast.Stmt s : block.stmts()) {
            boolean found = switch (s) {
                case Ast.Jump j -> j.value() != null;
                case Ast.If i -> {
                    boolean any = returnsValue(i.otherwise());
                    for (Ast.Branch b : i.branches()) any |= returnsValue(b.body());
                    yield any;
                }
                case Ast.Run r -> returnsValue(r.body());
                case Ast.Repeat r -> returnsValue(r.body());
                case Ast.ForRange f -> returnsValue(f.body());
                case Ast.ForEach f -> returnsValue(f.body());
                case Ast.While w -> returnsValue(w.body());
                case Ast.Forever f -> returnsValue(f.body());
                default -> false;
            };
            if (found) return true;
        }
        return false;
    }

    static boolean alwaysReturns(Ast.Block block) {
        if (block == null || block.stmts().isEmpty()) return false;
        Ast.Stmt last = block.stmts().get(block.stmts().size() - 1);
        if (last instanceof Ast.Jump j) return j.kind().equals("return");
        if (last instanceof Ast.If i) {
            if (i.otherwise() == null || !alwaysReturns(i.otherwise())) return false;
            for (Ast.Branch b : i.branches()) if (!alwaysReturns(b.body())) return false;
            return true;
        }
        return last instanceof Ast.Forever;
    }

    private void collect(Ast.Block block, Set<String> plain) {
        if (block == null) return;
        for (Ast.Stmt s : block.stmts()) {
            switch (s) {
                case Ast.Assign a -> {
                    if (c.consts.containsKey(a.name()) && !a.pinned() && !own.containsKey(a.name()))
                        c.error("«" + a.name() + "» — постоянная, её не меняют", a.nameSpan());
                    if (a.pinned()) c.pinned.add(a.name());
                    else if (a.scope() != null) scoped(a.name(), a.scope(), a.nameSpan());
                    else plain.add(a.name());
                    if (a.op().equals("=") && a.value() instanceof Ast.ListLit) shape(a.name(), "list");
                    if (a.op().equals("=") && a.value() instanceof Ast.MapLit) shape(a.name(), "map");
                    if (a.op().equals("=") && created(a.value()) != null) shape(a.name(), created(a.value()));
                    if (a.pinned()) assign(a.name(), null);
                    else if (a.op().equals("=")) assign(a.name(), a.value());
                    if (a.op().equals("=")) slots(a.value(), true);
                    if (a.op().equals("=") && !a.pinned() && !own.containsKey(a.name())) c.lints.assigned(a.name(), a.nameSpan());
                }
                case Ast.Declare d -> {
                    scoped(d.name(), d.scope(), d.span());
                    assign(d.name(), null, null, true);
                    state(d.name(), d.shape());
                }
                case Ast.IndexAssign a -> {
                    if (a.target().target() instanceof Ast.Var v) {
                        if (!c.pinned.contains(v.name())) plain.add(v.name());
                        if (a.target().index() instanceof Ast.Str) shape(v.name(), "map");
                        if (a.target().index() instanceof Ast.Num) shape(v.name(), "list");
                    }
                }
                case Ast.MultiAssign m -> {
                    for (int n = 0; n < m.names().size(); n++) {
                        if (m.scope() != null) scoped(m.names().get(n), m.scope(), m.nameSpans().get(n));
                        else plain.add(m.names().get(n));
                        assign(m.names().get(n), m.names().size() == m.values().size() ? m.values().get(n) : null);
                    }
                }
                case Ast.If i -> {
                    for (Ast.Branch b : i.branches()) collect(b.body(), plain);
                    collect(i.otherwise(), plain);
                }
                case Ast.Run r -> {
                    writtenVariables(r.call(), plain);
                    slots(r.call(), false);
                    collect(r.body(), plain);
                }
                case Ast.Jump j -> {
                    if (j.value() != null && function != null)
                        results.add(new Assigned(Wire.RESULT + function, j.value(), own, ownKinds, null, false));
                }
                case Ast.Repeat r -> collect(r.body(), plain);
                case Ast.ForRange f -> {
                    plain.add(f.name());
                    assign(f.name(), new Ast.Num("2", f.nameSpan()));
                    collect(f.body(), plain);
                }
                case Ast.ForEach f -> {
                    if (f.first() != null) plain.add(f.first());
                    plain.add(f.second());
                    if (f.first() != null) assign(f.first(), null);
                    assign(f.second(), null);
                    if (f.source() instanceof Ast.Var v) shape(v.name(), f.map() ? "map" : "list");
                    collect(f.body(), plain);
                }
                case Ast.While w -> collect(w.body(), plain);
                case Ast.Forever f -> collect(f.body(), plain);
                default -> { }
            }
        }
    }

    private void writtenVariables(Expr call, Set<String> plain) {
        Expr inner = call instanceof Ast.Targeted t ? t.inner() : call;
        if (!(inner instanceof Ast.Call cl) || c.userCall(cl)) return;
        Names.Entry e = c.names.resolve(Names.Space.ACTION, cl.shown());
        if (e == null) return;
        List<Data.Arg> args = e.action().args();
        int positional = 0;
        for (Arg a : cl.args()) {
            Data.Arg target = null;
            if (a.name() == null) {
                if (positional < args.size()) target = args.get(positional);
                positional++;
            } else {
                target = e.action().arg(a.name().toLowerCase(Locale.ROOT));
            }
            if (target != null && Data.VARIABLE.equals(target.kind()) && a.value() instanceof Ast.Var v) {
                if (v.scope() == null) plain.add(v.name());
                assign(v.name(), null, c.data.returned(e.action(), target), false);
            }
        }
    }

    private String created(Expr value) {
        if (!(value instanceof Ast.Call call) || c.userCall(call)) return null;
        if (call.qualifier() == null && call.name().equals("literal") && call.args().size() == 1) {
            Expr inner = call.args().get(0).value();
            return inner instanceof Ast.ListLit ? "list" : inner instanceof Ast.MapLit ? "map" : null;
        }
        Names.Entry e = c.names.resolve(Names.Space.ACTION, call.shown());
        if (e == null) return null;
        return switch (e.action().id()) {
            case "set_variable_create_list" -> "list";
            case "set_variable_create_map", "set_variable_create_map_from_values" -> "map";
            default -> null;
        };
    }

    private void scoped(String name, String scope, Span at) {
        if (!scope.equals(LINE)) {
            declare(name, scope, at);
            return;
        }
        if (c.consts.containsKey(name)) c.error("«" + name + "» — постоянная, её не объявляют переменной", at);
        String before = c.variables.get(name);
        if (before != null && !before.equals(scope)) c.error("переменная «" + name + "» уже объявлена как " + before + ", а здесь — " + scope, at);
        own.put(name, scope);
    }

    private void declare(String name, String scope, Span at) {
        if (c.consts.containsKey(name)) c.error("«" + name + "» — постоянная, её не объявляют переменной", at);
        String before = c.variables.get(name);
        if (before != null && !before.equals(scope)) {
            c.error("переменная «" + name + "» уже объявлена как " + before + ", а здесь — " + scope, at);
            return;
        }
        c.variables.put(name, scope);
        c.declared.add(name);
    }

    List<Object> values(Ast.Func f) {
        Map<String, Ast.Decorator> marks = new LinkedHashMap<>();
        for (Ast.Decorator d : f.decorators()) {
            if (!DECORATORS.contains(d.name())) {
                c.error("нет пометки @" + d.name(), d.span(), Suggest.best(d.name(), DECORATORS) != null
                        ? Suggest.hint(d.name(), DECORATORS) : "есть: @" + String.join(", @", DECORATORS));
                continue;
            }
            if (d.name().equals(LINE) || d.name().equals(CUSTOM_DATA) || d.name().equals("twin")) continue;
            if (marks.put(d.name(), d) != null) c.error("пометка @" + d.name() + " повторяется", d.span());
        }
        List<Object> declared = parameters(f);
        Ast.Decorator verbatim = marks.get("values");
        if (verbatim != null) {
            if (marks.size() > 1 || !declared.isEmpty())
                c.error("@values задаёт все значения строки сразу — другие пометки и параметры с ним не пишут",
                        verbatim.span());
            Object raw = c.guard(() -> c.rawJson(verbatim.args(), verbatim.span()));
            if (raw instanceof List<?>) return Json.array(raw);
            if (raw != null) c.error("@values — это JSON-массив", verbatim.span());
            return new ArrayList<>();
        }
        List<Object> values = new ArrayList<>();
        if (!declared.isEmpty()) values.add(entry("parameters", Context.array(declared)));
        for (String name : List.of("display_name", "display_description")) {
            Ast.Decorator d = marks.get(name);
            if (d == null) continue;
            Object v = c.guard(() -> localized(d));
            if (v != null) values.add(entry(name, v));
        }
        Ast.Decorator description = marks.get("description");
        if (description != null) {
            List<Object> cells = new ArrayList<>();
            for (Arg a : description.args()) {
                Object v = a.value() instanceof Ast.Blank ? null : c.guard(() -> c.compiler.value(a.value(), Kinds.TEXT));
                cells.add(v == null ? obj() : v);
            }
            values.add(entry("description", Context.array(cells)));
        }
        Ast.Decorator icon = marks.get("icon");
        if (icon != null) {
            if (icon.args().size() != 1) {
                c.error("@icon(item(…))", icon.span());
            } else {
                Object v = c.guard(() -> c.compiler.value(icon.args().get(0).value(), null));
                if (v != null) values.add(entry("icon", v));
            }
        }
        Ast.Decorator hidden = marks.get("hidden");
        if (hidden != null && !hidden.args().isEmpty()) c.error("@hidden пишется без скобок", hidden.span());
        values.add(entry("is_hidden", enumValue(hidden != null ? "TRUE" : "FALSE")));
        return values;
    }

    private Object localized(Ast.Decorator d) {
        if (d.args().size() == 1 && d.args().get(0).value() instanceof Ast.Call call
                && call.qualifier() == null && call.name().equals("raw")) return c.compiler.value(call, null);
        List<String> lines = new ArrayList<>();
        String parsing = null;
        for (Arg a : d.args()) {
            if (!(a.value() instanceof Ast.Str str) || a.name() != null)
                throw c.stop("@" + d.name() + "(\"строка\", \"строка\", …) или @" + d.name() + "(raw(\"…\"))",
                        a.value().span());
            String p = Context.parsing(str.prefix());
            if (parsing != null && !parsing.equals(p))
                throw c.stop("у строк описания должна быть одна разметка", a.value().span());
            parsing = p;
            lines.add(str.text());
        }
        if (lines.isEmpty()) throw c.stop("@" + d.name() + "(\"текст\")", d.span());
        Map<String, Object> data = obj("translations", obj(),
                "fallback", obj("rawText", String.join("\n", lines),
                        "parsingType", parsing.toUpperCase(Locale.ROOT)));
        return obj("type", "localized_text", "data", Json.write(data));
    }

    List<String> settled() {
        return settled;
    }

    private List<Object> parameters(Ast.Func f) {
        settled = new ArrayList<>();
        List<Object> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Wire.ParamChest chest = new Wire.ParamChest(f.params().size());
        for (Ast.Param p : f.params()) {
            if (!seen.add(p.name())) {
                c.error("параметр «" + p.name() + "» уже есть", p.span());
                continue;
            }
            if (chest.full()) {
                c.error("больше " + Wire.PARAM_SLOTS + " параметров сервер не разложит", p.span());
                break;
            }
            String about = about(p);
            if (p.options() != null || "enum".equalsIgnoreCase(p.type())) {
                if (p.options() == null || p.options().isEmpty() || !"enum".equalsIgnoreCase(p.type())) {
                    c.error("вид с вариантами пишется так: " + p.name() + ": enum(\"первый\", \"второй\")", p.span());
                    continue;
                }
                String first = p.options().get(0);
                if (p.fallback() != null && !(p.fallback() instanceof Ast.Str s && p.options().contains(s.text())))
                    c.error("по умолчанию у «" + p.name() + "» — один из вариантов: " + String.join(", ", p.options()), p.fallback().span());
                out.add(Wire.markerParameter(p.name(), p.options(), p.fallback() instanceof Ast.Str s ? s.text() : first, chest.take(), about));
                continue;
            }
            String type = "any";
            if (p.type() != null) {
                type = PARAM_TYPES.get(p.type().toLowerCase(Locale.ROOT));
                if (type == null) {
                    c.error("нет вида «" + p.type() + "»", p.span(), "есть: " + PARAM_TYPE_LIST);
                    type = "any";
                }
            }
            String fallback = p.many() ? Wire.EMPTY_LIST : "{}";
            if (p.fallback() != null && !(p.fallback() instanceof Ast.Blank)) {
                Object v = c.guard(() -> c.compiler.value(p.fallback(), null));
                if (v != null) fallback = Json.write(v);
                if (Wire.lazy(v) && !Calls.verbatim(p.fallback())) settled.add(p.name());
            }
            if (p.many()) {
                List<Integer> slots = chest.take(p.cells());
                if (slots == null) c.error("в сундуке функции не хватает клеток для «" + p.name() + "»", p.span());
                else out.add(Wire.pluralParameter(p.name(), type, p.fallback() == null, fallback, slots, about));
                continue;
            }
            out.add(Wire.parameter(p.name(), type, p.fallback() == null, fallback, chest.take(), about));
        }
        return out;
    }

    private String about(Ast.Param p) {
        if (p.about() == null) return Wire.NO_TRANSLATIONS;
        Map<String, Object> data = obj("translations", obj(), "fallback", obj("rawText", p.about().text(),
                "parsingType", Context.parsing(p.about().prefix()).toUpperCase(Locale.ROOT)));
        return Json.write(data);
    }
}
