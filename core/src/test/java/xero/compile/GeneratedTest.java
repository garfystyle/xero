package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private static final List<String> CONDITIONAL = List.of(
            "repeat_while", "select_player_by_conditional", "select_entity_by_conditional",
            "select_add_player_by_conditional", "select_add_entity_by_conditional",
            "select_filter_by_conditional");

    private static String sample(String kind) {
        return switch (kind) {
            case "Число" -> "1";
            case "Текст" -> "\"a\"";
            case "Местоположение" -> "loc(0, 64, 0)";
            case "Вектор" -> "vec(0, 1, 0)";
            case "Предмет" -> "item(stone)";
            case "Блок" -> "block(stone)";
            case "Список" -> "[1]";
            case "Словарь" -> "{\"a\": 1}";
            case "Эффект частиц" -> "particle(flame)";
            case "Звук" -> "sound(" + DATA.pickers().sounds().iterator().next() + ")";
            case "Зелье" -> "potion(speed)";
            case "Переменная" -> "v";
            default -> "1";
        };
    }

    private static String call(Names.Entry e) {
        Data.Action a = e.action();
        String name = e.bare() ? e.name() : e.qualified();
        List<String> args = new ArrayList<>();
        if (CONDITIONAL.contains(a.id())) args.add("v == 1");
        for (Data.Arg arg : a.args())
            args.add(arg.id() + ": " + (a.plural(arg.id()) ? "[" + sample(arg.kind()) + "]" : sample(arg.kind())));
        for (Data.Setting s : a.settings()) args.add(s.id() + ": " + s.options().keySet().iterator().next());
        return name + "(" + String.join(", ", args) + ")";
    }

    @Test
    void everyActionCompiles() {
        int checked = 0;
        List<String> failed = new ArrayList<>();
        for (Names.Entry e : NAMES.entries()) {
            if (e.space() != Names.Space.ACTION || e.action().alias()) continue;
            String stmt = call(e) + (e.action().container() ? " {}" : "");
            Compiler.Result r = Compiler.compile(DATA, NAMES, "when world_start {\nv = 1\n" + stmt + "\n}");
            checked++;
            if (!r.ok()) { failed.add(stmt + " → " + r.diagnostics()); continue; }
            List<Object> ops = Json.array(Json.object(Json.array(r.module().get("handlers")).get(0)).get("operations"));
            Map<String, Object> op = Json.object(ops.get(1));
            if (!e.action().id().equals(op.get("action"))) failed.add(stmt + " → " + op.get("action"));
            int expected = e.action().args().size() + e.action().settings().size();
            if (!CONDITIONAL.contains(e.action().id()) && Json.array(op.get("values")).size() != expected)
                failed.add(stmt + " → значений " + Json.array(op.get("values")).size() + " из " + expected);
        }
        assertEquals(List.of(), failed);
        assertTrue(checked > 900, "проверено " + checked);
    }

    @Test
    void everyConditionCompiles() {
        int checked = 0;
        List<String> failed = new ArrayList<>();
        for (Names.Entry e : NAMES.entries()) {
            if (e.space() != Names.Space.CONDITION || e.action().alias()) continue;
            String stmt = "if " + call(e) + " {}";
            Compiler.Result r = Compiler.compile(DATA, NAMES, "when world_start {\nv = 1\n" + stmt + "\n}");
            checked++;
            if (!r.ok()) { failed.add(stmt + " → " + r.diagnostics()); continue; }
            List<Object> ops = Json.array(Json.object(Json.array(r.module().get("handlers")).get(0)).get("operations"));
            if (!e.action().id().equals(Json.object(ops.get(1)).get("action"))) failed.add(stmt);
        }
        assertEquals(List.of(), failed);
        assertTrue(checked > 100, "проверено " + checked);
    }

    @Test
    void everyEventCompiles() {
        List<String> failed = new ArrayList<>();
        for (String id : DATA.events().keySet()) {
            Compiler.Result r = Compiler.compile(DATA, NAMES, "when " + id + " {}");
            if (!r.ok()) failed.add(id + " → " + r.diagnostics());
        }
        assertEquals(List.of(), failed);
    }

    @Test
    void everyGameValueCompiles() {
        List<String> failed = new ArrayList<>();
        for (String id : DATA.gameValues().keySet()) {
            Compiler.Result r = Compiler.compile(DATA, NAMES, "when world_start {\nx = value." + id + "\n}");
            if (!r.ok()) failed.add(id + " → " + r.diagnostics());
        }
        assertEquals(List.of(), failed);
    }

    @Test
    void everyParticleAndPotionCompiles() {
        List<String> failed = new ArrayList<>();
        for (String id : DATA.pickers().particles().keySet()) {
            Compiler.Result r = Compiler.compile(DATA, NAMES, "when world_start {\nx = particle(" + id.toLowerCase() + ")\n}");
            if (!r.ok()) failed.add(id + " → " + r.diagnostics());
        }
        for (String id : DATA.pickers().potions()) {
            Compiler.Result r = Compiler.compile(DATA, NAMES, "when world_start {\nx = potion(\"" + id + "\")\n}");
            if (!r.ok()) failed.add(id + " → " + r.diagnostics());
        }
        assertEquals(List.of(), failed);
    }
}
