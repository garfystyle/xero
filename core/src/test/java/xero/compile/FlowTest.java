package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.format.Formatter;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private static Compiler.Result compile(String text) {
        return Compiler.compile(DATA, NAMES, text);
    }

    private static List<String> messages(String text, boolean errors) {
        return compile(text).diagnostics().stream().filter(d -> d.error() == errors).map(Diagnostic::message).toList();
    }

    private static List<String> warnings(String body) {
        return messages("when player_join {\n" + body + "\n}", false);
    }

    private static List<String> errors(String body) {
        return messages("when player_join {\n" + body + "\n}", true);
    }

    private static List<Object> ops(String head, String body) {
        Compiler.Result r = compile(head + "when player_join {\n" + body + "\n}");
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        return Json.array(Json.object(Json.array(r.module().get("handlers")).get(0)).get("operations"));
    }

    private static List<Object> ops(String body) {
        return ops("", body);
    }

    private static List<String> actions(List<Object> ops) {
        List<String> out = new ArrayList<>();
        for (Object o : ops) out.add((String) Json.object(o).get("action"));
        return out;
    }

    private static String circle(String text) {
        Compiler.Result first = compile(text);
        assertTrue(first.ok(), () -> first.diagnostics().toString());
        Map<String, Object> module = Json.object(Json.parse(Json.write(first.module())));
        String printed = Decompiler.decompile(DATA, NAMES, module).text();
        Compiler.Result back = compile(printed);
        assertTrue(back.ok(), () -> printed + "\n" + back.diagnostics());
        assertNull(RoundTrip.firstDifference(RoundTrip.normalize(DATA, module),
                RoundTrip.normalize(DATA, Json.object(Json.parse(Json.write(back.module())))), ""), printed);
        return printed;
    }

    @Test
    void mapValuesAreSingleCells() {
        String json = Json.write(ops("m = {\"a\": 1}\nm[\"k\"] = [1, 2]").get(1));
        assertTrue(json.contains("{\"name\":\"value\",\"value\":{\"type\":\"array\",\"values\":[{\"type\":\"array\""), json);
        String printed = circle("when player_join {\n    m = {\"a\": 1}\n    m[\"k\"] = 2\n    m[\"l\"] = [1, 2]\n    x = m[\"k\"]\n    send_message(\"${x}\")\n}\n");
        assertTrue(printed.contains("m[\"k\"] = 2"), printed);
        assertTrue(printed.contains("m[\"l\"] = [1, 2]"), printed);
        assertTrue(printed.contains("x = m[\"k\"]"), printed);
    }

    @Test
    void oldMapBlockStaysOld() {
        List<Object> list = ops("m = {\"a\": 1}\ny = get_map_value(map: m, key: \"a\")\nz = m[\"a\"]\nsend_message(\"${y}${z}\")");
        assertEquals("set_variable_get_map_value", actions(list).get(1));
        assertEquals("set_variable_get_map_value_single", actions(list).get(2));
    }

    @Test
    void oldMapReadPrintsAsAnIndex() {
        String world = "{\"handlers\":[{\"type\":\"event\",\"event\":\"player_join\",\"position\":0,\"operations\":["
                + "{\"action\":\"set_variable_value\",\"values\":[{\"name\":\"variable\",\"value\":{\"type\":\"variable\",\"variable\":\"m\",\"scope\":\"local\"}},"
                + "{\"name\":\"value\",\"value\":{\"type\":\"map\",\"values\":{}}}]},"
                + "{\"action\":\"set_variable_get_map_value\",\"values\":["
                + "{\"name\":\"default_value\",\"value\":{}},"
                + "{\"name\":\"variable\",\"value\":{\"type\":\"array\",\"values\":[{\"type\":\"variable\",\"variable\":\"x\",\"scope\":\"local\"},{},{}]}},"
                + "{\"name\":\"key\",\"value\":{\"type\":\"array\",\"values\":[{\"type\":\"text\",\"text\":\"hp\",\"parsing\":\"plain\"},{},{}]}},"
                + "{\"name\":\"map\",\"value\":{\"type\":\"variable\",\"variable\":\"m\",\"scope\":\"local\"}}]},"
                + "{\"action\":\"set_variable_get_map_value\",\"values\":["
                + "{\"name\":\"variable\",\"value\":{\"type\":\"array\",\"values\":[{\"type\":\"variable\",\"variable\":\"y\",\"scope\":\"local\"}]}},"
                + "{\"name\":\"key\",\"value\":{\"type\":\"array\",\"values\":[{\"type\":\"variable\",\"variable\":\"x\",\"scope\":\"local\"}]}},"
                + "{\"name\":\"map\",\"value\":{\"type\":\"variable\",\"variable\":\"m\",\"scope\":\"local\"}}]},"
                + "{\"action\":\"set_variable_get_map_value\",\"values\":["
                + "{\"name\":\"variable\",\"value\":{\"type\":\"array\",\"values\":[{\"type\":\"variable\",\"variable\":\"a\",\"scope\":\"local\"},{\"type\":\"variable\",\"variable\":\"b\",\"scope\":\"local\"}]}},"
                + "{\"name\":\"key\",\"value\":{\"type\":\"array\",\"values\":[{\"type\":\"text\",\"text\":\"p\",\"parsing\":\"legacy\"},{\"type\":\"text\",\"text\":\"q\",\"parsing\":\"legacy\"}]}},"
                + "{\"name\":\"map\",\"value\":{\"type\":\"variable\",\"variable\":\"m\",\"scope\":\"local\"}}]}]}]}";
        Map<String, Object> module = Json.object(Json.parse(world));
        String printed = Decompiler.decompile(DATA, NAMES, module).text();
        assertTrue(printed.contains("x = m[\"hp\"]"), printed);
        assertTrue(printed.contains("y = m[x]"), printed);
        assertTrue(printed.contains("a, b = m[\"p\", \"q\"]"), "много ключей сразу — разложение\n" + printed);
        Compiler.Result back = compile(printed);
        assertTrue(back.ok(), () -> back.diagnostics().toString());
        assertNull(RoundTrip.firstDifference(RoundTrip.normalize(DATA, module),
                RoundTrip.normalize(DATA, Json.object(Json.parse(Json.write(back.module())))), ""));
        List<Delivery.Line> world1 = Delivery.lines(module);
        Delivery.Plan plan = Delivery.plan(DATA, world1, Delivery.lines(Json.object(Json.parse(Json.write(back.module())))));
        assertEquals(1, plan.same().size(), "строка без правок остаётся в мире как была");
        assertTrue(Json.write(plan.module(Delivery.lines(Json.object(Json.parse(Json.write(back.module())))))).contains("\"set_variable_get_map_value\""));
    }

    @Test
    void emptyCellsFromTheServerDisappear() {
        String world = """
                {"handlers":[{"type":"function","name":"f","position":0,"values":[
                {"name":"is_hidden","value":{"type":"enum","enum":"TRUE"}},
                {"name":"description","value":{"type":"array","values":[{"type":"text","text":"Первая","parsing":"legacy"},{},{}]}},
                {"name":"icon","value":{"type":"item","item":"AAAAAAAAAAA="}}],
                "operations":[
                {"action":"if_variable_equals","values":[
                {"name":"value","value":{"type":"variable","variable":"t","scope":"local"}},
                {"name":"compare","value":{"type":"array","values":[{"type":"text","text":"Word","parsing":"plain"},{},{},{}]}}],
                "operations":[{"action":"repeat_multi_times","values":[{"name":"variable","value":{}},{"name":"amount","value":{"type":"number","number":4.0}}],"operations":[]}]}]}]}
                """;
        Map<String, Object> module = Json.object(Json.parse(world));
        String printed = Decompiler.decompile(DATA, NAMES, module).text();
        assertTrue(printed.contains("if t == \"Word\" {"), printed);
        assertTrue(printed.contains("repeat 4 {}"), printed);
        assertTrue(printed.contains("@description(\"Первая\")"), printed);
        assertTrue(printed.contains("@hidden"), printed);
        assertFalse(printed.contains("raw("), printed);
        assertFalse(printed.contains("@values"), printed);
        Compiler.Result back = compile(printed);
        assertTrue(back.ok(), () -> back.diagnostics().toString());
        assertNull(RoundTrip.firstDifference(RoundTrip.normalize(DATA, module),
                RoundTrip.normalize(DATA, Json.object(Json.parse(Json.write(back.module())))), ""));
    }

    @Test
    void scopeAtTheUse() {
        List<Object> list = ops("game.a = 1\nsave.a = 2\nlocal.`с пробелом` = 3\ngame.a += 1\nsend_message(\"${game.a} ${save.a}\")");
        String json = Json.write(list);
        assertTrue(json.contains("\"variable\":\"a\",\"scope\":\"game\""), json);
        assertTrue(json.contains("\"variable\":\"a\",\"scope\":\"save\""), json);
        assertTrue(json.contains("\"variable\":\"с пробелом\",\"scope\":\"local\""), json);
        assertTrue(json.contains("%var(a) %var_save(a)"), json);
        assertTrue(Json.write(ops("game.a = 1\nx = game.a + 1\nsend_message(\"${x}\")")).contains("%math((%var(a))+1)"));
        String printed = circle("when player_join {\n    game.a = 1\n    save.a = 2\n    game.l = [1]\n    game.l[0] = 3\n    send_message([\"${game.a}\", save.a])\n}\n");
        assertTrue(printed.contains("game.a = 1"), printed);
        assertTrue(printed.contains("save.a = 2"), printed);
        assertEquals(List.of(), messages("when player_join {\n    game.block_equals(1, 2)\n}", false).stream()
                .filter(m -> m.contains("game")).toList());
        Formatter.Result formatted = Formatter.format("when player_join {\n    game . a  =  1\n}\n");
        assertEquals("when player_join {\n    game.a = 1\n}\n", formatted.text());
    }

    @Test
    void kindsFollowVariables() {
        assertTrue(warnings("x = 5\nplayer.teleport(x)").contains("«x» — число, а здесь нужно «местоположение»"));
        assertTrue(warnings("x = \"abc\"\nwait(x)").stream().anyMatch(m -> m.contains("текстовое значение в «число»")));
        assertTrue(warnings("x = loc(1, 2, 3)\ngive_items(x)").contains("«x» — местоположение, а здесь нужно «предмет»"));
        assertEquals(List.of(), warnings("x = 5\nx = loc(1, 2, 3)\nplayer.teleport(x)"));
        assertEquals(List.of(), warnings("x = loc(1, 2, 3)\nplayer.teleport(x)"));
        assertEquals(List.of(), warnings("x = loc(1, 2, 3)\ny = x\nplayer.teleport(y)"));
        assertTrue(warnings("x = 1\ny = x\nplayer.teleport(y)").contains("«y» — число, а здесь нужно «местоположение»"));
        assertEquals(List.of(), messages("game x\nwhen player_join {\n    x = 5\n    player.teleport(x)\n}", false));
        assertTrue(messages("func f(p: location) {\n    wait(p)\n}", false).contains("«p» — местоположение, а здесь нужно «число»"));
    }

    @Test
    void vectorsAreNotNumbers() {
        assertEquals(List.of("set_variable_value", "set_variable_value", "set_variable_add_vectors", "player_send_message"),
                actions(ops("a = vec(1, 2, 3)\nb = vec(1, 1, 1)\nc = a + b\nsend_message(\"${c}\")")));
        assertEquals("set_variable_subtract_vectors", actions(ops("a = vec(1, 2, 3)\nc = a - vec(1, 1, 1)\nsend_message(\"${c}\")")).get(1));
        assertEquals("set_variable_multiply_vector", actions(ops("a = vec(1, 2, 3)\nc = a * 2\nsend_message(\"${c}\")")).get(1));
        assertEquals("set_variable_divide_vector", actions(ops("a = vec(1, 2, 3)\nc = a / vec(2, 2, 2)\nsend_message(\"${c}\")")).get(1));
        assertEquals("set_variable_shift_location_on_vector", actions(ops("a = loc(1, 2, 3)\nc = a + vec(0, 1, 0)\nplayer.teleport(c)")).get(1));
        assertEquals("set_variable_add_vectors", actions(ops("a = vec(1, 2, 3)\na += vec(1, 1, 1)\nsend_message(\"${a}\")")).get(1));
        assertEquals(List.of("«a» — местоположение, а в формуле только числа"), errors("a = loc(1, 2, 3)\nc = a + 1\nsend_message(\"${c}\")"));
    }

    @Test
    void warningsForDeadCode() {
        assertTrue(warnings("x = 1").contains("«x» задана, но нигде не читается"));
        assertEquals(List.of(), warnings("x = 1\nsend_message(\"${x}\")"));
        assertEquals(List.of(), warnings("x = 1\nsend_message(\"%var_local(x)\")"));
        assertEquals(List.of(), warnings("x = 1\nsend_message(\"%math(x+1)\")"));
        assertEquals(List.of(), warnings("x = 1\ny = x + 1\nsend_message(\"${y}\")"));
        assertEquals(List.of(), messages("save x = 1\nwhen player_join {\n    x = 2\n}", false));
        assertEquals(List.of(), messages("when player_join {\n    game x = 2\n}", false));
        assertFalse(warnings("x = 1\n`W`()").contains("«x» задана, но нигде не читается"));
        assertTrue(messages("func f(a, b) {\n    send_message(\"${a}\")\n}\n", false).contains("параметр «b» не используется"));
        assertEquals(List.of(), messages("func f(a) {\n    send_message(\"%var_line(a)\")\n}\n", false));
        assertTrue(messages("func f() {\n    return\n    send_message(\"a\")\n}\n", false).contains("этот код никогда не выполнится"));
        assertTrue(warnings("forever {\n    break\n    send_message(\"a\")\n}").contains("этот код никогда не выполнится"));
        assertEquals(List.of("индекс списка считается с нуля — как сервер поймёт отрицательный, не проверено"),
                warnings("l = [1]\nx = l[-1]\nsend_message(\"${x}\")"));
    }

    @Test
    void constants() {
        String head = "const MAX = 5\nconst SWORD = item(diamond_sword, name: \"&bМеч\")\nconst HOME = loc(1, 2, 3)\n"
                + "const DOUBLE = MAX * 2\nconst TITLE = \"Счёт\"\nconst L = [1, 2]\n";
        List<Object> list = ops(head, "give_items(SWORD, MAX)\nplayer.teleport(HOME)\nx = MAX + 1\ny = DOUBLE\nsend_message(\"${TITLE}: ${MAX}, ${x}, ${y}\")\nfor v in L {\n    send_message(\"${v}\")\n}");
        String json = Json.write(list);
        assertTrue(json.contains("\"number\":6.0"), json);
        assertTrue(json.contains("\"number\":10.0"), json);
        assertTrue(json.contains("Счёт: 5, %var_local(x), %var_local(y)"), json);
        assertFalse(json.contains("\"variable\":\"MAX\""), json);
        assertEquals(List.of("«MAX» — постоянная, её не меняют"), messages("const MAX = 1\nwhen player_join {\n    MAX = 2\n}", true));
        assertEquals(List.of("постоянная «A» уже объявлена"), messages("const A = 1\nconst A = 2\n", true));
        assertEquals(List.of("в const только постоянное значение: число, текст, item(…), loc(…), список"),
                messages("const A = value.current_health\n", true));
        assertEquals(List.of("в const только постоянное значение: число, текст, item(…), loc(…), список",
                "в const только постоянное значение: число, текст, item(…), loc(…), список"), messages("const A = B\nconst B = A\n", true));
        assertEquals(List.of("const пишут вне when, func и process — в начале файла"), errors("const A = 1"));
        assertTrue(ops("func f(MAX) {\n    send_message(\"${MAX}\")\n}\nconst MAX = 5\n", "f(1)").size() == 1);
        assertEquals("when player_join {}\n", Formatter.format("when player_join {}\n").text());
        assertEquals("const A = 1\nconst B = 2\n\nwhen player_join {}\n", Formatter.format("const   A=1\nconst B  =2\nwhen player_join {}\n").text());
    }

    @Test
    void constantConditionsFold() {
        assertEquals(List.of("player_send_message"), actions(ops("const DEBUG = true\n", "if DEBUG {\n    send_message(\"a\")\n}")));
        assertEquals(List.of(), actions(ops("const DEBUG = false\n", "if DEBUG {\n    send_message(\"a\")\n}")));
        assertEquals(List.of("player_send_message"), actions(ops("const DEBUG = false\n", "if DEBUG {\n    wait(1)\n} else {\n    send_message(\"b\")\n}")));
        assertEquals(List.of("if_variable_equals", "else"), actions(ops("const DEBUG = false\n", "x = 1\nif DEBUG {\n    wait(1)\n} else if x == 1 {\n    send_message(\"b\")\n} else {\n    wait(2)\n}")).subList(1, 3));
        assertTrue(messages("const DEBUG = false\nwhen player_join {\n    if DEBUG {\n        give_items(item(stne))\n    }\n}", true).get(0).contains("stne"));
    }

    @Test
    void trueFalseAndFlags() {
        assertTrue(Json.write(ops("x = true\nsend_message(\"${x}\")").get(0)).contains("\"number\":1.0"));
        assertTrue(Json.write(ops("x = false\nsend_message(\"${x}\")").get(0)).contains("\"number\":0.0"));
        assertEquals(List.of("repeat_forever"), actions(ops("while true {\n    wait(1)\n}")));
        assertEquals(List.of("условие всегда ложно — повторение не выполнится ни разу"), errors("while false {\n    wait(1)\n}"));
        List<Object> flag = ops("ok = 1 > 0\nif ok {\n    send_message(\"y\")\n}");
        assertEquals("if_variable_equals", actions(flag).get(actions(flag).size() - 1));
        assertTrue(Json.write(flag.get(flag.size() - 1)).contains("\"compare\",\"value\":{\"type\":\"array\",\"values\":[{\"type\":\"number\",\"number\":1.0"));
        assertTrue(Boolean.TRUE.equals(Json.object(ops("ok = true\nif not ok {\n    wait(1)\n}").get(1)).get("is_inverted")));
        assertEquals(List.of("«x» — число: условие — это сравнение"), errors("x = 5\nif x {\n    wait(1)\n}"));
        assertEquals(List.of(), errors("x = 1\nif x {\n    wait(1)\n}"));
        assertEquals(List.of(), errors("if value.current_health > 1 and x {\n    wait(1)\n}\nx = true"));
        assertTrue(Json.write(ops("true = 5\nsend_message(\"${true}\")")).contains("%var_local(true)"));
        assertEquals(List.of(), errors("give_items(item(stick, unbreakable: true, glint: false))"));
    }

    @Test
    void membership() {
        assertEquals("if_variable_list_contains_value", actions(ops("l = [1, 2]\nif 1 in l {\n    wait(1)\n}")).get(1));
        assertEquals("if_variable_map_has_key", actions(ops("m = {\"a\": 1}\nif \"a\" in m {\n    wait(1)\n}")).get(1));
        Map<String, Object> range = Json.object(ops("x = 3\nif x in 1..5 {\n    wait(1)\n}").get(1));
        assertEquals("if_variable_number_in_range", range.get("action"));
        assertTrue(Json.write(range).contains("INCLUDE_ALL"));
        Map<String, Object> many = Json.object(ops("x = 3\nif x in [1, 2, 3] {\n    wait(1)\n}").get(1));
        assertEquals("if_variable_equals", many.get("action"));
        assertEquals(3, Json.array(Json.object(Json.object(Json.array(many.get("values")).get(1)).get("value")).get("values")).size());
        assertTrue(Boolean.TRUE.equals(Json.object(ops("x = 3\nif x not in [1, 2] {\n    wait(1)\n}").get(1)).get("is_inverted")));
        assertTrue(Boolean.TRUE.equals(Json.object(ops("x = 3\nif not x in [1, 2] {\n    wait(1)\n}").get(1)).get("is_inverted")));
        assertTrue(errors("if 1 in q {\n    wait(1)\n}\nq = 5").get(0).contains("не понятно, «q»"));
        assertEquals("when player_join {\n    if x in [1, 2] {}\n}\n", Formatter.format("when player_join {\n  if x in[1,2] {}\n}\n").text());
        String printed = circle("when player_join {\n    x = 3\n    l = [1]\n    m = {\"a\": 1}\n    if x in [1, 2] {}\n    if x in 1..5 {}\n    if not x in l {}\n    if \"a\" in m {}\n}\n");
        assertTrue(printed.contains("if x in [1, 2] {"), printed);
        assertTrue(printed.contains("if x in 1..5 {"), printed);
        assertTrue(printed.contains("if not x in l {"), printed);
        assertTrue(printed.contains("if \"a\" in m {"), printed);
    }

    @Test
    void oneVariableOrIsOneBlock() {
        assertEquals(List.of("set_variable_value", "if_variable_equals"), actions(ops("x = 1\nif x == 1 or x == 2 or x == 3 {\n    wait(1)\n}")));
        List<Object> not = ops("x = 1\nif x != 1 and x != 2 {\n    wait(1)\n}");
        assertEquals(List.of("set_variable_value", "if_variable_equals"), actions(not));
        assertTrue(Boolean.TRUE.equals(Json.object(not.get(1)).get("is_inverted")));
        assertEquals(List.of("set_variable_value", "set_variable_value", Conditions.PAIRS),
                actions(ops("x = 1\ny = 2\nif x == 1 or y == 2 {\n    wait(1)\n}")));
    }

    @Test
    void length() {
        assertEquals(List.of("set_variable_value", "player_send_message"), actions(ops("l = [1]\nsend_message(\"${len(l)}\")")));
        String json = Json.write(ops("save l = [1]\nx = len(l) - 1\nsend_message(\"${len(l)} ${x}\")"));
        assertTrue(json.contains("%math((%length_save(l))-1)"), json);
        assertTrue(json.contains("%length_save(l) %var_local(x)"), json);
        assertTrue(warnings("x = \"a\"\ny = len(x)\nsend_message(\"${y}\")").get(0).contains("len считает"));
        assertTrue(circle("when player_join {\n    l = [1]\n    x = len(l)\n    send_message(\"${x}\")\n}\n").contains("x = len(l)"));
    }

    @Test
    void time() {
        assertTrue(Json.write(ops("wait(5s)")).contains("\"number\":100.0"));
        assertTrue(Json.write(ops("wait(1m30s)")).contains("\"number\":1800.0"));
        assertTrue(Json.write(ops("wait(5с)")).contains("\"number\":100.0"));
        assertTrue(Json.write(ops("wait(2s, time_unit: seconds)")).contains("\"number\":2.0"));
        assertTrue(Json.write(ops("wait(2m, time_unit: minutes)")).contains("\"number\":2.0"));
        assertTrue(Json.write(ops("give_potion_effect(potion(speed, duration: 30s))")).contains("\"duration\":600"));
        assertEquals(List.of("это не целое число тиков: 0.2"), errors("wait(10ms)"));
        assertEquals("when player_join {\n    wait(1m30s)\n}\n", Formatter.format("when player_join {\n    wait( 1m30s )\n}\n").text());
    }

    @Test
    void markers() {
        String printed = circle("when player_join {\n    x = 1\n    controller.exception(variable: x, exception_type: enum(\"ERROR\")) {}\n    wait(1, time_unit: enum(\"SECONDS\", x))\n}\n");
        assertTrue(printed.contains("exception_type: enum(\"ERROR\")"), printed);
        assertTrue(printed.contains("time_unit: enum(\"SECONDS\", x)"), printed);
    }

    @Test
    void markerParameter() {
        String text = "func f(mode: enum(\"alpha\", \"beta\") = \"beta\", n: number = 1) {\n    send_message(\"${mode} ${n}\")\n}\n\n"
                + "when player_join {\n    x = \"beta\"\n    f(mode: \"alpha\")\n    f(mode: x)\n    f()\n}\n";
        String json = Json.write(compile(text).module());
        assertTrue(json.contains("\"type_key\":\"enum\""), json);
        assertTrue(json.contains("\"default_element\":\"beta\""), json);
        assertTrue(json.contains("{\\\"type\\\":\\\"text\\\",\\\"text\\\":\\\"mode\\\",\\\"parsing\\\":\\\"plain\\\"}\":{\"type\":\"text\",\"text\":\"alpha\",\"parsing\":\"plain\"}"), json);
        assertTrue(json.contains("{\"type\":\"array\",\"values\":[{\"type\":\"variable\",\"variable\":\"x\",\"scope\":\"local\"},{\"type\":\"text\",\"text\":\"beta\",\"parsing\":\"plain\"}]}"), json);
        String printed = circle(text);
        assertTrue(printed.contains("mode: enum(\"alpha\", \"beta\") = \"beta\""), printed);
        assertEquals(List.of("у «mode» нет варианта «gamma»"),
                messages("func f(mode: enum(\"a\")) {\n    send_message(\"${mode}\")\n}\nwhen player_join {\n    f(mode: \"gamma\")\n}", true));
    }

    private static Compiler.Result withModule(String text, String module) {
        return Compiler.compile(DATA, NAMES, "main.xero", text, path -> path.equals(Compiler.MODULE + "lib") ? module : null);
    }

    @Test
    void importedModule() {
        String module = "{\"handlers\":[{\"type\":\"event\",\"event\":\"world_start\",\"position\":7,\"operations\":[]},"
                + "{\"type\":\"function\",\"name\":\"Lib.a\",\"position\":3,\"operations\":[{\"action\":\"nobody_knows\",\"values\":[]}],\"values\":[]},"
                + "{\"type\":\"process\",\"name\":\"Lib.p\",\"position\":5,\"operations\":[],\"values\":[]}]}";
        Compiler.Result r = withModule("import \"lib\"\nwhen player_join {\n    `Lib.a`()\n    start `Lib.p`()\n}\n", module);
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        List<Object> handlers = Json.array(r.module().get("handlers"));
        assertEquals(4, handlers.size());
        assertEquals("Lib.a", Json.object(handlers.get(1)).get("name"));
        assertEquals(1, Json.object(handlers.get(1)).get("position"));
        assertEquals("Lib.p", Json.object(handlers.get(2)).get("name"));
        assertEquals("world_start", Json.object(handlers.get(3)).get("event"));
        assertTrue(Json.write(handlers.get(1)).contains("nobody_knows"), "код модуля уходит как есть");
        assertEquals(List.of("Lib.a", "Lib.p"), r.imported().stream().map(f -> f.name()).toList());
        assertFalse(r.diagnostics().stream().anyMatch(d -> d.message().contains("нет в этом файле")));
    }

    @Test
    void dottedNamesNeedNoBackticks() {
        String module = "{\"handlers\":[{\"type\":\"function\",\"name\":\"Lib.a\",\"position\":0,\"operations\":[],\"values\":[]},"
                + "{\"type\":\"function\",\"name\":\"Lib.sub.b\",\"position\":1,\"operations\":[],\"values\":[]},"
                + "{\"type\":\"process\",\"name\":\"Lib.p\",\"position\":2,\"operations\":[],\"values\":[]}]}";
        Compiler.Result r = withModule("import \"lib\"\nwhen player_join {\n    Lib.a()\n    Lib.sub.b()\n    `Lib.a`()\n    start Lib.p()\n}\n", module);
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        assertEquals(List.of(), r.diagnostics());
        String json = Json.write(Json.array(r.module().get("handlers")).get(0));
        assertTrue(json.contains("\"text\":\"Lib.sub.b\""), json);
        Compiler.Result typo = withModule("import \"lib\"\nwhen player_join {\n    Lib.aa()\n}\n", module);
        assertEquals("нет действия «Lib.aa»", typo.diagnostics().get(0).message());
        assertTrue(typo.diagnostics().get(0).hint().contains("Lib.a"), typo.diagnostics().get(0).hint());
        assertEquals("нет действия «plyer.teleport»", messages("when player_join {\n    plyer.teleport(loc(1, 2, 3))\n}", true).get(0));
        assertEquals("слишком длинное имя: player.a.b", messages("when player_join {\n    player.a.b()\n}", true).get(0));
        String printed = circle("func Mapi.load(a) {\n    send_message(\"${a}\")\n}\n\nprocess Mapi.tick() {}\n\n"
                + "when player_join {\n    Mapi.load(1)\n    start Mapi.tick()\n}\n");
        assertTrue(printed.contains("func Mapi.load(a) {"), printed);
        assertTrue(printed.contains("process Mapi.tick() {"), printed);
        assertTrue(printed.contains("    Mapi.load(a: 1)") || printed.contains("    Mapi.load(1)"), printed);
        assertTrue(printed.contains("    start Mapi.tick()"), printed);
        assertEquals("func Mapi.load() {}\n", Formatter.format("func  Mapi.load( ) {}\n").text());
    }

    @Test
    void formulasComeBack() {
        String printed = circle("""
                func plus(a, b) {
                    return a + b
                }

                when player_join {
                    a = value.current_health
                    b = 2
                    монеты = 3
                    l = [1]
                    save s = 1
                    x = a * 2 + b
                    y = (a + b) * 2
                    z = a - (b - 1)
                    w = -a + sqrt(a * a)
                    v = value.current_health * 2
                    u = len(l) - 1
                    t = монеты + 1
                    r = a % 2
                    plus(1, 2)
                    send_message("${x}${y}${z}${w}${v}${u}${t}${r}${s}")
                }
                """);
        for (String line : List.of("return a + b", "x = a * 2 + b", "y = (a + b) * 2", "z = a - (b - 1)", "w = -a + sqrt(a * a)",
                "v = value.current_health * 2", "u = len(l) - 1", "t = монеты + 1", "r = a % 2"))
            assertTrue(printed.contains(line), line + "\n" + printed);
        assertFalse(printed.contains("num("), printed);
    }

    @Test
    void formulasThatStayNumbers() {
        String self = circle("when player_join {\n    x = 1\n    x = num(\"%math(x+5)\")\n    send_message(\"${x}\")\n}\n");
        assertTrue(self.contains("x = num(\"%math(x+5)\")"), self);
        String hand = circle("when player_join {\n    l = [1]\n    x = num(\"%math(%length_local(l)-1)\")\n    send_message(\"${x}\")\n}\n");
        assertTrue(hand.contains("x = len(l) - 1"), hand);
        String stranger = circle("when player_join {\n    x = num(\"%math(q+1)\")\n    send_message(\"${x}\")\n}\n");
        assertTrue(stranger.contains("num(\"%math(q+1)\")"), stranger);
        String vector = circle("when player_join {\n    a = vec(1, 2, 3)\n    x = num(\"%math(a+1)\")\n    send_message(\"${x}${a}\")\n}\n");
        assertTrue(vector.contains("num(\"%math(a+1)\")"), vector);
        String action = circle("when player_join {\n    a = 1\n    x = num(\"%math(clamp(a,0,20))\")\n    send_message(\"${x}\")\n}\n");
        assertTrue(action.contains("num(\"%math(clamp(a,0,20))\")"), action);
    }

    @Test
    void plainFunctionNamesReadAsCalls() {
        String world = "{\"handlers\":[{\"type\":\"function\",\"name\":\"Lib.a\",\"position\":0,\"operations\":[],\"values\":[]},"
                + "{\"type\":\"process\",\"name\":\"Lib.p\",\"position\":1,\"operations\":[],\"values\":[]},"
                + "{\"type\":\"event\",\"event\":\"player_join\",\"position\":2,\"operations\":["
                + "{\"action\":\"call_function\",\"values\":[{\"name\":\"function_name\",\"value\":{\"type\":\"text\",\"text\":\"Lib.a\",\"parsing\":\"plain\"}}]},"
                + "{\"action\":\"start_process\",\"values\":[{\"name\":\"process_name\",\"value\":{\"type\":\"text\",\"text\":\"Lib.p\",\"parsing\":\"plain\"}}]}]}]}";
        Map<String, Object> module = Json.object(Json.parse(world));
        String printed = Decompiler.decompile(DATA, NAMES, module).text();
        assertTrue(printed.contains("    Lib.a()\n"), printed);
        assertTrue(printed.contains("    start Lib.p()\n"), printed);
        Compiler.Result back = compile(printed);
        assertTrue(back.ok(), () -> back.diagnostics().toString());
        assertNull(RoundTrip.firstDifference(RoundTrip.normalize(DATA, module),
                RoundTrip.normalize(DATA, Json.object(Json.parse(Json.write(back.module())))), ""));
    }

    @Test
    void twinsInTheWorld() {
        String line = "{\"type\":\"function\",\"name\":\"Mapi.a\",\"position\":%d,\"operations\":[],\"values\":[]}";
        Map<String, Object> module = Json.object(Json.parse("{\"handlers\":[" + line.replace("%d", "0") + "," + line.replace("%d", "1") + "]}"));
        Decompiler.Result text = Decompiler.decompile(DATA, NAMES, module);
        assertTrue(text.text().contains("@twin\nfunc Mapi.a()"), text.text());
        assertTrue(text.notes().stream().anyMatch(n -> n.contains("задвоил")), text.notes().toString());
        Compiler.Result back = compile(text.text());
        assertTrue(back.ok(), () -> back.diagnostics().toString());
        assertEquals(2, Json.array(back.module().get("handlers")).size());
        assertTrue(messages("func a() {}\nfunc a() {}\n", true).get(0).contains("уже объявлена выше"));
    }

    @Test
    void cancelOnlyWhereItWorks() {
        assertTrue(warnings("cancel_event()").get(0).contains("нельзя отменить"));
        assertEquals(List.of(), messages("when player_rejoin {\n    cancel_event()\n}", false));
        assertEquals(List.of(), messages("func f() {\n    cancel_event()\n}", false));
    }

    @Test
    void readableText() {
        String printed = circle("when player_join {\n    x = 1\n    game g = 2\n    `с пробелом` = 3\n    l = [1]\n"
                + "    send_message(\"a ${x} b ${g} c ${`с пробелом`} d ${x + 1} e ${len(l)} %player% %nothing(1) 50%\")\n}\n");
        assertTrue(printed.contains("\"a ${x} b ${g} c ${`с пробелом`} d ${x + 1} e ${len(l)} %player% %nothing(1) 50%\""), printed);
        Map<String, Object> module = Json.object(Json.parse("{\"handlers\":[{\"type\":\"event\",\"event\":\"player_join\",\"position\":0,\"operations\":["
                + "{\"action\":\"set_variable_value\",\"values\":[{\"name\":\"variable\",\"value\":{\"type\":\"variable\",\"variable\":\"t\",\"scope\":\"local\"}},"
                + "{\"name\":\"value\",\"value\":{\"type\":\"text\",\"text\":\"Word %var_local(t) %var_local(stranger)\",\"parsing\":\"plain\"}}]},"
                + "{\"action\":\"set_variable_value\",\"values\":[{\"name\":\"variable\",\"value\":{\"type\":\"variable\",\"variable\":\"u\",\"scope\":\"local\"}},"
                + "{\"name\":\"value\",\"value\":{\"type\":\"text\",\"text\":\"&cкрасный\",\"parsing\":\"plain\"}}]}]}]}"));
        String world = Decompiler.decompile(DATA, NAMES, module).text();
        assertTrue(world.contains("t = \"Word ${t} %var_local(stranger)\""), world);
        assertTrue(world.contains("u = plain\"&cкрасный\""), world);
        Compiler.Result back = compile(world);
        assertTrue(back.ok(), () -> back.diagnostics().toString());
        assertNull(RoundTrip.firstDifference(RoundTrip.normalize(DATA, module),
                RoundTrip.normalize(DATA, Json.object(Json.parse(Json.write(back.module())))), ""));
    }

    @Test
    void longLinesWrap() {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < 80; i++) list.append(i == 0 ? "" : ", ").append("\"id").append(i).append('"');
        String text = "when player_join {\n    l = create_list([" + list + "])\n    send_message(\"${l}\")\n}\n";
        String once = Formatter.format(text).text();
        for (String line : once.split("\n")) assertTrue(line.length() <= 100, line);
        assertEquals(once, Formatter.format(once).text());
        assertEquals(Json.write(compile(text).module()), Json.write(compile(once).module()));
        assertTrue(once.contains("l = create_list(["), once);
    }

    @Test
    void repeatedItemsBecomeConstants() {
        String long1 = "item(diamond_sword, name: \"&bМеч героя\", lore: [\"Первая строка описания\"], unbreakable: true)";
        String printed = circle("when player_join {\n    give_items(" + long1 + ")\n    give_items(" + long1 + ")\n    give_items(item(stick))\n}\n");
        assertTrue(printed.contains("const МЕЧ_ГЕРОЯ = item("), printed);
        assertTrue(printed.contains("give_items(МЕЧ_ГЕРОЯ)"), printed);
        assertTrue(printed.contains("give_items(item(stick))"), printed);
    }

    @Test
    void shortTypeCheck() {
        String printed = circle("when player_join {\n    x = 1\n    if var.is_type(x, variable_type: text) {}\n}\n");
        assertTrue(printed.contains("if var.is_type(x, text) {"), printed);
    }

    @Test
    void importFailures() {
        Compiler.Result refused = withModule("import \"lib\"\n", "{\"error\":\"автор закрыл код\",\"hint\":\"подсказка\"}");
        assertEquals("автор закрыл код", refused.diagnostics().get(0).message());
        assertEquals("подсказка", refused.diagnostics().get(0).hint());
        assertEquals("модуль «other» не получен", withModule("import \"other\"\n", "{}").diagnostics().get(0).message());
        assertEquals("у модуля «lib» непонятный код", withModule("import \"lib\"\n", "<html>").diagnostics().get(0).message());
        assertEquals("кто-то", withModule("import \"lib\"\n", "{\"note\":\"кто-то\",\"handlers\":[]}").diagnostics().get(0).message());
        assertTrue(compile("import \"kits.xero\"\n").diagnostics().get(0).message().contains("нет файла"));
        assertEquals("«x» уже есть в модуле «lib»", withModule("import \"lib\"\nfunc x() {}\n",
                "{\"handlers\":[{\"type\":\"function\",\"name\":\"x\",\"position\":0,\"operations\":[],\"values\":[]}]}").diagnostics().get(0).message());
    }

    @Test
    void importedParameters() {
        String module = "{\"handlers\":[{\"type\":\"function\",\"name\":\"f\",\"position\":0,\"operations\":[],\"values\":[{\"name\":\"parameters\",\"value\":"
                + "{\"type\":\"array\",\"values\":[" + Json.write(Wire.parameter("n", "number", true, "{}", 0, Wire.NO_TRANSLATIONS)) + ","
                + Json.write(Wire.markerParameter("mode", List.of("a", "b"), "a", 1, Wire.NO_TRANSLATIONS)) + "]}}]}]}";
        assertTrue(withModule("import \"lib\"\nwhen player_join {\n    f(1, mode: \"b\")\n}\n", module).ok());
        assertTrue(withModule("import \"lib\"\nwhen player_join {\n    f()\n}\n", module).diagnostics().get(0).message().contains("не передан параметр «n»"));
        assertTrue(withModule("import \"lib\"\nwhen player_join {\n    f(1, mode: \"c\")\n}\n", module).diagnostics().get(0).message().contains("нет варианта «c»"));
        assertTrue(withModule("import \"lib\"\nwhen player_join {\n    f(loc(1, 2, 3))\n}\n", module).diagnostics().get(0).message().contains("нужно «число»"));
    }

    @Test
    void liveMarkerParameterMatches() {
        Map<String, Object> p = Wire.markerParameter("mode", List.of("alpha"), "alpha", 0, Wire.NO_TRANSLATIONS);
        Object element = Json.array(Json.parse((String) p.get("elements"))).get(0);
        assertTrue(((String) element).contains("\\u003d\\u003d\""), (String) element);
    }
}
