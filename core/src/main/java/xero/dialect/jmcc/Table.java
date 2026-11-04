package xero.dialect.jmcc;

import xero.data.Category;
import xero.data.Data;
import xero.data.Names;
import xero.ide.Service;
import xero.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Table {
    record Entry(String object, String name, String id, List<String> order, List<String> result, String resultType,
                 String origin, List<String> lambda, boolean bool, List<String> oldOrder, boolean stale,
                 Map<String, List<String>> oldOptions, List<String> loose, Data.Action action, Names.Entry xero) {
        String label() { return object + "::" + name; }

        boolean live() { return action != null; }

        boolean container() { return action != null && action.container() && !bool; }

        boolean setting(String arg) { return action != null && action.setting(arg) != null; }

        boolean variable(String arg) {
            Data.Arg a = action == null ? null : action.arg(arg);
            return a != null && Data.VARIABLE.equals(a.kind());
        }

        String firstResult() { return result.isEmpty() ? null : result.get(0); }
    }

    static final Set<String> MATH = Set.of("abs", "sqrt", "cbrt", "ceil", "floor", "sin", "cos", "round", "pow", "min", "max",
            "sign", "atan2", "root", "clamp", "wrap", "sinr", "cosr", "asin", "acos");
    static final Set<String> CONDITIONAL = Set.of("repeat_while", "select_player_by_conditional",
            "select_entity_by_conditional", "select_add_player_by_conditional", "select_add_entity_by_conditional",
            "select_filter_by_conditional");

    private static final String BODY = " {\n\t$0\n}";

    private final Data data;
    private final Names names;
    private final Map<String, Entry> byLabel = new LinkedHashMap<>();
    private final Map<String, Entry> byId = new LinkedHashMap<>();
    private final Map<String, Entry> methods = new LinkedHashMap<>();
    private final Map<String, String> valueAliases = new LinkedHashMap<>();
    private final Set<String> staleValues = new LinkedHashSet<>();
    private final Map<String, String> valueTypes = new LinkedHashMap<>();
    private final Map<String, String> movedValues = new LinkedHashMap<>();
    private final Map<String, String> eventAliases = new LinkedHashMap<>();
    private final Set<String> staleEvents = new LinkedHashSet<>();
    private List<Service.Item> vocabulary;

    Table(Data data, Names names) {
        this.data = data;
        this.names = names;
        Map<String, Object> root = Json.object(Json.parse(resource()));
        List<Map<String, Object>> raw = new ArrayList<>();
        for (Object o : Json.array(root.get("actions"))) raw.add(Json.object(o));
        for (Map<String, Object> o : raw) add(o);
        for (Map<String, Object> o : raw) aliases(o);
        for (Data.Action a : data.actions().values()) if (!byId.containsKey(a.id())) derive(a);
        for (Entry e : byId.values()) {
            byLabel.putIfAbsent(e.object() + "::" + Names.shortName(e.id()), e);
            if (e.origin() != null) methods.putIfAbsent(e.name(), e);
        }
        for (Map<String, Object> o : raw) methodAliases(o);
        Map<String, Object> values = Json.object(root.get("values"));
        Json.object(values.get("aliases")).forEach((k, v) -> valueAliases.put(k, Json.string(v)));
        for (Object o : Json.array(values.get("stale"))) staleValues.add(Json.string(o));
        Json.object(values.get("types")).forEach((k, v) -> valueTypes.put(k, Json.string(v)));
        Json.object(values.get("moved")).forEach((k, v) -> movedValues.put(k, Json.string(v)));
        Map<String, Object> events = Json.object(root.get("events"));
        Json.object(events.get("aliases")).forEach((k, v) -> eventAliases.put(k, Json.string(v)));
        for (Object o : Json.array(events.get("stale"))) staleEvents.add(Json.string(o));
    }

    private static String resource() {
        for (String path : List.of("/xero/data/jmcc.json", "/assets/xerocode/jmcc.json")) {
            try (InputStream in = Table.class.getResourceAsStream(path)) {
                if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        throw new IllegalStateException("нет данных: /xero/data/jmcc.json");
    }

    private static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) for (Object o : Json.array(raw)) out.add(Json.string(o));
        return out;
    }

    private void add(Map<String, Object> o) {
        String id = Json.string(o.get("i"));
        Data.Action action = data.actions().get(id);
        List<String> order = strings(o.get("a"));
        List<String> old = o.get("to") == null ? null : strings(o.get("to"));
        if (action != null) {
            for (Data.Arg a : action.args()) if (!order.contains(a.id())) order.add(a.id());
            for (Data.Setting s : action.settings()) if (!order.contains(s.id())) order.add(s.id());
        }
        Map<String, List<String>> oldOptions = new LinkedHashMap<>();
        if (o.get("xe") != null) Json.object(o.get("xe")).forEach((k, v) -> oldOptions.put(k, strings(v)));
        Entry e = new Entry(Json.string(o.get("o")), Json.string(o.get("n")), id, order, strings(o.get("r")),
                o.get("t") instanceof String t ? t : null, o.get("g") instanceof String g ? g : null, strings(o.get("l")),
                o.containsKey("b"), old, o.containsKey("s"), oldOptions, strings(o.get("ea")), action,
                action == null ? null : names.byId(id));
        byId.put(id, e);
        byLabel.put(e.label(), e);
    }

    private void aliases(Map<String, Object> o) {
        Entry e = byId.get(Json.string(o.get("i")));
        for (String alias : strings(o.get("al"))) byLabel.putIfAbsent(alias.contains("::") ? alias : e.object() + "::" + alias, e);
    }

    private void methodAliases(Map<String, Object> o) {
        Entry e = byId.get(Json.string(o.get("i")));
        if (e.origin() == null) return;
        for (String alias : strings(o.get("al"))) if (!alias.contains("::")) methods.putIfAbsent(alias, e);
    }

    private void derive(Data.Action a) {
        String object = object(a.category());
        String name = Names.shortName(a.id());
        if (byLabel.containsKey(object + "::" + name)) name = a.id();
        List<String> order = new ArrayList<>();
        for (Data.Arg x : a.args()) order.add(x.id());
        for (Data.Setting s : a.settings()) order.add(s.id());
        Data.Arg result = a.result();
        List<String> results = result == null ? List.of() : List.of(result.id());
        Entry e = new Entry(object, name, a.id(), order, results, null, null, List.of(), a.category().condition, null, false,
                Map.of(), List.of(), a, names.byId(a.id()));
        byId.put(a.id(), e);
        byLabel.put(e.label(), e);
    }

    private static String object(Category c) {
        return switch (c) {
            case PLAYER, IF_PLAYER -> "player";
            case ENTITY, IF_ENTITY -> "entity";
            case GAME, IF_GAME -> "world";
            case VARIABLE, IF_VARIABLE -> "variable";
            case SELECT -> "select";
            case REPEAT -> "repeat";
            case CONTROL -> "code";
            case CONTROLLER -> "controller";
        };
    }

    Data data() { return data; }

    Names names() { return names; }

    Entry action(String object, String name) {
        String canonical = Words.object(object);
        return canonical == null ? null : byLabel.get(canonical + "::" + name);
    }

    Entry byId(String id) { return byId.get(id); }

    Entry method(String name) { return methods.get(name); }

    Set<String> labels() { return byLabel.keySet(); }

    Set<String> methodNames() { return methods.keySet(); }

    String valueId(String name) {
        if (data.gameValues().containsKey(name)) return name;
        String alias = valueAliases.get(name);
        return alias != null && data.gameValues().containsKey(alias) ? alias : null;
    }

    boolean staleValue(String name) { return staleValues.contains(name); }

    String valueType(String id) { return movedValues.containsKey(id) ? null : valueTypes.get(id); }

    String movedValue(String name) { return movedValues.get(name); }

    String eventId(String name) {
        if (data.events().containsKey(name)) return name;
        return eventAliases.getOrDefault(name, name);
    }

    boolean staleEvent(String name) { return staleEvents.contains(name); }

    String target(String family, String selector) {
        if (family == null) return null;
        List<String> ids = new ArrayList<>(names.selectors(family).values());
        String id = closest(selector, ids);
        if (id == null) return null;
        for (Map.Entry<String, String> e : names.selectors(family).entrySet()) if (e.getValue().equals(id)) return e.getKey();
        return null;
    }

    String selector(String family, String target) { return names.selectors(family).get(target); }

    String option(Entry e, String arg, String word) {
        Data.Setting s = e.action() == null ? null : e.action().setting(arg);
        return s == null ? null : closest(word, new ArrayList<>(s.options().keySet()));
    }

    static String closest(String word, List<String> options) {
        String lower = word.toLowerCase(Locale.ROOT);
        String found = null;
        int count = 0;
        for (String o : options) {
            String l = o.toLowerCase(Locale.ROOT);
            if (l.equals(lower)) return o;
            if (l.startsWith(lower)) {
                found = o;
                count++;
            }
        }
        return count == 1 ? found : null;
    }

    List<Service.Item> vocabulary() {
        if (vocabulary != null) return vocabulary;
        List<Service.Item> out = new ArrayList<>();
        for (Entry e : byId.values()) {
            if (e.action() == null || e.action().blank() || e.action().alias()) continue;
            Data.Action a = e.action();
            String kind = a.category().condition ? "condition" : "action";
            String doc = "**" + a.label() + "** — " + a.category().russian + (a.description().isEmpty() ? "" : "\n\n" + a.description());
            out.add(new Service.Item(e.label(), kind, a.label() + " · " + a.category().russian, doc, true, 0, snippet(e), false));
        }
        for (Data.Event ev : data.events().values()) {
            String doc = "**" + ev.label() + "** — " + ev.group() + (ev.description().isEmpty() ? "" : "\n\n" + ev.description());
            out.add(new Service.Item(ev.id(), "event", ev.label() + " · " + ev.group(), doc, false, 0));
        }
        for (Data.GameValue v : data.gameValues().values()) {
            String doc = "**" + v.label() + "** — " + v.group() + ", возвращает " + v.returns().toLowerCase(Locale.ROOT)
                    + (v.description().isEmpty() ? "" : "\n\n" + v.description());
            out.add(new Service.Item("value::" + v.id(), "value", v.label() + " → " + v.returns(), doc, false, 0));
        }
        vocabulary = List.copyOf(out);
        return vocabulary;
    }

    private static String snippet(Entry e) {
        StringBuilder out = new StringBuilder(e.label()).append('(');
        int n = 0;
        for (String arg : e.order()) {
            if (arg.equals(e.firstResult()) || e.lambda().contains(arg) || e.setting(arg)) continue;
            if (e.action().arg(arg) == null) continue;
            if (n > 0) out.append(", ");
            n++;
            out.append("${").append(n).append(':').append(arg).append('}');
        }
        out.append(')');
        return e.container() ? out + BODY : out + "$0";
    }

    Service.Signature signature(Entry e, int active, boolean skipResult, boolean method) {
        Data.Action a = e.action();
        StringBuilder label = new StringBuilder(method ? e.name() : e.label()).append('(');
        List<Service.Span> spans = new ArrayList<>();
        for (String arg : e.order()) {
            if (skipResult && arg.equals(e.firstResult())) continue;
            if (method && arg.equals(e.origin())) continue;
            if (!spans.isEmpty()) label.append(", ");
            String shown = shown(e, arg);
            spans.add(new Service.Span(label.length(), label.length() + shown.length()));
            label.append(shown);
        }
        label.append(')');
        String doc = a == null ? null : "**" + a.label() + "** — " + a.category().russian
                + (a.description().isEmpty() ? "" : "\n\n" + a.description());
        return new Service.Signature(label.toString(), spans, Math.min(active, Math.max(0, spans.size() - 1)), doc);
    }

    private static String shown(Entry e, String arg) {
        if (e.action() == null) return arg;
        Data.Setting s = e.action().setting(arg);
        if (s != null) {
            List<String> options = new ArrayList<>(s.options().keySet());
            String listed = String.join("|", options.size() > 6 ? options.subList(0, 6) : options).toUpperCase(Locale.ROOT);
            return arg + ": " + listed + (options.size() > 6 ? "|…" : "");
        }
        Data.Arg x = e.action().arg(arg);
        if (x == null) return arg;
        return arg + ": " + (x.label().isEmpty() ? x.kind() : x.label()) + (x.list() ? "…" : "");
    }
}
