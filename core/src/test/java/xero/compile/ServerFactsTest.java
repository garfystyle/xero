package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerFactsTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private static Compiler.Result compile(String text) {
        Compiler.Result r = Compiler.compile(DATA, NAMES, text);
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        return r;
    }

    private static List<Object> ops(String body) {
        return ops(compile("when player_join {\n" + body + "\n}\n"), 0);
    }

    private static List<Object> ops(Compiler.Result r, int handler) {
        return Json.array(Json.object(Json.array(r.module().get("handlers")).get(handler)).get("operations"));
    }

    @Test
    void assignmentFillsTheVariablesOfMultiple() {
        assertEquals(Json.write(ops("multiple(x, 5)")), Json.write(ops("x = multiple(5)")));
    }

    @Test
    void removingVariablesWorksInBothForms() {
        assertEquals(Json.write(ops("var.remove(x)")), Json.write(ops("x = var.remove()")));
    }

    private static String action(Object op) {
        return Json.string(Json.object(op).get("action"));
    }

    private static List<String> actions(List<Object> ops) {
        return ops.stream().map(ServerFactsTest::action).toList();
    }

    private static Object arg(Object op, String name) {
        for (Object v : Json.array(Json.object(op).get("values")))
            if (name.equals(Json.object(v).get("name"))) return Json.object(v).get("value");
        return null;
    }

    private static List<String> warnings(String text) {
        return Compiler.compile(DATA, NAMES, text).diagnostics().stream().filter(d -> !d.error()).map(Diagnostic::message).toList();
    }

    private static String decompiled(String text) {
        return Decompiler.decompile(DATA, NAMES, compile(text).module()).text();
    }

    @Test
    void listWithVariableIsBuiltAtRunTime() {
        assertEquals(List.of("set_variable_value", "set_variable_create_list"), actions(ops("a = 5\nl = [a, 2]\nsend_message(\"${l}\")")).subList(0, 2));
    }

    @Test
    void constantListStaysLiteral() {
        assertEquals("set_variable_value", action(ops("l = [1, \"x\"]\nsend_message(\"${l}\")").get(0)));
    }

    @Test
    void placeholderInsideListIsEvaluated() {
        assertEquals("set_variable_create_list", action(ops("l = [\"%player%\"]\nsend_message(\"${l}\")").get(0)));
    }

    @Test
    void mapWithVariableIsBuiltAtRunTime() {
        List<Object> ops = ops("a = 5\nm = {\"k\": a}\nsend_message(\"${m}\")");
        assertEquals("set_variable_create_map_from_values", action(ops.get(1)));
    }

    @Test
    void literalKeepsTheValueAsWritten() {
        assertEquals("set_variable_value", action(ops("a = 5\nl = literal([a, 2])\nsend_message(\"${l}\")").get(1)));
    }

    @Test
    void listArgumentGetsItsOwnBlock() {
        List<Object> ops = ops("a = 5\nx = get_list_length([a, 2])\nsend_message(\"${x}\")");
        assertEquals(List.of("set_variable_value", "set_variable_create_list", "set_variable_get_list_length", "player_send_message"), actions(ops));
    }

    @Test
    void builtListPrintsBackAsLiteral() {
        assertTrue(decompiled("when player_join {\n    a = 5\n    l = [a, 2]\n    send_message(\"${l}\")\n}\n").contains("l = [a, 2]"));
    }

    @Test
    void menuGridIsAlwaysWhole() {
        Object grid = arg(ops("show_inventory_menu(name: \"m\")\nexpand_inventory_menu(size: 3)").get(1), "items");
        assertEquals(27, Json.array(Json.object(grid).get("values")).size());
    }

    @Test
    void shortMenuGridIsPadded() {
        Object grid = arg(ops("expand_inventory_menu(item(stone), size: 1)").get(0), "items");
        assertEquals(27, Json.array(Json.object(grid).get("values")).size());
    }

    @Test
    void constantListFitsSlots() {
        Compiler.Result r = compile("const GRID = [_, item(stone)]\nwhen player_join {\n    expand_inventory_menu(GRID, size: 1)\n}\n");
        assertEquals(27, Json.array(Json.object(arg(ops(r, 0).get(0), "items")).get("values")).size());
    }

    @Test
    void functionOverwritingCallerLocalIsWarned() {
        List<String> found = warnings("func f() {\n    n = 5\n}\nwhen player_join {\n    n = 0\n    f()\n    send_message(\"${n}\")\n}\n");
        assertTrue(found.stream().anyMatch(w -> w.contains("та же локальная")), found::toString);
    }

    @Test
    void lineIsPrivateToItsOwnCode() {
        Compiler.Result r = compile("func f() {\n    line q = 9\n    send_message(\"${q}\")\n}\nwhen player_join {\n    q = 0\n    f()\n    send_message(\"${q}\")\n}\n");
        assertEquals("local", Json.object(arg(ops(r, 1).get(0), "variable")).get("scope"));
        assertEquals("line", Json.object(arg(ops(r, 0).get(0), "variable")).get("scope"));
    }

    @Test
    void currentValueInEntitySelectionIsWarned() {
        List<String> found = warnings("when player_join {\n    entity_by_conditional(@current value.name == \"a\")\n}\n");
        assertTrue(found.stream().anyMatch(w -> w.contains("не видит кандидата")), found::toString);
    }

    @Test
    void targetInsideTextIsHeldInAVariable() {
        List<Object> ops = ops("send_message(\"хп ${@current value.current_health}\")");
        assertEquals(List.of("set_variable_value", "player_send_message"), actions(ops));
    }

    @Test
    void targetInsideFormulaIsHeldInAVariable() {
        assertEquals("set_variable_value", action(ops("x = @current value.current_health + 1\nsend_message(\"${x}\")").get(0)));
    }

    @Test
    void itemActionWithoutAssignmentChangesTheVariable() {
        Object op = ops("x = item(stone)\nset_item_lore(x, \"описание\")\nsend_message(\"${x}\")").get(1);
        assertNotNull(arg(op, "lore"));
        assertEquals("x", Json.object(arg(op, "item")).get("variable"));
    }

    @Test
    void itemActionWithTwoItemsStaysAsWritten() {
        Object op = ops("x = 1\ny = item(stone)\nset_item_name(x, y, \"имя\")\nsend_message(\"${x}\")").get(2);
        assertEquals("y", Json.object(arg(op, "item")).get("variable"));
    }

    @Test
    void minAndMaxOfTwoAreFormulas() {
        Object number = arg(ops("a, b = 1, 2\nx = min(a, b)\ny = max(a, b)\nsend_message(\"${x} ${y}\")").get(1), "value");
        assertEquals("%math(min(a,b))", Json.object(number).get("number"));
    }

    @Test
    void minOfListStaysAnAction() {
        assertEquals("set_variable_min", action(ops("x = min([1, 2])\nsend_message(\"${x}\")").get(0)));
    }

    @Test
    void isolatedSelectionHasABody() {
        Object op = ops("isolated_selection(selection_mode: for_each) {\n    send_message(\"a\")\n}").get(0);
        assertEquals(1, Json.array(Json.object(op).get("operations")).size());
    }

    @Test
    void returnLeavesAnEvent() {
        assertEquals("control_end_thread", action(ops("return").get(0)));
    }

    @Test
    void returnLeavesAProcess() {
        Compiler.Result r = compile("process p() {\n    return\n}\n");
        assertEquals("control_end_thread", action(ops(r, 0).get(0)));
    }

    @Test
    void locationAndVectorTakeVariables() {
        assertEquals("set_variable_set_all_coordinates", action(ops("a = 5\nx = loc(a, 64, 0)\nsend_message(\"${x}\")").get(1)));
        assertEquals("set_variable_vector", action(ops("a = 5\nx = vec(a, 1, 0)\nsend_message(\"${x}\")").get(1)));
    }

    @Test
    void locationWithVariablePrintsBack() {
        assertTrue(decompiled("when player_join {\n    a = 5\n    x = loc(a, 64, 0)\n    send_message(\"${x}\")\n}\n").contains("x = loc(a, 64, 0)"));
    }

    @Test
    void shapeComesFromTheDeclaration() {
        Compiler.Result r = compile("save m: map\nsave l: list\nwhen player_join {\n    k = 1\n    m[k] = 1\n    l[k] = 2\n}\n");
        assertEquals(List.of("set_variable_value", "set_variable_set_map_value", "set_variable_set_list_value"), actions(ops(r, 0)));
    }

    @Test
    void shapeComesFromTheKey() {
        assertEquals("set_variable_set_map_value", action(ops("save m\nk = \"a\"\nm[k] = 1").get(1)));
    }

    @Test
    void variableNameKeepsDollarLiteralAndWarns() {
        List<String> found = warnings("when player_join {\n    `${n}_x` = 1\n    send_message(\"${`${n}_x`}\")\n}\n");
        assertTrue(found.stream().anyMatch(w -> w.contains("не подставляется")), found::toString);
    }

    @Test
    void functionAndProcessMayShareAName() {
        Compiler.Result r = compile("func Игра() {\n}\nprocess Игра() {\n}\nwhen player_join {\n    Игра()\n    start Игра()\n}\n");
        assertEquals(3, Json.array(r.module().get("handlers")).size());
    }

    @Test
    void quotedFunctionLeavesTheActionAlone() {
        Compiler.Result r = compile("func `wait`() {\n}\nwhen player_join {\n    wait(1)\n    `wait`()\n}\n");
        assertEquals(List.of("control_wait", "call_function"), actions(ops(r, 1)));
    }

    @Test
    void startByValue() {
        Compiler.Result r = compile("process p() {\n}\nwhen player_join {\n    n = \"p\"\n    start call(n, target_mode: no_target)\n}\n");
        assertEquals("start_process", action(ops(r, 1).get(1)));
    }

    @Test
    void oldHandlerFieldsSurvive() {
        Map<String, Object> world = Json.object(Json.parse("""
                {"handlers":[{"type":"function","name":"f","position":0,"is_hidden":true,
                "icon":"{Count:1b,id:\\"minecraft:emerald\\"}",
                "description":["{\\"text\\":\\"строка\\"}"],"operations":[]}]}"""));
        Decompiler.Result r = Decompiler.decompile(DATA, NAMES, world);
        assertTrue(r.text().contains("@hidden") && r.text().contains("@description(\"строка\")")
                && r.text().contains("@icon(item(emerald))") && r.text().contains("func f()"), r.text());
    }

    @Test
    void oldItemsAndTextsReadWell() {
        Map<String, Object> world = Json.object(Json.parse("""
                {"handlers":[{"type":"event","event":"player_join","position":0,"operations":[
                {"action":"player_give_items","values":[{"name":"items","value":{"type":"array","values":[
                {"type":"item","item":"{Count:3b,id:\\"minecraft:diamond_sword\\",tag:{display:{Name:'{\\"extra\\":[{\\"color\\":\\"aqua\\",\\"text\\":\\"Меч\\"}],\\"text\\":\\"\\"}'}}}"},
                {"type":"item","item":"{}"}]}}]},
                {"action":"player_send_message","values":[{"name":"messages","value":{"type":"text","text":"привет"}}]}]}]}"""));
        String text = Decompiler.decompile(DATA, NAMES, world).text();
        assertFalse(text.contains("raw("), text);
        assertTrue(text.contains("item(diamond_sword, 3, name: \"&bМеч\")") && text.contains("send_message(\"привет\")"), text);
    }

    @Test
    void oldEffectNamesAreCurrent() {
        Map<String, Object> world = Json.object(Json.parse("""
                {"handlers":[{"type":"event","event":"player_join","position":0,"operations":[
                {"action":"player_give_potion_effect","values":[{"name":"potions","value":{"type":"array","values":[
                {"type":"potion","potion":"minecraft:slow","amplifier":1,"duration":100}]}}]},
                {"action":"player_display_particle","values":[{"name":"particle","value":{"type":"particle","particle_type":"VILLAGER_HAPPY","count":1,"first_spread":0,"second_spread":0}}]}]}]}"""));
        String text = Decompiler.decompile(DATA, NAMES, world).text();
        assertTrue(text.contains("potion(slowness") && text.contains("particle(happy_villager"), text);
    }

    @Test
    void infinityIsANumber() {
        assertEquals(Double.POSITIVE_INFINITY, ((Json.Num) Json.object(Json.parse("{\"v\":Infinity}")).get("v")).value());
        assertEquals(Double.NEGATIVE_INFINITY, ((Json.Num) Json.parse("-Infinity")).value());
    }

    @Test
    void itemsWithNamesAreSameAfterServerRewrite() {
        String item = Json.string(Json.object(arg(ops("give_items(item(stone, name: \"&bA\"))").get(0), "items")).get("values") instanceof List<?> l
                ? Json.object(l.get(0)).get("item") : null);
        assertEquals(Delivery.plain(Map.of("type", "item", "item", item)), Delivery.plain(Map.of("type", "item", "item", Items.canonical(item))));
    }

    @Test
    void realActionsAndEventsOutsideThePaletteWork() {
        assertEquals("player_send_hover", action(ops("send_hover(\"a\", \"b\")").get(0)));
        assertTrue(DATA.events().containsKey("player_vehicle_move"));
        assertTrue(DATA.pickers().sounds().contains("music_disc.precipice"));
    }
}
