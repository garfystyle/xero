package xero.compile;

import xero.data.Data;
import xero.json.Json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class Delivery {
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    public enum Kind {
        EVENT("событие"), FUNCTION("функция"), PROCESS("процесс"), OTHER("строка");

        public final String russian;

        Kind(String russian) { this.russian = russian; }
    }

    public record Line(Kind kind, String name, int position, Map<String, Object> json) {
        public String key() { return kind.name().toLowerCase(Locale.ROOT) + ":" + name; }

        public String title() {
            String where = position >= 0 ? " (строка " + position + ")" : "";
            return switch (kind) {
                case EVENT -> "событие " + name + where;
                case FUNCTION, PROCESS -> kind.russian + " «" + name + "»" + where;
                case OTHER -> "строка " + name + where;
            };
        }
    }

    public record Clash(Line project, Line world, boolean doubled) {}

    static final int TEMPLATE_WIDTH = 43;

    static int width(Map<String, Object> block) {
        int width = 0;
        if (block.get("operations") instanceof List<?> operations)
            for (Object raw : operations) {
                Map<String, Object> operation = Json.object(raw);
                width += operation.containsKey("operations") ? 2 + width(operation) : 1;
            }
        return width;
    }

    public record Plan(List<Line> same, List<Line> changed, List<Line> added, List<Line> missing, List<Line> kept, List<Line> was) {
        public boolean lineByLine(List<Line> world, int limit) {
            if (!missing.isEmpty() || changed.size() + added.size() > limit) return false;
            for (int i = 0; i < changed.size(); i++) {
                Line before = was.get(i);
                if (changed.get(i).position() != before.position() || before.json() == null) return false;
                if (tooWide(changed.get(i)) || tooWide(before)) return false;
            }
            for (Line a : added) {
                if (a.position() < 0 || tooWide(a)) return false;
                for (Line w : world) if (w.position() == a.position()) return false;
            }
            return true;
        }

        private static boolean tooWide(Line line) {
            return width(line.json()) > TEMPLATE_WIDTH;
        }

        public List<Object> lines() {
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < changed.size(); i++) out.add(row(changed.get(i), was.get(i)));
            for (Line a : added) out.add(row(a, null));
            return out;
        }

        private static Map<String, Object> row(Line line, Line before) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("position", line.position());
            o.put("handler", line.json());
            if (before != null) o.put("was", before.json());
            return o;
        }

        public Map<String, Object> module(List<Line> project) {
            List<Object> handlers = new ArrayList<>();
            for (Line p : project) {
                int i = indexOf(same, p);
                handlers.add(i >= 0 ? kept.get(i).json() : p.json());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("handlers", handlers);
            return out;
        }

        private static int indexOf(List<Line> lines, Line line) {
            for (int i = 0; i < lines.size(); i++) if (lines.get(i) == line) return i;
            return -1;
        }
    }

    public static List<Line> lines(Map<String, Object> module) {
        List<Line> out = new ArrayList<>();
        Object list = module.get("handlers");
        if (!(list instanceof List<?> handlers)) return out;
        for (int i = 0; i < handlers.size(); i++) {
            if (!(handlers.get(i) instanceof Map<?, ?> raw)) {
                out.add(new Line(Kind.OTHER, "#" + i, -1, null));
                continue;
            }
            Map<String, Object> h = Json.object(raw);
            int position = h.get("position") instanceof Json.Num n ? n.intValue()
                    : h.get("position") instanceof Number n ? n.intValue() : -1;
            Object type = h.get("type");
            Kind kind = "event".equals(type) && h.get("event") instanceof String ? Kind.EVENT
                    : "function".equals(type) && h.get("name") instanceof String ? Kind.FUNCTION
                    : "process".equals(type) && h.get("name") instanceof String ? Kind.PROCESS
                    : Kind.OTHER;
            String name = switch (kind) {
                case EVENT -> (String) h.get("event");
                case FUNCTION, PROCESS -> (String) h.get("name");
                case OTHER -> "#" + i;
            };
            out.add(new Line(kind, name, position, h));
        }
        out.sort(Comparator.comparingInt(l -> l.position() < 0 ? Integer.MAX_VALUE : l.position()));
        return out;
    }

    public static List<Clash> clashes(List<Line> world, List<Line> project) {
        List<Clash> out = new ArrayList<>();
        for (Line p : project) {
            if (p.kind() == Kind.OTHER) continue;
            for (Line w : world) {
                if (!w.key().equals(p.key())) continue;
                out.add(new Clash(p, w, p.kind() != Kind.EVENT));
                break;
            }
        }
        return out;
    }

    public static Plan plan(Data data, List<Line> world, List<Line> project) {
        List<Line> same = new ArrayList<>(), changed = new ArrayList<>(), added = new ArrayList<>(), missing = new ArrayList<>();
        List<Line> kept = new ArrayList<>(), was = new ArrayList<>();
        Map<String, List<Line>> left = new LinkedHashMap<>();
        for (Line w : world) left.computeIfAbsent(w.key(), k -> new ArrayList<>()).add(w);
        List<Line> rest = new ArrayList<>();
        for (Line p : project) {
            List<Line> candidates = left.get(p.key());
            Line exact = null;
            if (candidates != null)
                for (Line w : candidates)
                    if (w.position() == p.position() && equal(data, w, p)) { exact = w; break; }
            if (exact != null) {
                candidates.remove(exact);
                same.add(p);
                kept.add(exact);
            } else {
                rest.add(p);
            }
        }
        for (Line p : rest) {
            List<Line> candidates = left.get(p.key());
            if (candidates == null || candidates.isEmpty()) {
                added.add(p);
                continue;
            }
            Line pick = candidates.get(0);
            for (Line w : candidates) if (equal(data, w, p)) { pick = w; break; }
            candidates.remove(pick);
            changed.add(p);
            was.add(pick);
        }
        for (List<Line> l : left.values()) missing.addAll(l);
        missing.sort(Comparator.comparingInt(Line::position));
        return new Plan(same, changed, added, missing, kept, was);
    }

    private static boolean equal(Data data, Line a, Line b) {
        return a.json() != null && b.json() != null && Objects.equals(normal(data, a.json()), normal(data, b.json()));
    }

    public static Object normal(Data data, Map<String, Object> handler) {
        Map<String, Object> copy = new TreeMap<>();
        for (Map.Entry<String, Object> e : handler.entrySet()) {
            if (e.getKey().equals("position")) continue;
            if (e.getKey().equals("values") && e.getValue() instanceof List<?> l)
                copy.put("values", named(data, null, Wire.tidyDeclaration(Json.array(l))));
            else if (e.getKey().equals("operations") && e.getValue() instanceof List<?> l) copy.put("operations", ops(data, Json.array(l)));
            else copy.put(e.getKey(), plain(e.getValue()));
        }
        if (!handler.containsKey("event")) copy.putIfAbsent("values", new TreeMap<>());
        return copy;
    }

    private static List<Object> ops(Data data, List<Object> ops) {
        List<Object> out = new ArrayList<>();
        for (Object o : ops) {
            if (!(o instanceof Map<?, ?> raw)) {
                out.add(plain(o));
                continue;
            }
            Map<String, Object> op = Json.object(raw);
            String owner = op.get("action") instanceof String s ? s : null;
            if (op.get("conditional") instanceof Map<?, ?> c && c.get("action") instanceof String s) owner = s;
            Map<String, Object> copy = new TreeMap<>();
            for (Map.Entry<String, Object> e : op.entrySet()) {
                if (e.getKey().equals("values") && e.getValue() instanceof List<?> l)
                    copy.put("values", named(data, owner, Json.array(l)));
                else if (e.getKey().equals("operations") && e.getValue() instanceof List<?> l)
                    copy.put("operations", ops(data, Json.array(l)));
                else copy.put(e.getKey(), plain(e.getValue()));
            }
            out.add(OLD_MAP_READ.equals(copy.get("action")) ? newMapRead(copy) : copy);
        }
        return out;
    }

    private static final String OLD_MAP_READ = "set_variable_get_map_value";
    private static final String NEW_MAP_READ = "set_variable_get_map_value_single";

    private static Map<String, Object> newMapRead(Map<String, Object> op) {
        if (!(op.get("values") instanceof Map<?, ?> raw)) return op;
        Map<String, Object> values = new TreeMap<>(Json.object(raw));
        for (String plural : List.of("variable", "key")) {
            if (!(values.get(plural) instanceof Map<?, ?> m) || !(m.get("values") instanceof List<?> cells) || cells.size() != 1) return op;
            values.put(plural, cells.get(0));
        }
        Map<String, Object> copy = new TreeMap<>(op);
        copy.put("action", NEW_MAP_READ);
        copy.put("values", values);
        return copy;
    }

    private static Object named(Data data, String owner, List<Object> values) {
        Data.Action a = owner == null ? null : data.actions().get(owner);
        Map<String, Object> out = new TreeMap<>();
        List<Object> odd = new ArrayList<>();
        for (Object raw : a == null ? values : Wire.tidy(a, values)) {
            if (!(raw instanceof Map<?, ?> m) || !(m.get("name") instanceof String name)) {
                odd.add(plain(raw));
                continue;
            }
            Object v = m.get("value");
            if (a != null && a.plural(name) && !(v instanceof Map<?, ?> x && "array".equals(x.get("type"))))
                v = Context.obj("type", "array", "values", List.of(v));
            out.put(name, name.equals("parameters") && owner == null ? parameters(v) : plain(v));
        }
        if (!odd.isEmpty()) out.put("", odd);
        return out;
    }

    private static Object parameters(Object v) {
        Object p = plain(v);
        if (p instanceof Map<?, ?> m && m.get("values") instanceof List<?> list)
            for (Object x : list)
                if (x instanceof Map<?, ?> param) {
                    param.remove("slot");
                    param.remove("description_slot");
                    param.remove("slots");
                    param.remove("description_slots");
                    if (param.get("default_value") instanceof String fallback) Json.object(param).put("default_value", canonical(fallback));
                }
        return p;
    }

    private static String canonical(String json) {
        try {
            return Json.write(plain(Json.parse(json)));
        } catch (Json.Error | IllegalArgumentException e) {
            return json;
        }
    }

    static Object plain(Object v) {
        if (v instanceof Json.Num n) {
            try {
                return new BigDecimal(n.text()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return n.text();
            }
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            new TreeMap<>(Json.object(m)).forEach((k, x) -> out.put(k, plain(x)));
            if ("text".equals(out.get("type")) && "plain".equals(out.get("parsing")) && out.get("text") instanceof String t
                    && Wire.sameInAnyMarkup(t)) out.put("parsing", "legacy");
            if ("item".equals(out.get("type")) && out.get("item") instanceof String item) out.put("item", Items.canonical(item));
            if ("number".equals(out.get("type")) && out.get("number") instanceof String n)
                out.put("number", Wire.plainNumber(n) ? new BigDecimal(n).stripTrailingZeros().toPlainString() : MathPrint.canonical(n));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object x : l) out.add(plain(x));
            return out;
        }
        return v;
    }

    private Delivery() {}
}
