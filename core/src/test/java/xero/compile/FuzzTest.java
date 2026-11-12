package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FuzzTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);
    private static final List<Names.Entry> ACTIONS = new ArrayList<>();
    private static final List<Names.Entry> CONDITIONS = new ArrayList<>();
    private static final List<String> EVENTS = new ArrayList<>(DATA.events().keySet());
    private static final List<String> GAME_VALUES = new ArrayList<>();
    private static final List<String> SOUNDS = new ArrayList<>(DATA.pickers().sounds());
    private static final List<String> PARTICLES = new ArrayList<>(DATA.pickers().particles().keySet());

    static {
        for (Data.GameValue g : DATA.gameValues().values()) if (g.returns().equals("Число")) GAME_VALUES.add(g.id());
        for (Names.Entry e : NAMES.entries()) {
            if (e.action().alias() || e.action().blank()) continue;
            if (e.action().id().equals("repeat_while") || e.action().id().contains("by_conditional")) continue;
            if (e.space() == Names.Space.ACTION) ACTIONS.add(e);
            else CONDITIONS.add(e);
        }
    }

    private static final String[] VARS = {"a", "b", "`с пробелом`", "`%player% счёт`"};

    private static String pick(Random r, String... options) {
        return options[r.nextInt(options.length)];
    }

    private static <T> T pick(Random r, List<T> list) {
        return list.get(r.nextInt(list.size()));
    }

    private static String sample(Random r, String kind, int depth) {
        return switch (kind) {
            case "Число" -> pick(r, "1", "-2.5", "0", "100", "num(\"%math(%var(a)+1)\")", "value." + pick(r, GAME_VALUES));
            case "Текст" -> pick(r, "\"a\"", "mm\"<red>b\"", "plain\"c\\\"d\"", "\"%player% \\\\n\"", "\"\"");
            case "Местоположение" -> pick(r, "loc(0, 64, 0)", "loc(1.5, -2, 3, 90, -45)", "@killer value.location");
            case "Вектор" -> pick(r, "vec(0, 1, 0)", "vec(-1, 0.5, 2)");
            case "Предмет" -> pick(r, "item(stone)", "item(diamond, 5)", "item(\"AAAAAAAAAAA=\")",
                    "item(diamond_sword, name: \"&bA\", lore: [\"x\", \"&6y &lz\"], enchantments: {sharpness: 2}, unbreakable: true, tags: {k: \"v\"})",
                    "item(paper, 2, glint: false, damage: 1, model: 2.5, components: \"{rarity:'epic',max_stack_size:4}\")",
                    "item(stick, name: mm\"<gradient:#ff0000:#00ff00>ab</gradient>\", hide_tooltip: true)");
            case "Блок" -> pick(r, "block(stone)", "block(oak_log)");
            case "Ячейка" -> pick(r, "cell(\"данные\")",
                    "cell(\"общее\", storage: \"ник/склад\", id: \"16ae1252-6c5d-4d1a-8ab4-353eff992a43\")");
            case "Список" -> depth > 1 ? "[1]" : "[" + sample(r, "Число", depth + 1) + ", _, " + sample(r, "Текст", depth + 1) + "]";
            case "Словарь" -> depth > 1 ? "{\"k\": 1}" : "{\"k\": " + sample(r, "Число", depth + 1) + ", 2: _}";
            case "Эффект частиц" -> "particle(" + pick(r, PARTICLES).toLowerCase() + pick(r, "", ", count: 3", ", first_spread: 0.5") + ")";
            case "Звук" -> "sound(\"" + pick(r, SOUNDS) + "\"" + pick(r, "", ", pitch: 2", ", source: ui", ", variation: \"x\"") + ")";
            case "Зелье" -> pick(r, "potion(speed)", "potion(regeneration, amplifier: 2, duration: 100)");
            case "Переменная" -> pick(r, VARS);
            default -> pick(r, "1", "\"x\"", VARS[r.nextInt(VARS.length)], "[1, 2]");
        };
    }

    private static String call(Random r, Names.Entry e) {
        Data.Action a = e.action();
        List<String> args = new ArrayList<>();
        boolean positional = r.nextBoolean();
        for (Data.Arg arg : a.args()) {
            if (r.nextInt(4) == 0) { positional = false; continue; }
            String v;
            if (a.plural(arg.id()) && (r.nextInt(3) == 0 || arg.kind().equals("Список"))) {
                v = "[" + sample(r, arg.kind(), 1) + ", _, " + sample(r, arg.kind(), 1) + "]";
            } else {
                v = sample(r, arg.kind(), 0);
            }
            args.add(positional ? v : arg.id() + ": " + v);
        }
        for (Data.Setting s : a.settings()) {
            if (r.nextBoolean()) continue;
            List<String> options = new ArrayList<>(s.options().keySet());
            String o = pick(r, options);
            args.add(s.id() + ": " + (o.matches("[a-z_][a-z0-9_]*") && xero.lang.Keywords.plainName(o) && !o.equals("game")
                    && !o.equals("save") && !o.equals("local") && !o.equals("line") ? o : "\"" + o + "\""));
        }
        String name = e.bare() ? e.name() : e.qualified();
        String target = "";
        if (a.family() != null && r.nextInt(3) == 0)
            target = "@" + pick(r, new ArrayList<>(NAMES.selectors(a.family()).keySet())) + " ";
        return target + name + "(" + String.join(", ", args) + ")";
    }

    private static String condition(Random r) {
        if (r.nextBoolean()) {
            String left = pick(r, VARS);
            return (r.nextInt(4) == 0 ? "not " : "") + left + " " + pick(r, "==", "!=", ">", ">=", "<", "<=") + " " + sample(r, "Число", 0);
        }
        return (r.nextInt(4) == 0 ? "not " : "") + call(r, pick(r, CONDITIONS));
    }

    private static void block(Random r, StringBuilder out, int depth, String indent) {
        int n = 1 + r.nextInt(depth == 0 ? 6 : 3);
        for (int i = 0; i < n; i++) {
            int kind = r.nextInt(10);
            if (kind < 4) {
                Names.Entry e = pick(r, ACTIONS);
                out.append(indent).append(call(r, e));
                if (e.action().container()) {
                    if (depth < 3 && r.nextBoolean()) {
                        out.append(" {\n");
                        block(r, out, depth + 1, indent + "    ");
                        out.append(indent).append("}");
                    } else {
                        out.append(" {}");
                    }
                }
                out.append('\n');
            } else if (kind < 6 && depth < 3) {
                out.append(indent).append("if ").append(condition(r)).append(" {\n");
                block(r, out, depth + 1, indent + "    ");
                out.append(indent).append("}");
                if (r.nextBoolean()) {
                    out.append(" else if ").append(condition(r)).append(" {\n");
                    block(r, out, depth + 1, indent + "    ");
                    out.append(indent).append("}");
                }
                if (r.nextBoolean()) out.append(" else {}");
                out.append('\n');
            } else if (kind < 8) {
                String op = pick(r, " = ", " += ", " -= ");
                String valueKind = op.equals(" = ") ? pick(r, "Число", "Текст", "Местоположение", "Список", "Словарь") : "Число";
                out.append(indent).append(pick(r, VARS)).append(op).append(sample(r, valueKind, 0)).append('\n');
            } else if (kind < 9) {
                out.append(indent).append("_\n");
            } else {
                out.append(indent).append("repeat.while(").append(condition(r)).append(") {}\n");
            }
        }
    }

    static String program(Random r) {
        StringBuilder out = new StringBuilder();
        out.append("save `%player% счёт`\ngame `с пробелом`\nlocal a\nlocal b\n");
        int handlers = 1 + r.nextInt(3);
        for (int h = 0; h < handlers; h++) {
            int kind = r.nextInt(4);
            if (kind == 0) {
                if (r.nextBoolean()) out.append("@hidden\n");
                if (r.nextBoolean()) out.append("@display_description(\"строка 1\", \"строка 2\")\n");
                if (r.nextBoolean()) out.append("@icon(item(stone))\n");
                out.append("func f").append(h).append("(p: number = 1, q) {\n");
            } else if (kind == 1) {
                out.append("process p").append(h).append("() {\n");
            } else {
                out.append("when ").append(pick(r, EVENTS)).append(" {\n");
            }
            block(r, out, 0, "    ");
            out.append("}\n\n");
        }
        return out.toString();
    }

    @Test
    void randomProgramsSurviveTheCircle() {
        List<String> failed = new ArrayList<>();
        Random seeds = new Random(20261016);
        for (int i = 0; i < 1000 && failed.size() < 5; i++) {
            long seed = seeds.nextLong();
            String text = program(new Random(seed));
            Compiler.Result first = Compiler.compile(DATA, NAMES, text);
            if (!first.ok()) {
                failed.add("seed " + seed + ": не собралась исходная: " + errors(first) + "\n" + text);
                continue;
            }
            String printed = Decompiler.decompile(DATA, NAMES, first.module()).text();
            Compiler.Result second = Compiler.compile(DATA, NAMES, printed);
            if (!second.ok()) {
                failed.add("seed " + seed + ": не собралась напечатанная: " + errors(second) + "\n" + printed);
                continue;
            }
            String a = Json.write(Json.parse(Json.write(first.module())));
            String b = Json.write(Json.parse(Json.write(second.module())));
            String d = RoundTrip.firstDifference(RoundTrip.normalize(DATA, Json.object(Json.parse(a))),
                    RoundTrip.normalize(DATA, Json.object(Json.parse(b))), "");
            if (d != null) failed.add("seed " + seed + ": " + d + "\n" + printed);
        }
        assertEquals(List.of(), failed);
    }

    @Test
    void brokenJsonNeverBreaksTheDecompiler() {
        List<String> failed = new ArrayList<>();
        Random seeds = new Random(1610);
        for (int i = 0; i < 1000 && failed.size() < 5; i++) {
            long seed = seeds.nextLong();
            Random r = new Random(seed);
            Compiler.Result first = Compiler.compile(DATA, NAMES, program(r));
            if (!first.ok()) continue;
            Map<String, Object> module = Json.object(Json.parse(Json.write(first.module())));
            for (int k = 0; k < 1 + r.nextInt(4); k++) mutate(r, module);
            String printed;
            try {
                printed = Decompiler.decompile(DATA, NAMES, module).text();
            } catch (RuntimeException e) {
                failed.add("seed " + seed + ": упал: " + e);
                continue;
            }
            Compiler.Result back = Compiler.compile(DATA, NAMES, printed);
            if (!back.ok()) {
                failed.add("seed " + seed + ": не собралось: " + errors(back) + "\n" + printed);
                continue;
            }
            String d = RoundTrip.firstDifference(RoundTrip.normalize(DATA, module),
                    RoundTrip.normalize(DATA, Json.object(Json.parse(Json.write(back.module())))), "");
            if (d != null) failed.add("seed " + seed + ": " + d + "\n" + printed);
        }
        assertEquals(List.of(), failed);
    }

    private static void mutate(Random r, Map<String, Object> module) {
        List<Map<String, Object>> ops = new ArrayList<>();
        for (Object h : Json.array(module.get("handlers"))) collect(Json.array(Json.object(h).get("operations")), ops);
        if (ops.isEmpty()) return;
        Map<String, Object> op = pick(r, ops);
        switch (r.nextInt(7)) {
            case 0 -> op.put("extra", new Json.Num("1"));
            case 1 -> op.put("action", pick(r, "player_new_thing", "else", "empty", "if_variable_equals"));
            case 2 -> op.remove("values");
            case 3 -> op.put("selection", Context.obj("type", pick(r, "all_players", "nobody", "current")));
            case 4 -> {
                List<Object> values = op.get("values") instanceof List<?> l ? Json.array(l) : null;
                if (values == null || values.isEmpty()) return;
                Map<String, Object> e = Json.object(pick(r, values));
                e.put("value", pick(r, List.of(new Json.Num("5"), "строка", Context.obj(),
                        Context.obj("type", "enum", "enum", "WHAT"), Context.obj("type", "mystery", "x", List.of()),
                        Context.obj("type", "text", "text", "a\nb\"\\", "parsing", "legacy"),
                        Context.obj("type", "number", "number", "не число"))));
            }
            case 5 -> {
                List<Object> values = op.get("values") instanceof List<?> l ? Json.array(l) : null;
                if (values == null) return;
                values.add(Context.obj("name", pick(r, "unknown_arg", "Странное имя", "values"), "value", Context.obj("type", "text", "text", "x", "parsing", "legacy")));
            }
            default -> op.put("is_inverted", r.nextBoolean());
        }
    }

    private static void collect(List<Object> ops, List<Map<String, Object>> out) {
        for (Object o : ops) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> op = Json.object(m);
            out.add(op);
            if (op.get("operations") instanceof List<?> l) collect(Json.array(l), out);
        }
    }

    private static List<String> errors(Compiler.Result r) {
        List<String> out = new ArrayList<>();
        for (Diagnostic d : r.diagnostics()) if (d.error()) out.add(d.message());
        return out.subList(0, Math.min(3, out.size()));
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> copy(Map<String, Object> m) {
        return new LinkedHashMap<>(m);
    }
}
