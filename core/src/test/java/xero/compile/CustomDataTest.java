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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomDataTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private static Compiler.Result compile(String source) {
        return Compiler.compile(DATA, NAMES, source);
    }

    private static Map<String, Object> first(String source) {
        Compiler.Result r = compile(source);
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        return Json.object(Json.array(r.module().get("handlers")).get(0));
    }

    private static boolean refused(String source, String part) {
        for (Diagnostic d : compile(source).diagnostics()) if (d.error() && d.message().contains(part)) return true;
        return false;
    }

    private static String mark(int length) {
        return "@custom_data(\"" + "я".repeat(length) + "\")\n";
    }

    @Test
    void goesNextToPositionOnEveryKindOfLine() {
        assertEquals("а", first("@custom_data(\"а\")\nwhen player_jump {\n}\n").get("custom_data"));
        assertEquals("б", first("@custom_data(\"б\")\nfunc f() {\n}\n").get("custom_data"));
        assertEquals("в", first("@custom_data(\"в\")\nprocess p() {\n}\n").get("custom_data"));
        assertNull(first("func f() {\n}\n").get("custom_data"));
    }

    @Test
    void worksWithOtherMarks() {
        Map<String, Object> line = first("@line(5)\n@custom_data(\"а\")\n@hidden\nfunc f() {\n}\n");
        assertEquals("а", line.get("custom_data"));
        assertEquals("5", String.valueOf(line.get("position")));
    }

    @Test
    void limitIsCountedInCharacters() {
        assertEquals(256, String.valueOf(first(mark(256) + "func f() {\n}\n").get("custom_data")).length());
        assertTrue(refused(mark(257) + "func f() {\n}\n", "257 символов"));
        assertTrue(refused(mark(257) + "when player_jump {\n}\n", "257 символов"));
    }

    @Test
    void takesOnlyPlainText() {
        assertTrue(refused("@custom_data(1)\nfunc f() {\n}\n", "обычная строка"));
        assertTrue(refused("@custom_data(\"a\", \"b\")\nfunc f() {\n}\n", "обычная строка"));
        assertTrue(refused("@custom_data(\"${1 + 1}\")\nfunc f() {\n}\n", "обычная строка"));
        assertTrue(refused("@custom_data(\"a\")\n@custom_data(\"b\")\nfunc f() {\n}\n", "повторяется"));
    }

    @Test
    void emptyTextIsKept() {
        assertEquals("", first("@custom_data(\"\")\nfunc f() {\n}\n").get("custom_data"));
    }

    @Test
    void eventStillRefusesOtherMarks() {
        assertTrue(refused("@hidden\nwhen player_jump {\n}\n", "только пометки @line и @custom_data"));
    }

    private static void survives(String handler) {
        String module = "{\"handlers\":[" + handler + "]}";
        Decompiler.Result text = Decompiler.decompile(DATA, NAMES, Json.object(Json.parse(module)));
        assertFalse(text.notes().stream().anyMatch(n -> n.contains("лишние поля")), text.notes()::toString);
        Compiler.Result back = compile(text.text());
        assertTrue(back.ok(), () -> text.text() + back.diagnostics());
        Map<String, Object> was = Json.object(Json.array(Json.object(Json.parse(module)).get("handlers")).get(0));
        Map<String, Object> now = Json.object(Json.array(back.module().get("handlers")).get(0));
        assertEquals(was.get("custom_data"), now.get("custom_data"), text::text);
        assertEquals(String.valueOf(was.get("position")), String.valueOf(now.get("position")));
    }

    private static String event(String data) {
        return "{\"type\":\"event\",\"event\":\"player_jump\",\"position\":7,\"custom_data\":" + Json.write(data) + ",\"operations\":[]}";
    }

    @Test
    void comesBackFromTheWorld() {
        for (String data : List.of("привет", "{\"x\":1,\"y\":2}", "", "кавычка \" и слэш \\", "%player% ${x} &a", "строка\nвторая", "z".repeat(256)))
            survives(event(data));
        survives("{\"type\":\"function\",\"name\":\"f\",\"values\":[],\"position\":3,\"custom_data\":\"ф\",\"operations\":[]}");
        survives("{\"type\":\"process\",\"name\":\"p\",\"values\":[],\"position\":40,\"custom_data\":\"п\",\"operations\":[]}");
    }

    @Test
    void canvasMarkIsNotPrinted() {
        String module = "{\"handlers\":[" + event("xc:40:300:Бой") + "]}";
        Decompiler.Result text = Decompiler.decompile(DATA, NAMES, Json.object(Json.parse(module)));
        assertFalse(text.text().contains("custom_data"), text::text);
        assertTrue(text.notes().isEmpty(), text.notes()::toString);
    }

    @Test
    void wrapKeepsItOnTheOriginalLine() {
        String body = "    send_message(\"x\")\n".repeat(100);
        Compiler.Result r = compile("@custom_data(\"данные\")\nfunc f() {\n" + body + "}\n");
        List<Object> handlers = Json.array(Wrap.fitted(r.module(), DATA).get("handlers"));
        assertEquals("данные", Json.object(handlers.get(0)).get("custom_data"));
        for (int i = 1; i < handlers.size(); i++) assertNull(Json.object(handlers.get(i)).get("custom_data"));
    }

    @Test
    void changedDataIsAChange() {
        String a = "{\"handlers\":[" + event("а") + "]}", b = "{\"handlers\":[" + event("б") + "]}";
        List<Delivery.Line> world = Delivery.lines(Json.object(Json.parse(a)));
        List<Delivery.Line> project = Delivery.lines(Json.object(Json.parse(b)));
        assertEquals(1, Delivery.plan(DATA, world, project).changed().size());
        assertEquals(1, Delivery.plan(DATA, world, world).same().size());
    }
}
