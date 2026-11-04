package xero.cli;

import xero.compile.Compiler;
import xero.data.Category;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

final class ReferenceDoc {
    record Rendered(Map<String, String> files, List<String> broken) {}

    private record Page(String file, String title, String family, List<Category> categories) {}

    private static final List<Page> PAGES = List.of(
            new Page("player", "Игрок", "p", List.of(Category.PLAYER, Category.IF_PLAYER)),
            new Page("entity", "Сущность", "e", List.of(Category.ENTITY, Category.IF_ENTITY)),
            new Page("world", "Мир", null, List.of(Category.GAME, Category.IF_GAME)),
            new Page("variable", "Переменные", null, List.of(Category.VARIABLE, Category.IF_VARIABLE)),
            new Page("select", "Выборка", null, List.of(Category.SELECT)),
            new Page("repeat", "Повторения", null, List.of(Category.REPEAT)),
            new Page("control", "Контроль", null, List.of(Category.CONTROL)),
            new Page("controller", "Контроллер", null, List.of(Category.CONTROLLER)));

    private static final Comparator<Object> RUSSIAN = Collator.getInstance(Locale.of("ru"));

    private final Data data;
    private final Names names;
    private final Properties lang;
    private final TreeSet<String> langKeys;
    private final Map<String, String> returns = new HashMap<>();
    private final Set<String> hidden = new HashSet<>();
    private final Map<String, String> groups = new HashMap<>();
    private final Map<String, List<String>> groupOrder = new HashMap<>();
    private final Map<String, String> anchors = new HashMap<>();
    private final List<String> broken = new ArrayList<>();

    private ReferenceDoc(Data data, Names names, Properties lang) {
        this.data = data;
        this.names = names;
        this.lang = lang;
        this.langKeys = new TreeSet<>(lang.stringPropertyNames());
        readCatalog();
        for (Object v : Json.array(Json.object(Json.parse(resource("values.json"))).get("gameValues"))) {
            Map<String, Object> value = Json.object(v);
            if (value.get("rd") != null) returns.put(Json.string(value.get("id")), Json.string(value.get("rd")));
        }
    }

    static Rendered render(Data data, Names names, Properties lang) {
        ReferenceDoc doc = new ReferenceDoc(data, names, lang);
        Map<String, String> files = new LinkedHashMap<>();
        for (Page page : PAGES) files.put("actions/" + page.file + ".md", doc.page(page));
        files.put("actions/README.md", doc.index());
        files.put("events.md", doc.events());
        files.put("values.md", doc.values());
        files.put("placeholders.md", doc.placeholders());
        files.put("effects.md", doc.effects());
        files.put("components.md", doc.components());
        return new Rendered(files, doc.broken);
    }

    private void readCatalog() {
        Map<String, Object> catalog = Json.object(Json.parse(resource("catalog.json")));
        List<Object> acts = Json.array(catalog.get("acts"));
        for (Object c : Json.array(catalog.get("cats"))) {
            Map<String, Object> cat = Json.object(c);
            String title = Json.string(cat.get("n"));
            for (Object s : Json.array(cat.get("subs"))) {
                Map<String, Object> sub = Json.object(s);
                String group = sub.get("n") == null ? "" : Json.string(sub.get("n"));
                groupOrder.computeIfAbsent(title, k -> new ArrayList<>()).add(group);
                for (Object i : Json.array(sub.get("a"))) {
                    Map<String, Object> act = Json.object(acts.get(((Json.Num) i).intValue()));
                    String key = title + "|" + Json.string(act.get("n"));
                    if (act.containsKey("h")) hidden.add(key);
                    groups.put(key, group);
                }
            }
        }
    }

    private boolean shown(Names.Entry e) {
        Data.Action a = e.action();
        return !a.blank() && !a.alias() && !hidden.contains(a.category().russian + "|" + a.label());
    }

    private List<Names.Entry> entries(Category category) {
        return names.entries().stream()
                .filter(e -> e.action().category() == category && shown(e))
                .sorted(Comparator.comparing((Names.Entry e) -> e.action().label(), RUSSIAN))
                .toList();
    }

