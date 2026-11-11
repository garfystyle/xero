package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.ide.Service;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodsTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private static Compiler.Result compile(String source) {
        return Compiler.compile(DATA, NAMES, source);
    }

    private static String module(String source) {
        Compiler.Result r = compile(source);
        assertTrue(r.ok(), r.diagnostics()::toString);
        return Json.write(r.module());
    }

    private static List<String> actions(String source) {
        List<String> out = new ArrayList<>();
        Compiler.Result r = compile(source);
        assertTrue(r.ok(), r.diagnostics()::toString);
        for (Object h : Json.array(r.module().get("handlers")))
            if (Json.object(h).get("operations") instanceof List<?> ops) walk(Json.array(ops), out);
        return out;
    }

    private static void walk(List<Object> ops, List<String> out) {
        for (Object raw : ops) {
            Map<String, Object> op = Json.object(raw);
            out.add(String.valueOf(op.get("action")));
            if (op.get("operations") instanceof List<?> body) walk(Json.array(body), out);
        }
    }

    private static String event(String body) {
        return "when player_join {\n" + body + "\n}\n";
    }

    @Test
    void valueMethodIsTheSameAsTheAction() {
        assertEquals(module(event("    s = item(stone)\n    give_items(set_item_amount(s, 10))")),
                module(event("    s = item(stone)\n    give_items(s.set_item_amount(10))")));
    }

    @Test
    void methodStatementWritesBack() {
        assertEquals(module(event("    s = item(stone)\n    s = set_item_amount(s, 10)\n    give_items(s)")),
                module(event("    s = item(stone)\n    s.set_item_amount(10)\n    give_items(s)")));
    }

    @Test
    void inPlaceActionsStayInPlace() {
        assertEquals(module(event("    l = [3, 1, 2]\n    sort_any_list(l)")), module(event("    l = [3, 1, 2]\n    l.sort_any_list()")));
    }

    @Test
    void methodsChainAndWorkOnExpressions() {
        assertEquals(List.of("set_variable_value", "set_variable_set_item_amount", "set_variable_set_item_name", "player_give_items"),
                actions(event("    s = item(stone)\n    give_items(s.set_item_amount(10).set_item_name(\"&aКамень\"))")));
        assertEquals(List.of("set_variable_set_item_amount", "player_give_items"),
                actions(event("    give_items(item(stone).set_item_amount(3))")));
    }

    @Test
    void conditionsAreMethodsToo() {
        assertEquals(module(event("    l = [1, 2]\n    if list_contains_value(l, 2) {\n        send_message(\"есть\")\n    }")),
                module(event("    l = [1, 2]\n    if l.list_contains_value(2) {\n        send_message(\"есть\")\n    }")));
    }

    @Test
    void scopedReceiver() {
        assertEquals(module(event("    game.s = item(stone)\n    give_items(set_item_amount(game.s, 2))")),
                module(event("    game.s = item(stone)\n    give_items(game.s.set_item_amount(2))")));
    }

    @Test
    void dottedFunctionsStayFunctions() {
        String source = "func a.set_item_amount() {\n    send_message(\"x\")\n}\n" + event("    a.set_item_amount()");
        assertTrue(actions(source).contains("call_function"));
    }

    @Test
    void inlineIsConstWithAHint() {
        Compiler.Result r = compile("inline var X = 5\n" + event("    send_message(\"${X}\")"));
        Diagnostic d = r.diagnostics().get(0);
        assertTrue(d.error() && d.message().contains("const"), r.diagnostics()::toString);
        assertEquals("const ", d.fixes().get(0).text());
        assertTrue(Json.write(r.module()).contains("\"text\":\"5\""));
        assertFalse(compile("inline X = 5\n" + event("    send_message(\"${X}\")")).ok());
    }

    @Test
    void constItemsFold() {
        assertEquals(module("const ITEM = item(diamond_sword, name: \"&bМеч\")\n" + event("    give_items(item(diamond_sword, 10, name: \"&bМеч\"))")),
                module("const ITEM = item(diamond_sword, name: \"&bМеч\")\n" + event("    give_items(ITEM.set_item_amount(10))")));
        assertEquals(module(event("    x = item(stone, 64)\n    give_items(x)")),
                module("const S = item(stone, 3)\n" + event("    x = S.set_item_amount(64)\n    give_items(x)")));
        assertEquals(module(event("    give_items(item(stone, lore: [\"&7строка 1\", \"&eстрока 2\"]))")),
                module("const S = item(stone)\n" + event("    give_items(S.set_item_lore([\"&7строка 1\", \"&eстрока 2\"]))")));
    }

    @Test
    void onlyWhatTheServerMakesTheSameFolds() {
        String s = "const S = item(stone)\n";
        assertTrue(actions(s + event("    give_items(S.set_item_name(\"&aКамень\"))")).contains("set_variable_set_item_name"));
        assertTrue(actions(event("    x = set_item_amount(item(stone), 5)\n    give_items(x)")).contains("set_variable_set_item_amount"));
        assertTrue(actions(event("    give_items(item(stone).set_item_amount(5))")).contains("set_variable_set_item_amount"));
        assertTrue(actions(s + event("    give_items(set_item_amount(S, 5))")).contains("set_variable_set_item_amount"));
        assertTrue(actions(s + event("    n = 5\n    give_items(S.set_item_amount(n))")).contains("set_variable_set_item_amount"));
        assertTrue(actions(s + event("    give_items(S.set_item_amount(100))")).contains("set_variable_set_item_amount"));
        assertTrue(actions(s + event("    give_items(S.set_item_lore([\"&aзелёный &lжирный\"]))")).contains("set_variable_set_item_lore"));
        assertTrue(actions(s + event("    give_items(S.set_item_lore([\"%player%\"]))")).contains("set_variable_set_item_lore"));
    }

    @Test
    void editorSuggestsMethodsOfTheValue() {
        Service service = Service.load();
        String text = "const ITEM = item(stone)\n" + event("    give_items(ITEM.)");
        int at = text.indexOf("ITEM.)") + 5;
        List<String> labels = service.complete(text, at).items().stream().map(Service.Item::label).toList();
        assertTrue(labels.contains("set_item_amount"), labels.toString());
        assertFalse(labels.contains("append_value"), labels.toString());
        String list = event("    l = [1, 2]\n    l.");
        List<String> forList = service.complete(list, list.indexOf("l.\n") + 2).items().stream().map(Service.Item::label).toList();
        assertTrue(forList.contains("append_value") && forList.contains("sort_any_list"), forList.toString());
        assertFalse(forList.contains("set_item_amount"), forList.toString());
    }
}
