package xero.compile;

import xero.data.Data;
import xero.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Wire {
    static final java.util.Set<String> COORDINATES = java.util.Set.of("x", "y", "z", "yaw", "pitch");
    static final String ATTRIBUTE_SETTING = "attribute_type";
    static final java.util.Set<String> KEEPS_ELEMENTS = java.util.Set.of("set_variable_remove_list_value", "set_variable_remove_list_value_at_index",
            "set_variable_remove_list_duplicates", "set_variable_remove_map_entry", "set_variable_reverse_list", "set_variable_reverse_map",
            "set_variable_randomize_list_order", "set_variable_randomize_map_order", "set_variable_sort_any_list", "set_variable_sort_any_map",
            "set_variable_trim_list", "set_variable_clear_map");
    static final java.util.Set<String> LAST_IS_ELEMENT = java.util.Set.of("set_variable_set_list_value", "set_variable_insert_list_value",
            "set_variable_set_map_value", "set_variable_put_map_value");
    static final java.util.regex.Pattern PARAMETER_READ = java.util.regex.Pattern.compile("%var(?:_local)?\\(([^()]+)\\)");
    static final java.util.regex.Pattern VARIABLE_PLACEHOLDER = java.util.regex.Pattern.compile("%var(?:_local|_line|_game|_save)?\\([^()]*\\)");

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
            new Builtin("cell", true, "ячейка хранилища: cell(\"данные\"), общая: cell(\"данные\", storage: \"ник/хранилище\", id: \"uuid\")",
                    List.of("\"имя\"", "storage: \"ник/хранилище\"", "id: \"uuid\"")),
            new Builtin("num", true, "число текстом: num(\"%math(…)\")", List.of("\"%math(…)\"")),
            new Builtin("raw", true, "значение как есть: raw(\"{…JSON…}\")", List.of("\"{…JSON…}\"")),
            new Builtin("enum", true, "вариант настройки как есть: enum(\"ERROR\"), привязанный к переменной: enum(\"ERROR\", x)",
                    List.of("\"ВАРИАНТ\"", "переменная")),
            new Builtin("len", true, "длина списка или словаря без блока: len(список)", List.of("список")),
            new Builtin("literal", true, "значение как есть, сервер его не считает: literal([a, 2]), в вызове функции literal(x + 1)",
                    List.of("значение")),
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

    static final Set<String> IN_PLACE = Set.of(
            "set_variable_remove_list_value", "set_variable_remove_list_value_at_index", "set_variable_insert_list_value",
            "set_variable_set_list_value", "set_variable_remove_list_duplicates", "set_variable_trim_list",
            "set_variable_sort_any_list", "set_variable_reverse_list", "set_variable_randomize_list_order",
            "set_variable_flatten_list", "set_variable_set_map_value", "set_variable_put_map_value",
            "set_variable_remove_map_entry", "set_variable_sort_any_map", "set_variable_reverse_map",
            "set_variable_randomize_map_order");

    static final String APPEND = "set_variable_append_value";

    static final List<String> NOT_IN_ITEMS = List.of("click:", "hover:", "insert:", "font:", "shadow:", "lang:", "tr:", "translate:",
            "lang_or:", "tr_or:", "translate_or:", "key:", "selector:", "sel:", "score:", "nbt:", "data:", "sprite:", "head:");

    static final Set<String> LOOP_EXITS = Set.of("control_wait", "control_end_thread", "control_return_function", "control_stop_repeat");

    static final List<String> PARTICLE_FIELDS = List.of("count", "first_spread", "second_spread", "x_motion", "y_motion", "z_motion",
            "color", "to_color", "size", "material");

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

    static final Set<String> WHOLE_GRID = Set.of("player_expand_inventory_menu");

    static final Set<String> LOGIC = Set.of("and", "or", "in");

    static final String RESULT = "__xero_";
    static final String TEMP = "__xero";
    static final String NO_TRANSLATIONS = "{\"translations\":{}}";
    static final int PARAM_SLOTS = 27;
    static final int CHEST_CELLS = 54;
    static final int LABEL_BELOW = 9;
    static final String EMPTY_LIST = "{\"type\":\"array\",\"values\":[]}";

    public static boolean noTarget(String group) {
        return NO_TARGET.contains(group);
    }

    public static boolean mathFunction(String name) {
        return Texts.mathFunction(name);
    }

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

    private static int paramSlot(int index) {
        return (index / 9) * 18 + index % 9;
    }

    static final class ParamChest {
        private final Set<Integer> taken = new HashSet<>();
        private int next, paramsLeft;

        ParamChest(int params) {
            paramsLeft = params;
        }

        boolean full() {
            while (next < PARAM_SLOTS && taken.contains(paramSlot(next))) next++;
            return next >= PARAM_SLOTS;
        }

        int take() {
            full();
            int slot = paramSlot(next++);
            taken.add(slot);
            taken.add(slot + LABEL_BELOW);
            paramsLeft--;
            return slot;
        }

        List<Integer> take(int cells) {
            List<Integer> slots = new ArrayList<>(List.of(take()));
            while (slots.size() < cells) {
                int cell = free();
                if (cell < 0) return null;
                taken.add(cell);
                slots.add(cell);
            }
            return slots;
        }

        private int free() {
            if (valueCellsLeft() > paramsLeft) {
                full();
                return paramSlot(next);
            }
            for (int cell = LABEL_BELOW; cell < CHEST_CELLS; cell++)
                if (!taken.contains(cell) && taken.contains(cell - LABEL_BELOW) && cell / LABEL_BELOW % 2 == 1) return cell;
            return -1;
        }

        private int valueCellsLeft() {
            int left = 0;
            for (int i = next; i < PARAM_SLOTS; i++) if (!taken.contains(paramSlot(i))) left++;
            return left;
        }
    }

    static Map<String, Object> markerParameter(String name, List<String> options, String fallback, int slot, String about) {
        List<Object> elements = new ArrayList<>();
        for (String o : options)
            elements.add("{\"name\":" + Json.write(o) + ",\"display_name\":{\"translations\":{}},\"icon\":\"" + MARKER_ICON + "\"}");
        return Context.obj("type", "parameter", "type_key", "enum", "description", about, "name", name,
                "slot", slot, "elements", Json.write(elements), "default_element", fallback);
    }

    static Map<String, Object> parameter(String name, String type, boolean required, String fallback, int slot, String about) {
        return Context.obj("type", "parameter", "type_key", "singular", "description", about,
                "name", name, "value_type", type, "is_required", required ? "true" : "false",
                "default_value", fallback, "slot", slot, "description_slot", slot + LABEL_BELOW);
    }

    static Map<String, Object> pluralParameter(String name, String type, boolean required, String fallback, List<Integer> slots, String about) {
        return Context.obj("type", "parameter", "type_key", "plural", "description", about,
                "name", name, "value_type", type, "is_required", required ? "true" : "false", "ignore_empty_values", "true",
                "default_value", fallback, "slots", slots.toString().replace(" ", ""),
                "description_slots", slots.isEmpty() ? "[]" : "[" + (slots.get(0) + LABEL_BELOW) + "]");
    }

    static boolean sameInAnyMarkup(String text) {
        return text.indexOf('&') < 0 && text.indexOf('§') < 0 && text.indexOf('\\') < 0;
    }

    static boolean hole(Object v) {
        return v instanceof Map<?, ?> m && m.isEmpty();
    }

    static boolean dynamic(Object v) {
        if (!(v instanceof Map<?, ?> m) || !(m.get("type") instanceof String type)) return false;
        return switch (type) {
            case "variable", "game_value" -> true;
            case "text" -> m.get("text") instanceof String t && t.indexOf('%') >= 0;
            case "number" -> m.get("number") instanceof String s && s.indexOf('%') >= 0;
            case "array" -> m.get("values") instanceof List<?> cells && cells.stream().anyMatch(Wire::dynamic);
            case "map" -> m.get("values") instanceof Map<?, ?> entries
                    && (entries.values().stream().anyMatch(Wire::dynamic) || entries.keySet().stream().anyMatch(Wire::dynamicKey));
            default -> false;
        };
    }

    static boolean plainNumber(String s) {
        int i = s.startsWith("-") ? 1 : 0, digits = 0, dots = 0;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') digits++;
            else if (c == '.' && digits > 0 && dots++ == 0 && i + 1 < s.length()) continue;
            else return false;
        }
        return digits > 0;
    }

    static boolean lazy(Object v) {
        if (!(v instanceof Map<?, ?> m) || !(m.get("type") instanceof String type)) return false;
        return switch (type) {
            case "number" -> m.get("number") instanceof String s && s.indexOf('%') >= 0;
            case "text" -> m.get("text") instanceof String t && placeholder(t);
            default -> false;
        };
    }

    private static boolean placeholder(String text) {
        for (int at = text.indexOf('%'); at >= 0; at = text.indexOf('%', at + 1)) {
            int i = at + 1;
            while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) i++;
            if (i > at + 1 && i < text.length() && (text.charAt(i) == '%' || text.charAt(i) == '(')) return true;
        }
        return false;
    }

    private static boolean dynamicKey(Object key) {
        try {
            return dynamic(Json.parse(String.valueOf(key)));
        } catch (Json.Error e) {
            return false;
        }
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
            boolean known = action.arg(name) != null, slotted = known && action.arg(name).slotted();
            if (known && !action.plural(name) && emptyItem(v)) continue;
            if (byDefault(action.setting(name), v)) continue;
            List<Object> cells = action.plural(name) ? cells(v) : null;
            if (cells == null) {
                out.add(raw);
                continue;
            }
            int size = cells.size();
            boolean holes = false;
            if (known && !slotted)
                for (int i = 0; i < cells.size(); i++)
                    if (emptyItem(cells.get(i))) {
                        cells.set(i, new LinkedHashMap<String, Object>());
                        holes = true;
                    }
            trim(cells);
            if (slotted) trimEmptyItems(cells);
            if (cells.isEmpty()) continue;
            out.add(cells.size() == size && !holes ? raw : Context.entry(name, Context.array(cells)));
        }
        return out;
    }

    private static boolean byDefault(Data.Setting s, Object v) {
        return s != null && v instanceof Map<?, ?> m && m.size() == 2 && m.get("enum") instanceof String option
                && option.equalsIgnoreCase(s.fallback());
    }

    static boolean emptyItem(Object v) {
        return v instanceof Map<?, ?> m && "item".equals(m.get("type")) && Items.EMPTY.equals(m.get("item"));
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

    private static void trimEmptyItems(List<Object> cells) {
        while (!cells.isEmpty() && cells.get(cells.size() - 1) instanceof Map<?, ?> m && Items.EMPTY.equals(m.get("item")))
            cells.remove(cells.size() - 1);
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
