package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateWidthTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private static String plain(int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append("    send_message(\"n").append(i).append("\")\n");
        return out.toString();
    }

    private static String repeated(int count, String block) {
        return block.repeat(count);
    }

    private static Map<String, Object> handler(String body) {
        Compiler.Result r = Compiler.compile(DATA, NAMES, "when player_jump {\n" + body + "}\n");
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        return Json.object(Json.array(r.module().get("handlers")).get(0));
    }

    private static Delivery.Line line(String body) {
        Map<String, Object> copy = Json.object(Json.parse(Json.write(handler(body))));
        copy.put("position", new Json.Num("3"));
        return new Delivery.Line(Delivery.Kind.EVENT, "player_jump", 3, copy);
    }

    private static boolean replaced(String before, String after) {
        Delivery.Line was = line(before);
        return new Delivery.Plan(List.of(), List.of(line(after)), List.of(), List.of(), List.of(), List.of(was))
                .lineByLine(List.of(was), 20);
    }

    @Test
    void widthsMeasuredOnTheServer() {
        String emptyIf = "    if value.name == \"a\" {\n    }\n";
        String ifElse = "    if value.name == \"a\" {\n    } else {\n    }\n";
        String loop = "    repeat 2 {\n    }\n";
        String deep = "    if value.name == \"a\" {\n    if value.name == \"b\" {\n    if value.name == \"c\" {\n"
                + plain(37) + "    }\n    }\n    }\n";
        assertEquals(43, Delivery.width(handler(plain(43))));
        assertEquals(43, Delivery.width(handler(repeated(5, emptyIf) + plain(33))));
        assertEquals(43, Delivery.width(handler(repeated(5, loop) + plain(33))));
        assertEquals(43, Delivery.width(handler(repeated(3, ifElse) + plain(31))));
        assertEquals(43, Delivery.width(handler(deep)));
    }

    @Test
    void widestLineGoesByLine() {
        assertTrue(replaced(plain(1), plain(43)));
    }

    @Test
    void tooWideLineGoesWhole() {
        assertFalse(replaced(plain(1), plain(44)));
    }

    @Test
    void tooWideOldLineIsNotWiped() {
        assertFalse(replaced(plain(44), plain(1)));
    }

    @Test
    void tooWideNewLineGoesWhole() {
        Delivery.Plan plan = new Delivery.Plan(List.of(), List.of(), List.of(line(plain(44))), List.of(), List.of(), List.of());
        assertFalse(plan.lineByLine(List.of(), 20));
    }
}
