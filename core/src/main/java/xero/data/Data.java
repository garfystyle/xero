package xero.data;

import xero.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Data {
    public record Setting(String id, int index, Map<String, String> options, String label, String fallback) {}

    public record Arg(String id, String kind, String label, boolean list, int capacity,
                      boolean slotted) {}

    public record Action(String id, Category category, String label, List<Arg> args,
                         List<Setting> settings, Set<String> plural, String family,
                         boolean container, String sub, String canonical, String description) {
        public boolean blank() { return id.endsWith("_dummy"); }
        public boolean alias() { return !id.equals(canonical); }
        public boolean plural(String arg) { return plural.contains(arg); }
        public Arg arg(String id) {
            for (Arg a : args) if (a.id().equals(id)) return a;
            return null;
        }
        public Setting setting(String id) {
            for (Setting s : settings) if (s.id().equals(id)) return s;
            return null;
        }
        public Arg result() {
            Arg named = arg("variable");
            if (named != null && VARIABLE.equals(named.kind())) return named;
            return !args.isEmpty() && VARIABLE.equals(args.get(0).kind()) ? args.get(0) : null;
        }
    }

    public static final String ANY = "Любое значение";
    public static final String VARIABLE = "Переменная";

    private static final Map<String, String> SLOTTED = Map.of(
            "Действие над игроком|Установить предметы", "",
            "Действие над игроком|Установить содержимое Эндер-сундука", "",
            "Действие над игроком|Показать меню", "",
            "Действие над игроком|Расширить меню", "",
            "Действие над миром|Установить предметы в контейнере", "",
            "Действие над миром|Установить предметы события", "",
            "Действие над миром|Добавить рецепт крафта", "Ингредиенты");

    public record Event(String id, String group, String label, String description) {}

    public record GameValue(String id, String label, String group, String returns, String description) {}

    public record Selector(String id, String label) {}

    public record Meta(String updated, int actions, int events) {}

    public record Pickers(Set<String> sounds, Map<String, List<String>> particles, Set<String> potions) {}

    public record Component(String id, String name, String description, String example, List<String> fields) {}

    public record Minecraft(Set<String> items, Set<String> blocks, Set<String> enchantments,
                            Map<String, Component> components) {}

    public record Placeholders(Set<String> plain, Set<String> functions) {}

    private static final String ROOT = "/xero/data/";
    private static final String MOD_ROOT = "/assets/xerocode/";

    private final Map<String, Action> actions;
    private final Map<String, Event> events;
    private final Map<String, GameValue> gameValues;
    private final Map<String, List<Selector>> selectors;
    private final List<Selector> valueSelectors;
    private final Meta meta;
    private final List<String> unknownCategories;
    private Pickers pickers = new Pickers(Set.of(), Map.of(), Set.of());
    private Minecraft minecraft = new Minecraft(Set.of(), Set.of(), Set.of(), Map.of());
    private Placeholders placeholders = new Placeholders(Set.of(), Set.of());
    private Map<String, String> returns = Map.of();
    private Map<String, List<String>> named = Map.of();

    private Data(Map<String, Action> actions, Map<String, Event> events,
                 Map<String, GameValue> gameValues, Map<String, List<Selector>> selectors,
                 List<Selector> valueSelectors, Meta meta, List<String> unknownCategories) {
        this.actions = actions;
        this.events = events;
        this.gameValues = gameValues;
        this.selectors = selectors;
        this.valueSelectors = valueSelectors;
        this.meta = meta;
        this.unknownCategories = unknownCategories;
    }

    private static Data cached;

    public static synchronized Data load() {
        if (cached != null) return cached;
        Map<String, Object> extra = extra();
        Data data = parse(withExtra(resource("mapping.json"), extra), resource("values.json"), resource("catalog.json"));
        data.pickers = pickers(Json.object(Json.parse(resource("pickers.json"))), extra);
        data.returns = returns(extra);
        data.named = named(extra);
        Map<String, Object> mc = Json.object(Json.parse(resource("minecraft.json")));
        data.minecraft = new Minecraft(strings(mc.get("items")), strings(mc.get("blocks")),
                strings(mc.get("enchantments")), components(mc.get("components"),
                        Json.object(Json.parse(resource("components.json")))));
        Set<String> plain = new LinkedHashSet<>();
        Set<String> functions = new LinkedHashSet<>();
        for (Object raw : Json.array(Json.object(Json.parse(resource("placeholders.json"))).get("items"))) {
            String p = Json.string(Json.object(raw).get("p"));
            if (p.startsWith("%") && p.endsWith("()")) functions.add(p.substring(1, p.length() - 2));
            else if (p.startsWith("%") && p.endsWith("%") && p.length() > 2) plain.add(p.substring(1, p.length() - 1));
        }
        data.placeholders = new Placeholders(Collections.unmodifiableSet(plain), Collections.unmodifiableSet(functions));
        cached = data;
        return data;
    }

    public Pickers pickers() { return pickers; }
    public Minecraft minecraft() { return minecraft; }
    public Placeholders placeholders() { return placeholders; }

    public String returned(Action action) {
        String kind = returns.get(action.id());
        return kind != null ? kind : returns.get(action.canonical());
    }

    public String returned(Action action, Arg arg) {
        String own = returns.get(action.id() + "." + arg.id());
        if (own != null) return own;
        return arg.equals(action.result()) ? returned(action) : null;
    }

    public List<String> named(String actionId) {
        return named.get(actionId);
    }

    private static Map<String, List<String>> named(Map<String, Object> extra) {
        if (extra.get("named") == null) return Map.of();
        Map<String, List<String>> out = new LinkedHashMap<>();
        Json.object(extra.get("named")).forEach((id, raw) -> {
            List<String> params = new ArrayList<>();
            for (Object p : Json.array(raw)) params.add(Json.string(p));
            out.put(id, List.copyOf(params));
        });
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, String> returns(Map<String, Object> extra) {
        if (extra.get("returns") == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        Json.object(extra.get("returns")).forEach((kind, ids) -> {
            for (Object id : Json.array(ids)) out.put(Json.string(id), kind);
        });
        return Collections.unmodifiableMap(out);
    }

    private static Set<String> strings(Object raw) {
        Set<String> out = new LinkedHashSet<>();
        for (Object o : Json.array(raw)) out.add(Json.string(o));
        return Collections.unmodifiableSet(out);
    }

    private static Map<String, Component> components(Object ids, Map<String, Object> info) {
        Map<String, Component> out = new LinkedHashMap<>();
        for (Object raw : Json.array(ids)) {
            String id = Json.string(raw);
            Map<String, Object> c = info.get(id) instanceof Map<?, ?> m ? Json.object(m) : Map.of();
            List<String> fields = new ArrayList<>();
            if (c.get("f") != null) for (Object f : Json.array(c.get("f"))) fields.add(Json.string(f));
            out.put(id, new Component(id, text(c.get("n")), text(c.get("d")), text(c.get("e")),
                    Collections.unmodifiableList(fields)));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, Object> extra() {
        try (InputStream in = Data.class.getResourceAsStream(ROOT + "extra.json")) {
            if (in == null) return Map.of();
            return Json.object(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String withExtra(String mappingJson, Map<String, Object> extra) {
        if (extra.isEmpty()) return mappingJson;
        Map<String, Object> mapping = Json.object(Json.parse(mappingJson));
        Map<String, Object> events = Json.object(mapping.get("events")), actions = Json.object(mapping.get("actions"));
        events.putAll(Json.object(extra.get("events")));
        actions.putAll(Json.object(extra.get("actions")));
        Map<String, Object> meta = Json.object(mapping.get("meta"));
        meta.put("actions", new Json.Num(Integer.toString(actions.size())));
        meta.put("events", new Json.Num(Integer.toString(events.size())));
        return Json.write(mapping);
    }

    private static Pickers pickers(Map<String, Object> root, Map<String, Object> extra) {
        Set<String> sounds = new LinkedHashSet<>();
        for (Object group : Json.array(root.get("sounds")))
            for (Object s : Json.array(Json.object(group).get("v"))) sounds.add(Json.string(s));
        if (extra.get("sounds") != null) for (Object s : Json.array(extra.get("sounds"))) sounds.add(Json.string(s));
        Map<String, List<String>> particles = new LinkedHashMap<>();
        for (Object raw : Json.array(root.get("particles"))) {
            Map<String, Object> p = Json.object(raw);
            List<String> extras = new ArrayList<>();
            for (Object x : Json.array(p.get("x"))) extras.add(Json.string(x));
            particles.put(Json.string(p.get("id")), Collections.unmodifiableList(extras));
        }
        Set<String> potions = new LinkedHashSet<>();
        for (Object raw : Json.array(root.get("potions"))) potions.add(Json.string(Json.object(raw).get("id")));
        return new Pickers(Collections.unmodifiableSet(sounds), Collections.unmodifiableMap(particles),
                Collections.unmodifiableSet(potions));
    }

    public static Data parse(String mappingJson, String valuesJson, String catalogJson) {
        Map<String, Object> mapping = Json.object(Json.parse(mappingJson));
        Map<String, Object> values = Json.object(Json.parse(valuesJson));
        Map<String, List<Map<String, Object>>> catalog = catalog(Json.object(Json.parse(catalogJson)));
        List<String> unknown = new ArrayList<>();

        Map<String, Object> rawActions = Json.object(mapping.get("actions"));
        Map<String, String> canonical = canonical(rawActions,
                mapping.get("export") == null ? Map.of() : Json.object(mapping.get("export")));
        Map<String, Action> actions = new LinkedHashMap<>();
        rawActions.forEach((id, raw) -> {
            Map<String, Object> o = Json.object(raw);
            String key = Json.string(o.get("k"));
            int cut = key.indexOf('|');
            String categoryName = cut < 0 ? key : key.substring(0, cut);
            Category category = Category.of(categoryName);
            if (category == null) { unknown.add(id + " → " + categoryName); return; }
            String sub = o.get("u") instanceof String u ? u : null;
            Map<String, Object> entry = entry(catalog, key, sub);
            actions.put(id, new Action(id, category, cut < 0 ? key : key.substring(cut + 1),
                    args(o.get("a"), entry, SLOTTED.get(key)),
                    settings(o.get("s"), entry), plural(o.get("p")),
                    o.get("t") instanceof String t ? t : family(category),
                    o.containsKey("c") || category == Category.CONTROLLER, sub, canonical.get(id), entry == null ? "" : text(entry.get("d"))));
        });

        Map<String, Event> events = new LinkedHashMap<>();
        Json.object(mapping.get("events")).forEach((id, raw) -> {
            String key = Json.string(raw);
            int cut = key.indexOf('|');
            Map<String, Object> entry = entry(catalog, key, null);
            events.put(id, new Event(id, cut < 0 ? "" : key.substring(0, cut),
                    cut < 0 ? key : key.substring(cut + 1), entry == null ? "" : text(entry.get("d"))));
        });

        Map<String, List<Selector>> selectors = new LinkedHashMap<>();
        Json.object(mapping.get("selections")).forEach((family, raw) -> {
            List<Selector> list = new ArrayList<>();
            for (Object row : Json.array(raw)) {
                List<Object> pair = Json.array(row);
                list.add(new Selector(Json.string(pair.get(0)), Json.string(pair.get(1))));
            }
            selectors.put(family, Collections.unmodifiableList(list));
        });

        Map<String, GameValue> gameValues = new LinkedHashMap<>();
        for (Object raw : Json.array(values.get("gameValues"))) {
            Map<String, Object> o = Json.object(raw);
            String id = Json.string(o.get("id"));
            gameValues.put(id, new GameValue(id, text(o.get("n")), text(o.get("c")),
                    text(o.get("r")), text(o.get("d"))));
        }

        List<Selector> valueSelectors = new ArrayList<>();
        for (Object raw : Json.array(values.get("selectors"))) {
            Map<String, Object> o = Json.object(raw);
            valueSelectors.add(new Selector(Json.string(o.get("id")), text(o.get("n"))));
        }

        Map<String, Object> m = Json.object(mapping.get("meta"));
        Meta meta = new Meta(text(m.get("updated")), number(m.get("actions")),
                number(m.get("events")));

        return new Data(Collections.unmodifiableMap(actions), Collections.unmodifiableMap(events),
                Collections.unmodifiableMap(gameValues), Collections.unmodifiableMap(selectors),
                Collections.unmodifiableList(valueSelectors), meta,
                Collections.unmodifiableList(unknown));
    }

    public Map<String, Action> actions() { return actions; }
    public Map<String, Event> events() { return events; }
    public Map<String, GameValue> gameValues() { return gameValues; }
    public List<Selector> selectors(String family) { return selectors.getOrDefault(family, List.of()); }
    public Set<String> selectorFamilies() { return selectors.keySet(); }
    public List<Selector> valueSelectors() { return valueSelectors; }
    public Meta meta() { return meta; }
    public List<String> unknownCategories() { return unknownCategories; }

    private static Map<String, String> canonical(Map<String, Object> actions,
                                                 Map<String, Object> export) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        actions.forEach((id, raw) -> {
            Map<String, Object> o = Json.object(raw);
            String group = Json.string(o.get("k")) + "\u0000"
                    + (o.get("u") instanceof String u ? u : "");
            groups.computeIfAbsent(group, g -> new ArrayList<>()).add(id);
        });
        Map<String, String> out = new LinkedHashMap<>();
        groups.forEach((group, ids) -> {
            String key = group.substring(0, group.indexOf('\u0000'));
            String live = export.get(key) instanceof String s && ids.contains(s) ? s : ids.get(0);
            for (String id : ids) out.put(id, live);
        });
        return out;
    }

    private static String family(Category category) {
        return switch (category) {
            case PLAYER, IF_PLAYER -> "p";
            case ENTITY, IF_ENTITY -> "e";
            default -> null;
        };
    }

    private static Map<String, List<Map<String, Object>>> catalog(Map<String, Object> root) {
        List<Object> acts = Json.array(root.get("acts"));
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (Object rawCat : Json.array(root.get("cats"))) {
            Map<String, Object> cat = Json.object(rawCat);
            String catName = text(cat.get("n"));
            for (Object rawSub : Json.array(cat.get("subs"))) {
                Map<String, Object> sub = Json.object(rawSub);
                for (Object index : Json.array(sub.get("a"))) {
                    Map<String, Object> act = new LinkedHashMap<>(
                            Json.object(acts.get(((Json.Num) index).intValue())));
                    act.put("sub", sub.get("n"));
                    out.computeIfAbsent(catName + "|" + text(act.get("n")), k -> new ArrayList<>()).add(act);
                }
            }
        }
        return out;
    }

    private static Map<String, Object> entry(Map<String, List<Map<String, Object>>> catalog,
                                             String key, String sub) {
        List<Map<String, Object>> found = catalog.get(key);
        if (found == null || found.isEmpty()) return null;
        if (sub != null)
            for (Map<String, Object> e : found) if (sub.equals(e.get("sub"))) return e;
        for (Map<String, Object> e : found) if (!e.containsKey("h")) return e;
        return found.get(0);
    }

    private static List<Arg> args(Object raw, Map<String, Object> entry, String slottedLabel) {
        if (raw == null) return List.of();
        Map<String, Object> a = Json.object(raw);
        String[] ordered = new String[a.size()];
        List<String> extra = new ArrayList<>();
        a.forEach((name, index) -> {
            int i = ((Json.Num) index).intValue();
            if (i >= 0 && i < ordered.length && ordered[i] == null) ordered[i] = name;
            else extra.add(name);
        });
        List<String> ids = new ArrayList<>();
        for (String name : ordered) if (name != null) ids.add(name);
        ids.addAll(extra);
        List<Object> info = entry != null && entry.get("a") != null ? Json.array(entry.get("a")) : List.of();
        List<Arg> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            int index = a.get(id) instanceof Json.Num n ? n.intValue() : -1;
            Map<String, Object> c = index >= 0 && index < info.size() ? Json.object(info.get(index)) : Map.of();
            String label = text(c.get("p"));
            boolean slotted = slottedLabel != null
                    && (slottedLabel.isEmpty() ? i == 0 : slottedLabel.equals(label));
            out.add(new Arg(id, c.isEmpty() ? ANY : text(c.get("t")), label,
                    Boolean.TRUE.equals(c.get("l")), number(c.get("c")), slotted));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<Setting> settings(Object raw, Map<String, Object> entry) {
        if (raw == null) return List.of();
        List<Object> info = entry != null && entry.get("s") != null ? Json.array(entry.get("s")) : List.of();
        List<Setting> out = new ArrayList<>();
        Json.object(raw).forEach((id, value) -> {
            Map<String, Object> o = Json.object(value);
            Map<String, String> options = new LinkedHashMap<>();
            Json.object(o.get("e")).forEach((option, label) -> options.put(option, Json.string(label)));
            int index = ((Json.Num) o.get("i")).intValue();
            Map<String, Object> shown = index >= 0 && index < info.size() ? Json.object(info.get(index)) : Map.of();
            String fallback = options.isEmpty() ? "" : options.keySet().iterator().next();
            for (Map.Entry<String, String> option : options.entrySet())
                if (option.getValue().equals(text(shown.get("d")))) fallback = option.getKey();
            out.add(new Setting(id, index, Collections.unmodifiableMap(options), text(shown.get("l")), fallback));
        });
        out.sort((x, y) -> Integer.compare(x.index(), y.index()));
        return Collections.unmodifiableList(out);
    }

    private static Set<String> plural(Object raw) {
        if (raw == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (Object o : Json.array(raw)) out.add(Json.string(o));
        return Collections.unmodifiableSet(out);
    }

    private static String text(Object raw) { return raw instanceof String s ? s : ""; }

    private static int number(Object raw) { return raw instanceof Json.Num n ? n.intValue() : -1; }

    private static String resource(String name) {
        try (InputStream own = Data.class.getResourceAsStream(ROOT + name);
             InputStream mod = own != null ? null : Data.class.getResourceAsStream(MOD_ROOT + name)) {
            InputStream in = own != null ? own : mod;
            if (in == null) throw new IllegalStateException("нет данных: " + ROOT + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
