package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CellTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);
    private static final String STORAGE = "16ae1252-6c5d-4d1a-8ab4-353eff992a43";

    private static Compiler.Result compile(String body) {
        return Compiler.compile(DATA, NAMES, "when player_join {\n" + body + "\n}\n");
    }

    private static Map<String, Object> value(Compiler.Result r, String action, String arg) {
        assertTrue(r.ok(), r.diagnostics()::toString);
        for (Object h : Json.array(r.module().get("handlers")))
            for (Object raw : Json.array(Json.object(h).get("operations"))) {
                Map<String, Object> op = Json.object(raw);
                if (!action.equals(op.get("action"))) continue;
                for (Object v : Json.array(op.get("values")))
                    if (arg.equals(Json.object(v).get("name"))) return Json.object(Json.object(v).get("value"));
            }
        throw new AssertionError("нет " + action + "." + arg + " в " + Json.write(r.module()));
    }

    private static String roundTrip(Compiler.Result r) {
        String text = Decompiler.decompile(DATA, NAMES, r.module()).text();
        Compiler.Result again = Compiler.compile(DATA, NAMES, text);
        assertTrue(again.ok(), () -> text + "\n" + again.diagnostics());
        assertEquals(Json.write(r.module()), Json.write(again.module()), text);
        return text;
    }

    @Test
    void worldCell() {
        Compiler.Result r = compile("    cell_save(status, cell(\"данные\"), [score])");
        assertEquals("{\"type\":\"cell\",\"cell\":\"данные\"}", Json.write(value(r, "set_variable_cell_save", "cell")));
        assertTrue(roundTrip(r).contains("cell(\"данные\")"));
    }

    @Test
    void sharedCellKeepsServerFields() {
        Compiler.Result r = compile("    cell_load(status, cell(\"общее\", storage: \"GarfyYT/xcprobe\", id: \""
                + STORAGE + "\"), [score], lock: true)");
        assertEquals("{\"type\":\"cell\",\"cell\":\"общее\",\"global\":\"true\",\"storage\":\"" + STORAGE
                        + "\",\"storage_path\":\"GarfyYT/xcprobe\"}",
                Json.write(value(r, "set_variable_cell_load", "cell")));
        String text = roundTrip(r);
        assertTrue(text.contains("storage: \"GarfyYT/xcprobe\"") && text.contains("id: \"" + STORAGE + "\""), text);
    }

    @Test
    void storageNeedsBothFields() {
        Compiler.Result r = compile("    c = cell(\"x\", storage: \"GarfyYT/xcprobe\")");
        assertFalse(r.ok());
        assertTrue(r.diagnostics().toString().contains("общему хранилищу нужны оба"), r.diagnostics()::toString);
    }

    @Test
    void cellNeedsName() {
        assertFalse(compile("    c = cell(\"\")").ok());
        assertFalse(compile("    c = cell(5)").ok());
        assertFalse(compile("    c = cell(\"" + "я".repeat(257) + "\")").ok());
        assertTrue(compile("    c = cell(\"" + "я".repeat(256) + "\")").ok());
    }

    @Test
    void textIntoCellSlotIsWrong() {
        Compiler.Result r = compile("    if cell_exists(\"данные\") {\n        send_message(\"есть\")\n    }");
        assertFalse(r.ok());
        assertTrue(r.diagnostics().toString().contains("ячейка"), r.diagnostics()::toString);
    }

    @Test
    void unknownCellFieldsStayRaw() {
        Map<String, Object> module = Json.object(Json.parse("{\"handlers\":[{\"type\":\"event\",\"event\":\"player_join\","
                + "\"position\":0,\"operations\":[{\"action\":\"if_variable_cell_exists\",\"values\":[{\"name\":\"cell\","
                + "\"value\":{\"type\":\"cell\",\"cell\":\"x\",\"future\":\"1\"}}],\"operations\":[]}]}]}"));
        String text = Decompiler.decompile(DATA, NAMES, module).text();
        assertFalse(text.contains("cell(\"x\""), text);
        Compiler.Result again = Compiler.compile(DATA, NAMES, text);
        assertTrue(again.ok(), () -> text + "\n" + again.diagnostics());
        assertEquals(Json.write(module), Json.write(again.module()), text);
    }

    @Test
    void dialogsRoundTrip() {
        Compiler.Result r = compile("""
                    create_dialog("menu", "Меню", type: confirmation)
                    add_text_box_dialog_input("menu", "name", "Имя")
                    show_dialog_menu("menu")
                    name = get_dialog_text("name")
                    if dialog_input_is_true("agree") {
                        send_message("${name}")
                    }\
                """);
        assertEquals("CONFIRMATION", value(r, "game_create_dialog", "type").get("enum"));
        assertTrue(roundTrip(r).contains("name = get_dialog_text(\"name\")"));
    }
}
