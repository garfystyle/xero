package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnpackTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private static Compiler.Result compile(String body) {
        return Compiler.compile(DATA, NAMES, "when player_join {\n" + body + "\n}\n");
    }

    private static Map<String, Object> op(Compiler.Result r, String action) {
        assertTrue(r.ok(), r.diagnostics()::toString);
        for (Object h : Json.array(r.module().get("handlers")))
            for (Object raw : Json.array(Json.object(h).get("operations"))) {
                Map<String, Object> op = Json.object(raw);
                if (action.equals(op.get("action"))) return op;
            }
        throw new AssertionError("нет " + action + " в " + Json.write(r.module()));
    }

    private static List<String> names(Map<String, Object> op) {
        List<String> out = new ArrayList<>();
        for (Object v : Json.array(op.get("values"))) out.add(String.valueOf(Json.object(v).get("name")));
        return out;
    }

    private static String roundTrip(Compiler.Result r) {
        String text = Decompiler.decompile(DATA, NAMES, r.module()).text();
        Compiler.Result again = Compiler.compile(DATA, NAMES, text);
        assertTrue(again.ok(), () -> text + "\n" + again.diagnostics());
        assertEquals(Json.write(r.module()), Json.write(again.module()), text);
        return text;
    }

    @Test
    void listByIndexes() {
        Compiler.Result r = compile("    l = [5, 6, 7]\n    x, y, z = l[0, 1, 2]\n    send_message(\"${x}${y}${z}\")");
        Map<String, Object> op = op(r, "set_variable_get_list_value_multiple");
        assertTrue(names(op).containsAll(List.of("variable", "list", "number")), names(op)::toString);
        String written = Json.write(op);
        assertTrue(written.contains("\"variable\":\"x\"") && written.contains("\"variable\":\"z\""), written);
        assertTrue(roundTrip(r).contains("x, y, z = l[0, 1, 2]"));
    }

    @Test
    void mapByKeys() {
        Compiler.Result r = compile("    m = {\"a\": 1, \"b\": 2}\n    i, j = m[\"a\", \"b\"]\n    send_message(\"${i}${j}\")");
        Map<String, Object> op = op(r, "set_variable_get_map_value");
        assertTrue(names(op).containsAll(List.of("variable", "map", "key")), names(op)::toString);
        assertTrue(roundTrip(r).contains("i, j = m[\"a\", \"b\"]"));
    }

    @Test
    void wholeList() {
        Compiler.Result r = compile("    l = [5, 6]\n    a, b = l\n    send_message(\"${a}${b}\")");
        Map<String, Object> op = op(r, "set_variable_destructure_list");
        assertEquals(List.of("variables", "list"), names(op).stream().sorted((p, q) -> q.compareTo(p)).toList());
        assertTrue(roundTrip(r).contains("a, b = l"));
    }

    @Test
    void plainMultipleAssignStaysTheSame() {
        Compiler.Result r = compile("    a, b = 1, 2\n    send_message(\"${a}${b}\")");
        op(r, "set_variable_multiple");
    }

    @Test
    void mistakesAreExplained() {
        Compiler.Result count = compile("    l = [5, 6, 7]\n    x, y = l[0, 1, 2]\n    send_message(\"${x}${y}\")");
        assertFalse(count.ok());
        assertTrue(count.diagnostics().get(0).message().contains("индексов 3"), count.diagnostics()::toString);
        Compiler.Result single = compile("    l = [5, 6]\n    x = l[0, 1]\n    send_message(\"${x}\")");
        assertFalse(single.ok());
        assertTrue(single.diagnostics().get(0).message().contains("только при разложении"), single.diagnostics()::toString);
        Compiler.Result number = compile("    a, b = 5\n    send_message(\"${a}${b}\")");
        assertFalse(number.ok());
        assertTrue(number.diagnostics().get(0).message().contains("не список"), number.diagnostics()::toString);
    }
}
