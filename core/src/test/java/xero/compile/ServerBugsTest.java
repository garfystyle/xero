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

class ServerBugsTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private static Compiler.Result compile(String text) {
        return Compiler.compile(DATA, NAMES, text);
    }

    private static Compiler.Result good(String text) {
        Compiler.Result r = compile(text);
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        return r;
    }

    private static List<Object> ops(String text) {
        List<Object> handlers = Json.array(good(text).module().get("handlers"));
        return Json.array(Json.object(handlers.get(handlers.size() - 1)).get("operations"));
    }

    private static List<Object> body(String lines) {
        return ops("when player_join {\n" + lines + "\n}");
    }

    private static Map<String, Object> op(List<Object> ops, int at) {
        return Json.object(ops.get(at));
    }

    private static Map<String, Object> arg(Map<String, Object> op, String name) {
        for (Object v : Json.array(op.get("values"))) {
            Map<String, Object> e = Json.object(v);
            if (name.equals(e.get("name"))) return Json.object(e.get("value"));
        }
        return null;
    }

    private static List<String> actions(List<Object> ops) {
        List<String> out = new ArrayList<>();
        for (Object o : ops) out.add(String.valueOf(Json.object(o).get("action")));
        return out;
    }

    private static String formula(String expression) {
        List<Object> ops = body("a = 7\nb = 3\nc = 2\nx = " + expression);
        return String.valueOf(arg(op(ops, ops.size() - 1), "value").get("number"));
    }

    private static List<String> errors(String text) {
        List<String> out = new ArrayList<>();
        for (Diagnostic d : compile(text).diagnostics()) if (d.error()) out.add(d.message());
        return out;
    }

    private static boolean warned(String text, String part) {
        return good(text).diagnostics().stream().anyMatch(d -> !d.error() && d.message().contains(part));
    }

    private static Object passed(Map<String, Object> call, String param) {
        Map<String, Object> entries = Json.object(arg(call, "args").get("values"));
        return entries.get("{\"type\":\"text\",\"text\":\"" + param + "\",\"parsing\":\"plain\"}");
    }

    @Test
    void rightSideKeepsItsBrackets() {
        assertEquals("%math(a*(b%c))", formula("a * (b % c)"));
        assertEquals("%math(a*(b/c))", formula("a * (b / c)"));
        assertEquals("%math(a+(b-c))", formula("a + (b - c)"));
        assertEquals("%math(a-(b-c))", formula("a - (b - c)"));
        assertEquals("%math(a*b%c)", formula("a * b % c"));
    }

    @Test
    void remainderDoesNotLookLikeAPlaceholder() {
        assertEquals("%math((a%b)%c)", formula("a % b % c"));
        assertEquals("%math(a%(min(b,c)))", formula("a % min(b, c)"));
        assertFalse(warned("when player_join {\na = 7\nb = 3\nc = 2\nx = a % b % c\nsend_message(\"${x}\")\n}", "%math"));
    }

    private static final String SHOW = "func show(p) {\n    send_message(\"${p}\")\n}\n";

    @Test
    void formulaArgumentIsCountedBeforeTheCall() {
        List<Object> ops = ops(SHOW + "when player_join {\nx = 1\nshow(x + 1)\n}");
        assertEquals(List.of("set_variable_value", "set_variable_value", "call_function"), actions(ops));
        assertEquals("%math(x+1)", arg(op(ops, 1), "value").get("number"));
        assertEquals("variable", Json.object(passed(op(ops, 2), "p")).get("type"));
    }

    @Test
    void textWithPlaceholderIsCountedBeforeTheCall() {
        List<Object> ops = ops(SHOW + "when player_join {\nx = 1\nshow(\"t${x}\")\nshow(\"%player%\")\n}");
        assertEquals(List.of("set_variable_value", "set_variable_value", "call_function", "set_variable_value", "call_function"), actions(ops));
    }

    @Test
    void plainValuesGoAsTheyAre() {
        List<Object> ops = ops(SHOW + "when player_join {\nx = 1\nshow(x)\nshow(5)\nshow(\"100%\")\nshow(value.name)\n}");
        assertEquals(List.of("set_variable_value", "call_function", "call_function", "call_function", "call_function"), actions(ops));
    }

    @Test
    void literalAndNumStayUncounted() {
        List<Object> ops = ops(SHOW + "when player_join {\nx = 1\nshow(literal(x + 1))\nshow(num(\"%math(x+2)\"))\n}");
        assertEquals(List.of("set_variable_value", "call_function", "call_function"), actions(ops));
        assertEquals("%math(x+1)", Json.object(passed(op(ops, 1), "p")).get("number"));
    }

    @Test
    void processArgumentIsCountedToo() {
        List<Object> ops = ops("process p(v) { send_message(\"${v}\") }\nwhen player_join {\nx = 1\nstart p(x + 1)\n}");
        assertEquals(List.of("set_variable_value", "set_variable_value", "start_process"), actions(ops));
    }

    @Test
    void worldCodeWithLazyArgumentSurvivesTheCircle() {
        Map<String, Object> world = good(SHOW + "when player_join {\nx = 1\nshow(literal(x + 1))\nshow(literal(\"t${x}\"))\n}").module();
        String text = Decompiler.decompile(DATA, NAMES, world).text();
        assertTrue(text.contains("literal(x + 1)"), text);
        assertTrue(text.contains("literal(\"t${x}\")"), text);
        assertEquals(Json.write(world), Json.write(good(text).module()));
    }

    @Test
    void anotherCallDoesNotOverwriteTheResult() {
        List<Object> ops = ops("func two(a) { return a * 2 }\nfunc three(a) { return two(a) + 1 }\n"
                + "when player_join {\nx = two(1) + three(2)\nsend_message(\"${x}\")\n}");
        assertEquals(List.of("call_function", "set_variable_value", "call_function", "set_variable_value", "set_variable_value", "player_send_message"), actions(ops));
    }

    @Test
    void functionInIndexRunsOnce() {
        String head = "func f() { return 0 }\n";
        assertEquals(1, actions(ops(head + "when player_join {\nq = [1]\nq[f()] += 1\n}")).stream().filter("call_function"::equals).count());
        assertEquals(1, actions(ops(head + "when player_join {\ng = [[1]]\ng[f()][0] = 5\n}")).stream().filter("call_function"::equals).count());
    }

    @Test
    void constantInsideAndOr() {
        String head = "const OFF = false\nconst ON = true\n";
        assertEquals(List.of("set_variable_value"), actions(ops(head + "when player_join {\nx = 1\nif OFF and x == 1 { x = 2 }\n}")));
        assertEquals(List.of("set_variable_value", "if_variable_equals"), actions(ops(head + "when player_join {\nx = 1\nif ON and x == 1 { x = 2 }\n}")));
        assertEquals(List.of("set_variable_value", "if_variable_equals"), actions(ops(head + "when player_join {\nx = 1\nif OFF or x == 1 { x = 2 }\n}")));
        assertEquals(List.of("set_variable_value", "set_variable_value"), actions(ops(head + "when player_join {\nx = 1\nif ON or x == 1 { x = 2 }\n}")));
    }

    @Test
    void constantLeftWithCallStillCalls() {
        List<Object> ops = ops("func f() { return 1 }\nwhen player_join {\nif f() == 1 and false { x = 1 }\n}");
        assertEquals("call_function", op(ops, 0).get("action"));
    }

    @Test
    void ternaryOnConstant() {
        assertEquals(List.of("set_variable_value"), actions(ops("const ON = true\nwhen player_join {\nx = ON ? 1 : 2\n}")));
        assertEquals(1.0, Double.parseDouble(String.valueOf(arg(op(ops("when player_join {\nx = true ? 1 : 2\n}"), 0), "value").get("number"))));
    }

    @Test
    void notOfConstantIsAValue() {
        assertTrue(compile("when player_join {\nx = not true\n}").ok());
    }

    @Test
    void comparingWithAListIsOneCell() {
        List<Object> ops = body("t = [1, 2]\nif t == [1, 2] { x = 1 }");
        List<Object> compare = Json.array(arg(op(ops, 1), "compare").get("values"));
        assertEquals(1, compare.size());
        assertEquals("array", Json.object(compare.get(0)).get("type"));
    }

    @Test
    void localKindBelongsToItsHandler() {
        String text = "when player_join {\nd = vec(1, 0, 0)\ne = d * 2\nsend_message(\"${e}\")\n}\n"
                + "when player_quit {\nd = 5\ne = d * 2\nsend_message(\"${e}\")\n}";
        List<Object> handlers = Json.array(good(text).module().get("handlers"));
        List<Object> first = Json.array(Json.object(handlers.get(0)).get("operations"));
        List<Object> second = Json.array(Json.object(handlers.get(1)).get("operations"));
        assertEquals("set_variable_multiply_vector", op(first, 1).get("action"));
        assertEquals("%math(d*2)", arg(op(second, 1), "value").get("number"));
    }

    @Test
    void localShapeBelongsToItsHandler() {
        assertTrue(compile("when player_join {\nq = [1]\nk = 0\nx = q[k]\n}\nwhen player_quit {\nq = {\"a\": 1}\nk = \"a\"\nx = q[k]\n}").ok());
    }

    @Test
    void unknownNeighbourOfALocationIsAVector() {
        assertEquals("set_variable_shift_location_on_vector",
                op(body("save v\np = loc(1, 2, 3)\nq = p - v\nsend_message(\"${q}\")"), 2).get("action"));
    }

    @Test
    void functionAndIndexAreConditions() {
        assertEquals(List.of("call_function", "if_variable_equals"), actions(ops("func f() { return 1 }\nwhen player_join {\nif f() { x = 1 }\n}")));
        assertTrue(compile("when player_join {\nq = [1]\nif q[0] { x = 1 }\n}").ok());
    }

    @Test
    void indexVariableWithZeroOrOneIsANumber() {
        assertTrue(compile("when player_join {\nsave q\ni = 1\nq[i] = 5\n}").ok());
    }

    @Test
    void readsGoLeftToRight() {
        String head = "game c\nfunc inc() {\n    c += 1\n    return c\n}\n";
        List<Object> ops = ops(head + "when player_join {\nx = c + inc()\nsend_message(\"${x}\")\n}");
        assertEquals("set_variable_value", op(ops, 0).get("action"));
        assertEquals("c", arg(op(ops, 0), "value").get("variable"));
        assertEquals("call_function", op(ops, 1).get("action"));
    }

    @Test
    void functionThatDoesNotWriteAddsNoBlocks() {
        List<Object> ops = ops("func pure(a) { return a + 1 }\nwhen player_join {\nb = 1\nx = b + pure(1)\nsend_message(\"${x}\")\n}");
        assertEquals(List.of("set_variable_value", "call_function", "set_variable_value", "player_send_message"), actions(ops));
    }

    @Test
    void ownArgumentNeedsNoCopy() {
        List<Object> ops = ops("func chg(p) {\n    v = 100\n    return p\n}\nwhen player_join {\nv = 5\nx = chg(v)\nsend_message(\"${x}\")\n}");
        assertEquals(List.of("set_variable_value", "call_function", "set_variable_value", "player_send_message"), actions(ops));
    }

    @Test
    void formulaDefaultIsCountedInsideTheFunction() {
        List<Object> ops = ops("game g\nfunc f(a = g + 1) {\n    send_message(\"${a}\")\n}\nfunc z() { }");
        List<Object> handlers = Json.array(good("game g\nfunc f(a = g + 1) {\n    send_message(\"${a}\")\n}").module().get("handlers"));
        List<Object> own = Json.array(Json.object(handlers.get(0)).get("operations"));
        assertEquals(List.of("set_variable_value", "player_send_message"), actions(own));
        assertEquals(arg(op(own, 0), "variable"), arg(op(own, 0), "value"));
        assertEquals(List.of(), actions(ops));
    }

    @Test
    void plainDefaultAddsNothing() {
        List<Object> handlers = Json.array(good("func f(a = 5, b = \"x\") {\n    send_message(\"${a} ${b}\")\n}").module().get("handlers"));
        assertEquals(List.of("player_send_message"), actions(Json.array(Json.object(handlers.get(0)).get("operations"))));
    }

    @Test
    void formulaDefaultSurvivesTheCircle() {
        Map<String, Object> world = good("game g\nfunc f(a = g + 1) {\n    send_message(\"${a}\")\n}").module();
        String text = Decompiler.decompile(DATA, NAMES, world).text();
        assertEquals(Json.write(Json.object(Json.array(world.get("handlers")).get(0)).get("operations")),
                Json.write(Json.object(Json.array(good(text).module().get("handlers")).get(0)).get("operations")));
    }

    @Test
    void returnOfACallKeepsTheFunctionReturning() {
        Map<String, Object> world = good("func two(a) { return a * 2 }\nfunc inner() { return two(5) }\nwhen player_join {\ny = inner()\nsend_message(\"${y}\")\n}").module();
        String text = Decompiler.decompile(DATA, NAMES, world).text();
        assertTrue(compile(text).ok(), text);
    }

    @Test
    void nameWithClosingBracket() {
        List<Object> ops = body("`a)b` = 3\nx = `a)b` + 1\nsend_message(\"${`a)b`}\")");
        assertEquals("set_variable_value", op(ops, 1).get("action"));
        assertEquals("a)b", arg(op(ops, 1), "value").get("variable"));
    }

    @Test
    void wordOfTimeUnitInQuotes() {
        assertEquals(2.0, Double.parseDouble(String.valueOf(arg(op(body("wait(2s, time_unit: \"seconds\")"), 0), "duration").get("number"))));
    }

    @Test
    void foreignDigitsAreNotNumbers() {
        assertFalse(compile("when player_join {\nx = ٣\n}").ok());
    }

    @Test
    void backslashBeforeNewlineDoesNotGlueLines() {
        assertTrue(errors("when player_join {\nx = \"a\\\ny = 1\n}").stream().anyMatch(e -> e.contains("не закрыта")));
    }

    @Test
    void closingBraceInsideQuotedName() {
        List<Object> ops = body("`a}b` = 3\nsend_message(\"${`a}b`}\")");
        assertEquals("%var_local(a}b)", Json.object(Json.array(arg(op(ops, 1), "messages").get("values")).get(0)).get("text"));
    }

    @Test
    void temporaryDoesNotTakeANameFromTheCode() {
        List<Object> ops = body("__xero1 = 5\nl = [1, 2]\ni = 1\nsend_message(\"${l[i]} ${__xero1}\")");
        assertNull(arg(op(ops, ops.size() - 2), "variable").get("nothing"));
        assertEquals("__xero2", arg(op(ops, ops.size() - 2), "variable").get("variable"));
    }

    @Test
    void lengthOfAConstantListSkipsHoles() {
        assertEquals(2.0, Double.parseDouble(String.valueOf(arg(op(ops("const L = [1, _, 3]\nwhen player_join {\nx = len(L)\n}"), 0), "value").get("number"))));
    }

    @Test
    void editingAParameterInPlaceReadsIt() {
        assertFalse(warned("func f(p: list) { append_value(p, 9) }\nwhen player_join {\nf([1])\n}", "не используется"));
    }

    @Test
    void jsonNameIsUpright() {
        List<Object> ops = body("give_items(item(paper, name: json\"{\\\"text\\\":\\\"J\\\"}\"))");
        String base64 = String.valueOf(Json.object(Json.array(arg(op(ops, 0), "items").get("values")).get(0)).get("item"));
        assertTrue(Items.decode(base64).components().toString().contains("italic"));
    }

    @Test
    void tooDeepIsAnErrorNotACrash() {
        String deep = "when player_join {\nx = " + "(".repeat(30000) + "1" + ")".repeat(30000) + "\n}";
        assertFalse(compile(deep).ok());
        assertFalse(Formatter.format(deep).errors().isEmpty());
    }

    @Test
    void defaultValueWithServerNumbersIsTheSame() {
        Map<String, Object> world = good("func f(a: location = loc(1, 2, 3)) {\n    send_message(\"${a}\")\n}").module();
        Map<String, Object> changed = Json.object(Json.parse(Json.write(world).replace("\\\"x\\\":1.0", "\\\"x\\\":1.0000")));
        assertEquals(Delivery.normal(DATA, Json.object(Json.array(world.get("handlers")).get(0))),
                Delivery.normal(DATA, Json.object(Json.array(changed.get("handlers")).get(0))));
    }

    private static String pulled(String text) {
        return Decompiler.decompile(DATA, NAMES, good(text).module()).text();
    }

    private static Map<String, Object> worldOf(String json) {
        return Json.object(Json.parse("{\"handlers\":[{\"type\":\"event\",\"event\":\"player_join\",\"position\":0,\"operations\":[" + json + "]}]}"));
    }

    private static final String EMPTY = "item(\"AAAAAAAAAAA=\")";

    @Test
    void emptyStackInAPlainArgumentIsNotPrinted() {
        String text = pulled("when player_join {\nspawn_mob(" + EMPTY + ", item(husk_spawn_egg), " + EMPTY + ", value.location, boots: " + EMPTY + ")\n}");
        assertFalse(text.contains("AAAA"), text);
        assertTrue(text.contains("spawn_mob(mob: item(husk_spawn_egg), location: value.location)"), text);
    }

    @Test
    void emptyStackInCellsIsAHole() {
        String text = pulled("when player_join {\ngive_items([item(stone), " + EMPTY + ", item(dirt), " + EMPTY + ", " + EMPTY + "])\n}");
        assertFalse(text.contains("AAAA"), text);
        assertTrue(text.contains("give_items([item(stone), _, item(dirt)])"), text);
    }

    @Test
    void emptyStackStaysInASlotGrid() {
        List<Object> ops = body("set_items([item(stone), _, item(dirt)])");
        assertTrue(Json.write(ops).contains("AAAAAAAAAAA="), Json.write(ops));
    }

    @Test
    void emptyStacksDoNotCountAsAChange() {
        Map<String, Object> a = Json.object(Json.array(good("when player_join {\nspawn_mob(mob: item(husk_spawn_egg), helmet: " + EMPTY + ")\n}").module().get("handlers")).get(0));
        Map<String, Object> b = Json.object(Json.array(good("when player_join {\nspawn_mob(mob: item(husk_spawn_egg))\n}").module().get("handlers")).get(0));
        assertEquals(Delivery.normal(DATA, a), Delivery.normal(DATA, b));
    }

    @Test
    void numberWrittenAsTextPrintsAsANumber() {
        String op = "{\"action\":\"control_wait\",\"values\":[{\"name\":\"duration\",\"value\":{\"type\":\"number\",\"number\":\"5.0\"}}]}";
        String text = Decompiler.decompile(DATA, NAMES, worldOf(op)).text();
        assertTrue(text.contains("wait(5)"), text);
        assertEquals(Delivery.normal(DATA, Json.object(Json.array(worldOf(op).get("handlers")).get(0))),
                Delivery.normal(DATA, Json.object(Json.array(good(text).module().get("handlers")).get(0))));
    }

    @Test
    void plainReturnInAFunction() {
        assertTrue(pulled("func f() {\n    send_message(\"a\")\n    return\n}").contains("    return\n"));
    }

    @Test
    void oldItemWithoutComponentsPrintsAsAField() {
        String old = Items.encode("minecraft:stone", 1);
        Nbt.Compound root = new Nbt.Compound().put("DataVersion", Nbt.Num.ofInt(3955)).put("id", new Nbt.Text("minecraft:stone")).put("count", Nbt.Num.ofInt(3));
        String b64 = java.util.Base64.getEncoder().encodeToString(Items.gzip(Nbt.root(root)));
        assertEquals("minecraft:stone", Items.readable(b64).id());
        assertEquals(3, Items.readable(b64).count());
        assertTrue(Items.readable(old) != null);
    }

    @Test
    void oldItemWithComponentsStaysAsItIs() {
        Nbt.Compound comps = new Nbt.Compound().put("minecraft:custom_name", new Nbt.Text("{\"text\":\"a\"}"));
        Nbt.Compound root = new Nbt.Compound().put("DataVersion", Nbt.Num.ofInt(3955)).put("id", new Nbt.Text("minecraft:stone"))
                .put("count", Nbt.Num.ofInt(1)).put("components", comps);
        assertNull(Items.readable(java.util.Base64.getEncoder().encodeToString(Items.gzip(Nbt.root(root)))));
    }

    @Test
    void formulaFromTheWorldPrintsAsAFormula() {
        String op = "{\"action\":\"set_variable_value\",\"values\":[{\"name\":\"variable\",\"value\":{\"type\":\"variable\",\"variable\":\"z\",\"scope\":\"local\"}},"
                + "{\"name\":\"value\",\"value\":{\"type\":\"number\",\"number\":\"%math(%var_local(x) / 2 - %var_local(y y))\"}}]}";
        String text = Decompiler.decompile(DATA, NAMES, worldOf(op)).text();
        assertFalse(text.contains("num("), text);
    }

    @Test
    void formulasAreComparedByMeaning() {
        assertEquals(MathPrint.canonical("%math(x-1)"), MathPrint.canonical("%math(%var_local(x) - 1)"));
        assertEquals(MathPrint.canonical("%math((%var_local(я))*2)"), MathPrint.canonical("%math(%var_local(я)*2)"));
        assertFalse(MathPrint.canonical("%math(%var_save(x)-1)").equals(MathPrint.canonical("%math(x-1)")));
        assertFalse(MathPrint.canonical("%math(a*(b%c))").equals(MathPrint.canonical("%math(a*b%c)")));
    }

    @Test
    void remainderPrintsAsAFormula() {
        String text = pulled("when player_join {\na = 7\nb = 3\nx = a % b\ny = a * (b % 2)\nsend_message(\"${x} ${y}\")\n}");
        assertTrue(text.contains("x = a % b"), text);
        assertTrue(text.contains("y = a * (b % 2)"), text);
    }

    @Test
    void closingTagMustMatchWhatWasOpened() throws Styled.Problem {
        Nbt.Tag wrong = Styled.component("<#ff8800>a</color>b", "minimessage", false);
        assertTrue(Nbt.print(wrong).contains("</color>"), Nbt.print(wrong));
        Nbt.Tag right = Styled.component("<#ff8800>a</#FF8800>b", "minimessage", false);
        assertFalse(Nbt.print(right).contains("</"), Nbt.print(right));
        assertTrue(Nbt.print(Styled.component("<b>a</bold>c", "minimessage", false)).contains("</bold>"));
    }

    @Test
    void rainbowAndGradientCountLikeTheServer() throws Styled.Problem {
        String rainbow = Nbt.print(Styled.component("<rainbow:!>abcd</rainbow>", "minimessage", false));
        assertTrue(rainbow.indexOf("#7F00FF") < rainbow.indexOf("#FF0000") && rainbow.contains("#00FFFF"), rainbow);
        assertTrue(Nbt.print(Styled.component("<rainbow:2>abcd</rainbow>", "minimessage", false)).contains("#CBFF00"));
        String phase = Nbt.print(Styled.component("<gradient:red:blue:0.5>abcd</gradient>", "minimessage", false));
        assertTrue(phase.contains("#AA55AA") && phase.contains("#7155E3"), phase);
        assertTrue(Nbt.print(Styled.component("<transition:red:blue:0.5>a", "minimessage", false)).contains("#AA55AA"));
    }

    @Test
    void tagThatCannotWorkInAnItemIsReported() {
        assertTrue(warned("when player_join {\ngive_items(item(stone, name: mm\"<font:minecraft:alt>a\"))\n}", "не сработает"));
    }

    private static xero.dialect.Dialect xero() {
        return xero.dialect.Dialects.byId("xero");
    }

    @Test
    void stringPrefixBelongsToTheString() {
        byte[] ink = new byte[8];
        xero.dialect.Ink.scan(xero().syntax(), Map.of(), "mm\"<red>\"", xero.dialect.Ink.NORMAL, ink);
        assertEquals(xero.dialect.Ink.ESCAPE, ink[0]);
        assertEquals(xero.dialect.Ink.ESCAPE, ink[1]);
    }

    @Test
    void valueWindowKeepsTheMarkup() {
        for (String text : List.of("mm\"<red>x\"", "plain\"p\"", "plain\"a&cb\"", "\"&cx\"", "mm\"<red>${x}\""))
            assertEquals(text, xero().valueText(xero().valueOf(text)));
        assertEquals("minimessage", xero().valueOf("mm\"<red>x\"").get("parsing"));
    }

    @Test
    void valueWindowOpensTextWithCode() {
        for (String text : List.of("\"a ${x} b\"", "\"${m[\"k\"]} и ${f(\"a\", 1)}!\"", "\"a\\\"b ${x}\"", "\"${l[0]}\""))
            assertEquals(text, xero().valueText(xero().valueOf(text)));
    }

    @Test
    void markerByDefaultIsNotPrinted() {
        assertTrue(pulled("when player_join {\nwait(1, time_unit: ticks)\n}").contains("wait(1)\n"));
        assertTrue(pulled("when player_join {\nwait(1, time_unit: seconds)\n}").contains("time_unit: seconds"));
    }

    @Test
    void markerByDefaultDoesNotCountAsAChange() {
        Map<String, Object> a = Json.object(Json.array(good("when player_join {\nwait(1, time_unit: ticks)\n}").module().get("handlers")).get(0));
        Map<String, Object> b = Json.object(Json.array(good("when player_join {\nwait(1)\n}").module().get("handlers")).get(0));
        assertEquals(Delivery.normal(DATA, a), Delivery.normal(DATA, b));
    }

    @Test
    void variableNamedValueNeedsNoQuotes() {
        String text = pulled("when player_join {\nl = [1]\nfor `value` in l {\nsend_message(`value`)\n}\n}");
        assertTrue(text.contains("for value in l") && text.contains("send_message(value)"), text);
        assertTrue(compile(text).ok(), text);
    }
}
