package xero.data;

import xero.compile.Wire;
import xero.lang.Keywords;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Names {
    public enum Space { ACTION, CONDITION }

    public record Entry(Data.Action action, Space space, String name, String qualified,
                        boolean bare, String reason) {
        public String live() { return action.canonical(); }
    }

    public static final String SHARED = "совпадает в ";
    public static final String KEYWORD = "слово языка";

    public static final Set<String> RESERVED = reserved();

    private static final List<String> PREFIXES = List.of(
            "if_variable_", "if_player_", "if_entity_", "if_game_", "set_variable_",
            "controller_", "control_", "repeat_", "select_", "player_", "entity_", "game_");

    private static final List<String> SELECTOR_SUFFIXES = List.of(
            "_players", "_player", "_entities", "_entity");

    private final List<Entry> entries;
    private final Map<String, Entry> byId;
    private final Map<Space, Map<String, String>> qualifiedToLive;
    private final Map<Space, Map<String, String>> bareToLive;
    private final Map<Space, Map<String, List<Entry>>> collisions;
    private final Map<String, Map<String, String>> selectors;
    private final List<String> duplicates;

    private Names(List<Entry> entries, Map<Space, Map<String, String>> qualifiedToLive,
                  Map<Space, Map<String, String>> bareToLive,
                  Map<Space, Map<String, List<Entry>>> collisions,
                  Map<String, Map<String, String>> selectors, List<String> duplicates) {
        this.entries = entries;
        this.qualifiedToLive = qualifiedToLive;
        this.bareToLive = bareToLive;
        this.collisions = collisions;
        this.selectors = selectors;
        this.duplicates = duplicates;
        Map<String, Entry> ids = new LinkedHashMap<>();
        for (Entry e : entries) ids.put(e.action().id(), e);
        this.byId = ids;
    }

    public static Names build(Data data) {
        Map<Space, Map<String, Set<String>>> liveByName = spaces();
        Map<Space, Map<String, Set<String>>> liveByQualified = spaces();
        for (Data.Action a : data.actions().values()) {
            String name = shortName(a.id());
            liveByName.get(space(a)).computeIfAbsent(name, k -> new LinkedHashSet<>()).add(a.canonical());
            liveByQualified.get(space(a)).computeIfAbsent(qualified(a), k -> new LinkedHashSet<>())
                    .add(a.canonical());
        }

        List<Entry> entries = new ArrayList<>();
        Map<Space, Map<String, List<Entry>>> collisions = new LinkedHashMap<>();
        Map<Space, Map<String, String>> qualifiedToLive = new LinkedHashMap<>();
        Map<Space, Map<String, String>> bareToLive = new LinkedHashMap<>();
        for (Space s : Space.values()) {
            collisions.put(s, new LinkedHashMap<>());
            qualifiedToLive.put(s, new LinkedHashMap<>());
            bareToLive.put(s, new LinkedHashMap<>());
        }
        for (Data.Action a : data.actions().values()) {
            Space space = space(a);
            String name = shortName(a.id());
            Set<String> sharing = liveByName.get(space).get(name);
            String reason = null;
            if (sharing.size() > 1) reason = SHARED + categories(data, sharing);
            else if (RESERVED.contains(name)) reason = KEYWORD;
            Entry e = new Entry(a, space, name, qualified(a), reason == null, reason);
            entries.add(e);
            if (sharing.size() > 1)
                collisions.get(space).computeIfAbsent(name, k -> new ArrayList<>()).add(e);
        }
        for (boolean spare : new boolean[]{false, true}) {
            for (Entry e : entries) {
                if (e.action().alias() != spare) continue;
                qualifiedToLive.get(e.space()).putIfAbsent(e.qualified(), e.action().id());
                if (e.bare()) bareToLive.get(e.space()).putIfAbsent(e.name(), e.action().id());
            }
        }
        collisions.values().forEach(m -> m.entrySet().removeIf(x -> x.getValue().size() < 2));

        List<String> duplicates = new ArrayList<>();
        liveByQualified.forEach((space, map) -> map.forEach((q, live) -> {
            if (live.size() > 1) duplicates.add(q + " → " + String.join(", ", live));
        }));

        Map<String, Map<String, String>> selectors = new LinkedHashMap<>();
        for (String family : data.selectorFamilies())
            selectors.put(family, selectorNames(data.selectors(family)));
        selectors.put("value", selectorNames(data.valueSelectors()));

        return new Names(Collections.unmodifiableList(entries), qualifiedToLive, bareToLive,
                collisions, selectors, Collections.unmodifiableList(duplicates));
    }

    private static Set<String> reserved() {
        Set<String> out = new LinkedHashSet<>(Keywords.english());
        out.addAll(List.of("true", "false", "as"));
        for (Category c : Category.values()) out.add(c.qualifier);
        out.addAll(Wire.CALL_WORDS);
        return Collections.unmodifiableSet(out);
    }

    public static Space space(Data.Action action) {
        return action.category().condition ? Space.CONDITION : Space.ACTION;
    }

    public static String shortName(String id) {
        for (String p : PREFIXES)
            if (id.startsWith(p) && id.length() > p.length()) return id.substring(p.length());
        return id;
    }

    public List<Entry> entries() { return entries; }

    public Entry byId(String id) { return byId.get(id); }

    public Entry resolve(Space space, String text) {
        String t = text.toLowerCase(Locale.ROOT);
        String id = qualifiedToLive.get(space).get(t);
        if (id == null) id = bareToLive.get(space).get(t);
        return id == null ? null : byId.get(id);
    }

    public Map<String, List<Entry>> collisions(Space space) {
        return Collections.unmodifiableMap(collisions.get(space));
    }

    public Map<String, String> selectors(String family) {
        return selectors.getOrDefault(family, Map.of());
    }

    public Set<String> selectorFamilies() { return selectors.keySet(); }

    public List<String> duplicates() { return duplicates; }

    private static String qualified(Data.Action a) {
        return a.category().qualifier + "." + shortName(a.id());
    }

    private static Map<Space, Map<String, Set<String>>> spaces() {
        Map<Space, Map<String, Set<String>>> out = new LinkedHashMap<>();
        for (Space s : Space.values()) out.put(s, new LinkedHashMap<>());
        return out;
    }

    private static Map<String, String> selectorNames(List<Data.Selector> list) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Data.Selector s : list) count.merge(stripSuffix(s.id()), 1, Integer::sum);
        Map<String, String> out = new LinkedHashMap<>();
        for (Data.Selector s : list) {
            String name = stripSuffix(s.id());
            if (count.get(name) > 1 && !name.equals(s.id())) name = s.id();
            out.put(name, s.id());
        }
        return Collections.unmodifiableMap(out);
    }

    private static String stripSuffix(String id) {
        for (String suffix : SELECTOR_SUFFIXES)
            if (id.endsWith(suffix) && id.length() > suffix.length())
                return id.substring(0, id.length() - suffix.length());
        return id;
    }

    private static String categories(Data data, Set<String> live) {
        List<String> names = new ArrayList<>();
        for (String id : live) {
            String q = data.actions().get(id).category().qualifier;
            if (!names.contains(q)) names.add(q);
        }
        return String.join(", ", names);
    }
}
