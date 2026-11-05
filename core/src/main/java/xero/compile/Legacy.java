package xero.compile;

import xero.data.Data;
import xero.json.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Legacy {
    private static final Map<String, String> PARTICLES = Map.ofEntries(
            Map.entry("EXPLOSION_NORMAL", "POOF"), Map.entry("EXPLOSION_LARGE", "EXPLOSION"),
            Map.entry("EXPLOSION_HUGE", "EXPLOSION_EMITTER"), Map.entry("FIREWORKS_SPARK", "FIREWORK"),
            Map.entry("WATER_BUBBLE", "BUBBLE"), Map.entry("WATER_SPLASH", "SPLASH"), Map.entry("WATER_WAKE", "FISHING"),
            Map.entry("WATER_DROP", "RAIN"), Map.entry("SUSPENDED", "UNDERWATER"), Map.entry("SUSPENDED_DEPTH", "UNDERWATER"),
            Map.entry("CRIT_MAGIC", "ENCHANTED_HIT"), Map.entry("SMOKE_NORMAL", "SMOKE"), Map.entry("SMOKE_LARGE", "LARGE_SMOKE"),
            Map.entry("SPELL", "EFFECT"), Map.entry("SPELL_INSTANT", "INSTANT_EFFECT"), Map.entry("SPELL_MOB", "ENTITY_EFFECT"),
            Map.entry("SPELL_MOB_AMBIENT", "ENTITY_EFFECT"), Map.entry("SPELL_WITCH", "WITCH"),
            Map.entry("DRIP_WATER", "DRIPPING_WATER"), Map.entry("DRIP_LAVA", "DRIPPING_LAVA"),
            Map.entry("VILLAGER_ANGRY", "ANGRY_VILLAGER"), Map.entry("VILLAGER_HAPPY", "HAPPY_VILLAGER"),
            Map.entry("TOWN_AURA", "MYCELIUM"), Map.entry("ENCHANTMENT_TABLE", "ENCHANT"), Map.entry("REDSTONE", "DUST"),
            Map.entry("SNOWBALL", "ITEM_SNOWBALL"), Map.entry("SNOW_SHOVEL", "ITEM_SNOWBALL"), Map.entry("SLIME", "ITEM_SLIME"),
            Map.entry("ITEM_CRACK", "ITEM"), Map.entry("BLOCK_CRACK", "BLOCK"), Map.entry("BLOCK_DUST", "BLOCK"),
            Map.entry("MOB_APPEARANCE", "ELDER_GUARDIAN"), Map.entry("TOTEM", "TOTEM_OF_UNDYING"));

    private static final Map<String, String> POTIONS = Map.of(
            "slow", "slowness", "fast_digging", "haste", "slow_digging", "mining_fatigue",
            "increase_damage", "strength", "heal", "instant_health", "harm", "instant_damage",
            "jump", "jump_boost", "confusion", "nausea", "damage_resistance", "resistance");

    private static final Map<String, String> ITEMS = Map.of(
            "chain", "iron_chain", "grass", "short_grass", "scute", "turtle_scute");

    private static final Map<String, List<String>> ORDER = Map.of(
            "call_function", List.of("function_name", "args"),
            "start_process", List.of("process_name", "local_variables_mode", "target_mode", "args"));

    private static final int WHITE = 0xFFFFFFFF;
    private static final Set<String> NAME_ARGS = Set.of("function_name", "process_name");

    private final Data data;

    private Legacy(Data data) {
        this.data = data;
    }

    static Map<String, Object> upgrade(Data data, Map<String, Object> module) {
        Legacy legacy = new Legacy(data);
        if (module.get("handlers") instanceof List<?> handlers)
            for (Object h : handlers) if (h instanceof Map<?, ?> m) legacy.handler(Json.object(m));
        return module;
    }

    private void handler(Map<String, Object> h) {
        List<Object> values = h.get("values") instanceof List<?> l ? values(h, l) : new ArrayList<>();
        values.removeIf(e -> e instanceof Map<?, ?> m && m.containsKey("value")
                && (Wire.hole(m.get("value")) || noText(m.get("value")) || noParameters(m)));
        Object description = h.remove("description");
        Object icon = h.remove("icon");
        Object hidden = h.remove("is_hidden");
        if (!"event".equals(h.get("type"))) {
            addDescription(values, description);
            addIcon(values, icon);
            if (Boolean.TRUE.equals(hidden)) values.add(Context.entry("is_hidden", Context.enumValue("TRUE")));
            if (values.isEmpty()) h.remove("values");
            else h.put("values", values);
        }
        operations(h.get("operations"));
    }

    private static void addDescription(List<Object> values, Object description) {
        if (!(description instanceof List<?> lines) || lines.isEmpty()) return;
        List<Object> cells = new ArrayList<>();
        for (Object line : lines) cells.add(Context.text(line instanceof String s ? legacyText(s) : "", "legacy"));
        values.add(Context.entry("description", Context.array(cells)));
    }

    private static void addIcon(List<Object> values, Object icon) {
        if (!(icon instanceof String snbt) || snbt.equals("{}")) return;
        String item = stack(snbt);
        if (item != null) values.add(Context.entry("icon", Context.obj("type", "item", "item", item)));
    }

    private void operations(Object ops) {
        if (!(ops instanceof List<?> list)) return;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> op = Json.object(m);
            if (op.get("values") instanceof List<?> values) op.put("values", values(op, values));
            operations(op.get("operations"));
        }
    }

    private List<Object> values(Map<String, Object> op, List<?> values) {
        List<String> order = op.get("action") instanceof String id ? ORDER.get(id) : null;
        List<Object> out = new ArrayList<>();
        for (Object raw : values) {
            if (!(raw instanceof Map<?, ?> m) || !(m.get("name") instanceof String name)) {
                out.add(raw);
                continue;
            }
            if (order != null && !order.contains(name)) continue;
            if (order != null && name.equals("args") && noArguments(m.get("value"))) continue;
            Object v = name.equals("args") ? arguments(m.get("value")) : value(m.get("value"));
            if (NAME_ARGS.contains(name)) plainName(v);
            out.add(Context.entry(name, slotted(op, name) ? grid(v) : v));
        }
        if (order != null) out.sort(Comparator.comparingInt(e -> order.indexOf(Json.object(e).get("name"))));
        return out;
    }

    private static void plainName(Object v) {
        if (!(v instanceof Map<?, ?> m) || !"text".equals(m.get("type")) || !(m.get("text") instanceof String text)) return;
        if (text.indexOf('<') < 0 && Wire.sameInAnyMarkup(text)) Json.object(m).put("parsing", "plain");
    }

    private static boolean noArguments(Object v) {
        return Wire.hole(v) || v instanceof Map<?, ?> m && "map".equals(m.get("type")) && m.get("values") instanceof Map<?, ?> e && e.isEmpty();
    }

    private static boolean noParameters(Map<?, ?> entry) {
        return "parameters".equals(entry.get("name")) && entry.get("value") instanceof Map<?, ?> m
                && m.get("values") instanceof List<?> l && l.isEmpty();
    }

    private static boolean noText(Object v) {
        return v instanceof Map<?, ?> m && "localized_text".equals(m.get("type")) && Wire.NO_TRANSLATIONS.equals(m.get("data"));
    }

    private boolean slotted(Map<String, Object> op, String name) {
        Data.Action action = op.get("action") instanceof String id ? data.actions().get(id) : null;
        Data.Arg arg = action == null ? null : action.arg(name);
        return arg != null && arg.slotted();
    }

    private static Object grid(Object v) {
        if (!(v instanceof Map<?, ?> m) || !(m.get("values") instanceof List<?> cells)) return v;
        List<Object> out = new ArrayList<>();
        for (Object cell : cells) out.add(Wire.hole(cell) ? Context.obj("type", "item", "item", Items.EMPTY) : cell);
        Map<String, Object> copy = new LinkedHashMap<>(Json.object(m));
        copy.put("values", out);
        return copy;
    }

    private Object arguments(Object v) {
        if (!(v instanceof Map<?, ?> m) || !(m.get("values") instanceof Map<?, ?> entries)) return value(v);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : entries.entrySet()) out.put(plainKey((String) e.getKey()), value(e.getValue()));
        Map<String, Object> copy = new LinkedHashMap<>(Json.object(m));
        copy.put("values", out);
        return copy;
    }

    private static String plainKey(String key) {
        try {
            if (Json.parse(key) instanceof Map<?, ?> m && "text".equals(m.get("type")) && m.get("text") instanceof String t)
                return Json.write(Context.text(t, "plain"));
        } catch (Json.Error ignored) {
        }
        return key;
    }

    private Object value(Object raw) {
        if (raw instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object x : l) out.add(value(x));
            return out;
        }
        if (!(raw instanceof Map<?, ?> m)) return raw;
        Map<String, Object> v = new LinkedHashMap<>(Json.object(m));
        if (!(v.get("type") instanceof String type)) return v;
        return switch (type) {
            case "text" -> {
                v.putIfAbsent("parsing", "legacy");
                yield v;
            }
            case "sound" -> {
                v.putIfAbsent("variation", "");
                v.putIfAbsent("source", "MASTER");
                yield v;
            }
            case "potion" -> potion(v);
            case "particle" -> particle(v);
            case "game_value" -> gameValue(v);
            case "item" -> item(v);
            case "block" -> block(v);
            case "localized_text" -> localized(v);
            case "parameter" -> parameter(v);
            case "array" -> array(v);
            case "map" -> map(v);
            default -> v;
        };
    }

    private Object potion(Map<String, Object> v) {
        if (!(v.get("potion") instanceof String id)) return v;
        String bare = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        v.put("potion", "minecraft:" + POTIONS.getOrDefault(bare, bare));
        return v;
    }

    private Object gameValue(Map<String, Object> v) {
        if (!(v.get("selection") instanceof String selection) || !(v.get("game_value") instanceof String id)) return v;
        Data.GameValue known = data.gameValues().get(id);
        if (selection.equals("null")) {
            if (known != null && !Wire.NO_TARGET.contains(known.group())) v.put("selection", "{\"type\":\"default\"}");
            return v;
        }
        try {
            v.put("selection", Json.write(Json.parse(selection)));
        } catch (Json.Error ignored) {
        }
        return v;
    }

    private Object particle(Map<String, Object> v) {
        if (!(v.get("particle_type") instanceof String old)) return v;
        Map<String, List<String>> known = data.pickers().particles();
        String id = known.containsKey(old) ? old : PARTICLES.getOrDefault(old, old);
        List<String> extras = known.get(id);
        if (extras == null) return v;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "particle");
        out.put("particle_type", id);
        out.put("count", v.getOrDefault("count", Context.num(1)));
        out.put("first_spread", v.getOrDefault("first_spread", Context.num(0)));
        out.put("second_spread", v.getOrDefault("second_spread", Context.num(0)));
        if (extras.contains("Движение"))
            for (String k : List.of("x_motion", "y_motion", "z_motion")) out.put(k, v.getOrDefault(k, Context.num(0)));
        if (extras.contains("Цвет")) out.put("color", v.getOrDefault("color", new Json.Num(Integer.toString(WHITE))));
        if (extras.contains("Цвет перехода")) out.put("to_color", v.getOrDefault("to_color", new Json.Num(Integer.toString(WHITE))));
        if (extras.contains("Размер")) out.put("size", v.getOrDefault("size", Context.num(1)));
        if (extras.contains("Материал")) out.put("material", v.getOrDefault("material", "STONE"));
        return out;
    }

    private static Object block(Map<String, Object> v) {
        if (!(v.get("block") instanceof String id)) return v;
        String bare = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        if (ITEMS.containsKey(bare)) v.put("block", "minecraft:" + ITEMS.get(bare));
        return v;
    }

    private static Object localized(Map<String, Object> v) {
        if (v.get("data") instanceof String data) v.put("data", sameJson(data));
        return v;
    }

    private static String sameJson(String data) {
        try {
            return Json.write(Json.parse(data));
        } catch (Json.Error e) {
            return data;
        }
    }

    private Object parameter(Map<String, Object> v) {
        if (v.get("description") instanceof String about) v.put("description", sameJson(about));
        if (v.get("default_value") instanceof String fallback) {
            String normal = sameJson(fallback);
            try {
                normal = Json.write(sameMarkup(value(Json.parse(normal))));
            } catch (Json.Error ignored) {
            }
            v.put("default_value", "plural".equals(v.get("type_key")) && normal.equals("{}") ? Wire.EMPTY_LIST : normal);
        }
        return v;
    }

    private static Object sameMarkup(Object v) {
        if (v instanceof Map<?, ?> m && "text".equals(m.get("type")) && "plain".equals(m.get("parsing"))
                && m.get("text") instanceof String text && Wire.sameInAnyMarkup(text)) Json.object(m).put("parsing", "legacy");
        return v;
    }

    private Object item(Map<String, Object> v) {
        if (!(v.get("item") instanceof String snbt) || !snbt.startsWith("{")) return v;
        if (snbt.equals("{}")) return new LinkedHashMap<String, Object>();
        String item = stack(snbt);
        if (item != null) v.put("item", item);
        return v;
    }

    private Object array(Map<String, Object> v) {
        List<Object> cells = new ArrayList<>();
        if (v.get("values") instanceof List<?> l) for (Object cell : l) cells.add(value(cell));
        while (!cells.isEmpty() && Wire.hole(cells.get(cells.size() - 1))) cells.remove(cells.size() - 1);
        v.put("values", cells);
        return v;
    }

    private Object map(Map<String, Object> v) {
        if (!(v.get("values") instanceof Map<?, ?> entries)) return v;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : entries.entrySet()) out.put(key((String) e.getKey()), value(e.getValue()));
        v.put("values", out);
        return v;
    }

    private String key(String key) {
        try {
            return Json.write(value(Json.parse(key)));
        } catch (Json.Error e) {
            return key;
        }
    }

    private static String stack(String snbt) {
        try {
            if (!(Nbt.parse(snbt) instanceof Nbt.Compound old) || !(old.get("id") instanceof Nbt.Text id)) return null;
            String bare = id.value().startsWith("minecraft:") ? id.value().substring("minecraft:".length()) : id.value();
            int count = old.get("Count") instanceof Nbt.Num n ? (int) n.bits() : 1;
            Nbt.Compound tag = old.get("tag") instanceof Nbt.Compound t ? t : new Nbt.Compound();
            Nbt.Compound components = ItemSpec.build(parts(tag));
            return Items.encode(new Items.Stack("minecraft:" + ITEMS.getOrDefault(bare, bare), count, components));
        } catch (Nbt.Error | ItemSpec.Clash | Styled.Problem | RuntimeException e) {
            return null;
        }
    }

    private static List<ItemSpec.Part> parts(Nbt.Compound tag) {
        List<ItemSpec.Part> parts = new ArrayList<>();
        if (tag.get("display") instanceof Nbt.Compound display) {
            if (display.get("Name") instanceof Nbt.Text name) parts.add(new ItemSpec.Name(new ItemSpec.Line(legacyText(name.value()), "legacy")));
            if (display.get("Lore") instanceof Nbt.ListTag lore && !lore.items().isEmpty()) {
                List<ItemSpec.Line> lines = new ArrayList<>();
                for (Nbt.Tag line : lore.items())
                    lines.add(new ItemSpec.Line(line instanceof Nbt.Text t ? legacyText(t.value()) : "", "legacy"));
                parts.add(new ItemSpec.Lore(lines));
            }
        }
        Map<String, Integer> levels = enchantments(tag);
        if (!levels.isEmpty()) parts.add(new ItemSpec.Enchantments(levels));
        if (tag.get("Damage") instanceof Nbt.Num damage && damage.bits() > 0) parts.add(new ItemSpec.Whole("damage", (int) damage.bits()));
        if (tag.get("Unbreakable") instanceof Nbt.Num flag && flag.bits() != 0) parts.add(new ItemSpec.Flag("unbreakable", true));
        if (tag.get("CustomModelData") instanceof Nbt.Num model) parts.add(new ItemSpec.Model((float) model.bits()));
        Map<String, String> tags = tags(tag);
        if (!tags.isEmpty()) parts.add(new ItemSpec.Tags(tags));
        return parts;
    }

    private static Map<String, Integer> enchantments(Nbt.Compound tag) {
        Map<String, Integer> levels = new LinkedHashMap<>();
        if (!(tag.get("Enchantments") instanceof Nbt.ListTag list)) return levels;
        for (Nbt.Tag raw : list.items()) {
            if (!(raw instanceof Nbt.Compound e) || !(e.get("id") instanceof Nbt.Text id) || !(e.get("lvl") instanceof Nbt.Num lvl)) continue;
            if (lvl.bits() >= 1) levels.put(id.value().replace("minecraft:", ""), (int) lvl.bits());
        }
        return levels;
    }

    private static Map<String, String> tags(Nbt.Compound tag) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!(tag.get(ItemSpec.BUKKIT) instanceof Nbt.Compound bukkit)) return out;
        for (Map.Entry<String, Nbt.Tag> e : bukkit.entries().entrySet())
            if (e.getKey().startsWith(ItemSpec.TAG_PREFIX) && e.getValue() instanceof Nbt.Text t)
                out.put(e.getKey().substring(ItemSpec.TAG_PREFIX.length()), t.value());
        return out;
    }

    private static final Map<String, String> COLORS = Map.ofEntries(
            Map.entry("black", "0"), Map.entry("dark_blue", "1"), Map.entry("dark_green", "2"), Map.entry("dark_aqua", "3"),
            Map.entry("dark_red", "4"), Map.entry("dark_purple", "5"), Map.entry("gold", "6"), Map.entry("gray", "7"),
            Map.entry("dark_gray", "8"), Map.entry("blue", "9"), Map.entry("green", "a"), Map.entry("aqua", "b"),
            Map.entry("red", "c"), Map.entry("light_purple", "d"), Map.entry("yellow", "e"), Map.entry("white", "f"));

    private static String legacyText(String component) {
        try {
            return flat(Json.parse(component));
        } catch (Json.Error e) {
            return component;
        }
    }

    private static String flat(Object part) {
        if (part instanceof String s) return s;
        if (part instanceof List<?> l) {
            StringBuilder out = new StringBuilder();
            for (Object x : l) out.append(flat(x));
            return out.toString();
        }
        if (!(part instanceof Map<?, ?> m)) return "";
        StringBuilder out = new StringBuilder();
        if (m.get("color") instanceof String color) {
            String code = COLORS.get(color.toLowerCase(Locale.ROOT));
            if (code != null) out.append('&').append(code);
        }
        if (Boolean.TRUE.equals(m.get("bold"))) out.append("&l");
        if (Boolean.TRUE.equals(m.get("italic"))) out.append("&o");
        if (Boolean.TRUE.equals(m.get("underlined"))) out.append("&n");
        if (m.get("text") instanceof String text) out.append(text);
        if (m.get("extra") != null) out.append(flat(m.get("extra")));
        return out.toString();
    }

}
