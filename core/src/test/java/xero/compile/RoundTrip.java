package xero.compile;

import xero.data.Data;
import xero.json.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class RoundTrip {
    static Object normalize(Data data, Map<String, Object> module) {
        List<Map<String, Object>> handlers = new ArrayList<>();
        for (Object h : Json.array(module.get("handlers"))) handlers.add(Json.object(h));
        handlers.sort(Comparator.comparingInt(h -> h.get("position") instanceof Json.Num n ? n.intValue() : 0));
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> h : handlers) {
            Map<String, Object> copy = new TreeMap<>(Json.object(Delivery.normal(data, h)));
            if (h.get("position") instanceof Json.Num n) copy.put("position", n.intValue());
            out.add(copy);
        }
        return out;
    }

    static Map<String, Object> healed(Map<String, Object> module) {
        Map<String, Object> copy = Json.object(Json.parse(Json.write(module)));
        Map<String, Map<String, Object>> params = new HashMap<>();
        for (Object h : Json.array(copy.get("handlers"))) {
            Map<String, Object> handler = Json.object(h);
            if (!(handler.get("name") instanceof String name) || !(handler.get("values") instanceof List<?> values)) continue;
            for (Object v : values) {
                Map<String, Object> e = Json.object(v);
                if (!"parameters".equals(e.get("name")) || !(e.get("value") instanceof Map<?, ?> arr)
                        || !(arr.get("values") instanceof List<?> list)) continue;
                Map<String, Object> own = new LinkedHashMap<>();
                for (Object raw : list) {
                    Map<String, Object> p = Json.object(raw);
                    if ("true".equals(p.get("is_required"))) continue;
                    Map<String, Object> value = new LinkedHashMap<>();
                    if ("enum".equals(p.get("type_key"))) {
                        value.put("type", "text");
                        value.put("text", p.get("default_element"));
                        value.put("parsing", "plain");
                    }
                    own.put(key((String) p.get("name")), value);
                }
                params.put(name, own);
            }
        }
        for (Object h : Json.array(copy.get("handlers"))) heal(Json.object(h), params);
        return copy;
    }

    private static String key(String name) {
        return "{\"type\":\"text\",\"text\":" + Json.write(name) + ",\"parsing\":\"plain\"}";
    }

    private static void heal(Map<String, Object> op, Map<String, Map<String, Object>> params) {
        if (op.get("operations") instanceof List<?> inner) for (Object o : inner) heal(Json.object(o), params);
        boolean call = "call_function".equals(op.get("action")), start = "start_process".equals(op.get("action"));
        if ((!call && !start) || !(op.get("values") instanceof List<?> raw)) return;
        List<Object> values = Json.array(op.get("values"));
        Object name = null;
        Map<String, Object> args = null;
        for (Object v : raw) {
            Map<String, Object> e = Json.object(v);
            if ("function_name".equals(e.get("name")) || "process_name".equals(e.get("name")))
                name = Json.object(e.get("value")).get("text");
            if ("args".equals(e.get("name"))) args = Json.object(Json.object(e.get("value")).get("values"));
        }
        Map<String, Object> own = params.get(name);
        if (own == null || own.isEmpty()) return;
        if (args == null) {
            args = new LinkedHashMap<>();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", "map");
            map.put("values", args);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", "args");
            entry.put("value", map);
            values.add(entry);
        }
        for (Map.Entry<String, Object> p : own.entrySet()) args.putIfAbsent(p.getKey(), p.getValue());
    }

    static Object plain(Object v) {
        return Delivery.plain(v);
    }

    static String firstDifference(Object a, Object b, String path) {
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            for (Object k : x.keySet()) {
                if (!y.containsKey(k)) return path + "." + k + ": нет после круга";
                String d = firstDifference(x.get(k), y.get(k), path + "." + k);
                if (d != null) return d;
            }
            for (Object k : y.keySet()) if (!x.containsKey(k)) return path + "." + k + ": лишнее после круга";
            return null;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return path + ": длина " + x.size() + " ≠ " + y.size();
            for (int i = 0; i < x.size(); i++) {
                String d = firstDifference(x.get(i), y.get(i), path + "[" + i + "]");
                if (d != null) return d;
            }
            return null;
        }
        if (a == null ? b == null : a.equals(b)) return null;
        String sa = Json.write(a), sb = Json.write(b);
        return path + ": " + sa.substring(0, Math.min(150, sa.length())) + " ≠ " + sb.substring(0, Math.min(150, sb.length()));
    }

    private RoundTrip() {}
}
