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

    private static Delivery.Line wide(int width) {
        Map<String, Object> copy = Json.object(Json.parse(Json.write(handler(plain(1)))));
        List<Object> ops = Json.array(copy.get("operations"));
        Object one = ops.get(0);
        while (ops.size() < width) ops.add(one);
        copy.put("position", new Json.Num("3"));
        return new Delivery.Line(Delivery.Kind.EVENT, "player_jump", 3, copy);
    }

    private static boolean replaced(Delivery.Line was, Delivery.Line after) {
        return new Delivery.Plan(List.of(), List.of(after), List.of(), List.of(), List.of(), List.of(was))
                .lineByLine(List.of(was), 20);
    }

    @Test
    void widthsMeasuredOnTheServer() {
        String emptyIf = "    if value.name == \"a\" {\n    }\n";
        String ifElse = "    if value.name == \"a\" {\n    } else {\n    }\n";
        String loop = "    repeat 2 {\n    }\n";
        String deep = "    if value.name == \"a\" {\n    if value.name == \"b\" {\n    if value.name == \"c\" {\n"
                + plain(61) +"    }\n    }\n    }\n";
        assertEquals(Wrap.WIDEST, Delivery.width(handler(plain(67))));
        assertEquals(Wrap.WIDEST, Delivery.width(handler(repeated(5, emptyIf) + plain(57))));
        assertEquals(Wrap.WIDEST, Delivery.width(handler(repeated(5, loop) + plain(57))));
        assertEquals(Wrap.WIDEST, Delivery.width(handler(repeated(3, ifElse) + plain(55))));
        assertEquals(Wrap.WIDEST, Delivery.width(handler(deep)));
    }

    @Test
    void widestLineGoesByLine() {
        assertTrue(replaced(line(plain(1)), line(plain(Wrap.WIDEST))));
    }

    @Test
    void tooWideLineGoesWhole() {
        assertFalse(replaced(line(plain(1)), wide(Wrap.WIDEST + 1)));
    }

    @Test
    void tooWideOldLineIsNotWiped() {
        assertFalse(replaced(wide(Wrap.WIDEST + 1), line(plain(1))));
    }

    @Test
    void tooWideNewLineGoesWhole() {
        Delivery.Plan plan = new Delivery.Plan(List.of(), List.of(), List.of(wide(Wrap.WIDEST + 1)), List.of(), List.of(), List.of());
        assertFalse(plan.lineByLine(List.of(), 20));
    }
}
