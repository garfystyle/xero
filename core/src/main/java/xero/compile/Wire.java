package xero.compile;

import xero.data.Data;
import xero.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Wire {
    public record Builtin(String name, boolean value, String doc, List<String> args) {}

    public static final List<Builtin> BUILTINS = List.of(
            new Builtin("item", true, "предмет: item(diamond, 5)", List.of("id", "count", "name: текст",
                    "lore: [текст, …]", "enchantments: {чары: уровень}", "unbreakable: true", "damage: число",
                    "glint: true|false", "model: число", "hide_tooltip: true", "tags: {имя: текст}",
                    "components: \"SNBT\"")),
            new Builtin("loc", true, "местоположение: loc(x, y, z, yaw, pitch)", List.of("x", "y", "z", "yaw", "pitch")),
            new Builtin("vec", true, "вектор: vec(x, y, z)", List.of("x", "y", "z")),
            new Builtin("sound", true, "звук: sound(entity.player.levelup, pitch: 2)", List.of("id", "volume: число",
                    "pitch: число", "variation: текст", "source: master|music|…")),
            new Builtin("particle", true, "частица: particle(flame, count: 10)", List.of("id", "count: число",
                    "first_spread: число", "second_spread: число", "x_motion: число", "y_motion: число",
                    "z_motion: число", "color: \"#RRGGBB\"", "to_color: \"#RRGGBB\"", "size: число", "material: блок")),
            new Builtin("potion", true, "зелье: potion(speed, amplifier: 1, duration: 200)",
                    List.of("id", "amplifier: число", "duration: тики")),
            new Builtin("block", true, "блок: block(stone)", List.of("id")),
            new Builtin("num", true, "число текстом: num(\"%math(…)\")", List.of("\"%math(…)\"")),
            new Builtin("raw", true, "значение как есть: raw(\"{…JSON…}\")", List.of("\"{…JSON…}\"")),
            new Builtin("enum", true, "вариант настройки как есть: enum(\"ERROR\"), привязанный к переменной: enum(\"ERROR\", x)",
                    List.of("\"ВАРИАНТ\"", "переменная")),
            new Builtin("len", true, "длина списка или словаря без блока: len(список)", List.of("список")),
            new Builtin("call", false, "вызов функции по имени из значения: call(имя, аргумент: значение)",
                    List.of("имя", "аргумент: значение")));

    public static final Set<String> CALL_WORDS;
    static final Set<String> VALUES;

    static {
        Set<String> all = new LinkedHashSet<>();
        Set<String> values = new LinkedHashSet<>();
        for (Builtin b : BUILTINS) {
            all.add(b.name());
            if (b.value()) values.add(b.name());
        }
        CALL_WORDS = Collections.unmodifiableSet(all);
        VALUES = Collections.unmodifiableSet(values);
    }

    static final Set<String> CONDITIONAL = Set.of(
            "repeat_while", "select_player_by_conditional", "select_entity_by_conditional",
            "select_add_player_by_conditional", "select_add_entity_by_conditional",
            "select_filter_by_conditional");

    static final Map<String, String> COMPARE = ordered(
            "==", "if_variable_equals", "!=", "if_variable_equals",
            ">", "if_variable_greater", ">=", "if_variable_greater_or_equals",
            "<", "if_variable_less", "<=", "if_variable_less_or_equals");

    static final Map<String, String> COMPOUND = ordered(
            "+=", "set_variable_add", "-=", "set_variable_subtract",
            "*=", "set_variable_multiply", "/=", "set_variable_divide");

    static final List<String> SOUND_SOURCES = List.of(
            "master", "music", "record", "weather", "block", "hostile", "neutral", "player",
            "ambient", "voice", "ui");

    static final Map<String, List<String>> START_SETTINGS = ordered(
            "local_variables_mode", List.of("dont_copy", "copy", "share"),
            "target_mode", List.of("current_target", "current_selection", "no_target", "for_each_in_selection"));

    static final Set<String> NO_TARGET = Set.of("Мир", "Событие");

    static final Set<String> LOGIC = Set.of("and", "or", "in");

    static final String RESULT = "__xero_";
    static final String TEMP = "__xero";
    static final String NO_TRANSLATIONS = "{\"translations\":{}}";
    static final int PARAM_SLOTS = 27;

    static String compareOp(String id) {
        for (Map.Entry<String, String> e : COMPARE.entrySet())
            if (e.getValue().equals(id) && !e.getKey().equals("!=")) return e.getKey();
        return null;
    }

    static String compoundOp(String id) {
        for (Map.Entry<String, String> e : COMPOUND.entrySet()) if (e.getValue().equals(id)) return e.getKey();
        return null;
    }

    static final String MARKER_ICON = "H4sIAAAAAAAA/+NiYGBm4HZJLEkMSy0qzszPY2AQVudgYMpMYRDNzcxLTS5KTCuxSs5PSspJLS7Jz0tlZmBNzi/NK2FgYGBkAAAxEbrgPgAAAA\\u003d\\u003d";

    static Map<String, Object> markerParameter(String name, List<String> options, String fallback, int index) {
        List<Object> elements = new ArrayList<>();
        for (String o : options)
            elements.add("{\"name\":" + Json.write(o) + ",\"display_name\":{\"translations\":{}},\"icon\":\"" + MARKER_ICON + "\"}");
        return Context.obj("type", "parameter", "type_key", "enum", "description", NO_TRANSLATIONS, "name", name,
                "slot", (index / 9) * 18 + index % 9, "elements", Json.write(elements), "default_element", fallback);
    }

    static Map<String, Object> parameter(String name, String type, boolean required, String fallback, int index) {
        int slot = (index / 9) * 18 + index % 9;
        return Context.obj("type", "parameter", "type_key", "singular", "description", NO_TRANSLATIONS,
                "name", name, "value_type", type, "is_required", required ? "true" : "false",
                "default_value", fallback, "slot", slot, "description_slot", slot + 9);
    }

    static boolean sameInAnyMarkup(String text) {
        return text.indexOf('&') < 0 && text.indexOf('§') < 0 && text.indexOf('\\') < 0;
    }

    static boolean hole(Object v) {
        return v instanceof Map<?, ?> m && m.isEmpty();
    }

    static List<Object> tidy(Data.Action action, List<Object> values) {
        List<Object> out = new ArrayList<>();
        for (Object raw : values) {
            if (!(raw instanceof Map<?, ?> m) || !(m.get("name") instanceof String name)) {
                out.add(raw);
                continue;
            }
            Object v = m.get("value");
            if (hole(v)) continue;
            List<Object> cells = action.plural(name) ? cells(v) : null;
            if (cells == null) {
                out.add(raw);
                continue;
            }
            int size = cells.size();
            trim(cells);
            if (cells.isEmpty()) continue;
            out.add(cells.size() == size ? raw : Context.entry(name, Context.array(cells)));
        }
        return out;
    }

    static List<Object> tidyDeclaration(List<Object> values) {
        List<Object> out = new ArrayList<>();
        for (Object raw : values) {
            if (!(raw instanceof Map<?, ?> m) || !(m.get("name") instanceof String name)) {
                out.add(raw);
                continue;
            }
            Object v = m.get("value");
            if (hole(v) || name.equals("icon") && v instanceof Map<?, ?> i && Items.EMPTY.equals(i.get("item"))
                    || name.equals("is_hidden") && v instanceof Map<?, ?> h && "FALSE".equals(h.get("enum"))) continue;
            List<Object> cells = name.equals("description") ? cells(v) : null;
            if (cells == null) {
                out.add(raw);
                continue;
            }
            trim(cells);
            if (!cells.isEmpty()) out.add(Context.entry(name, Context.array(cells)));
        }
        return out;
    }

    private static List<Object> cells(Object v) {
        if (v instanceof Map<?, ?> m && m.size() == 2 && "array".equals(m.get("type")) && m.get("values") instanceof List<?> l)
            return new ArrayList<>(l);
        return null;
    }

    private static void trim(List<Object> cells) {
        while (!cells.isEmpty() && hole(cells.get(cells.size() - 1))) cells.remove(cells.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> ordered(Object... kv) {
        Map<String, V> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], (V) kv[i + 1]);
        return Collections.unmodifiableMap(m);
    }

    private Wire() {}
}
