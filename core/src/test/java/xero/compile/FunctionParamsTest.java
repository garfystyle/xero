package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.format.Formatter;
import xero.json.Json;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionParamsTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private static Map<String, Object> build(String source) {
        Compiler.Result result = Compiler.compile(DATA, NAMES, source);
        assertTrue(result.ok(), () -> result.diagnostics().toString());
        return result.module();
    }

    private static List<Object> parameters(Map<String, Object> module) {
        Map<String, Object> function = Json.object(Json.array(module.get("handlers")).get(0));
        for (Object entry : Json.array(function.get("values"))) {
            Map<String, Object> e = Json.object(entry);
            if ("parameters".equals(e.get("name"))) return Json.array(Json.object(e.get("value")).get("values"));
        }
        return List.of();
    }

    private static String circle(String source) {
        String printed = Decompiler.decompile(DATA, NAMES, build(source)).text();
        assertEquals(Json.write(build(source)), Json.write(build(printed)), printed);
        return printed;
    }

    @Test
    void descriptionTravelsWithTheParameter() {
        String source = "func f(message: text \"Что сказать\", who \"Кому\" = \"все\") {\n    send_message(message)\n}\n";
        Map<String, Object> first = Json.object(parameters(build(source)).get(0));
        assertTrue(Json.string(first.get("description")).contains("Что сказать"));
        String printed = circle(source);
        assertTrue(printed.contains("message: text \"Что сказать\""), printed);
        assertTrue(printed.contains("who \"Кому\" = \"все\""), printed);
    }

    @Test
    void quotedParameterNameIsWritten() {
        String printed = circle("func f(`Список вещей`: array) {\n    send_message(`Список вещей`)\n}\n\nwhen player_join {\n    f(`Список вещей`: [1])\n}\n");
        assertTrue(printed.contains("func f(`Список вещей`: array)"), printed);
        assertFalse(printed.contains("@values"), printed);
    }

    @Test
    void manyValuesTakeTheirOwnCells() {
        String source = "func f(title: text, items: text[3] \"Строки\") {\n    send_message(items)\n}\n\nwhen player_join {\n    f(\"a\", [\"x\", _, \"z\"])\n}\n";
        Map<String, Object> plural = Json.object(parameters(build(source)).get(1));
        assertEquals("plural", plural.get("type_key"));
        assertEquals("[1,2,3]", plural.get("slots"));
        assertEquals("[10]", plural.get("description_slots"));
        String printed = circle(source);
        assertTrue(printed.contains("items: text[3] \"Строки\""), printed);
        assertTrue(printed.contains("[\"x\", _, \"z\"]"), printed);
    }

    @Test
    void manyValuesWithVariablesStayCells() {
        String source = "func f(items: any[4]) {\n    send_message(items)\n}\n\nwhen player_join {\n    a = 1\n    f(items: [a, 2])\n}\n";
        Map<String, Object> module = build(source);
        assertFalse(Json.write(module).contains("set_variable_create_list"), Json.write(module));
        circle(source);
    }

    @Test
    void manyValuesAreCountedAsAList() {
        Compiler.Result r = Compiler.compile(DATA, NAMES, "func f(lines: text[3]) {\n    send_message(\"${len(lines)}\")\n}\n");
        assertTrue(r.diagnostics().isEmpty(), () -> r.diagnostics().toString());
    }

    @Test
    void manyValuesReadLikeAList() {
        circle("func f(lines: text[3]) {\n    send_message(\"${lines[0]} ${len(lines)}\")\n    for line in lines {\n        send_message(line)\n    }\n}\n");
    }

    @Test
    void cellsFollowTheOrderOfParameters() {
        List<Object> params = parameters(build("func f(a: text, b: text[3], c: text) {}\n"));
        assertEquals("[1,2,3]", Json.object(params.get(1)).get("slots"));
        assertEquals("4", String.valueOf(Json.object(params.get(2)).get("slot")));
        assertEquals("13", String.valueOf(Json.object(params.get(2)).get("description_slot")));
    }

    @Test
    void groupLargerThanValueRowsUsesFreeCells() {
        List<Object> params = parameters(build("func f(a: text, b: text[36]) {}\n"));
        String slots = String.valueOf(Json.object(params.get(1)).get("slots"));
        assertEquals(36, Json.array(Json.parse(slots)).size());
        assertFalse(slots.contains(",9,") || slots.contains(",10,"), slots);
    }

    @Test
    void groupLeavesRoomForParametersAfterIt() {
        List<Object> params = parameters(build("func f(a: text[27], b: text, c: text) {}\n"));
        Map<String, Object> group = Json.object(params.get(0));
        assertEquals(27, Json.array(Json.parse(String.valueOf(group.get("slots")))).size());
        assertEquals("43", String.valueOf(Json.object(params.get(1)).get("slot")));
        assertEquals("44", String.valueOf(Json.object(params.get(2)).get("slot")));
    }

    @Test
    void chestLimitIsReported() {
        Compiler.Result r = Compiler.compile(DATA, NAMES, "func f(a: text[60]) {}\n");
        assertFalse(r.ok());
    }

    @Test
    void emptyOptionalParameterIsWritten() {
        String printed = circle("func f(a: text = _) {\n    send_message(a)\n}\n");
        assertTrue(printed.contains("a: text = _"), printed);
    }

    @Test
    void longSignatureGoesOnSeparateLines() {
        String printed = circle("func f(first: text \"Первая довольно длинная подпись параметра\", second: number \"Вторая довольно длинная подпись параметра\", third: item \"Третья\") {}\n");
        assertTrue(printed.contains("func f(\n    first: text"), printed);
        assertTrue(printed.contains("\n) {"), printed);
        assertEquals(printed, Formatter.format(printed).text());
    }

    @Test
    void translatedDescriptionStaysVerbatim() {
        String about = "{\\\"translations\\\":{\\\"ru-RU\\\":{\\\"rawText\\\":\\\"а\\\",\\\"parsingType\\\":\\\"LEGACY\\\"}}}";
        String values = "[{\"name\":\"parameters\",\"value\":{\"type\":\"array\",\"values\":[{\"type\":\"parameter\",\"type_key\":\"singular\","
                + "\"description\":\"" + about + "\",\"name\":\"a\",\"value_type\":\"any\",\"is_required\":\"true\",\"default_value\":\"{}\","
                + "\"slot\":0,\"description_slot\":9}]}}]";
        Map<String, Object> module = Json.object(Json.parse("{\"handlers\":[{\"type\":\"function\",\"name\":\"f\",\"position\":0,\"values\":" + values + ",\"operations\":[]}]}"));
        String printed = Decompiler.decompile(DATA, NAMES, module).text();
        assertTrue(printed.contains("@values("), printed);
        assertTrue(Json.write(build(printed)).contains("ru-RU"), printed);
    }
}
