package xero.compile;

import xero.data.Data;
import xero.json.Json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Wrap {
    public static final int WIDEST = 67;

    private static final Set<String> LEAVES = Set.of("control_return_function", "control_end_thread",
            "control_break_label", "control_call_exception");
    private static final Set<String> LOOP_LEAVES = Set.of("control_stop_repeat", "control_skip_iteration");
    private static final String ELSE = "else";
    private static final String LOOP = "repeat_";
    private static final String CONDITION = "if_";
    private static final String ARGUMENTS = "args";

    private record Unit(int from, int to, int width, boolean leaves, Map<String, Integer> mentions, Set<String> writes) {}

    private record Run(int from, int to, int width, List<String> passed) {}

    private final Data data;
    private final Set<String> names;
    private final List<Map<String, Object>> added = new ArrayList<>();
    private List<Object> root;
    private String host;
    private Set<String> params;
    private Set<String> lineVariables;
    private Map<String, Integer> everywhere;

    Wrap(Data data, Collection<String> names) {
        this.data = data;
        this.names = new HashSet<>(names);
    }

    public static String fitted(String module, Data data) {
        Map<String, Object> parsed = Json.object(Json.parse(module));
        Map<String, Object> fitted = fitted(parsed, data);
        return fitted == parsed ? module : Json.write(fitted);
    }

    public static Map<String, Object> fitted(Map<String, Object> module, Data data) {
        boolean wide = false;
        for (Object raw : Json.array(module.get("handlers"))) wide |= Delivery.width(Json.object(raw)) > WIDEST;
        if (!wide) return module;
        Map<String, Object> copy = Json.object(Json.parse(Json.write(module)));
        List<Object> handlers = Json.array(copy.get("handlers"));
        Set<String> taken = new HashSet<>();
        int next = 0;
        for (Object raw : handlers) {
            Map<String, Object> handler = Json.object(raw);
            if (handler.get("name") instanceof String name) taken.add(name);
            if (handler.get("position") instanceof Json.Num position) next = Math.max(next, position.intValue() + 1);
        }
        Wrap wrap = new Wrap(data, taken);
        for (Object raw : handlers) wrap.line(Json.object(raw));
        for (Map<String, Object> line : wrap.added) {
            line.put("position", next++);
            handlers.add(line);
        }
        return copy;
    }

    public static Map<String, Integer> tooWide(String module) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Object raw : Json.array(Json.object(Json.parse(module)).get("handlers"))) {
            Map<String, Object> handler = Json.object(raw);
            int width = Delivery.width(handler);
            if (width > WIDEST) out.put(String.valueOf(handler.containsKey("name") ? handler.get("name") : handler.get("event")), width);
        }
        return out;
    }

    static boolean carries(Map<String, Object> handler, Data data) {
        return Delivery.width(handler) <= WIDEST || new Wrap(data, Set.of()).line(Json.object(Json.parse(Json.write(handler))));
    }

    List<Map<String, Object>> added() {
        return added;
    }

    boolean line(Map<String, Object> handler) {
        if (!(handler.get("operations") instanceof List<?>)) return true;
        List<Object> ops = Json.array(handler.get("operations"));
        if (width(ops) <= WIDEST) return true;
        root = ops;
        host = String.valueOf(handler.containsKey("name") ? handler.get("name") : handler.get("event"));
        params = parameters(handler);
        lineVariables = new LinkedHashSet<>(params);
        lineVariables(ops, lineVariables);
        everywhere = null;
        return fit(ops, WIDEST, false, Map.of());
    }

    private boolean fit(List<Object> ops, int room, boolean looped, Map<String, Integer> later) {
        while (width(ops) > room) {
            Run run = bestRun(ops, looped, later);
            if (run != null) move(ops, run);
            else if (!shrink(ops, width(ops) - room, looped, later)) return false;
        }
        return true;
    }

    private boolean shrink(List<Object> ops, int excess, boolean looped, Map<String, Integer> later) {
        List<Integer> bodies = new ArrayList<>();
        for (int at = 0; at < ops.size(); at++) if (opens(Json.object(ops.get(at)))) bodies.add(at);
        bodies.sort((a, b) -> width(body(Json.object(ops.get(b)))) - width(body(Json.object(ops.get(a)))));
        for (int at : bodies) {
            Map<String, Object> op = Json.object(ops.get(at));
            List<Object> inside = body(op);
            int before = width(inside);
            Map<String, Integer> after = joined(later, mentions(ops.subList(at + 1, ops.size())));
            fit(inside, Math.max(0, before - excess), looped || action(op).startsWith(LOOP), after);
            if (width(inside) < before) return true;
        }
        return false;
    }

    private static Map<String, Integer> joined(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> out = new HashMap<>(a);
        b.forEach((name, count) -> out.merge(name, count, Integer::sum));
        return out;
    }

    private Run bestRun(List<Object> ops, boolean looped, Map<String, Integer> later) {
        List<Unit> units = units(ops);
        if (everywhere == null) everywhere = mentions(root);
        List<Map<String, Integer>> after = new ArrayList<>(Collections.nCopies(units.size() + 1, later));
        for (int i = units.size() - 1; i >= 0; i--) after.set(i, joined(after.get(i + 1), units.get(i).mentions()));
        Run best = null;
        for (int first = 0; first < units.size(); first++) {
            int width = 0;
            Map<String, Integer> inside = new HashMap<>();
            Set<String> written = new HashSet<>();
            for (int last = first; last < units.size(); last++) {
                Unit unit = units.get(last);
                width += unit.width();
                if (unit.leaves() || width > WIDEST) break;
                unit.mentions().forEach((name, count) -> inside.merge(name, count, Integer::sum));
                written.addAll(unit.writes());
                if (width < 2 || best != null && width <= best.width()) continue;
                List<String> passed = passed(inside, written, looped, after.get(last + 1));
                if (passed != null) best = new Run(units.get(first).from(), unit.to(), width, passed);
            }
        }
        return best;
    }

    private List<String> passed(Map<String, Integer> inside, Set<String> written, boolean looped, Map<String, Integer> after) {
        for (String name : written) {
            boolean lost = looped ? usedOutside(name, inside) || !name.startsWith(Wire.TEMP) : after.getOrDefault(name, 0) > 0;
            if (lost) return null;
        }
        List<String> out = new ArrayList<>();
        for (String name : inside.keySet()) if (usedOutside(name, inside)) out.add(name);
        Collections.sort(out);
        return out.size() > Wire.PARAM_SLOTS ? null : out;
    }

    private boolean usedOutside(String name, Map<String, Integer> inside) {
        return params.contains(name) || everywhere.getOrDefault(name, 0) > inside.getOrDefault(name, 0);
    }

    private void move(List<Object> ops, Run run) {
        String name = freshName();
        List<Object> part = ops.subList(run.from(), run.to());
        added.add(function(name, run.passed(), new ArrayList<>(part)));
        part.clear();
        ops.add(run.from(), call(name, run.passed()));
        everywhere = null;
    }

    private String freshName() {
        for (int n = 1; ; n++) {
            String name = host + "." + n;
            if (names.add(name)) return name;
        }
    }

    private static Map<String, Object> call(String name, List<String> passed) {
        List<Object> values = new ArrayList<>();
        values.add(Context.entry("function_name", Context.text(name, "legacy")));
        if (!passed.isEmpty()) {
            Map<String, Object> args = new LinkedHashMap<>();
            for (String p : passed)
                args.put(Json.write(Context.text(p, "plain")), Context.obj("type", "variable", "variable", p, "scope", Declarations.LINE));
            values.add(Context.entry("args", Context.obj("type", "map", "values", args)));
        }
        return Context.obj("action", "call_function", "values", values);
    }

    private static Map<String, Object> function(String name, List<String> passed, List<Object> body) {
        Wire.ParamChest chest = new Wire.ParamChest(passed.size());
        List<Object> declared = new ArrayList<>();
        for (String p : passed) declared.add(Wire.parameter(p, "any", true, "{}", chest.take(), Wire.NO_TRANSLATIONS));
        List<Object> values = new ArrayList<>();
        if (!declared.isEmpty()) values.add(Context.entry("parameters", Context.array(declared)));
        values.add(Context.entry("is_hidden", Context.enumValue("TRUE")));
        return Context.obj("type", "function", "name", name, "values", values, "operations", body);
    }

    private List<Unit> units(List<Object> ops) {
        List<Unit> out = new ArrayList<>();
        for (int from = 0; from < ops.size(); ) {
            int to = from + 1;
            while (to < ops.size() && ELSE.equals(action(Json.object(ops.get(to))))) to++;
            List<Object> part = ops.subList(from, to);
            Set<String> writes = new HashSet<>();
            boolean leaves = false;
            for (Object raw : part) {
                writes(raw, writes);
                leaves |= leaves(Json.object(raw), false);
            }
            out.add(new Unit(from, to, width(part), leaves, mentions(part), writes));
            from = to;
        }
        return out;
    }

    private static boolean leaves(Map<String, Object> op, boolean inLoop) {
        String id = action(op);
        if (LEAVES.contains(id) || !inLoop && LOOP_LEAVES.contains(id)) return true;
        boolean loop = inLoop || id.startsWith(LOOP);
        for (Object child : body(op)) if (leaves(Json.object(child), loop)) return true;
        return false;
    }

    private void writes(Object raw, Set<String> out) {
        Map<String, Object> op = Json.object(raw);
        Data.Action action = data.actions().get(action(op));
        if (op.get("values") instanceof List<?> values)
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> cell) || ARGUMENTS.equals(cell.get("name"))) continue;
                Data.Arg arg = action == null ? null : action.arg(String.valueOf(cell.get("name")));
                if (arg == null || Data.VARIABLE.equals(arg.kind())) lineVariables(cell.get("value"), out);
            }
        for (Object child : body(op)) writes(child, out);
    }

    private static void lineVariables(Object value, Set<String> out) {
        if (value instanceof List<?> list) {
            for (Object item : list) lineVariables(item, out);
        } else if (value instanceof Map<?, ?> map) {
            if ("variable".equals(map.get("type")) && Declarations.LINE.equals(map.get("scope")) && map.get("variable") instanceof String name)
                out.add(name);
            for (Object item : map.values()) lineVariables(item, out);
        }
    }

    private static Set<String> parameters(Map<String, Object> handler) {
        Set<String> out = new LinkedHashSet<>();
        if (!(handler.get("values") instanceof List<?> values)) return out;
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> cell) || !"parameters".equals(cell.get("name"))) continue;
            if (!(cell.get("value") instanceof Map<?, ?> array) || !(array.get("values") instanceof List<?> declared)) continue;
            for (Object parameter : declared)
                if (parameter instanceof Map<?, ?> p && p.get("name") instanceof String name) out.add(name);
        }
        return out;
    }

    private Map<String, Integer> mentions(Object node) {
        String text = Json.write(node);
        Map<String, Integer> out = new HashMap<>();
        for (int at = 0; at < text.length(); at++) {
            if (!wordChar(text.charAt(at))) continue;
            int end = at;
            while (end < text.length() && wordChar(text.charAt(end))) end++;
            String word = text.substring(at, end);
            if (lineVariables.contains(word)) out.merge(word, 1, Integer::sum);
            at = end;
        }
        for (String name : lineVariables) {
            if (isWord(name)) continue;
            String spelled = Json.write(name);
            int count = occurrences(text, spelled.substring(1, spelled.length() - 1));
            if (count > 0) out.put(name, count);
        }
        return out;
    }

    private static boolean isWord(String name) {
        for (int i = 0; i < name.length(); i++) if (!wordChar(name.charAt(i))) return false;
        return !name.isEmpty();
    }

    private static int occurrences(String text, String word) {
        int count = 0;
        for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + 1)) {
            int end = at + word.length();
            boolean starts = at == 0 || !wordChar(text.charAt(at - 1));
            boolean ends = end == text.length() || !wordChar(text.charAt(end));
            if (starts && ends) count++;
        }
        return count;
    }

    private static boolean wordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean opens(Map<String, Object> op) {
        String id = action(op);
        return op.containsKey("operations") && (id.startsWith(LOOP) || id.startsWith(CONDITION) || id.equals(ELSE));
    }

    private static String action(Map<String, Object> op) {
        return String.valueOf(op.get("action"));
    }

    private static List<Object> body(Map<String, Object> op) {
        return op.get("operations") instanceof List<?> ? Json.array(op.get("operations")) : List.of();
    }

    private static int width(List<Object> ops) {
        return Delivery.width(Context.obj("operations", ops));
    }
}
