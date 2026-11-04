package xero.dialect.justice;

import xero.compile.Items;
import xero.compile.Nbt;
import xero.data.Data;
import xero.json.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Printer {
    private static final int WIDTH = 100;
    private static final String STEP = "    ";
    private static final Set<String> SCOPES = Set.of("local", "game", "save", "line");
    private static final Set<String> UNSAFE = Set.of("_", "not", "raw");
    private static final Map<String, String> PARSINGS = Map.of("plain", "", "legacy", "l", "minimessage", "m", "json", "j");
    private static final Set<String> HANDLER_KEYS = Set.of("type", "name", "event", "values", "position", "operations");
    private static final Set<String> OPERATION_KEYS = Set.of("action", "conditional", "is_inverted", "selection", "values", "operations");
    private static final Set<String> KNOWN_TYPES = Set.of("text", "number", "variable", "game_value", "enum", "location", "vector",
            "item", "block", "sound", "potion", "particle", "array", "map", "raw");

    private final Data data;
    private final List<String> notes;
    private final StringBuilder out = new StringBuilder();

    private Printer(Data data, List<String> notes) {
        this.data = data;
        this.notes = notes;
    }

    static String print(Data data, Map<String, Object> module, List<String> notes) {
        return new Printer(data, notes).module(module);
    }

    private String module(Map<String, Object> module) {
        List<Map<String, Object>> handlers = new ArrayList<>();
        if (module.get("handlers") instanceof List<?> list) {
            for (Object h : list) {
                if (h instanceof Map<?, ?>) handlers.add(Json.object(h));
                else notes.add("строка пропущена: " + Json.write(h));
            }
        } else {
            notes.add("в модуле нет поля handlers");
        }
        handlers.sort(Comparator.comparingInt(Printer::position));
        int expected = 0;
        boolean first = true;
        for (Map<String, Object> h : handlers) {
            if (!first) out.append('\n');
            first = false;
            int at = position(h);
            if (at == Integer.MAX_VALUE) {
                notes.add("у строки нет номера — встанет на " + expected);
                at = expected;
            }
            if (at != expected) out.append("@line(").append(at).append(")\n");
            expected = at + 1;
            handler(h);
        }
        return out.toString();
    }

    private static int position(Map<String, Object> h) {
        return h.get("position") instanceof Json.Num n && n.text().matches("-?\\d{1,9}") ? Integer.parseInt(n.text()) : Integer.MAX_VALUE;
    }

    private void handler(Map<String, Object> h) {
        Object type = h.get("type");
        boolean event = "event".equals(type) && h.get("event") instanceof String && !h.containsKey("values") && !h.containsKey("name");
        boolean function = ("function".equals(type) || "process".equals(type)) && h.get("name") instanceof String && !h.containsKey("event");
        List<Object> values = function ? declaration(h.get("values")) : List.of();
        if (!event && !function || values == null || !h.keySet().stream().allMatch(HANDLER_KEYS::contains)
                || !(h.get("operations") instanceof List<?> ops)) {
            Map<String, Object> copy = new LinkedHashMap<>(h);
            copy.remove("position");
            out.append(raw(copy)).append('\n');
            return;
        }
        String name = event ? (String) h.get("event") : (String) h.get("name");
        String head = type + " " + identifier(name);
        out.append(values.isEmpty() ? head : wrap(head + "(", arguments(values, 0), ")", 0)).append(" {\n");
        operations(Json.array(ops), 1);
        out.append("}\n");
    }

    private static List<Object> declaration(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) return null;
        List<Object> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m) || m.size() != 2 || !(m.get("name") instanceof String name) || !m.containsKey("value")) return null;
            Object v = m.get("value");
            if (hole(v) || name.equals("icon") && v instanceof Map<?, ?> i && Items.EMPTY.equals(i.get("item"))
                    || name.equals("is_hidden") && v instanceof Map<?, ?> e && "FALSE".equals(e.get("enum"))) continue;
            if (name.equals("description")) {
                Object cut = trimmed(v);
                if (cut != null) out.add(entry(name, cut));
            } else {
                out.add(o);
            }
        }
        return out;
    }

    private static Map<String, Object> entry(String name, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        return m;
    }

    private static boolean hole(Object v) {
        return v instanceof Map<?, ?> m && m.isEmpty();
    }

    private static Object trimmed(Object v) {
        if (!(v instanceof Map<?, ?> m) || m.size() != 2 || !"array".equals(m.get("type")) || !(m.get("values") instanceof List<?> l)) return v;
        List<Object> cells = new ArrayList<>(l);
        while (!cells.isEmpty() && hole(cells.get(cells.size() - 1))) cells.remove(cells.size() - 1);
        if (cells.isEmpty()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "array");
        out.put("values", cells);
        return out;
    }

    private void operations(List<Object> ops, int level) {
        for (Object o : ops) {
            out.append(STEP.repeat(level));
            if (!(o instanceof Map<?, ?> raw)) {
                notes.add("не объект в списке блоков");
                out.append("raw{ ").append(quoted(Json.write(o), '"')).append(" };\n");
                continue;
            }
            Map<String, Object> op = Json.object(raw);
            if (plainOperation(op)) {
                head(op, level);
            } else {
                Map<String, Object> copy = new LinkedHashMap<>(op);
                if (op.get("operations") instanceof List<?>) copy.remove("operations");
                out.append(raw(copy));
            }
            if (op.get("operations") instanceof List<?> body) {
                out.append(" {\n");
                operations(Json.array(body), level + 1);
                out.append(STEP.repeat(level)).append("}\n");
            } else {
                out.append(";\n");
            }
        }
    }

    private static boolean plainOperation(Map<String, Object> op) {
        if (!(op.get("action") instanceof String) || !OPERATION_KEYS.containsAll(op.keySet())) return false;
        if (op.containsKey("is_inverted") && !(op.get("is_inverted") instanceof Boolean)) return false;
        if (op.containsKey("operations") && !(op.get("operations") instanceof List<?>)) return false;
        boolean selected = op.get("selection") instanceof Map<?, ?> m && m.size() == 1 && m.get("type") instanceof String;
        if (op.containsKey("selection") && !selected) return false;
        boolean delegated = op.get("conditional") instanceof Map<?, ?> m && m.get("action") instanceof String
                && (m.size() == 1 || m.size() == 2 && m.get("is_inverted") instanceof Boolean);
        if (op.containsKey("conditional") && !delegated) return false;
        if (op.containsKey("values") && !(op.get("values") instanceof List<?> values && named(values))) return false;
        return true;
    }

    private static boolean named(List<?> values) {
        for (Object o : values)
            if (!(o instanceof Map<?, ?> m) || m.size() != 2 || !(m.get("name") instanceof String) || !m.containsKey("value")) return false;
        return true;
    }

    private void head(Map<String, Object> op, int level) {
        if (Boolean.TRUE.equals(op.get("is_inverted"))) out.append("not ");
        out.append(identifier((String) op.get("action")));
        Object owner = op.get("action");
        if (op.get("conditional") instanceof Map<?, ?> c) {
            owner = c.get("action");
            out.append(Boolean.TRUE.equals(c.get("is_inverted")) ? " not " : " ").append(identifier((String) owner));
        }
        if (op.get("selection") instanceof Map<?, ?> s) out.append('<').append(identifier((String) s.get("type"))).append('>');
        List<Object> values = op.get("values") instanceof List<?> l ? tidy(data.actions().get(owner), Json.array(l)) : List.of();
        if (!values.isEmpty()) out.append(wrap("(", arguments(values, level), ")", level));
    }

    private static List<Object> tidy(Data.Action action, List<Object> values) {
        if (action == null) return values;
        List<Object> out = new ArrayList<>();
        for (Object raw : values) {
            Map<String, Object> m = Json.object(raw);
            String name = (String) m.get("name");
            Object v = m.get("value");
            if (hole(v)) continue;
            Object cut = action.plural(name) ? trimmed(v) : v;
            if (cut != null) out.add(cut == v ? raw : entry(name, cut));
        }
        return out;
    }

    private List<String> arguments(List<Object> values, int level) {
        List<String> parts = new ArrayList<>();
        for (Object raw : values) {
            Map<String, Object> m = Json.object(raw);
            parts.add(identifier((String) m.get("name")) + "=" + value(m.get("value"), level + 1));
        }
        return parts;
    }

    private String value(Object v, int level) {
        if (!(v instanceof Map<?, ?> raw)) return raw(v);
        Map<String, Object> m = Json.object(raw);
        if (m.isEmpty()) return "_";
        if ("array".equals(m.get("type"))) {
            String list = array(m, level);
            if (list != null) return list;
            return raw(v);
        }
        if ("map".equals(m.get("type"))) {
            String map = map(m, level);
            if (map != null) return map;
            return raw(v);
        }
        if ("item".equals(m.get("type"))) {
            String item = item(m);
            if (item != null) return item;
            return raw(v);
        }
        String pretty;
        try {
            pretty = pretty(m);
        } catch (RuntimeException e) {
            pretty = null;
        }
        return pretty != null && same(m, Parser.value(pretty, data)) ? pretty : raw(v);
    }

    private String pretty(Map<String, Object> m) {
        if (!(m.get("type") instanceof String type)) return null;
        return switch (type) {
            case "text" -> text(m);
            case "number" -> number(m);
            case "variable" -> variable(m);
            case "game_value" -> gameValue(m);
            case "enum" -> enumeration(m);
            case "location" -> location(m);
            case "vector" -> "vector{ " + num(m.get("x")) + ", " + num(m.get("y")) + ", " + num(m.get("z")) + " }";
            case "block" -> m.containsKey("block") ? "block{ " + quoted(bare((String) m.get("block")), '"') + " }" : "block{}";
            case "sound" -> sound(m);
            case "potion" -> potion(m);
            case "particle" -> particle(m);
            default -> typed(type, m);
        };
    }

    private static String bare(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static String text(Map<String, Object> m) {
        String prefix = PARSINGS.get(String.valueOf(m.get("parsing")));
        if (prefix == null || !(m.get("text") instanceof String s)) return null;
        return prefix + quoted(s, '"');
    }

    private String number(Map<String, Object> m) {
        if (m.get("number") instanceof Json.Num n) return n.text();
        if (!(m.get("number") instanceof String s)) return null;
        return s.startsWith("%") && same(m, Parser.value(s, data)) ? s : "number{ " + quoted(s, '"') + " }";
    }

    private static String variable(Map<String, Object> m) {
        if (!(m.get("variable") instanceof String name) || !(m.get("scope") instanceof String scope) || !SCOPES.contains(scope)) return null;
        return switch (scope) {
            case "local" -> identifier(name);
            case "game" -> "g" + quoted(name, '`');
            case "save" -> "s" + quoted(name, '`');
            default -> "line" + quoted(name, '`');
        };
    }

    private static String gameValue(Map<String, Object> m) {
        if (!(m.get("game_value") instanceof String id) || !(m.get("selection") instanceof String selection)) return null;
        if (selection.equals("null")) return "<>" + identifier(id);
        String opening = "{\"type\":\"";
        if (!selection.startsWith(opening) || !selection.endsWith("\"}")) return null;
        String target = selection.substring(opening.length(), selection.length() - 2);
        return "<" + identifier(target) + ">" + identifier(id);
    }

    private static String enumeration(Map<String, Object> m) {
        if (!(m.get("enum") instanceof String option)) return null;
        if (!m.containsKey("variable")) return quoted(option, '\'');
        String bound = variable(m);
        return bound == null ? null : "enum{ " + quoted(option, '\'') + ", " + bound + " }";
    }

    private static String num(Object v) {
        if (v instanceof Json.Num n) return n.text();
        throw new IllegalArgumentException("не число");
    }

    private static boolean zero(Object v) {
        return v instanceof Json.Num n && n.value() == 0;
    }

    private static boolean equal(Object v, double wanted) {
        return v instanceof Json.Num n && n.value() == wanted;
    }

    private static String location(Map<String, Object> m) {
        String head = "location{ " + num(m.get("x")) + ", " + num(m.get("y")) + ", " + num(m.get("z"));
        if (zero(m.get("yaw")) && zero(m.get("pitch"))) return head + " }";
        return head + ", " + num(m.get("yaw")) + ", " + num(m.get("pitch")) + " }";
    }

    private String item(Map<String, Object> m) {
        if (m.size() != 2 || !(m.get("item") instanceof String base64)) return null;
        if (base64.equals(Items.EMPTY)) return "item{}";
        Nbt.Compound root = Stacks.decode(base64);
        if (root != null) {
            try {
                String snbt = Nbt.print(Stacks.shown(root));
                if (Nbt.parse(snbt) instanceof Nbt.Compound back && Nbt.same(root, Stacks.full(back))) return "item" + snbt;
            } catch (Nbt.Error | IllegalArgumentException e) {
                notes.add("предмет записан как есть: " + e.getMessage());
            }
        }
        return base64.indexOf('"') < 0 && base64.indexOf('\\') < 0 ? "item{ \"" + base64 + "\" }" : null;
    }

    private static String sound(Map<String, Object> m) {
        List<String> parts = new ArrayList<>();
        parts.add(quoted(bare((String) m.get("sound")), '"'));
        if (!equal(m.get("volume"), 1)) parts.add("volume=" + num(m.get("volume")));
        if (!equal(m.get("pitch"), 1)) parts.add("pitch=" + num(m.get("pitch")));
        if (!"MASTER".equals(m.get("source"))) parts.add("source=" + quoted((String) m.get("source"), '"'));
        if (!"".equals(m.get("variation"))) parts.add("variant=" + quoted((String) m.get("variation"), '"'));
        return "sound{ " + String.join(", ", parts) + " }";
    }

    private static String potion(Map<String, Object> m) {
        List<String> parts = new ArrayList<>();
        parts.add(quoted(bare((String) m.get("potion")), '"'));
        if (!zero(m.get("amplifier"))) parts.add("amplifier=" + num(m.get("amplifier")));
        if (!equal(m.get("duration"), -1)) parts.add("duration=" + num(m.get("duration")));
        return "potion{ " + String.join(", ", parts) + " }";
    }

    private String particle(Map<String, Object> m) {
        List<String> extras = data.pickers().particles().getOrDefault(String.valueOf(m.get("particle_type")), List.of());
        List<String> parts = new ArrayList<>();
        parts.add(quoted((String) m.get("particle_type"), '"'));
        if (m.containsKey("material") && !(extras.contains("Материал") && Parser.DEFAULT_MATERIAL.equals(m.get("material"))))
            parts.add("material=" + quoted((String) m.get("material"), '"'));
        if (!zero(m.get("first_spread")) || !zero(m.get("second_spread")))
            parts.add("spread={ " + num(m.get("first_spread")) + ", " + num(m.get("second_spread")) + " }");
        boolean moves = m.containsKey("x_motion") || m.containsKey("y_motion") || m.containsKey("z_motion");
        boolean still = zero(m.get("x_motion")) && zero(m.get("y_motion")) && zero(m.get("z_motion"));
        if (moves && !(still && extras.contains("Движение")))
            parts.add("motion={ " + num(m.get("x_motion")) + ", " + num(m.get("y_motion")) + ", " + num(m.get("z_motion")) + " }");
        if (!equal(m.get("count"), 1)) parts.add("count=" + num(m.get("count")));
        if (m.containsKey("color") && !(extras.contains("Цвет") && equal(m.get("color"), Parser.DEFAULT_COLOR)))
            parts.add("color=" + color(m.get("color")));
        if (m.containsKey("to_color") && !(extras.contains("Цвет перехода") && equal(m.get("to_color"), Parser.DEFAULT_COLOR)))
            parts.add("to_color=" + color(m.get("to_color")));
        if (m.containsKey("size") && !(extras.contains("Размер") && equal(m.get("size"), 1))) parts.add("size=" + num(m.get("size")));
        return "particle{ " + String.join(", ", parts) + " }";
    }

    private static String color(Object v) {
        if (v instanceof Json.Num n && n.text().matches("\\d{1,8}")) {
            long c = Long.parseLong(n.text());
            if (c <= 0xFFFFFF) {
                String hex = Long.toHexString(c).toUpperCase(Locale.ROOT);
                return "#" + "0".repeat(6 - hex.length()) + hex;
            }
        }
        return num(v);
    }

    private String array(Map<String, Object> m, int level) {
        if (m.size() != 2 || !(m.get("values") instanceof List<?> cells)) return null;
        List<String> parts = new ArrayList<>();
        for (Object cell : cells) parts.add(value(cell, level + 1));
        return parts.isEmpty() ? "{ }" : wrap("{ ", parts, " }", level);
    }

    private String map(Map<String, Object> m, int level) {
        if (m.size() != 2 || !(m.get("values") instanceof Map<?, ?> raw)) return null;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> e : Json.object(raw).entrySet()) {
            Object key;
            try {
                key = Json.parse(e.getKey());
            } catch (Json.Error | IllegalArgumentException ex) {
                return null;
            }
            if (!Json.write(key).equals(e.getKey())) return null;
            parts.add(value(key, level + 1) + "=" + value(e.getValue(), level + 1));
        }
        return "map" + (parts.isEmpty() ? "{ }" : wrap("{ ", parts, " }", level));
    }

    private static String typed(String type, Map<String, Object> m) {
        if (!Lexer.word(type) || KNOWN_TYPES.contains(type)) return null;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getKey().equals("type")) continue;
            if (!Lexer.word(e.getKey())) return null;
            String field = switch (e.getValue()) {
                case null -> "null";
                case String s -> quoted(s, '"');
                case Json.Num n -> n.text();
                case Boolean b -> b.toString();
                default -> null;
            };
            if (field == null) return null;
            parts.add(e.getKey() + "=" + field);
        }
        return type + "{ " + String.join(", ", parts) + " }";
    }

    private static String raw(Object v) {
        return "raw{ " + quoted(Json.write(v), '"') + " }";
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) return false;
            for (Map.Entry<?, ?> e : x.entrySet()) if (!y.containsKey(e.getKey()) || !same(e.getValue(), y.get(e.getKey()))) return false;
            return true;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof Json.Num x && b instanceof Json.Num y) return x.value() == y.value();
        return a == null ? b == null : a.equals(b);
    }

    private static String identifier(String name) {
        return Lexer.word(name) && !UNSAFE.contains(name) ? name : quoted(name, '`');
    }

    private static String wrap(String open, List<String> parts, String close, int level) {
        String flat = open + String.join(", ", parts) + close;
        if (flat.indexOf('\n') < 0 && level * STEP.length() + flat.length() <= WIDTH) return flat;
        String pad = STEP.repeat(level + 1);
        StringBuilder lines = new StringBuilder(open.stripTrailing()).append('\n');
        StringBuilder line = new StringBuilder(pad);
        for (String part : parts) {
            boolean tall = part.indexOf('\n') >= 0;
            if (line.length() > pad.length() && (tall || line.length() + part.length() + 1 > WIDTH)) {
                lines.append(line.toString().stripTrailing()).append('\n');
                line = new StringBuilder(pad);
            }
            line.append(part).append(", ");
            if (tall) {
                lines.append(line.toString().stripTrailing()).append('\n');
                line = new StringBuilder(pad);
            }
        }
        if (line.length() > pad.length()) lines.append(line.toString().stripTrailing()).append('\n');
        return lines.append(STEP.repeat(level)).append(close.strip()).toString();
    }

    private static String quoted(String s, char quote) {
        StringBuilder body = new StringBuilder().append(quote);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> body.append("\\\\");
                case '\n' -> body.append("\\n");
                case '\t' -> body.append("\\t");
                case '\r' -> body.append("\\r");
                default -> {
                    if (c == quote) {
                        body.append('\\').append(c);
                    } else if (c < 0x20 || c == 0x7F) {
                        String hex = Integer.toHexString(c);
                        body.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
                    } else {
                        body.append(c);
                    }
                }
            }
        }
        return body.append(quote).toString();
    }
}
