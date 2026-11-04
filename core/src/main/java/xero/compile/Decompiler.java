package xero.compile;

import xero.data.Category;
import xero.data.Data;
import xero.data.Names;
import xero.format.Formatter;
import xero.json.Json;
import xero.lang.Keywords;
import xero.lang.Lexer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class Decompiler {
    public record Result(String text, List<String> notes) {}

    private static final class Raw extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Raw(String why) { super(why, null, false, false); }
    }

    private static final List<String> SCOPES = List.of("game", "save", "line", "local");
    private static final List<String> PARSINGS = List.of("legacy", "plain", "minimessage", "json");
    private static final List<String> DECLARATION_ORDER =
            List.of("parameters", "display_name", "display_description", "description", "icon", "is_hidden");
    private static final String CONFLICT = "?";
    private static final int SHARED_ITEM = 60;

    private final Data data;
    private final Names names;
    private final List<String> notes = new ArrayList<>();
    private final Map<String, String> scopes = new LinkedHashMap<>();
    private final Set<String> written = new LinkedHashSet<>();
    private final Set<String> read = new LinkedHashSet<>();
    private final Map<String, Map<String, Object>> functions = new HashMap<>();
    private final Set<String> returning = new HashSet<>();
    private final Map<String, String> shapes = new HashMap<>();
    private final Set<String> notNumbers = new HashSet<>();
    private final Set<String> printedLines = new HashSet<>();
    private final Map<String, Integer> itemUses = new LinkedHashMap<>();
    private boolean literalKeys;
    private String sharedItems = "";
    private Map<String, String> lineParams = Map.of();
    private String current;

    private Decompiler(Data data, Names names) {
        this.data = data;
        this.names = names;
    }

    public static Result decompile(Data data, Names names, Map<String, Object> module) {
        Decompiler d = new Decompiler(data, names);
        String text = d.module(Json.object(Json.parse(Json.write(module))));
        return new Result(text, List.copyOf(d.notes));
    }

    private String module(Map<String, Object> module) {
        List<Map<String, Object>> handlers = new ArrayList<>();
        Object list = module.get("handlers");
        if (list instanceof List<?> l) for (Object h : l) if (h instanceof Map<?, ?>) handlers.add(Json.object(h));
        handlers.sort(Comparator.comparingInt(h -> h.get("position") instanceof Json.Num n ? n.intValue() : Integer.MAX_VALUE));
        for (Map<String, Object> h : handlers) {
            if (("function".equals(h.get("type")) || "process".equals(h.get("type"))) && h.get("name") instanceof String n
                    && functions.put(n, h) != null)
                notes.add("«" + n + "» в мире не одна — сервер задвоил её при заливке, вторая помечена @twin");
            if (h.get("values") instanceof List<?> l) h.put("values", Wire.tidyDeclaration(Json.array(l)));
            tidy(h.get("operations"));
        }
        scan(module);
        for (Map.Entry<String, Map<String, Object>> f : functions.entrySet())
            if ("function".equals(f.getValue().get("type")) && returns(f.getValue().get("operations"), Wire.RESULT + f.getKey()))
                returning.add(f.getKey());
        shapes(module);
        StringBuilder body = new StringBuilder();
        int expected = 0;
        for (Map<String, Object> h : handlers) {
            String printed = handler(h);
            if (printed == null) continue;
            int at = h.get("position") instanceof Json.Num n && n.text().matches("\\d{1,6}") ? Integer.parseInt(n.text()) : -1;
            if (at > expected) printed = "@line(" + at + ")\n" + printed;
            else if (at >= 0 && at < expected) notes.add("строка " + at + " повторяет номер — встанет на " + expected);
            expected = Math.max(at, expected) + 1;
            if (!body.isEmpty()) body.append('\n');
            body.append(printed);
        }
        String code = shareItems(body.toString());
        StringBuilder header = new StringBuilder(sharedItems.isEmpty() ? "" : sharedItems + "\n");
        for (String scope : SCOPES) {
            List<String> declared = new ArrayList<>();
            for (Map.Entry<String, String> e : scopes.entrySet()) {
                if (!scope.equals(e.getValue())) continue;
                if (scope.equals("local") && !(read.contains(e.getKey()) && !written.contains(e.getKey()))) continue;
                declared.add(e.getKey());
            }
            declared.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
            for (String name : declared) header.append(scope).append(' ').append(varName(name)).append('\n');
        }
        String printed = header.isEmpty() ? code : header + "\n" + code;
        Formatter.Result tidy = Formatter.format(printed);
        return tidy.ok() ? tidy.text() : printed;
    }

    private String shareItems(String body) {
        Set<String> taken = new HashSet<>(scopes.keySet());
        taken.addAll(functions.keySet());
        StringBuilder consts = new StringBuilder();
        String out = body;
        for (Map.Entry<String, Integer> e : itemUses.entrySet()) {
            String item = e.getKey();
            if (e.getValue() < 2 || item.length() < SHARED_ITEM) continue;
            String name = itemName(item, taken);
            taken.add(name);
            out = out.replace(item, name);
            consts.append("const ").append(name).append(" = ").append(item).append('\n');
        }
        sharedItems = consts.toString();
        return out;
    }

    private static String itemName(String item, Set<String> taken) {
        int at = item.indexOf("name: ");
        String source = item.substring("item(".length(), item.indexOf(item.indexOf(',') > 0 ? ',' : ')'));
        if (at > 0 && item.indexOf('"', at) > 0) {
            int open = item.indexOf('"', at), close = item.indexOf('"', open + 1);
            if (close > open) source = item.substring(open + 1, close).replaceAll("&#[0-9a-fA-F]{6}|&.|<[^>]*>", "");
        }
        StringBuilder word = new StringBuilder();
        for (char ch : source.toCharArray()) {
            if (Character.isLetterOrDigit(ch)) word.append(Character.toUpperCase(ch));
            else if (!word.isEmpty() && word.charAt(word.length() - 1) != '_') word.append('_');
        }
        String base = word.toString().replaceAll("_+$", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) base = "ITEM_" + base;
        String name = base;
        for (int n = 2; taken.contains(name) || !Keywords.plainName(name); n++) name = base + "_" + n;
        return name;
    }

    private void tidy(Object ops) {
        if (!(ops instanceof List<?> list)) return;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> op = Json.object(raw);
            Object owner = op.get("conditional") instanceof Map<?, ?> c ? c.get("action") : op.get("action");
            Data.Action a = owner instanceof String id ? data.actions().get(id) : null;
            if (a != null && op.get("values") instanceof List<?> values) op.put("values", Wire.tidy(a, Json.array(values)));
            tidy(op.get("operations"));
        }
    }

    private void scan(Object o) {
        if (o instanceof Map<?, ?> m) {
            if ("map".equals(m.get("type")) && m.get("values") instanceof Map<?, ?> entries) {
                for (Object key : entries.keySet()) {
                    try {
                        scan(Json.parse((String) key));
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            if ("variable".equals(m.get("type")) && m.get("variable") instanceof String name
                    && m.get("scope") instanceof String scope && SCOPES.contains(scope)) {
                String before = scopes.get(name);
                if (before == null) scopes.put(name, scope);
                else if (!before.equals(scope)) scopes.put(name, CONFLICT);
            }
            for (Object v : m.values()) scan(v);
        } else if (o instanceof List<?> l) {
            for (Object v : l) scan(v);
        }
    }

    private boolean returns(Object ops, String result) {
        if (!(ops instanceof List<?> list)) return false;
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> m)) continue;
            if (i + 1 < list.size() && list.get(i + 1) instanceof Map<?, ?> next && isReturn(Json.object(next))
                    && resultSet(Json.object(m), result) != null) return true;
            if (returns(m.get("operations"), result)) return true;
        }
        return false;
    }

    private static boolean isReturn(Map<String, Object> op) {
        return "control_return_function".equals(op.get("action")) && only(op, "action", "values")
                && op.get("values") instanceof List<?> l && l.isEmpty();
    }

    private static Object resultSet(Map<String, Object> op, String result) {
        if (!"set_variable_value".equals(op.get("action")) || !only(op, "action", "values")
                || !(op.get("values") instanceof List<?> values) || values.size() != 2) return null;
        if (!(values.get(0) instanceof Map<?, ?> a) || !(values.get(1) instanceof Map<?, ?> b)) return null;
        if (!"variable".equals(a.get("name")) || !"value".equals(b.get("name"))) return null;
        if (!isVariable(a.get("value"), result)) return null;
        Object v = b.get("value");
        return isHole(v) ? null : v;
    }

    private static boolean isVariable(Object v, String name) {
        return v instanceof Map<?, ?> m && only(Json.object(m), "type", "variable", "scope") && "variable".equals(m.get("type"))
                && name.equals(m.get("variable")) && "local".equals(m.get("scope"));
    }

    private void shapes(Object o) {
        if (o instanceof List<?> l) {
            for (Object x : l) shapes(x);
            return;
        }
        if (!(o instanceof Map<?, ?> raw)) return;
        Map<String, Object> m = Json.object(raw);
        if (m.get("action") instanceof String id && m.get("values") instanceof List<?> values) {
            Map<String, Object> byName = new HashMap<>();
            for (Object v : values) if (v instanceof Map<?, ?> e && e.get("name") instanceof String n) byName.put(n, e.get("value"));
            if ((id.equals("set_variable_create_list") || id.equals("set_variable_create_map"))
                    && byName.get("variable") instanceof Map<?, ?> var && var.get("variable") instanceof String name)
                shape(name, id.equals("set_variable_create_list") ? "list" : "map");
            if (id.equals("set_variable_value") && byName.get("variable") instanceof Map<?, ?> var
                    && var.get("variable") instanceof String name && byName.get("value") instanceof Map<?, ?> value) {
                if ("array".equals(value.get("type"))) shape(name, "list");
                if ("map".equals(value.get("type"))) shape(name, "map");
                if (value.get("type") instanceof String t && !t.equals("number") && !t.equals("variable") && !t.equals("game_value"))
                    notNumbers.add(name);
            }
            if (id.equals("repeat_for_each_in_list") && byName.get("list") instanceof Map<?, ?> var
                    && "variable".equals(var.get("type")) && var.get("variable") instanceof String name) shape(name, "list");
            if (id.equals("repeat_for_each_map_entry") && byName.get("map") instanceof Map<?, ?> var
                    && "variable".equals(var.get("type")) && var.get("variable") instanceof String name) shape(name, "map");
        }
        if ("parameter".equals(m.get("type")) && m.get("name") instanceof String name) {
            if ("array".equals(m.get("value_type"))) shape(name, "list");
            if ("map".equals(m.get("value_type"))) shape(name, "map");
        }
        for (Object v : m.values()) shapes(v);
    }

    private void shape(String name, String kind) {
        String before = shapes.putIfAbsent(name, kind);
        if (before != null && !before.equals(kind)) shapes.put(name, "?");
    }

    private boolean shapeFits(String name, String kind) {
        String s = shapes.get(name);
        return s == null || s.equals("?") || s.equals(kind);
    }

    private String handler(Map<String, Object> h) {
        Object type = h.get("type");
        current = "function".equals(type) && h.get("name") instanceof String n ? n : null;
        lineParams = lineParams(h);
        List<Object> ops = h.get("operations") instanceof List<?> l ? Json.array(l) : List.of();
        if ("event".equals(type)) {
            if (!only(h, "type", "event", "position", "operations") || !(h.get("event") instanceof String event)
                    || !Lexer.identifier(event)) {
                notes.add("строка пропущена: непонятное событие " + Json.write(h.get("event")));
                return null;
            }
            if (!data.events().containsKey(event)) notes.add("события «" + event + "» нет в данных");
            return "when " + event + " " + block(ops, 0) + "\n";
        }
        if (("function".equals(type) || "process".equals(type)) && h.get("name") instanceof String name
                && only(h, "type", "name", "values", "position", "operations")) {
            if (name.indexOf('`') >= 0 || name.indexOf('\n') >= 0) {
                notes.add("строка пропущена: имя «" + name + "» не записать");
                return null;
            }
            List<Object> values = h.get("values") instanceof List<?> l ? Json.array(l) : null;
            StringBuilder out = new StringBuilder();
            String params = declaration(values, out);
            if (!printedLines.add(name)) out.append("@twin\n");
            out.append("process".equals(type) ? "process " : "func ").append(functionName(name))
                    .append('(').append(params).append(") ").append(block(ops, 0)).append('\n');
            return out.toString();
        }
        notes.add("строка пропущена: " + Json.write(h).substring(0, Math.min(80, Json.write(h).length())));
        return null;
    }

    private static Map<String, String> lineParams(Map<String, Object> h) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!(h.get("values") instanceof List<?> values)) return out;
        for (Object raw : values) {
            if (!(raw instanceof Map<?, ?> e) || !"parameters".equals(e.get("name")) || !(e.get("value") instanceof Map<?, ?> array)
                    || !(array.get("values") instanceof List<?> list)) continue;
            for (Object p : list)
                if (p instanceof Map<?, ?> m && m.get("name") instanceof String n)
                    out.put(n, m.get("value_type") instanceof String t ? t : "");
        }
        return out;
    }

    private String declaration(List<Object> values, StringBuilder out) {
        try {
            Map<String, Object> byName = new LinkedHashMap<>();
            for (Object v : values == null ? List.of() : values) {
                Map<String, Object> e = Json.object(v);
                if (!only(e, "name", "value") || !(e.get("name") instanceof String n)) throw new Raw("значение строки");
                if (!DECLARATION_ORDER.contains(n)) throw new Raw("значение строки " + n);
                if (byName.put(n, e.get("value")) != null) throw new Raw("повтор");
            }
            Object hidden = byName.get("is_hidden");
            boolean isHidden = enumIs(hidden, "TRUE");
            if (hidden != null && !isHidden) throw new Raw("is_hidden");
            String params = byName.containsKey("parameters") ? parameters(byName.get("parameters")) : "";
            StringBuilder marks = new StringBuilder();
            for (String n : List.of("display_name", "display_description"))
                if (byName.containsKey(n)) marks.append('@').append(n).append('(').append(localized(byName.get(n))).append(")\n");
            if (byName.containsKey("description"))
                marks.append("@description(").append(description(byName.get("description"))).append(")\n");
            if (byName.containsKey("icon")) marks.append("@icon(").append(value(byName.get("icon"), false)).append(")\n");
            if (isHidden) marks.append("@hidden\n");
            out.append(marks);
            return params;
        } catch (Raw | IllegalArgumentException | ClassCastException r) {
            notes.add("значения функции записаны как есть: " + r.getMessage());
            out.append("@values(").append(quote(Json.write(values == null ? List.of() : values), null)).append(")\n");
            return "";
        }
    }

    private String description(Object raw) {
        Map<String, Object> array = Json.object(raw);
        if (!only(array, "type", "values") || !"array".equals(array.get("type"))) throw new Raw("description");
        List<String> lines = new ArrayList<>();
        for (Object cell : Json.array(array.get("values"))) {
            if (isHole(cell)) {
                lines.add("_");
                continue;
            }
            if (!(cell instanceof Map<?, ?> m) || !"text".equals(m.get("type"))) throw new Raw("description");
            String line = text(Json.object(m));
            if (line == null) throw new Raw("description");
            lines.add(line);
        }
        return String.join(", ", lines);
    }

    private String parameters(Object raw) {
        Map<String, Object> array = Json.object(raw);
        if (!only(array, "type", "values") || !"array".equals(array.get("type"))) throw new Raw("parameters");
        List<String> out = new ArrayList<>();
        List<Object> list = Json.array(array.get("values"));
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> p = Json.object(list.get(i));
            if (!(p.get("name") instanceof String name) || !Keywords.plainName(name))
                throw new Raw("имя параметра");
            if ("enum".equals(p.get("type_key"))) {
                out.add(markerParameter(p, name, i));
                continue;
            }
            String type = p.get("value_type") instanceof String t ? t : "";
            boolean required = "true".equals(p.get("is_required"));
            String fallback = p.get("default_value") instanceof String f ? f : "";
            Map<String, Object> expected = Wire.parameter(name, type, required, fallback, i);
            if (!sameParameter(expected, p)) throw new Raw("параметр " + name);
            String printed = name + ("any".equals(type) ? "" : ": " + type);
            if (!required) {
                Object def = Json.parse(fallback);
                String v = value(def, false);
                if (v.startsWith("raw(")) throw new Raw("значение по умолчанию");
                printed += " = " + v;
            } else if (!"{}".equals(fallback)) {
                throw new Raw("значение по умолчанию у обязательного");
            }
            out.add(printed);
        }
        return String.join(", ", out);
    }

    private static boolean sameParameter(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> x = Json.object(Json.parse(Json.write(a))), y = Json.object(Json.parse(Json.write(b)));
        for (Map<String, Object> m : List.of(x, y)) {
            m.remove("slot");
            m.remove("description_slot");
        }
        return x.equals(y);
    }

    private String markerParameter(Map<String, Object> p, String name, int index) {
        if (!(p.get("elements") instanceof String raw) || !(p.get("default_element") instanceof String fallback))
            throw new Raw("параметр " + name);
        List<String> options = new ArrayList<>();
        for (Object element : Json.array(Json.parse(raw))) {
            Object option = Json.object(Json.parse(Json.string(element))).get("name");
            if (!(option instanceof String text)) throw new Raw("параметр " + name);
            options.add(text);
        }
        if (options.isEmpty() || !sameParameter(Wire.markerParameter(name, options, fallback, index), p)) throw new Raw("параметр " + name);
        List<String> printed = new ArrayList<>();
        for (String o : options) printed.add(quote(o, null));
        String out = name + ": enum(" + String.join(", ", printed) + ")";
        return fallback.equals(options.get(0)) ? out : out + " = " + quote(fallback, null);
    }

    private String localized(Object raw) {
        Map<String, Object> v = Json.object(raw);
        if (only(v, "type", "data") && "localized_text".equals(v.get("type")) && v.get("data") instanceof String data) {
            try {
                Map<String, Object> d = Json.object(Json.parse(data));
                Map<String, Object> fallback = d.get("fallback") instanceof Map<?, ?> f ? Json.object(f) : null;
                if (only(d, "translations", "fallback") && Map.of().equals(d.get("translations")) && fallback != null
                        && only(fallback, "rawText", "parsingType") && fallback.get("rawText") instanceof String text
                        && fallback.get("parsingType") instanceof String type
                        && PARSINGS.contains(type.toLowerCase(Locale.ROOT))
                        && type.equals(type.toUpperCase(Locale.ROOT))) {
                    Map<String, Object> rebuilt = Context.obj("translations", Context.obj(),
                            "fallback", Context.obj("rawText", text, "parsingType", type));
                    if (Json.write(rebuilt).equals(data)) {
                        String prefix = prefix(type.toLowerCase(Locale.ROOT));
                        List<String> lines = new ArrayList<>();
                        for (String line : text.split("\n", -1)) lines.add(quote(line, prefix));
                        return String.join(", ", lines);
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        return rawValue(raw);
    }

    private String block(List<Object> ops, int depth) {
        if (ops.isEmpty()) return "{}";
        return "{\n" + statements(ops, depth + 1) + "    ".repeat(depth) + "}";
    }

    private String statements(List<Object> ops, int depth) {
        StringBuilder out = new StringBuilder();
        String indent = "    ".repeat(depth);
        int i = 0;
        while (i < ops.size()) {
            Map<String, Object> op = ops.get(i) instanceof Map<?, ?> m ? Json.object(m) : null;
            if (op == null) {
                notes.add("не объект в списке блоков");
                out.append(indent).append("raw(").append(quote(Json.write(ops.get(i)), null)).append(")\n");
                i++;
                continue;
            }
            String head = conditionHead(op);
            if (head != null) {
                out.append(indent).append("if ").append(head).append(' ').append(block(Json.array(op.get("operations")), depth));
                int j = i + 1;
                while (j < ops.size() && ops.get(j) instanceof Map<?, ?> next) {
                    Map<String, Object> e = Json.object(next);
                    String elseHead = elseHead(e);
                    if (elseHead == null) break;
                    out.append(" else ").append(elseHead).append(block(Json.array(e.get("operations")), depth));
                    j++;
                    if (elseHead.isEmpty()) break;
                }
                out.append('\n');
                i = j;
                continue;
            }
            String pair = i + 1 < ops.size() ? pair(op, ops.get(i + 1)) : null;
            if (pair != null) {
                out.append(indent).append(pair).append('\n');
                i += 2;
                continue;
            }
            out.append(indent).append(statement(op, depth)).append('\n');
            i++;
        }
        return out.toString();
    }

    private String pair(Map<String, Object> op, Object nextRaw) {
        if (!(nextRaw instanceof Map<?, ?> nm)) return null;
        Map<String, Object> next = Json.object(nm);
        try {
            if (current != null && returning.contains(current) && isReturn(next)) {
                Object v = resultSet(op, Wire.RESULT + current);
                if (v != null) return "return " + value(v, false);
            }
            if ("call_function".equals(op.get("action")) && op.get("values") instanceof List<?> values) {
                for (String f : returning) {
                    if (!isCopy(next, Wire.RESULT + f)) continue;
                    String call = invoke(op, Json.array(values));
                    if (!call.startsWith(functionName(f) + "(")) return null;
                    Map<String, Object> lhs = Json.object(Json.object(Json.array(next.get("values")).get(0)).get("value"));
                    String name = assignable(lhs);
                    if (name == null) return null;
                    written.add(Json.string(lhs.get("variable")));
                    return name + " = " + call;
                }
            }
        } catch (Raw | IllegalArgumentException | ClassCastException e) {
            return null;
        }
        return null;
    }

    private static boolean isCopy(Map<String, Object> op, String result) {
        if (!"set_variable_value".equals(op.get("action")) || !only(op, "action", "values")
                || !(op.get("values") instanceof List<?> values) || values.size() != 2) return false;
        if (!(values.get(0) instanceof Map<?, ?> a) || !(values.get(1) instanceof Map<?, ?> b)) return false;
        return "variable".equals(a.get("name")) && "value".equals(b.get("name")) && isVariable(b.get("value"), result);
    }

    private String conditionHead(Map<String, Object> op) {
        if (!(op.get("action") instanceof String id)) return null;
        Data.Action a = data.actions().get(id);
        if (a == null || !a.category().condition || !(op.get("operations") instanceof List<?>)
                || !(op.get("values") instanceof List<?>)) return null;
        if (!only(op, "action", "values", "selection", "is_inverted", "operations")) return null;
        Object inverted = op.get("is_inverted");
        if (inverted != null && !Boolean.TRUE.equals(inverted)) return null;
        try {
            return condition(id, Json.array(op.get("values")), inverted != null, op.get("selection"));
        } catch (Raw | IllegalArgumentException | ClassCastException r) {
            return null;
        }
    }

    private String elseHead(Map<String, Object> op) {
        if (!"else".equals(op.get("action")) || !(op.get("operations") instanceof List<?>)
                || !(op.get("values") instanceof List<?> values)) return null;
        if (!op.containsKey("conditional")) {
            return only(op, "action", "values", "operations") && values.isEmpty() ? "" : null;
        }
        if (!only(op, "action", "values", "conditional", "selection", "operations")) return null;
        Map<String, Object> c = op.get("conditional") instanceof Map<?, ?> m ? Json.object(m) : null;
        if (c == null || !only(c, "action", "is_inverted") || !(c.get("action") instanceof String id)
                || !(c.get("is_inverted") instanceof Boolean inverted)) return null;
        Data.Action a = data.actions().get(id);
        if (a == null || !a.category().condition) return null;
        try {
            return "if " + condition(id, Json.array(values), inverted, op.get("selection")) + " ";
        } catch (Raw | IllegalArgumentException | ClassCastException r) {
            return null;
        }
    }

    private String condition(String id, List<Object> values, boolean inverted, Object selection) {
        Data.Action a = data.actions().get(id);
        if (a == null || !exact(a)) throw new Raw("условие " + id);
        Map<String, Object> bound = bound(a, values);
        String op = Wire.compareOp(id);
        if (op != null && selection == null && bound.size() == 2 && bound.containsKey("value") && bound.containsKey("compare")) {
            Object compare = bound.get("compare");
            Object right = null;
            if (a.plural("compare")) {
                if (compare instanceof Map<?, ?> m && "array".equals(m.get("type")) && m.get("values") instanceof List<?> cells
                        && cells.size() == 1 && !isHole(cells.get(0))) right = cells.get(0);
            } else if (!isHole(compare)) {
                right = compare;
            }
            if (right != null && !isHole(bound.get("value"))) {
                String left = fitting(a.arg("value").kind(), bound.get("value"), false);
                String r = fitting(a.arg("compare").kind(), right, false);
                if (id.equals("if_variable_equals")) return left + (inverted ? " != " : " == ") + r;
                return (inverted ? "not " : "") + left + " " + op + " " + r;
            }
        }
        String in = selection == null ? membership(a, bound) : null;
        if (in != null) return (inverted ? "not " : "") + in;
        String target = selection == null ? "" : target(a, selection);
        return (inverted ? "not " : "") + target + actionName(a) + "(" + args(a, values, bound, false, null) + ")";
    }

    private String membership(Data.Action a, Map<String, Object> bound) {
        switch (a.id()) {
            case "if_variable_equals" -> {
                if (!bound.keySet().equals(Set.of("value", "compare")) || !isArray(bound.get("compare"))) return null;
                List<Object> cells = Json.array(Json.object(bound.get("compare")).get("values"));
                if (cells.size() < 2) return null;
                List<String> printed = new ArrayList<>();
                for (Object cell : cells) {
                    if (isHole(cell) || isArray(cell)) return null;
                    printed.add(fitting(a.arg("compare").kind(), cell, false));
                }
                return fitting(a.arg("value").kind(), bound.get("value"), false) + " in [" + String.join(", ", printed) + "]";
            }
            case "if_variable_number_in_range" -> {
                if (!bound.keySet().equals(Set.of("value", "min", "max", "including"))
                        || !enumIs(bound.get("including"), "INCLUDE_ALL")) return null;
                return fitting(a.arg("value").kind(), bound.get("value"), false) + " in "
                        + fitting(a.arg("min").kind(), bound.get("min"), false) + ".."
                        + fitting(a.arg("max").kind(), bound.get("max"), false);
            }
            case "if_variable_list_contains_value", "if_variable_map_has_key" -> {
                boolean list = a.id().equals("if_variable_list_contains_value");
                String holder = list ? "list" : "map", cell = list ? "values" : "key";
                if (!bound.keySet().equals(Set.of(holder, cell))) return null;
                Object one = one(a, cell, bound.get(cell));
                if (one == null || isHole(one) || isArray(one)) return null;
                if (!(bound.get(holder) instanceof Map<?, ?> m) || !(m.get("variable") instanceof String name)
                        || !(list ? "list" : "map").equals(shapes.get(name)) || isPinned(m)) return null;
                String right = assignable(m);
                if (right == null) return null;
                read(m);
                return fitting(a.arg(cell).kind(), one, false) + " in " + right;
            }
            default -> {
                return null;
            }
        }
    }

    private String statement(Map<String, Object> op, int depth) {
        try {
            return pretty(op, depth);
        } catch (Raw | IllegalArgumentException | ClassCastException r) {
            notes.add("записано как есть (" + r.getMessage() + "): " + op.get("action"));
            Map<String, Object> copy = new LinkedHashMap<>(op);
            Object ops = copy.remove("operations");
            String out = "raw(" + quote(Json.write(copy), null) + ")";
            if (ops instanceof List<?> l) out += " " + block(Json.array(l), depth);
            else if (ops != null) out = "raw(" + quote(Json.write(op), null) + ")";
            return out;
        }
    }

    private String pretty(Map<String, Object> op, int depth) {
        if (!(op.get("action") instanceof String id)) throw new Raw("нет action");
        if (!(op.get("values") instanceof List<?> rawValues)) throw new Raw("нет values");
        List<Object> values = Json.array(rawValues);
        if (id.equals("empty") && only(op, "action", "values") && values.isEmpty()) return "_";
        if (id.equals("call_function")) return invoke(op, values);
        if (id.equals("start_process")) return start(op, values);
        Data.Action a = data.actions().get(id);
        if (a == null) throw new Raw("нет в данных");
        if (!exact(a)) throw new Raw("запасной id");
        if (a.category().condition) throw new Raw("условие вне if");
        boolean conditional = Wire.CONDITIONAL.contains(id);
        if (!(conditional ? only(op, "action", "values", "selection", "operations", "conditional")
                : only(op, "action", "values", "selection", "operations")))
            throw new Raw("лишние поля");
        if (a.container() != op.containsKey("operations")) throw new Raw("тело");
        String target = op.containsKey("selection") ? target(a, op.get("selection")) : "";
        String body = a.container() ? " " + block(Json.array(op.get("operations")), depth) : "";
        if (target.isEmpty()) {
            String loop = loop(a, op, values, body);
            if (loop != null) return loop;
        }
        if (conditional) {
            Map<String, Object> c = op.get("conditional") instanceof Map<?, ?> m ? Json.object(m) : null;
            if (c == null || !only(c, "action", "is_inverted") || !(c.get("action") instanceof String cid)
                    || !(c.get("is_inverted") instanceof Boolean inverted)) throw new Raw("условие");
            return target + actionName(a) + "(" + condition(cid, values, inverted, null) + ")" + body;
        }
        Map<String, Object> bound = bound(a, values);
        if (target.isEmpty() && !a.container()) {
            String sugar = sugar(a, bound);
            if (sugar != null) return sugar;
        }
        return target + actionName(a) + "(" + args(a, values, bound, true, null) + ")" + body;
    }

    private String loop(Data.Action a, Map<String, Object> op, List<Object> values, String body) {
        if (a.id().equals("repeat_forever") && values.isEmpty()) return "forever" + body;
        if (a.id().equals("repeat_while")) {
            Map<String, Object> c = op.get("conditional") instanceof Map<?, ?> m ? Json.object(m) : null;
            if (c == null || !only(c, "action", "is_inverted") || !(c.get("action") instanceof String cid)
                    || !(c.get("is_inverted") instanceof Boolean inverted)) return null;
            try {
                return "while " + condition(cid, values, inverted, null) + body;
            } catch (Raw | IllegalArgumentException | ClassCastException e) {
                return null;
            }
        }
        if (!a.id().startsWith("repeat_")) return null;
        Map<String, Object> bound;
        try {
            bound = bound(a, values);
        } catch (Raw e) {
            return null;
        }
        switch (a.id()) {
            case "repeat_multi_times" -> {
                if (!bound.keySet().equals(Set.of("amount")) || isHole(bound.get("amount"))) return null;
                return "repeat " + fitting(a.arg("amount").kind(), bound.get("amount"), false) + body;
            }
            case "repeat_on_range" -> {
                if (!bound.keySet().containsAll(List.of("variable", "start", "end"))) return null;
                String name = plainVariable(bound.get("variable"));
                if (name == null || isHole(bound.get("start")) || isHole(bound.get("end"))) return null;
                String step = "";
                if (bound.containsKey("interval")) {
                    if (isHole(bound.get("interval"))) return null;
                    step = " step " + fitting(a.arg("interval").kind(), bound.get("interval"), false);
                }
                if (bound.size() != (step.isEmpty() ? 3 : 4)) return null;
                written.add(Json.string(Json.object(bound.get("variable")).get("variable")));
                return "for " + name + " in " + fitting(a.arg("start").kind(), bound.get("start"), false) + ".."
                        + fitting(a.arg("end").kind(), bound.get("end"), false) + step + body;
            }
            case "repeat_for_each_in_list" -> {
                if (!bound.containsKey("value_variable") || !bound.containsKey("list") || isHole(bound.get("list"))) return null;
                String value = plainVariable(bound.get("value_variable"));
                String index = bound.containsKey("index_variable") ? plainVariable(bound.get("index_variable")) : "";
                if (value == null || index == null || bound.size() != (index.isEmpty() ? 2 : 3)) return null;
                written.add(Json.string(Json.object(bound.get("value_variable")).get("variable")));
                if (!index.isEmpty()) written.add(Json.string(Json.object(bound.get("index_variable")).get("variable")));
                return "for " + (index.isEmpty() ? "" : index + ", ") + value + " in "
                        + fitting(a.arg("list").kind(), bound.get("list"), false) + body;
            }
            case "repeat_for_each_map_entry" -> {
                if (!bound.keySet().equals(Set.of("key_variable", "value_variable", "map")) || isHole(bound.get("map"))) return null;
                String key = plainVariable(bound.get("key_variable"));
                String value = plainVariable(bound.get("value_variable"));
                if (key == null || value == null) return null;
                written.add(Json.string(Json.object(bound.get("key_variable")).get("variable")));
                written.add(Json.string(Json.object(bound.get("value_variable")).get("variable")));
                return "for " + key + ": " + value + " in " + fitting(a.arg("map").kind(), bound.get("map"), false) + body;
            }
            default -> {
                return null;
            }
        }
    }

    private String multiple(Map<String, Object> bound) {
        if (!bound.keySet().equals(Set.of("variables", "values"))) return null;
        if (!(bound.get("variables") instanceof Map<?, ?> vm) || !"array".equals(vm.get("type"))
                || !(vm.get("values") instanceof List<?> vars) || vars.size() < 2) return null;
        if (!(bound.get("values") instanceof Map<?, ?> wm) || !"array".equals(wm.get("type"))
                || !(wm.get("values") instanceof List<?> vals) || vals.size() != vars.size()) return null;
        List<String> left = new ArrayList<>();
        for (Object v : vars) {
            String name = plainVariable(v);
            if (name == null) return null;
            left.add(name);
        }
        List<String> right = new ArrayList<>();
        for (Object v : vals) {
            if (isHole(v)) return null;
            right.add(value(v, false));
        }
        for (Object v : vars) written.add(Json.string(Json.object(v).get("variable")));
        return String.join(", ", left) + " = " + String.join(", ", right);
    }

    private String sugar(Data.Action a, Map<String, Object> bound) {
        if (a.id().equals("set_variable_multiple")) return multiple(bound);
        String index = index(a, bound);
        if (index != null) return index;
        Data.Arg result = a.result();
        if (result == null) return null;
        String first = result.id();
        Object lhs = one(a, first, bound.get(first));
        if (lhs == null) return null;
        String name = assignable(lhs);
        if (name == null) return null;
        if (a.id().equals("set_variable_value") && bound.size() == 2 && bound.containsKey("value")
                && !isHole(bound.get("value"))) {
            String v = value(bound.get("value"), false);
            for (String op : Wire.COMPOUND.keySet())
                if (v.startsWith(name + " " + op.charAt(0) + " ")) v = "num(" + quote(Json.string(Json.object(bound.get("value")).get("number")), null) + ")";
            written.add(Json.string(Json.object(lhs).get("variable")));
            return name + " = " + v;
        }
        if (a.id().equals("set_variable_remainder") && bound.keySet().equals(Set.of("variable", "dividend", "divisor"))
                && lhs.equals(bound.get("dividend")) && !isHole(bound.get("divisor")) && !isArray(bound.get("divisor"))) {
            String v = fitting(a.arg("divisor").kind(), bound.get("divisor"), false);
            read(lhs);
            written.add(Json.string(Json.object(lhs).get("variable")));
            return name + " %= " + v;
        }
        String op = Wire.compoundOp(a.id());
        if (op != null && bound.size() == 2 && bound.get("value") instanceof Map<?, ?> m && "array".equals(m.get("type"))
                && m.get("values") instanceof List<?> cells && cells.size() == 2 && lhs.equals(cells.get(0))
                && !isHole(cells.get(1)) && !isArray(cells.get(1))) {
            String v = fitting(a.arg("value").kind(), cells.get(1), false);
            read(lhs);
            written.add(Json.string(Json.object(lhs).get("variable")));
            return name + " " + op + " " + v;
        }
        if (a.category() != Category.VARIABLE) return null;
        Map<String, Object> rest = new LinkedHashMap<>(bound);
        rest.remove(first);
        String args = args(a, null, rest, false, first);
        written.add(Json.string(Json.object(lhs).get("variable")));
        return name + " = " + actionName(a) + "(" + args + ")";
    }

    private String index(Data.Action a, Map<String, Object> bound) {
        boolean read = a.id().equals("set_variable_get_list_value") || a.id().equals("set_variable_get_map_value_single")
                || a.id().equals("set_variable_get_map_value");
        boolean list = a.id().equals("set_variable_get_list_value") || a.id().equals("set_variable_set_list_value");
        if (!read && !a.id().equals("set_variable_set_list_value") && !a.id().equals("set_variable_set_map_value")) return null;
        String container = list ? "list" : "map";
        String slot = list ? "number" : "key";
        Set<String> keys = read ? Set.of("variable", container, slot) : Set.of("variable", container, slot, "value");
        if (!bound.keySet().equals(keys)) return null;
        Object variable = one(a, "variable", bound.get("variable"));
        Object key = one(a, slot, bound.get(slot));
        Object source = bound.get(container);
        if (variable == null || key == null) return null;
        String sourceName = assignable(source);
        if (sourceName == null || (!read && !source.equals(variable))) return null;
        String name = Json.string(Json.object(source).get("variable"));
        if (!shapeFits(name, list ? "list" : "map")) return null;
        String printedKey;
        if (list) {
            if (!(key instanceof Map<?, ?> k) || !only(Json.object(k), "type", "number") || !(k.get("number") instanceof Json.Num n))
                return null;
            printedKey = literal(n);
            if (!printedKey.matches("\\d+")) return null;
        } else {
            if (key instanceof Map<?, ?> k && "variable".equals(k.get("type"))) {
                if (!"map".equals(shapes.get(name)) || !isVariableAlone(k)) return null;
                printedKey = value(key, false);
            } else {
                if (!(key instanceof Map<?, ?> k) || !only(Json.object(k), "type", "text", "parsing")
                        || !(k.get("text") instanceof String t)
                        || !("legacy".equals(k.get("parsing")) || "plain".equals(k.get("parsing")) && Wire.sameInAnyMarkup(t))) return null;
                printedKey = quote(t, null);
            }
        }
        if (read) {
            String lhs = assignable(variable);
            if (lhs == null) return null;
            read(source);
            written.add(Json.string(Json.object(variable).get("variable")));
            return lhs + " = " + sourceName + "[" + printedKey + "]";
        }
        Object value = one(a, "value", bound.get("value"));
        if (value == null || isHole(value)) return null;
        String v = fitting(a.arg("value").kind(), value, false);
        read(source);
        written.add(name);
        return sourceName + "[" + printedKey + "] = " + v;
    }

    private static boolean isVariableAlone(Map<?, ?> m) {
        return m.size() == 3 && m.get("variable") instanceof String && m.get("scope") instanceof String;
    }

    private static Object one(Data.Action a, String arg, Object v) {
        if (v == null || !a.plural(arg)) return v;
        if (v instanceof Map<?, ?> m && "array".equals(m.get("type"))) {
            return m.get("values") instanceof List<?> cells && cells.size() == 1 ? cells.get(0) : null;
        }
        return v;
    }

    private String assignable(Object v) {
        if (!(v instanceof Map<?, ?> m) || !only(Json.object(m), "type", "variable", "scope") || !"variable".equals(m.get("type"))
                || !(m.get("variable") instanceof String name) || !(m.get("scope") instanceof String scope)) return null;
        return printed(name, scope);
    }

    private String plainVariable(Object v) {
        String name = assignable(v);
        return name == null || isPinned(v) ? null : name;
    }

    private boolean isPinned(Object v) {
        return v instanceof Map<?, ?> m && m.get("variable") instanceof String name && CONFLICT.equals(scopes.get(name));
    }

    private String printed(String name, String scope) {
        if (unwritable(name) || !SCOPES.contains(scope)) return null;
        if (CONFLICT.equals(scopes.get(name))) return scope + "." + varName(name);
        return scope.equals(scopes.get(name)) ? varName(name) : null;
    }

    private boolean exact(Data.Action a) {
        Names.Entry e = names.byId(a.id());
        Names.Entry back = names.resolve(e.space(), e.bare() ? e.name() : e.qualified());
        return back != null && back.action().id().equals(a.id());
    }

    private Map<String, Object> bound(Data.Action a, List<Object> values) {
        Map<String, Object> bound = new LinkedHashMap<>();
        for (Object raw : values) {
            Map<String, Object> e = raw instanceof Map<?, ?> m ? Json.object(m) : null;
            if (e == null || !only(e, "name", "value") || !(e.get("name") instanceof String n)) throw new Raw("значение");
            if (a.arg(n) == null && a.setting(n) == null && (!Keywords.plainName(n)
                    || !n.equals(n.toLowerCase(Locale.ROOT))))
                throw new Raw("аргумент «" + n + "» не записать");
            if (bound.put(n, e.get("value")) != null) throw new Raw("аргумент дважды");
        }
        return bound;
    }

    private String args(Data.Action a, List<Object> values, Map<String, Object> bound, boolean statement, String skip) {
        List<String> parts = new ArrayList<>();
        boolean positional = true;
        for (int i = 0; i < a.args().size(); i++) {
            Data.Arg arg = a.args().get(i);
            if (arg.id().equals(skip)) continue;
            Object v = bound.get(arg.id());
            if (v == null) {
                positional = false;
                continue;
            }
            String printed = argument(a, arg, v);
            if (statement && Data.VARIABLE.equals(arg.kind())) markWritten(a, arg, v);
            parts.add(positional ? printed : arg.id() + ": " + printed);
        }
        for (Data.Setting s : a.settings()) {
            Object v = bound.get(s.id());
            if (v == null) continue;
            String option = marker(s, v);
            parts.add(a.id().equals("if_variable_is_type") && positional && s == a.settings().get(0) ? option : s.id() + ": " + option);
        }
        for (Map.Entry<String, Object> extra : bound.entrySet()) {
            if (a.arg(extra.getKey()) != null || a.setting(extra.getKey()) != null) continue;
            if (extra.getKey().equals(skip)) continue;
            String option = enumCall(extra.getValue());
            parts.add(extra.getKey() + ": " + (option != null ? option : rawValue(extra.getValue())));
        }
        return String.join(", ", parts);
    }

    private void markWritten(Data.Action a, Data.Arg arg, Object v) {
        Object cell = v;
        if (a.plural(arg.id()) && v instanceof Map<?, ?> m && m.get("values") instanceof List<?> cells && cells.size() == 1)
            cell = cells.get(0);
        if (cell instanceof Map<?, ?> m && "variable".equals(m.get("type")) && m.get("variable") instanceof String n
                && assignable(cell) != null) written.add(n);
    }

    private String fitting(String slot, Object v, boolean inList) {
        if (v instanceof Map<?, ?> m && m.get("type") instanceof String t) {
            String kind = t.equals("game_value") && m.get("game_value") instanceof String id
                    ? Kinds.ofGameValue(data, id) : Kinds.ofType(t);
            String text = t.equals("text") && m.get("text") instanceof String s ? s : null;
            Kinds.Problem p = Kinds.check(slot, kind, text);
            if (p != null && p.error()) return rawValue(v);
        }
        return value(v, inList);
    }

    private String argument(Data.Action a, Data.Arg arg, Object v) {
        if (!a.plural(arg.id())) return isHole(v) ? rawValue(v) : fitting(arg.kind(), v, false);
        if (v instanceof Map<?, ?> m && only(Json.object(m), "type", "values") && "array".equals(m.get("type"))
                && m.get("values") instanceof List<?> cells) {
            List<String> printed = new ArrayList<>();
            for (Object c : cells) printed.add(cell(arg, c));
            if (cells.size() == 1 && !isArray(cells.get(0))) return printed.get(0);
            return "[" + String.join(", ", printed) + "]";
        }
        return isHole(v) ? rawValue(v) : fitting(arg.kind(), v, false);
    }

    private String cell(Data.Arg arg, Object c) {
        if (arg.slotted()) {
            if (c instanceof Map<?, ?> m && only(Json.object(m), "type", "item") && "item".equals(m.get("type"))
                    && Items.EMPTY.equals(m.get("item"))) return "_";
            if (isHole(c)) return rawValue(c);
            return fitting(arg.kind(), c, false);
        }
        return isHole(c) ? "_" : fitting(arg.kind(), c, false);
    }

    private String enumCall(Object v) {
        if (!(v instanceof Map<?, ?> m) || !"enum".equals(m.get("type")) || !(m.get("enum") instanceof String id)) return null;
        Map<String, Object> o = Json.object(m);
        if (only(o, "type", "enum")) return "enum(" + quote(id, null) + ")";
        if (!only(o, "type", "enum", "variable", "scope") || !(o.get("variable") instanceof String name)
                || !(o.get("scope") instanceof String scope)) return null;
        String printed = printed(name, scope);
        if (printed == null) return null;
        read.add(name);
        return "enum(" + quote(id, null) + ", " + printed + ")";
    }

    private String marker(Data.Setting s, Object v) {
        String call = enumCall(v);
        if (call == null) throw new Raw("настройка " + s.id());
        Map<String, Object> m = Json.object(v);
        String id = Json.string(m.get("enum"));
        String option = id.toLowerCase(Locale.ROOT);
        if (m.size() > 2 || !s.options().containsKey(option) || !option.toUpperCase(Locale.ROOT).equals(id)) return call;
        return Keywords.plainName(option) ? option : quote(option, null);
    }

    private String target(Data.Action a, Object selection) {
        if (a.family() == null || !(selection instanceof Map<?, ?> m) || !only(Json.object(m), "type")
                || !(m.get("type") instanceof String id)) throw new Raw("цель");
        for (Map.Entry<String, String> e : names.selectors(a.family()).entrySet())
            if (e.getValue().equals(id)) return "@" + e.getKey() + " ";
        throw new Raw("цель " + id);
    }

    private String invoke(Map<String, Object> op, List<Object> values) {
        if (!only(op, "action", "values")) throw new Raw("лишние поля");
        Map<String, Object> byName = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (Object raw : values) {
            Map<String, Object> e = Json.object(raw);
            if (!only(e, "name", "value") || !(e.get("name") instanceof String n)) throw new Raw("значение");
            if (byName.put(n, e.get("value")) != null) throw new Raw("дважды");
            order.add(n);
        }
        if (!order.equals(List.of("function_name")) && !order.equals(List.of("function_name", "args")))
            throw new Raw("значения вызова");
        Object fn = byName.get("function_name");
        String name = plainName(fn);
        Map<String, Object> known = name == null ? null : functions.get(name);
        List<String> args = passedArgs(known == null ? byName.get("args") : withoutDefaults(byName.get("args"), known));
        if (name != null) {
            Map<String, Object> f = known;
            if (f == null) return "`" + name + "`(" + String.join(", ", args) + ")";
            if ("function".equals(f.get("type")) && (args.isEmpty() || acceptsArgs(f, byName.get("args"))))
                return functionName(name) + "(" + String.join(", ", inOrder(args, f)) + ")";
        }
        String head = isHole(fn) ? rawValue(fn) : value(fn, false);
        return "call(" + head + (args.isEmpty() ? "" : ", " + String.join(", ", args)) + ")";
    }

    private String start(Map<String, Object> op, List<Object> values) {
        if (!only(op, "action", "values")) throw new Raw("лишние поля");
        Map<String, Object> byName = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (Object raw : values) {
            Map<String, Object> e = Json.object(raw);
            if (!only(e, "name", "value") || !(e.get("name") instanceof String n)) throw new Raw("значение");
            if (byName.put(n, e.get("value")) != null) throw new Raw("дважды");
            order.add(n);
        }
        List<String> canonical = List.of("process_name", "local_variables_mode", "target_mode", "args");
        int last = -1;
        for (String n : order) {
            int i = canonical.indexOf(n);
            if (i <= last) throw new Raw("значения запуска");
            last = i;
        }
        String name = plainName(byName.get("process_name"));
        if (name == null) throw new Raw("имя процесса");
        Map<String, Object> f = functions.get(name);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<String>> setting : Wire.START_SETTINGS.entrySet()) {
            String key = setting.getKey();
            if (!byName.containsKey(key)) continue;
            Object v = byName.get(key);
            if (!(v instanceof Map<?, ?> m) || !only(Json.object(m), "type", "enum") || !"enum".equals(m.get("type"))
                    || !(m.get("enum") instanceof String id) || !id.equals(id.toUpperCase(Locale.ROOT))
                    || !setting.getValue().contains(id.toLowerCase(Locale.ROOT))) throw new Raw(key);
            parts.add(key + ": " + id.toLowerCase(Locale.ROOT));
        }
        parts.addAll(f == null ? passedArgs(byName.get("args")) : inOrder(passedArgs(withoutDefaults(byName.get("args"), f)), f));
        if (f != null && !"process".equals(f.get("type"))) throw new Raw("это не процесс");
        if (f != null && byName.containsKey("args") && !acceptsArgs(f, byName.get("args"))) throw new Raw("параметры");
        String printed = f == null ? "`" + name + "`" : functionName(name);
        return "start " + printed + "(" + String.join(", ", parts) + ")";
    }

    private static List<String> inOrder(List<String> args, Map<String, Object> function) {
        List<String> order = new ArrayList<>(lineParams(function).keySet());
        List<String> out = new ArrayList<>(args);
        out.sort(Comparator.comparingInt(a -> {
            int i = order.indexOf(a.substring(0, a.indexOf(':')));
            return i < 0 ? Integer.MAX_VALUE : i;
        }));
        return out;
    }

    private String plainName(Object v) {
        if (v instanceof Map<?, ?> m && only(Json.object(m), "type", "text", "parsing") && "text".equals(m.get("type"))
                && m.get("text") instanceof String name && !name.isEmpty() && name.indexOf('`') < 0 && name.indexOf('\n') < 0
                && ("legacy".equals(m.get("parsing")) || "plain".equals(m.get("parsing")) && name.indexOf('&') < 0)) return name;
        return null;
    }

    private List<String> passedArgs(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        if (!(raw instanceof Map<?, ?> m) || !only(Json.object(m), "type", "values") || !"map".equals(m.get("type"))
                || !(m.get("values") instanceof Map<?, ?> entries) || entries.isEmpty()) throw new Raw("args");
        for (Map.Entry<?, ?> e : entries.entrySet()) {
            String key = (String) e.getKey();
            String param;
            try {
                Map<String, Object> k = Json.object(Json.parse(key));
                if (!only(k, "type", "text", "parsing") || !"text".equals(k.get("type")) || !"plain".equals(k.get("parsing"))
                        || !(k.get("text") instanceof String t) || !Keywords.plainName(t)
                        || !Json.write(k).equals(key)) throw new Raw("ключ аргумента");
                param = t;
            } catch (Json.Error | IllegalArgumentException ex) {
                throw new Raw("ключ аргумента");
            }
            out.add(param + ": " + (isHole(e.getValue()) ? rawValue(e.getValue()) : value(e.getValue(), false)));
        }
        return out;
    }

    private Object withoutDefaults(Object args, Map<String, Object> f) {
        if (!(args instanceof Map<?, ?> m) || !(m.get("values") instanceof Map<?, ?> entries)) return args;
        Map<String, Object> left = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : entries.entrySet())
            if (!byDefault((String) e.getKey(), e.getValue(), f)) left.put((String) e.getKey(), e.getValue());
        if (left.isEmpty()) return null;
        Map<String, Object> out = new LinkedHashMap<>(Json.object(m));
        out.put("values", left);
        return out;
    }

    private boolean byDefault(String key, Object value, Map<String, Object> f) {
        Object name;
        try {
            name = Json.object(Json.parse(key)).get("text");
        } catch (RuntimeException e) {
            return false;
        }
        if (!(f.get("values") instanceof List<?> values)) return false;
        for (Object raw : values) {
            Map<String, Object> e = Json.object(raw);
            if (!"parameters".equals(e.get("name")) || !(e.get("value") instanceof Map<?, ?> arr)) continue;
            if (!(arr.get("values") instanceof List<?> list)) continue;
            for (Object p : list) {
                Map<String, Object> pm = Json.object(p);
                if (!name.equals(pm.get("name"))) continue;
                if ("enum".equals(pm.get("type_key")))
                    return value instanceof Map<?, ?> v && "text".equals(v.get("type")) && "plain".equals(v.get("parsing"))
                            && Objects.equals(v.get("text"), pm.get("default_element"));
                return isHole(value) && !"true".equals(pm.get("is_required"));
            }
        }
        return false;
    }

    private boolean acceptsArgs(Map<String, Object> f, Object args) {
        Set<String> params = new LinkedHashSet<>();
        Set<String> required = new LinkedHashSet<>();
        if (f.get("values") instanceof List<?> values) {
            for (Object raw : values) {
                Map<String, Object> e = Json.object(raw);
                if (!"parameters".equals(e.get("name")) || !(e.get("value") instanceof Map<?, ?> arr)) continue;
                if (!(arr.get("values") instanceof List<?> list)) continue;
                for (Object p : list) {
                    Map<String, Object> pm = Json.object(p);
                    if (pm.get("name") instanceof String n) {
                        params.add(n);
                        if ("true".equals(pm.get("is_required"))) required.add(n);
                    }
                }
            }
        }
        Set<String> given = new LinkedHashSet<>();
        if (args instanceof Map<?, ?> m && m.get("values") instanceof Map<?, ?> entries)
            for (Object key : entries.keySet()) {
                try {
                    Object t = Json.object(Json.parse((String) key)).get("text");
                    if (t instanceof String s) given.add(s);
                } catch (RuntimeException e) {
                    return false;
                }
            }
        return params.containsAll(given) && given.containsAll(required);
    }

    private String value(Object v, boolean inList) {
        try {
            return valueOrRaw(v, inList);
        } catch (Raw | IllegalArgumentException | ClassCastException e) {
            return rawValue(v);
        }
    }

    private String valueOrRaw(Object v, boolean inList) {
        if (!(v instanceof Map<?, ?> raw)) return rawValue(v);
        Map<String, Object> m = Json.object(raw);
        if (m.isEmpty()) return inList ? "_" : rawValue(m);
        if (!(m.get("type") instanceof String type)) return rawValue(m);
        String printed = switch (type) {
            case "text" -> text(m);
            case "number" -> number(m);
            case "location" -> location(m);
            case "vector" -> vector(m);
            case "variable" -> variable(m);
            case "game_value" -> gameValue(m);
            case "item" -> item(m);
            case "block" -> block(m);
            case "sound" -> sound(m);
            case "particle" -> particle(m);
            case "potion" -> potion(m);
            case "array" -> array(m);
            case "map" -> map(m);
            default -> null;
        };
        return printed == null ? rawValue(m) : printed;
    }

    private String text(Map<String, Object> m) {
        if (!only(m, "type", "text", "parsing") || !(m.get("text") instanceof String t)
                || !(m.get("parsing") instanceof String p) || !PARSINGS.contains(p)) return null;
        if (literalKeys) return quote(t, prefix(p));
        String prefix = p.equals("plain") && Wire.sameInAnyMarkup(t) ? null : prefix(p);
        return (prefix == null ? "" : prefix) + '"' + spoken(t) + '"';
    }

    private String spoken(String t) {
        StringBuilder out = new StringBuilder();
        int from = 0;
        int at = t.indexOf('%');
        while (at >= 0) {
            int end = placeholderEnd(t, at);
            String code = end < 0 ? null : placed(t.substring(at, end));
            if (code == null) {
                at = t.indexOf('%', at + 1);
                continue;
            }
            out.append(body(t.substring(from, at))).append("${").append(code).append('}');
            from = end;
            at = t.indexOf('%', end);
        }
        return out.append(body(t.substring(from))).toString();
    }

    private static int placeholderEnd(String t, int at) {
        int open = at + 1;
        while (open < t.length() && (Character.isLetter(t.charAt(open)) || t.charAt(open) == '_')) open++;
        if (open == at + 1 || open >= t.length() || t.charAt(open) != '(') return -1;
        int depth = 0;
        for (int i = open; i < t.length(); i++) {
            if (t.charAt(i) == '(') depth++;
            else if (t.charAt(i) == ')' && --depth == 0) return i + 1;
        }
        return -1;
    }

    private String placed(String whole) {
        int open = whole.indexOf('(');
        String head = whole.substring(1, open), inner = whole.substring(open + 1, whole.length() - 1);
        if (inner.isEmpty() || inner.matches(".*[`\"\\\\{}].*")) return null;
        if (head.equals("math")) {
            String printed = formula(whole);
            return printed == null || printed.matches("[^ (]+") ? null : printed;
        }
        if (head.equals("val"))
            return data.gameValues().containsKey(inner) && Wire.NO_TARGET.contains(data.gameValues().get(inner).group()) ? "value." + inner : null;
        if (head.startsWith("length")) {
            String printed = length(whole);
            return printed;
        }
        if (inner.matches(".*[,()%].*")) return null;
        String scope = switch (head) {
            case "var" -> "game";
            case "var_local" -> "local";
            case "var_save" -> "save";
            case "var_line" -> "line";
            default -> null;
        };
        if (scope == null) return null;
        if (scope.equals("line") && lineParams.containsKey(inner)) return varName(inner);
        String printed = printed(inner, scope);
        if (printed != null) read.add(inner);
        return printed;
    }

    private static String prefix(String parsing) {
        return switch (parsing) {
            case "plain" -> "plain";
            case "minimessage" -> "mm";
            case "json" -> "json";
            default -> null;
        };
    }

    private String number(Map<String, Object> m) {
        if (!only(m, "type", "number")) return null;
        Object n = m.get("number");
        if (n instanceof String s) {
            String length = length(s);
            if (length != null) return length;
            String formula = formula(s);
            return formula != null ? formula : "num(" + quote(s, null) + ")";
        }
        return n instanceof Json.Num num ? literal(num) : null;
    }

    private String formula(String text) {
        if (!text.startsWith("%math(") || !text.endsWith(")")) return null;
        String inner = text.substring("%math(".length(), text.length() - 1);
        MathPrint.Node node = MathPrint.parse(inner);
        if (node == null || !MathPrint.moving(node) || !MathPrint.math(node, 0).equals(inner)) return null;
        if (node instanceof MathPrint.Call call
                && (names.resolve(Names.Space.ACTION, call.name()) != null || functions.containsKey(call.name()))) return null;
        return MathPrint.printed(node, this::formulaVariable, this::formulaValue);
    }

    private String formulaVariable(String name, String scope) {
        if (scope != null && scope.startsWith("#")) return printed(name, scope.substring(1));
        if (notNumbers.contains(name)) return null;
        if (scope == null) {
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || Texts.mathWord(name)) return null;
            if (lineParams.containsKey(name)) {
                String type = lineParams.get(name);
                return type.equals("any") || type.equals("number") ? varName(name) : null;
            }
            String known = scopes.get(name);
            if (known == null || CONFLICT.equals(known) || unwritable(name)) return null;
            read.add(name);
            return varName(name);
        }
        if (unwritable(name) || !SCOPES.contains(scope) || lineParams.containsKey(name)) return null;
        read.add(name);
        boolean ascii = name.matches("[A-Za-z_][A-Za-z0-9_]*") && !Texts.mathWord(name);
        if (!ascii && scope.equals(scopes.get(name))) return varName(name);
        return scope + "." + varName(name);
    }

    private String formulaValue(String id, String unused) {
        if (!data.gameValues().containsKey(id)) return null;
        String kind = Kinds.ofGameValue(data, id);
        return kind == null || kind.equals(Kinds.NUMBER) ? "value." + id : null;
    }

    private String length(String text) {
        int open = text.indexOf('(');
        if (!text.startsWith("%length") || open < 0 || !text.endsWith(")")) return null;
        String name = text.substring(open + 1, text.length() - 1);
        String scope = switch (text.substring(0, open)) {
            case "%length" -> "game";
            case "%length_local" -> "local";
            case "%length_save" -> "save";
            case "%length_line" -> "line";
            default -> null;
        };
        if (scope == null || name.isEmpty() || name.matches(".*[,()].*")) return null;
        String printed = printed(name, scope);
        if (printed == null) return null;
        read.add(name);
        return "len(" + printed + ")";
    }

    private static String literal(Json.Num n) {
        BigDecimal d = new BigDecimal(n.text()).stripTrailingZeros();
        if (d.signum() == 0) return "0";
        return d.toPlainString();
    }

    private String location(Map<String, Object> m) {
        if (!only(m, "type", "x", "y", "z", "yaw", "pitch")) return null;
        List<String> parts = numbers(m, "x", "y", "z", "yaw", "pitch");
        if (parts == null) return null;
        if (parts.get(3).equals("0") && parts.get(4).equals("0")) parts = parts.subList(0, 3);
        return "loc(" + String.join(", ", parts) + ")";
    }

    private String vector(Map<String, Object> m) {
        if (!only(m, "type", "x", "y", "z")) return null;
        List<String> parts = numbers(m, "x", "y", "z");
        return parts == null ? null : "vec(" + String.join(", ", parts) + ")";
    }

    private static List<String> numbers(Map<String, Object> m, String... keys) {
        List<String> out = new ArrayList<>();
        for (String k : keys) {
            if (!(m.get(k) instanceof Json.Num n)) return null;
            out.add(literal(n));
        }
        return out;
    }

    private String variable(Map<String, Object> m) {
        if (!only(m, "type", "variable", "scope") || !(m.get("variable") instanceof String name)
                || !(m.get("scope") instanceof String scope)) return null;
        String printed = printed(name, scope);
        if (printed != null) read.add(name);
        return printed;
    }

    private void read(Object v) {
        if (v instanceof Map<?, ?> m && m.get("variable") instanceof String n) read.add(n);
    }

    private String gameValue(Map<String, Object> m) {
        if (!only(m, "type", "game_value", "selection") || !(m.get("game_value") instanceof String id)
                || !data.gameValues().containsKey(id) || !(m.get("selection") instanceof String selection)) return null;
        boolean noTarget = Wire.NO_TARGET.contains(data.gameValues().get(id).group());
        if (selection.equals("null")) return noTarget ? "value." + id : null;
        for (Map.Entry<String, String> e : names.selectors("value").entrySet()) {
            if (!Json.write(Context.obj("type", e.getValue())).equals(selection)) continue;
            String target = e.getValue().equals("default") && !noTarget ? "" : "@" + e.getKey() + " ";
            return target + "value." + id;
        }
        return null;
    }

    public static String fields(Data data, Names names, String base64) {
        String printed = new Decompiler(data, names).fields(base64);
        if (printed == null) return null;
        Compiler.Result r = Compiler.compile(data, names, "when world_start {\n    x = " + printed + "\n}\n");
        if (!r.ok()) return null;
        Object op = Json.array(Json.object(Json.array(r.module().get("handlers")).get(0)).get("operations")).get(0);
        for (Object v : Json.array(Json.object(op).get("values"))) {
            Map<String, Object> e = Json.object(v);
            if ("value".equals(e.get("name")) && e.get("value") instanceof Map<?, ?> item
                    && base64.equals(item.get("item"))) return printed;
        }
        return null;
    }

    private String fields(String b64) {
        Items.Stack s = b64.equals(Items.EMPTY) ? null : Items.decode(b64);
        if (s == null || s.count() < 1 || s.count() > 99 || !data.minecraft().items().contains(s.id())) return null;
        String id = shortId(s.id());
        return id == null ? null : stack(id, s);
    }

    private String item(Map<String, Object> m) {
        if (!only(m, "type", "item") || !(m.get("item") instanceof String b64)) return null;
        String printed = fields(b64);
        if (printed != null) {
            itemUses.merge(printed, 1, Integer::sum);
            return printed;
        }
        if (!b64.startsWith("H4sI") && !b64.equals(Items.EMPTY)) return null;
        return "item(" + quote(b64, null) + ")";
    }

    private String stack(String id, Items.Stack s) {
        StringBuilder out = new StringBuilder("item(").append(id);
        if (s.count() != 1) out.append(", ").append(s.count());
        if (s.components() != null) {
            List<ItemSpec.Part> parts = ItemSpec.read(s.components(), data.minecraft().enchantments());
            if (parts == null) return null;
            for (ItemSpec.Part p : parts) {
                String printed = part(p);
                if (printed == null) return null;
                out.append(", ").append(printed);
            }
        }
        return out.append(')').toString();
    }

    private String part(ItemSpec.Part p) {
        return switch (p) {
            case ItemSpec.Name n -> "name: " + quote(n.text().text(), prefix(n.text().parsing()));
            case ItemSpec.Lore l -> {
                List<String> lines = new ArrayList<>();
                for (ItemSpec.Line line : l.lines()) lines.add(quote(line.text(), prefix(line.parsing())));
                yield "lore: [" + String.join(", ", lines) + "]";
            }
            case ItemSpec.Enchantments e -> {
                List<String> levels = new ArrayList<>();
                for (Map.Entry<String, Integer> lv : e.levels().entrySet()) levels.add(key(lv.getKey()) + ": " + lv.getValue());
                yield "enchantments: {" + String.join(", ", levels) + "}";
            }
            case ItemSpec.Flag f -> f.key() + ": " + f.on();
            case ItemSpec.Whole w -> w.key() + ": " + w.value();
            case ItemSpec.Model m -> {
                String text = literal(new Json.Num(Float.toString(m.value())));
                yield (float) Double.parseDouble(text) == m.value() ? "model: " + text : null;
            }
            case ItemSpec.Tags t -> {
                List<String> values = new ArrayList<>();
                for (Map.Entry<String, String> v : t.values().entrySet()) values.add(key(v.getKey()) + ": " + quote(v.getValue(), null));
                yield "tags: {" + String.join(", ", values) + "}";
            }
            case ItemSpec.Raw r -> {
                try {
                    String snbt = Nbt.print(r.entries());
                    yield Nbt.same(Nbt.parse(snbt), r.entries()) ? "components: " + quote(snbt, null) : null;
                } catch (IllegalArgumentException | Nbt.Error e) {
                    yield null;
                }
            }
        };
    }

    private static String key(String k) {
        return Keywords.plainName(k) ? k : quote(k, null);
    }

    private static String shortId(String id) {
        if (!id.equals(id.toLowerCase(Locale.ROOT))) return null;
        if (id.startsWith("minecraft:")) {
            String bare = id.substring("minecraft:".length());
            if (Keywords.plainName(bare)) return bare;
        }
        return id.contains(":") ? quote(id, null) : null;
    }

    private String block(Map<String, Object> m) {
        if (!only(m, "type", "block") || !(m.get("block") instanceof String id)) return null;
        if (!data.minecraft().blocks().contains(id)) return null;
        String s = shortId(id);
        return s == null ? null : "block(" + s + ")";
    }

    private String sound(Map<String, Object> m) {
        if (!only(m, "type", "sound", "pitch", "volume", "variation", "source")) return null;
        if (!(m.get("sound") instanceof String id) || !id.startsWith("minecraft:")) return null;
        String bare = id.substring("minecraft:".length());
        if (!data.pickers().sounds().contains(bare)) return null;
        if (!(m.get("pitch") instanceof Json.Num pitch) || !(m.get("volume") instanceof Json.Num volume)
                || !(m.get("variation") instanceof String variation) || !(m.get("source") instanceof String source)
                || !Wire.SOUND_SOURCES.contains(source.toLowerCase(Locale.ROOT))
                || !source.equals(source.toUpperCase(Locale.ROOT))) return null;
        StringBuilder out = new StringBuilder("sound(").append(path(bare));
        if (!literal(pitch).equals("1")) out.append(", pitch: ").append(literal(pitch));
        if (!literal(volume).equals("1")) out.append(", volume: ").append(literal(volume));
        if (!variation.isEmpty()) out.append(", variation: ").append(quote(variation, null));
        if (!source.equals("MASTER")) out.append(", source: ").append(source.toLowerCase(Locale.ROOT));
        return out.append(')').toString();
    }

    private static String path(String dotted) {
        for (String part : dotted.split("\\.", -1))
            if (!Lexer.identifier(part)) return quote(dotted, null);
        String first = dotted.split("\\.")[0];
        return Keywords.word(first) || Wire.CALL_WORDS.contains(first) ? quote(dotted, null) : dotted;
    }

    private String particle(Map<String, Object> m) {
        if (!(m.get("particle_type") instanceof String id) || !id.equals(id.toUpperCase(Locale.ROOT))) return null;
        List<String> extras = data.pickers().particles().get(id);
        if (extras == null) return null;
        Set<String> keys = new LinkedHashSet<>(List.of("type", "particle_type", "count", "first_spread", "second_spread"));
        if (extras.contains("Движение")) keys.addAll(List.of("x_motion", "y_motion", "z_motion"));
        if (extras.contains("Цвет")) keys.add("color");
        if (extras.contains("Цвет перехода")) keys.add("to_color");
        if (extras.contains("Размер")) keys.add("size");
        if (extras.contains("Материал")) keys.add("material");
        if (!m.keySet().equals(keys)) return null;
        String lower = id.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder("particle(")
                .append(Keywords.plainName(lower) && !Wire.CALL_WORDS.contains(lower)
                        ? lower : quote(lower, null));
        if (!(m.get("count") instanceof Json.Num count) || count.text().contains(".")) return null;
        if (!literal(count).equals("1")) out.append(", count: ").append(literal(count));
        for (String k : List.of("first_spread", "second_spread", "x_motion", "y_motion", "z_motion")) {
            if (!keys.contains(k)) continue;
            if (!(m.get(k) instanceof Json.Num n)) return null;
            if (!literal(n).equals("0")) out.append(", ").append(k).append(": ").append(literal(n));
        }
        for (String k : List.of("color", "to_color")) {
            if (!keys.contains(k)) continue;
            if (!(m.get(k) instanceof Json.Num n) || n.text().contains(".")) return null;
            long c = Long.parseLong(n.text());
            if (c < Integer.MIN_VALUE || c > Integer.MAX_VALUE) return null;
            if ((c & 0xFFFFFFFFL) >>> 24 != 0xFF) {
                out.append(", ").append(k).append(": ").append(c);
                continue;
            }
            int rgb = (int) (c & 0xFFFFFF);
            if (rgb != 0xFF0000) out.append(", ").append(k).append(": ").append(quote(String.format("#%06x", rgb), null));
        }
        if (keys.contains("size")) {
            if (!(m.get("size") instanceof Json.Num n)) return null;
            if (!literal(n).equals("1")) out.append(", size: ").append(literal(n));
        }
        if (keys.contains("material")) {
            if (!(m.get("material") instanceof String raw)) return null;
            boolean bukkit = raw.matches("[A-Z0-9_]+");
            if (!bukkit && !(raw.startsWith("minecraft:") && raw.equals(raw.toLowerCase(Locale.ROOT)))) return null;
            String mat = bukkit ? "minecraft:" + raw.toLowerCase(Locale.ROOT) : raw;
            if (!data.minecraft().blocks().contains(mat) && !data.minecraft().items().contains(mat)) return null;
            String s = shortId(mat);
            if (s == null) return null;
            if (!bukkit) notes.add("материал частицы «" + raw + "» записан по-старому — при сборке станет "
                    + Values.material(mat) + ", как его понимает сервер");
            if (!mat.equals("minecraft:stone")) out.append(", material: ").append(s);
        }
        return out.append(')').toString();
    }

    private String potion(Map<String, Object> m) {
        if (!only(m, "type", "potion", "duration", "amplifier") || !(m.get("potion") instanceof String id)
                || !data.pickers().potions().contains(id)) return null;
        if (!(m.get("duration") instanceof Json.Num duration) || duration.text().contains(".")
                || !(m.get("amplifier") instanceof Json.Num amplifier) || amplifier.text().contains(".")) return null;
        String s = shortId(id);
        if (s == null) return null;
        StringBuilder out = new StringBuilder("potion(").append(s);
        if (!literal(amplifier).equals("0")) out.append(", amplifier: ").append(literal(amplifier));
        if (!literal(duration).equals("-1")) out.append(", duration: ").append(literal(duration));
        return out.append(')').toString();
    }

    private String array(Map<String, Object> m) {
        if (!only(m, "type", "values") || !(m.get("values") instanceof List<?> cells)) return null;
        if (!cells.isEmpty() && isHole(cells.get(cells.size() - 1))) return null;
        List<String> printed = new ArrayList<>();
        for (Object c : cells) printed.add(value(c, true));
        return "[" + String.join(", ", printed) + "]";
    }

    private String map(Map<String, Object> m) {
        if (!only(m, "type", "values") || !(m.get("values") instanceof Map<?, ?> entries)) return null;
        List<String> printed = new ArrayList<>();
        for (Map.Entry<?, ?> e : entries.entrySet()) {
            String key = (String) e.getKey();
            Object parsed;
            try {
                parsed = Json.parse(key);
            } catch (Json.Error ex) {
                return null;
            }
            if (!Json.write(parsed).equals(key) || isHole(parsed)) return null;
            boolean outer = literalKeys;
            literalKeys = true;
            String k = value(parsed, false);
            literalKeys = outer;
            if (k.startsWith("raw(") || k.startsWith("[") || k.startsWith("{")) return null;
            printed.add(k + ": " + value(e.getValue(), true));
        }
        return "{" + String.join(", ", printed) + "}";
    }

    private String rawValue(Object v) {
        notes.add("значение записано как есть");
        return "raw(" + quote(Json.write(v), null) + ")";
    }

    private String actionName(Data.Action a) {
        Names.Entry e = names.byId(a.id());
        return e.bare() ? e.name() : e.qualified();
    }

    private String functionName(String name) {
        boolean plain = Keywords.dotted(name) || Keywords.plainName(name) && !Wire.CALL_WORDS.contains(name)
                && names.resolve(Names.Space.ACTION, name) == null;
        return plain ? name : "`" + name + "`";
    }

    private static String varName(String name) {
        return Keywords.name(name);
    }

    private boolean unwritable(String name) {
        return name.isEmpty() || name.indexOf('`') >= 0 || name.indexOf('\n') >= 0;
    }

    private static boolean isHole(Object v) {
        return v instanceof Map<?, ?> m && m.isEmpty();
    }

    private static boolean isArray(Object v) {
        return v instanceof Map<?, ?> m && "array".equals(m.get("type"));
    }

    private static boolean enumIs(Object v, String id) {
        return v instanceof Map<?, ?> m && m.size() == 2 && "enum".equals(m.get("type")) && id.equals(m.get("enum"));
    }

    private static boolean only(Map<String, Object> m, String... allowed) {
        Set<String> ok = Set.of(allowed);
        for (String k : m.keySet()) if (!ok.contains(k)) return false;
        return true;
    }

    static String quote(String s, String prefix) {
        return (prefix == null ? "" : prefix) + '"' + body(s) + '"';
    }

    private static String body(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                out.append("\\\"");
            } else if (c == '$' && i + 1 < s.length() && s.charAt(i + 1) == '{') {
                out.append("\\$");
            } else if (c == '\\') {
                char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
                out.append(next == '"' || next == '\\' || next == 'u' || next == '$' || next == '\0' ? "\\\\" : "\\");
            } else if (c < 0x20 || c == 0x7F) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