    private static String written(Names.Entry e) {
        return e.bare() ? e.name() : e.qualified();
    }

    private String page(Page page) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(page.title).append("\n\n").append(nav(page)).append("\n\n");
        if (page.family != null) {
            md.append("**Цели:** ");
            List<String> parts = new ArrayList<>();
            names.selectors(page.family).keySet().forEach(n -> parts.add("`@" + n + "`"));
            md.append(String.join(" · ", parts)).append("\n\n");
        }
        Map<Category, Map<String, List<Names.Entry>>> grouped = new LinkedHashMap<>();
        for (Category category : page.categories) {
            Map<String, List<Names.Entry>> byGroup = new LinkedHashMap<>();
            for (String group : groupOrder.getOrDefault(category.russian, List.of())) byGroup.put(group, new ArrayList<>());
            for (Names.Entry e : entries(category))
                byGroup.computeIfAbsent(groups.getOrDefault(category.russian + "|" + e.action().label(), ""),
                        k -> new ArrayList<>()).add(e);
            byGroup.values().removeIf(List::isEmpty);
            grouped.put(category, byGroup);
        }
        md.append("## Содержание\n\n");
        Map<String, Integer> seen = new HashMap<>();
        seen.put("содержание", 1);
        StringBuilder body = new StringBuilder();
        grouped.forEach((category, byGroup) -> {
            int total = byGroup.values().stream().mapToInt(List::size).sum();
            md.append("- [").append(category.russian).append("](#").append(anchor(category.russian, seen))
                    .append(") — ").append(total).append('\n');
            body.append("## ").append(category.russian).append("\n\n");
            byGroup.forEach((group, list) -> {
                if (!group.isEmpty()) {
                    md.append("  - [").append(group).append("](#").append(anchor(group, seen)).append(") — ")
                            .append(list.size()).append('\n');
                    body.append("### ").append(group).append("\n\n");
                }
                for (Names.Entry e : list) entry(body, e, page, seen);
            });
        });
        return md.append('\n').append(body).toString();
    }

    private void entry(StringBuilder md, Names.Entry e, Page page, Map<String, Integer> seen) {
        Data.Action a = e.action();
        String name = written(e);
        anchors.put(a.id(), page.file + ".md#" + anchor(name, seen));
        String key = "creative_plus.action." + a.id() + ".";
        String about = about(key, a.description());
        md.append("#### `").append(name).append("`\n\n**").append(cell(a.label())).append("**");
        if (!about.isEmpty()) md.append(" — ").append(cell(about));
        md.append("\n\n");
        List<String> notes = notes(key, a.description());
        if (!notes.isEmpty()) {
            for (String note : notes) md.append("- ").append(note).append('\n');
            md.append('\n');
        }
        String example = example(e);
        if (compiles(example)) md.append("```kotlin\n").append(example).append("\n```\n\n");
        else broken.add(a.id() + ": " + example);
        if (a.args().isEmpty() && a.settings().isEmpty()) return;
        md.append("| Аргумент | Вид | Описание |\n|:--|:--|:--|\n");
        for (Data.Arg arg : a.args())
            md.append("| `").append(arg.id()).append("` | ").append(kind(arg)).append(" | ")
                    .append(cell(arg.label() == null ? "" : arg.label())).append(" |\n");
        for (Data.Setting s : a.settings()) {
            List<String> options = new ArrayList<>();
            s.options().forEach((id, label) -> {
                String more = text(key + "argument." + s.id() + ".enum." + id + ".description");
                options.add("`" + id + "` " + cell(label) + (more.isEmpty() ? "" : " — " + cell(more)));
            });
            md.append("| `").append(s.id()).append("` | маркер | ");
            if (!s.label().isEmpty()) md.append(cell(s.label())).append(":<br>");
            md.append(String.join("<br>", options)).append(" |\n");
        }
        md.append('\n');
    }

    private static String kind(Data.Arg arg) {
        String word = arg.kind() == null || arg.kind().isEmpty() ? "любое" : arg.kind().toLowerCase(Locale.ROOT);
        if (word.equals("любое значение")) word = "любое";
        if (arg.slotted()) return word + ", сетка до " + arg.capacity();
        return arg.list() && arg.capacity() > 1 ? word + ", до " + arg.capacity() : word;
    }

    private static String sample(Data.Arg arg) {
        return switch (arg.kind() == null ? "" : arg.kind()) {
            case "Текст" -> "\"текст\"";
            case "Местоположение" -> "loc(0, 64, 0)";
            case "Вектор" -> "vec(0, 1, 0)";
            case "Предмет" -> "item(stone)";
            case "Блок" -> "block(stone)";
            case "Звук" -> "sound(entity.player.levelup)";
            case "Эффект частиц" -> "particle(flame)";
            case "Зелье" -> "potion(speed)";
            case "Список" -> arg.list() && arg.capacity() > 1 ? "[[1], [2]]" : "[1, 2]";
            case "Словарь" -> "{\"a\": 1}";
            case Data.VARIABLE -> "x";
            default -> "1";
        };
    }

    private static String example(Names.Entry e) {
        Data.Action a = e.action();
        boolean condition = e.space() == Names.Space.CONDITION;
        Data.Arg result = condition || a.container() ? null : a.result();
        List<String> parts = new ArrayList<>();
        for (Data.Arg arg : a.args()) if (arg != result) parts.add(sample(arg));
        if (a.id().endsWith("_by_conditional") || a.id().equals("repeat_while")) parts.add("x == 1");
        String call = written(e) + "(" + String.join(", ", parts) + ")";
        if (condition) return "if " + call + " { }";
        if (a.container()) return call + " { }";
        return result == null ? call : "x = " + call;
    }

    private boolean compiles(String example) {
        return Compiler.compile(data, names, "when player_join {\nx = 1\n" + example + "\n}\n").ok();
    }

    private String index() {
        StringBuilder md = new StringBuilder("# Действия\n\n").append(nav(null)).append("\n\n");
        md.append("| Раздел | Действий | Условий |\n|:--|--:|--:|\n");
        for (Page page : PAGES) {
            int actions = 0, conditions = 0;
            for (Category c : page.categories) {
                if (c.condition) conditions += entries(c).size();
                else actions += entries(c).size();
            }
            md.append("| [").append(page.title).append("](").append(page.file).append(".md) | ").append(actions)
                    .append(" | ").append(conditions == 0 ? "—" : conditions).append(" |\n");
        }
        md.append("\n## Всё по названию в игре\n\n| В игре | Xero | Раздел |\n|:--|:--|:--|\n");
        List<Names.Entry> all = new ArrayList<>();
        for (Page page : PAGES) for (Category c : page.categories) all.addAll(entries(c));
        all.sort(Comparator.comparing((Names.Entry e) -> e.action().label(), RUSSIAN));
        for (Names.Entry e : all) {
            String title = "";
            for (Page page : PAGES) if (page.categories.contains(e.action().category())) title = page.title;
            md.append("| ").append(cell(e.action().label())).append(" | [`").append(written(e)).append("`](")
                    .append(anchors.get(e.action().id())).append(") | ").append(title).append(" |\n");
        }
        return md.toString();
    }

    private String events() {
        Map<String, Map<String, List<Data.Event>>> grouped = new LinkedHashMap<>();
        for (Data.Event ev : data.events().values()) {
            if (ev.id().endsWith("_dummy")) continue;
            String sub = groups.getOrDefault(ev.group() + "|" + ev.label(), "");
            grouped.computeIfAbsent(ev.group(), k -> {
                Map<String, List<Data.Event>> subs = new LinkedHashMap<>();
                for (String group : groupOrder.getOrDefault(k, List.of())) subs.put(group, new ArrayList<>());
                return subs;
            }).computeIfAbsent(sub, k -> new ArrayList<>()).add(ev);
        }
        grouped.values().forEach(subs -> subs.values().removeIf(List::isEmpty));
        StringBuilder md = new StringBuilder("# События\n\n").append(back()).append("\n\n");
        md.append("```kotlin\nwhen player_join {\n    send_message(\"Привет, %player%!\")\n}\n```\n\n");
        md.append("## Содержание\n\n");
        Map<String, Integer> seen = new HashMap<>();
        seen.put("содержание", 1);
        StringBuilder body = new StringBuilder();
        grouped.forEach((group, subs) -> {
            md.append("- [").append(group).append("](#").append(anchor(group, seen)).append(")\n");
            body.append("## ").append(group).append("\n\n");
            subs.forEach((sub, list) -> {
                if (!sub.isEmpty()) {
                    md.append("  - [").append(sub).append("](#").append(anchor(sub, seen)).append(") — ")
                            .append(list.size()).append('\n');
                    body.append("### ").append(sub).append("\n\n");
                }
                body.append("| Событие | В игре | Что значит | Отменяемое |\n|:--|:--|:--|:-:|\n");
                list.sort(Comparator.comparing(Data.Event::label, RUSSIAN));
                for (Data.Event ev : list) {
                    String key = "creative_plus.trigger." + ev.id() + ".";
                    List<String> parts = new ArrayList<>(List.of(cell(about(key, ev.description()))));
                    parts.addAll(notes(key, ev.description()));
                    body.append("| `").append(ev.id()).append("` | ").append(cell(ev.label())).append(" | ")
                            .append(String.join("<br>", parts)).append(" | ")
                            .append(ev.description().contains("Отменяемое") ? "✓" : "").append(" |\n");
                }
                body.append('\n');
            });
        });
        return md.append('\n').append(body).toString();
    }

    private String values() {
        Map<String, List<Data.GameValue>> grouped = new LinkedHashMap<>();
        for (Data.GameValue v : data.gameValues().values())
            grouped.computeIfAbsent(v.group() == null || v.group().isEmpty() ? "Разное" : v.group(), k -> new ArrayList<>()).add(v);
        StringBuilder md = new StringBuilder("# Игровые значения\n\n").append(back()).append("\n\n");
        md.append("```kotlin\nwhen player_jump {\n    hp = value.current_health\n")
                .append("    x = @killer value.current_health\n")
                .append("    send_message(\"Здоровье: ${value.current_health}\")\n}\n```\n\n");
        md.append("**Цели:** ");
        List<String> targets = new ArrayList<>();
        for (Data.Selector s : data.valueSelectors()) targets.add("`@" + s.id() + "`");
        md.append(String.join(" · ", targets)).append("\n\n## Содержание\n\n");
        grouped.forEach((group, list) -> md.append("- [").append(group).append("](#").append(slug(group))
                .append(") — ").append(list.size()).append('\n'));
        md.append('\n');
        grouped.forEach((group, list) -> {
            md.append("## ").append(group).append("\n\n| Значение | В игре | Вид | Что значит |\n|:--|:--|:--|:--|\n");
            list.sort(Comparator.comparing(Data.GameValue::label, RUSSIAN));
            for (Data.GameValue v : list) {
                String key = "creative_plus.game_value." + v.id() + ".";
                List<String> parts = new ArrayList<>(List.of(cell(about(key, v.description()))));
                String raw = returns.getOrDefault(v.id(), "");
                String result = whole(raw);
                if (!result.isEmpty() && !result.equals(v.label())) parts.add("→ " + cell(result));
                parts.addAll(notes(key, v.description()));
                if (raw.contains("Без цели")) parts.add("Без цели");
                md.append("| `value.").append(v.id()).append("` | ").append(cell(v.label())).append(" | ")
                        .append(cell(v.returns().toLowerCase(Locale.ROOT))).append(" | ")
                        .append(String.join("<br>", parts)).append(" |\n");
            }
            md.append('\n');
        });
        return md.toString();
    }

    private String placeholders() {
        Map<String, Object> raw = Json.object(Json.parse(resource("placeholders.json")));
        StringBuilder md = new StringBuilder("# Плейсхолдеры\n\n").append(back()).append("\n\n");
        md.append("Работают внутри текста. Две формы: `%player%` и `%var(Имя)` — у функции после скобки `%` не ставят.\n\n");
        md.append("```kotlin\nsend_message(\"Привет, %player%! Монет: %var_save(coins)\")\n```\n\n");
        for (Object c : Json.array(raw.get("cats"))) {
            Map<String, Object> cat = Json.object(c);
            String id = Json.string(cat.get("id"));
            md.append("## ").append(Json.string(cat.get("n"))).append("\n\n| Запись | Что значит |\n|:--|:--|\n");
            for (Object i : Json.array(raw.get("items"))) {
                Map<String, Object> item = Json.object(i);
                if (!id.equals(Json.string(item.get("c")))) continue;
                md.append("| `").append(code(Json.string(item.get("p")))).append("` | ")
                        .append(cell(Json.string(item.get("d")))).append(" |\n");
            }
            md.append('\n');
        }
        return md.toString();
    }

    private String effects() {
        Map<String, Object> raw = Json.object(Json.parse(resource("pickers.json")));
        StringBuilder md = new StringBuilder("# Частицы, зелья, звуки, чары\n\n").append(back()).append("\n\n");
        md.append("- [Частицы](#частицы)\n- [Зелья](#зелья)\n- [Чары](#чары)\n- [Звуки](#звуки)\n\n");
        md.append("## Частицы\n\n```kotlin\nx = particle(flame, count: 3, first_spread: 0.5, second_spread: 0.5)\n```\n\n");
        md.append("| Частица | В игре | Что значит | Свои поля |\n|:--|:--|:--|:--|\n");
        for (Object p : Json.array(raw.get("particles"))) {
            Map<String, Object> particle = Json.object(p);
            List<String> fields = new ArrayList<>();
            for (Object f : Json.array(particle.get("x"))) fields.add("`" + Json.string(f) + "`");
            md.append("| `").append(Json.string(particle.get("id")).toLowerCase(Locale.ROOT)).append("` | ")
                    .append(cell(Json.string(particle.get("n")))).append(" | ")
                    .append(cell(Json.string(particle.get("d")))).append(" | ")
                    .append(String.join(" ", fields)).append(" |\n");
        }
        md.append("\n## Зелья\n\n```kotlin\nx = potion(speed, amplifier: 1, duration: 30s)\n```\n\n");
        md.append("| Зелье | В игре | Группа | Что значит |\n|:--|:--|:--|:--|\n");
        for (Object p : Json.array(raw.get("potions"))) {
            Map<String, Object> potion = Json.object(p);
            md.append("| `").append(Json.string(potion.get("id")).replace("minecraft:", "")).append("` | ")
                    .append(cell(Json.string(potion.get("n")))).append(" | ")
                    .append(cell(Json.string(potion.get("c")))).append(" | ")
                    .append(cell(Json.string(potion.get("d")).replace("» ", ""))).append(" |\n");
        }
        md.append("\n## Чары\n\n```kotlin\nx = item(diamond_sword, enchantments: {sharpness: 5, unbreaking: 3})\n```\n\n");
        List<String> enchantments = new ArrayList<>();
        for (String id : data.minecraft().enchantments()) enchantments.add("`" + id.replace("minecraft:", "") + "`");
        enchantments.sort(null);
        md.append(String.join(" · ", enchantments)).append("\n\n");
        md.append("## Звуки\n\n```kotlin\nx = sound(entity.player.levelup, volume: 1, pitch: 2)\n```\n\n");
        md.append("Id с цифрой на конце пишется в кавычках: `sound(\"item.goat_horn.sound.1\")`.\n\n");
        for (Object g : Json.array(raw.get("sounds"))) {
            Map<String, Object> group = Json.object(g);
            List<Object> list = Json.array(group.get("v"));
            md.append("<details>\n<summary>").append(Json.string(group.get("n"))).append(" — ").append(list.size())
                    .append("</summary>\n\n");
            for (Object s : list) md.append("`").append(Json.string(s)).append("` ");
            md.append("\n\n</details>\n\n");
        }
        return md.toString();
    }

    private String components() {
        StringBuilder md = new StringBuilder("# Компоненты предмета\n\n").append(back()).append("\n\n");
        md.append("Всё, что не покрывают поля `item(…)`, пишется в `components:` так же, как в `/give`. `minecraft:` можно не писать.\n\n");
        md.append("```kotlin\nx = item(stick, components: \"{max_stack_size:1,rarity:'epic'}\")\n```\n\n");
        md.append("| Компонент | Название | Что значит | Пример |\n|:--|:--|:--|:--|\n");
        List<Data.Component> list = new ArrayList<>(data.minecraft().components().values());
        list.sort(Comparator.comparing(Data.Component::id));
        for (Data.Component c : list) {
            String id = c.id().replace("minecraft:", "");
            md.append("| `").append(id).append("` | ").append(cell(c.name())).append(" | ")
                    .append(cell(c.description())).append(" | ");
            if (!c.example().isEmpty()) md.append("`").append(code(c.example())).append("`");
            md.append(" |\n");
        }
        return md.toString();
    }

    private static String nav(Page current) {
        List<String> parts = new ArrayList<>();
        parts.add("[Xero](../../README.md)");
        parts.add(current == null ? "**Все действия**" : "[Все действия](README.md)");
        for (Page page : PAGES)
            parts.add(page == current ? "**" + page.title + "**" : "[" + page.title + "](" + page.file + ".md)");
        return String.join(" · ", parts);
    }

    private static String back() {
        return "[Xero](../README.md) · [Действия](actions/README.md) · [События](events.md) · "
                + "[Игровые значения](values.md) · [Плейсхолдеры](placeholders.md) · "
                + "[Частицы, зелья, звуки](effects.md) · [Компоненты](components.md)";
    }

    private static String anchor(String heading, Map<String, Integer> seen) {
        String base = slug(heading);
        int times = seen.merge(base, 1, Integer::sum) - 1;
        return times == 0 ? base : base + "-" + times;
    }

    private static String slug(String heading) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < heading.length(); ) {
            int c = heading.codePointAt(i);
            i += Character.charCount(c);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') out.appendCodePoint(Character.toLowerCase(c));
            else if (c == ' ') out.append('-');
        }
        return out.toString();
    }

    private String about(String key, String catalog) {
        String full = text(key + "description");
        return full.isEmpty() ? whole(catalog) : full;
    }

    private List<String> notes(String key, String catalog) {
        List<String> out = new ArrayList<>();
        if (langKeys.isEmpty()) {
            String[] parts = (catalog == null ? "" : line(catalog)).split(" » ");
            for (int i = 1; i < parts.length; i++) out.add(cell(parts[i].replaceAll(" ?ø .*$", "")));
            return out;
        }
        String info = key + "additional_information.";
        for (String k : langKeys.subSet(info, info + Character.MAX_VALUE)) {
            String note = text(k);
            if (!note.isEmpty()) out.add(cell(note));
        }
        String with = key + "work_with.";
        List<String> works = new ArrayList<>();
        for (String k : langKeys.subSet(with, with + Character.MAX_VALUE)) {
            String what = text(k);
            if (!what.isEmpty()) works.add(cell(what));
        }
        if (!works.isEmpty()) out.add("Работает с: " + String.join(", ", works));
        return out;
    }

    private String text(String key) {
        String value = lang.getProperty(key, "");
        String clean = line(value.replaceAll("&[0-9a-fk-or]", ""));
        return clean.equals("...") ? "" : clean;
    }

    private static String whole(String catalog) {
        String out = catalog == null ? "" : line(catalog);
        for (String mark : List.of(" » ", " Дополнительная информация:", " ø ", "ø Отменяемое")) {
            int at = out.indexOf(mark);
            if (at >= 0) out = out.substring(0, at);
        }
        return out.trim();
    }

    private static String line(String text) {
        return text.replace("\r", "").replace("\n", " ").trim();
    }

    private static String code(String text) {
        return line(text == null ? "" : text).replace("|", "\\|");
    }

    private static String cell(String text) {
        return code(text).replace("<", "&lt;");
    }

    private static String resource(String name) {
        try (InputStream in = Data.class.getResourceAsStream("/xero/data/" + name)) {
            if (in == null) throw new IllegalStateException("нет данных: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
