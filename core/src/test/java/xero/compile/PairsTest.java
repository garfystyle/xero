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

class PairsTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);
    private static final String VARS = "    a = 1\n    b = 2\n    c = 3\n";

    private static Compiler.Result compile(String condition) {
        return Compiler.compile(DATA, NAMES, "when player_join {\n" + VARS + "    if " + condition + " {\n        send_message(\"да\")\n    }\n}\n");
    }

    private static List<Map<String, Object>> ops(Compiler.Result r) {
        assertTrue(r.ok(), r.diagnostics()::toString);
        List<Map<String, Object>> out = new ArrayList<>();
        List<Object> all = Json.array(Json.object(Json.array(r.module().get("handlers")).get(0)).get("operations"));
        for (Object raw : all.subList(3, all.size())) out.add(Json.object(raw));
        return out;
    }

    private static void folded(String condition, String mode, boolean inverted) {
        List<Map<String, Object>> ops = ops(compile(condition));
        assertEquals(1, ops.size(), () -> condition + ": " + ops);
        Map<String, Object> op = ops.get(0);
        assertEquals(Conditions.PAIRS, op.get("action"), condition);
        assertEquals(inverted, Boolean.TRUE.equals(op.get("is_inverted")), condition);
        assertTrue(Json.write(op).contains("\"enum\":\"" + mode + "\""), () -> condition + ": " + Json.write(op));
    }

    @Test
    void andOrAndNegationsFoldIntoOneBlock() {
        folded("a == 1 and b == 2 and c == 3", "ALL", false);
        folded("a == 1 or b == 2", "ANY", false);
        folded("a != 1 and b != 2", "ANY", true);
        folded("a != 1 or b != 2", "ALL", true);
        folded("not (a == 1 and b == 2)", "ALL", true);
    }

    @Test
    void pairsKeepTheirOrder() {
        String written = Json.write(ops(compile("a == 1 and b == \"x\" and c == b")).get(0));
        assertTrue(written.indexOf("\"variable\":\"a\"") < written.indexOf("\"variable\":\"c\""), written);
        assertTrue(written.contains("\"text\":\"x\""), written);
    }

    @Test
    void whatCannotFoldStaysAsBefore() {
        assertEquals("if_variable_equals", ops(compile("a == 1 and b != 2")).get(0).get("action"));
        assertTrue(!Json.write(compile("a == 1 and b == c + 1").module()).contains(Conditions.PAIRS));
        folded("a == 1 or a == 2 or b == 3", "ANY", false);
        Map<String, Object> same = ops(compile("a == 1 or a == 2")).get(0);
        assertEquals("if_variable_equals", same.get("action"));
    }

    @Test
    void decompilerPrintsTheChainBack() {
        for (String condition : List.of("a == 1 and b == 2 and c == 3", "a == 1 or b == 2", "a != 1 and b != 2", "a != 1 or b != 2")) {
            Compiler.Result r = compile(condition);
            String text = Decompiler.decompile(DATA, NAMES, r.module()).text();
            assertTrue(text.contains("if " + condition + " {"), () -> condition + "\n" + text);
            Compiler.Result again = Compiler.compile(DATA, NAMES, text);
            assertTrue(again.ok(), again.diagnostics()::toString);
            assertEquals(Json.write(r.module()), Json.write(again.module()), text);
        }
    }

    @Test
    void whileUsesOneConditionToo() {
        Compiler.Result r = Compiler.compile(DATA, NAMES, "when player_join {\n" + VARS
                + "    while a == 1 and b == 2 {\n        a = 0\n    }\n}\n");
        String written = Json.write(r.module());
        assertTrue(r.ok(), r.diagnostics()::toString);
        assertTrue(written.contains("\"action\":\"repeat_while\"") && written.contains(Conditions.PAIRS), written);
    }
}
