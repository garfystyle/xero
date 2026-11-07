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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnownBugsTest {
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
        Compiler.Result r = good(text);
        List<Object> handlers = Json.array(r.module().get("handlers"));
        return Json.array(Json.object(handlers.get(handlers.size() - 1)).get("operations"));
    }

    private static List<Object> body(String body) {
        return ops("when player_join {\n" + body + "\n}");
    }

    private static List<String> warnings(String text) {
        List<String> out = new ArrayList<>();
        for (Diagnostic d : good(text).diagnostics()) if (!d.error()) out.add(d.message());
        return out;
    }

    private static List<String> errors(String text) {
        List<String> out = new ArrayList<>();
        for (Diagnostic d : compile(text).diagnostics()) if (d.error()) out.add(d.message());
        return out;
    }

    private static boolean warned(String text, String part) {
        return warnings(text).stream().anyMatch(m -> m.contains(part));
    }

    private static Map<String, Object> op(List<Object> ops, int at) {
        return Json.object(ops.get(at));
    }

    private static String action(List<Object> ops, int at) {
        return String.valueOf(op(ops, at).get("action"));
    }

    private static Map<String, Object> arg(Map<String, Object> op, String name) {
        for (Object v : Json.array(op.get("values"))) {
            Map<String, Object> e = Json.object(v);
            if (name.equals(e.get("name"))) return Json.object(e.get("value"));
        }
        return null;
    }

    private static double number(Map<String, Object> value) {
        return Double.parseDouble(String.valueOf(value.get("number")));
    }

    private static String selection(Map<String, Object> op) {
        return op.get("selection") instanceof Map<?, ?> m ? String.valueOf(m.get("type")) : null;
    }

    private static void roundTrip(String text) {
        Map<String, Object> module = good(text).module();
        String printed = Decompiler.decompile(DATA, NAMES, module).text();
        assertFalse(printed.contains("raw("), printed);
        assertEquals(Json.write(module), Json.write(good(printed).module()), printed);
    }

    @Test
    void targetReachesWhileBlock() {
        Map<String, Object> loop = op(body("while @killer is_sneaking() {\nwait(1)\n}"), 0);
        assertEquals("repeat_while", loop.get("action"));
        assertEquals("killer_player", selection(loop));
        assertEquals("if_player_is_sneaking", Json.object(loop.get("conditional")).get("action"));
    }

    @Test
    void targetReachesRepeatWhileCall() {
        Map<String, Object> loop = op(body("repeat.while(@killer is_sneaking()) {\nwait(1)\n}"), 0);
        assertEquals("repeat_while", loop.get("action"));
        assertEquals("killer_player", selection(loop));
    }

    @Test
    void targetSurvivesNot() {
        Map<String, Object> loop = op(body("while not @victim is_sneaking() {\nwait(1)\n}"), 0);
        assertEquals("victim_player", selection(loop));
        assertEquals(Boolean.TRUE, Json.object(loop.get("conditional")).get("is_inverted"));
    }

    @Test
    void targetReachesSelection() {
        assertEquals("killer_player", selection(op(body("player_by_conditional(@killer is_sneaking())"), 0)));
        assertEquals("killer_player", selection(op(body("filter_by_conditional(not @killer is_sneaking())"), 0)));
        assertNull(selection(op(body("player_by_conditional(is_sneaking())"), 0)));
    }

    @Test
    void targetSurvivesDecompiler() {
        roundTrip("when player_join {\nwhile @killer is_sneaking() {\nwait(1)\n}\nplayer_by_conditional(@killer is_sneaking())\n}");
    }

    @Test
    void actionResultIsVector() {
        List<Object> ops = body("left = rotate_vector_around_axis(vec(1, 0, 0), 90)\nb = left * 0.5\nplayer.set_velocity(b)");
        assertEquals("set_variable_multiply_vector", action(ops, 1));
        assertEquals("left", arg(op(ops, 1), "vector").get("variable"));
        assertFalse(warned("when player_join {\nleft = rotate_vector_around_axis(vec(1, 0, 0), 90)\nb = left * 0.5\nplayer.set_velocity(b)\n}", "число"));
    }

    @Test
    void everyVectorActionMultiplies() {
        for (String call : List.of("set_vector_length(vec(1, 0, 0), 2)", "get_vector_between_locations(value.location, value.eye_location)",
                "get_vector_from_block_face(\"north\")", "get_location_direction(value.location)", "add_vectors([vec(1, 0, 0), vec(0, 1, 0)])",
                "var.vector(1, 2, 3)", "vector_cross_product(vec(1, 0, 0), vec(0, 1, 0))")) {
            List<Object> ops = body("v = " + call + "\nw = v * 2");
            assertEquals("set_variable_multiply_vector", action(ops, 1), call);
        }
    }

    @Test
    void numberActionsStayFormulas() {
        for (String call : List.of("get_vector_length(vec(1, 0, 0))", "get_vector_component(vec(1, 0, 0))",
                "vector_dot_product(vec(1, 0, 0), vec(0, 1, 0))", "get_angle_between_vectors(vec(1, 0, 0), vec(0, 1, 0))")) {
            List<Object> ops = body("n = " + call + "\nw = n * 2");
            assertEquals("set_variable_value", action(ops, 1), call);
        }
    }

    @Test
    void writtenResultIsVectorToo() {
        List<Object> ops = body("rotate_vector_around_axis(left, vec(1, 0, 0), 90)\nb = left * 2");
        assertEquals("set_variable_multiply_vector", action(ops, 1));
    }

    @Test
    void inlineActionIsVector() {
        List<Object> ops = body("b = rotate_vector_around_axis(vec(1, 0, 0), 90) * 2");
        assertEquals("set_variable_multiply_vector", action(ops, ops.size() - 1));
    }

    @Test
    void functionResultIsVector() {
        List<Object> ops = ops("func dir() {\nreturn vec(1, 0, 0)\n}\nwhen player_join {\nv = dir()\nw = v * 2\n}");
        assertEquals("set_variable_multiply_vector", action(ops, ops.size() - 1));
    }

    @Test
    void vectorDividedByNumberIsMultiplied() {
        List<Object> ops = body("v = vec(1, 0, 0)\nw = v / 2");
        assertEquals("set_variable_multiply_vector", action(ops, 1));
        assertEquals(0.5, number(arg(op(ops, 1), "multiplier")));
    }

    @Test
    void negatedVectorIsMultiplied() {
        assertEquals("set_variable_multiply_vector", action(body("v = vec(1, 0, 0)\nplayer.set_velocity(-v)"), 1));
    }

    @Test
    void oldFormulaOverActionResultStillBuilds() {
        String text = "when player_join {\nleft = rotate_vector_around_axis(vec(1, 0, 0), 90)\nx = left + 1\nsend_message(\"${x}\")\n}";
        assertTrue(warned(text, "в формуле только числа"));
        assertEquals("set_variable_value", action(ops(text), 1));
    }

    @Test
    void declaredLocationPlusVector() {
        List<Object> ops = ops("game base: location\nwhen player_join {\nat = base + vec(1, 0, 0)\nplayer.teleport(at)\n}");
        assertEquals("set_variable_shift_location_on_vector", action(ops, 0));
        assertEquals("base", arg(op(ops, 0), "location").get("variable"));
        assertTrue(warnings("game base: location\nwhen player_join {\nat = base + vec(1, 0, 0)\nplayer.teleport(at)\n}").isEmpty());
    }

    @Test
    void declaredVariableWithLocationValue() {
        List<Object> ops = ops("game base\nwhen world_start {\nbase = loc(0, 64, 0)\n}\nwhen player_join {\nat = base + vec(1, 0, 0)\nplayer.teleport(at)\n}");
        assertEquals("set_variable_shift_location_on_vector", action(ops, 0));
    }

    @Test
    void actionResultIsLocation() {
        for (String call : List.of("align_location(value.location)", "shift_coordinate(value.location, 1)", "center_location([value.location])",
                "shift_location_on_vector(value.location, vec(0, 1, 0))", "set_all_coordinates(1, 2, 3)", "random_location(value.location, value.eye_location)")) {
            List<Object> ops = body("l = " + call + "\nat = l + vec(0, 1, 0)\nplayer.teleport(at)");
            assertEquals("set_variable_shift_location_on_vector", action(ops, 1), call);
        }
    }

    @Test
    void loopPointIsLocation() {
        List<Object> ops = body("on_sphere(p, value.location, 5, 100) {\nq = p + vec(0, 1, 0)\nset_block(q, block(stone))\n}");
        assertEquals("set_variable_shift_location_on_vector", action(Json.array(op(ops, 0).get("operations")), 0));
    }

    @Test
    void lineVariableKeepsKind() {
        List<Object> ops = body("line base = value.location\nat = base + vec(1, 0, 0)\nplayer.teleport(at)");
        assertEquals("set_variable_shift_location_on_vector", action(ops, 1));
    }

    @Test
    void useDecidesUnknownVariable() {
        String text = "save base\nwhen player_join {\nat = base + vec(1, 0, 0)\nplayer.teleport(at)\n}";
        assertEquals("set_variable_shift_location_on_vector", action(ops(text), 0));
        assertTrue(warnings(text).isEmpty(), () -> warnings(text).toString());
        assertEquals("set_variable_shift_location_on_vector", action(ops("save base\nwhen player_join {\nplayer.teleport(base + vec(1, 0, 0))\n}"), 0));
        assertEquals("set_variable_shift_location_on_vector",
                action(ops("save base\nwhen player_join {\nplayer.teleport(base)\nat = base + vec(1, 0, 0)\nsend_message(\"${at}\")\n}"), 1));
    }

    @Test
    void unknownPlusVectorWarns() {
        String text = "save base\nwhen player_join {\nat = base + vec(1, 0, 0)\nsend_message(\"${at}\")\n}";
        assertEquals("set_variable_add_vectors", action(ops(text), 0));
        assertTrue(warned(text, "место или вектор"));
    }

    @Test
    void vectorsStillAdd() {
        List<Object> ops = body("v = vec(0, 1, 0)\nw = v + vec(1, 0, 0)\nplayer.set_velocity(w)");
        assertEquals("set_variable_add_vectors", action(ops, 1));
    }

    @Test
    void vectorPlusLocationAndLocationMinusVector() {
        assertEquals("set_variable_shift_location_on_vector", action(body("at = vec(0, 1, 0) + value.location\nplayer.teleport(at)"), 0));
        List<Object> ops = body("v = vec(0, 1, 0)\nat = value.location - v\nplayer.teleport(at)");
        assertEquals("set_variable_multiply_vector", action(ops, 1));
        assertEquals("set_variable_shift_location_on_vector", action(ops, 2));
    }

    @Test
    void constIndexIsSubstituted() {
        List<Object> ops = ops("const L = [10, 20]\nconst M = {\"a\": 1}\nconst G = [[1, 2], [3, 4]]\nwhen player_join {\nx = L[1]\ny = M[\"a\"]\nz = G[1][0]\n}");
        assertEquals(3, ops.size());
        assertEquals("set_variable_value", action(ops, 0));
        assertEquals(20.0, number(arg(op(ops, 0), "value")));
        assertEquals(1.0, number(arg(op(ops, 1), "value")));
        assertEquals(3.0, number(arg(op(ops, 2), "value")));
    }

    @Test
    void constIndexWithVariableStaysBlock() {
        List<Object> ops = ops("const L = [10, 20]\nwhen player_join {\ni = 1\nx = L[i]\nsend_message(\"${x}\")\n}");
        assertEquals("set_variable_get_list_value", action(ops, 1));
    }

    @Test
    void constLength() {
        List<Object> ops = ops("const L = [1, 2, _]\nconst M = {\"a\": 1}\nwhen player_join {\nx = len(L)\ny = len(M) + 1\nsend_message(\"${len(L)}\")\n}");
        assertEquals(2.0, number(arg(op(ops, 0), "value")));
        assertEquals(2.0, number(arg(op(ops, 1), "value")));
        Object cell = Json.array(arg(op(ops, 2), "messages").get("values")).get(0);
        assertEquals("2", Json.object(cell).get("text"));
    }

    @Test
    void constInItemFields() {
        String direct = "when player_join {\ngive_items(item(stone, name: \"Имя\", lore: [\"Имя\", \"x\"], tags: {a: \"k\", b: 5}))\n}";
        String named = "const N = \"Имя\"\nconst T = \"k\"\nconst C = 5\nconst LORE = [N, \"x\"]\nconst TAGS = {\"a\": T, \"b\": C}\n"
                + "when player_join {\ngive_items(item(stone, name: N, lore: LORE, tags: TAGS))\n}";
        assertEquals(Json.write(ops(direct)), Json.write(ops(named)));
    }

    @Test
    void constInItemText() {
        assertEquals(Json.write(body("give_items(item(stone, name: \"a 5\", lore: \"b\"))")),
                Json.write(ops("const C = 5\nconst B = \"b\"\nwhen player_join {\ngive_items(item(stone, name: \"a ${C}\", lore: \"${B}\"))\n}")));
    }

    @Test
    void itemTextWithVariableIsStillError() {
        assertTrue(errors("when player_join {\nx = 1\ngive_items(item(stone, name: \"${x}\"))\n}").get(0).contains("текст постоянный"));
    }

    @Test
    void quotedConst() {
        assertEquals(Json.write(body("x = 5\nsend_message(\"${x}\")")),
                Json.write(ops("const `MY C` = 5\nwhen player_join {\nx = `MY C`\nsend_message(\"${x}\")\n}")));
        assertEquals("[]", Json.write(errors("const `A B` = \"t\"\nconst `C D` = `A B`\nwhen player_join {\nsend_message(`C D`)\n}")));
    }

    @Test
    void quotedVariableBesideConstStaysVariable() {
        List<Object> ops = ops("const X = 5\nfunc f(`X`) {\nsend_message(`X`)\n}");
        assertEquals("variable", Json.object(Json.array(arg(op(ops, 0), "messages").get("values")).get(0)).get("type"));
    }

    @Test
    void orInSelectionIsOneBlock() {
        List<Object> ops = body("x = 1\nplayer_by_conditional(x == 1 or x == 2)");
        assertEquals("if_variable_equals", Json.object(op(ops, 1).get("conditional")).get("action"));
        assertEquals(2, Json.array(arg(op(ops, 1), "compare").get("values")).size());
        assertEquals(Boolean.TRUE, Json.object(op(body("x = 1\nfilter_by_conditional(x != 1 and x != 2)"), 1).get("conditional")).get("is_inverted"));
    }

    @Test
    void orWithDifferentVariablesExplains() {
        Compiler.Result r = compile("when player_join {\nx = 1\ny = 2\nplayer_by_conditional(x == 1 or y == 2)\n}");
        Diagnostic d = r.diagnostics().stream().filter(Diagnostic::error).findFirst().orElseThrow();
        assertTrue(d.message().contains("and/or нельзя"), d.message());
        assertTrue(d.hint().contains("in ["), d.hint());
    }

    @Test
    void parameterInVariableNameIsUsed() {
        assertFalse(warned("func f(n) {\n`kills %var_line(n)` = 1\n}", "не используется"));
        assertFalse(warned("func f(n) {\ngame `kills %var_line(n)` = 1\n}", "не используется"));
        assertFalse(warned("func f(n) {\ngame `kills %var_line(n)`\n}", "не используется"));
        assertFalse(warned("func f(n) {\nx = 1\n`a %var_line(n)` += x\n}", "не используется"));
        assertFalse(warned("func f(n) {\ngive_items(item(stone, name: \"%var_line(n)\"))\n}", "не используется"));
        assertFalse(warned("func f(n) {\ngive_items(item(stone, lore: [\"%var_line(n)\"]))\n}", "не используется"));
        assertFalse(warned("func f(n) {\ngive_items(item(stone, tags: {a: \"%var_line(n)\"}))\n}", "не используется"));
        assertTrue(warned("func f(n, m) {\n`kills %var_line(n)` = 1\n}", "параметр «m» не используется"));
    }

    @Test
    void writtenParameterIsNotCalledUnused() {
        assertFalse(warned("func f(a) {\na = 1\n}", "не используется"));
        assertTrue(warned("func f(a) {\na = 1\n}", "«a» задана, но нигде не читается"));
        assertFalse(warned("func f(a) {\na += 1\n}", "не используется"));
        assertTrue(warned("func f(a) {\na += 1\n}", "«a» задана, но нигде не читается"));
        assertFalse(warned("func f(a) {\na = 1\nsend_message(\"${a}\")\n}", "читается"));
        assertTrue(warned("func f(a) {\n}", "параметр «a» не используется"));
    }

    @Test
    void brokenLineDoesNotCallParameterUnused() {
        for (String text : List.of("func f(a) {\nif 1 in a {\nsend_message(\"да\")\n}\n}", "func f(a) {\ngive_items(item(stone, a))\n}")) {
            assertFalse(errors(text).isEmpty(), text);
            for (Diagnostic d : compile(text).diagnostics()) assertFalse(d.message().contains("не используется"), d::message);
        }
    }

    @Test
    void dottedFunctionWithCategory() {
        List<Object> ops = ops("func player.x() {\nsend_message(\"a\")\n}\nwhen player_join {\nplayer.x()\n}");
        assertEquals("call_function", action(ops, 0));
        assertEquals("player.x", arg(op(ops, 0), "function_name").get("text"));
    }

    @Test
    void dottedFunctionResult() {
        List<Object> ops = ops("func player.x(a) {\nreturn a + 1\n}\nwhen player_join {\ny = player.x(1) + player.x(2)\nsend_message(\"${y}\")\n}");
        assertEquals("call_function", action(ops, 0));
        assertEquals("a", Json.object(Json.parse(String.valueOf(Json.object(arg(op(ops, 0), "args").get("values")).keySet().iterator().next()))).get("text"));
    }

    @Test
    void dottedFunctionNeverHidesAction() {
        List<Object> ops = ops("func `player.teleport`() {\n}\nwhen player_join {\nplayer.teleport(value.location)\n}");
        assertEquals("player_teleport", action(ops, 0));
    }

    @Test
    void longDottedFunction() {
        List<Object> ops = ops("func player.a.b() {\nwait(1)\n}\nwhen player_join {\nplayer.a.b()\n}");
        assertEquals("player.a.b", arg(op(ops, 0), "function_name").get("text"));
    }

    @Test
    void particleFieldsAreChecked() {
        String color = "when player_join {\ndisplay_particle(particle(flame, color: \"#ff0000\"), value.location)\n}";
        assertTrue(warned(color, "нет цвета"));
        assertEquals(Json.write(body("display_particle(particle(flame), value.location)")), Json.write(ops(color)));
        assertTrue(warned("when player_join {\ndisplay_particle(particle(flame, foo: 1), value.location)\n}", "«foo»"));
        assertTrue(warned("when player_join {\ndisplay_particle(particle(dust, x_motion: 1), value.location)\n}", "нет движения"));
        assertTrue(warnings("when player_join {\ndisplay_particle(particle(dust, color: \"#ff0000\", size: 2), value.location)\n}").isEmpty());
    }

    @Test
    void particleWithVariableStaysError() {
        assertEquals(1, errors("when player_join {\nn = 2\ndisplay_particle(particle(flame, count: n), value.location)\n}").size());
    }

    @Test
    void markerFromVariable() {
        List<Object> ops = body("t = \"birch\"\ngenerate_tree(value.location, tree_type: t)");
        Map<String, Object> marker = arg(op(ops, 1), "tree_type");
        assertEquals("enum", marker.get("type"));
        assertEquals("t", marker.get("variable"));
        assertEquals("local", marker.get("scope"));
        assertEquals("TREE", marker.get("enum"));
    }

    @Test
    void anyMarkerTakesVariable() {
        Map<String, Object> marker = arg(op(ops("game u\nwhen player_join {\nwait(1, u)\n}"), 0), "time_unit");
        assertEquals("u", marker.get("variable"));
        assertEquals("game", marker.get("scope"));
        assertEquals("u", arg(op(body("u = \"x\"\nwait(1, time_unit: u)"), 1), "time_unit").get("variable"));
    }

    @Test
    void startSettingTakesVariable() {
        String text = "process p() {\nwait(1)\n}\nwhen player_join {\nm = \"x\"\nstart p(target_mode: m)\n}";
        assertEquals("m", arg(op(ops(text), 1), "target_mode").get("variable"));
        roundTrip(text);
    }

    @Test
    void optionBeatsVariable() {
        assertNull(arg(op(body("seconds = 1\nwait(1, time_unit: seconds)"), 1), "time_unit").get("variable"));
    }

    @Test
    void unknownMarkerWordIsStillError() {
        assertEquals(List.of("у «tree_type» нет варианта «t»"), errors("when player_join {\ngenerate_tree(value.location, tree_type: t)\n}"));
    }

    @Test
    void appendWithRepeatedList() {
        String bare = Json.write(body("l = [1]\nl = append_value(5)"));
        assertEquals(bare, Json.write(body("l = [1]\nl = append_value(l, 5)")));
        assertEquals(bare, Json.write(body("l = [1]\nappend_value(l, 5)")));
    }

    @Test
    void spareValuesHintList() {
        Compiler.Result r = compile("when player_join {\nl = [1]\nl = append_value(l, 5, 6)\n}");
        Diagnostic d = r.diagnostics().stream().filter(Diagnostic::error).findFirst().orElseThrow();
        assertTrue(d.message().contains("лишний аргумент"), d.message());
        assertTrue(d.hint().contains("append_value(l, [5, 6])") || d.hint().contains("["), d.hint());
    }

    @Test
    void listMutatorsInPlace() {
        String full = Json.write(body("q = [1]\nx = 1\nq = remove_list_value(q, x)"));
        assertEquals(full, Json.write(body("q = [1]\nx = 1\nremove_list_value(q, x)")));
        assertEquals(Json.write(body("q = [1]\nq = remove_list_value(q, 1)")), Json.write(body("q = [1]\nremove_list_value(q, 1)")));
        assertEquals(Json.write(body("q = [1]\nq = insert_list_value(q, 0, \"a\")")), Json.write(body("q = [1]\ninsert_list_value(q, 0, \"a\")")));
        assertEquals(Json.write(body("q = [1]\nq = sort_any_list(q)")), Json.write(body("q = [1]\nsort_any_list(q)")));
    }

    @Test
    void removeAtIndexPositional() {
        String named = Json.write(body("q = [1]\nq = remove_list_value_at_index(list: q, index: 1)"));
        assertEquals(named, Json.write(body("q = [1]\nq = remove_list_value_at_index(q, 1)")));
        assertEquals(named, Json.write(body("q = [1]\nremove_list_value_at_index(q, 1)")));
        String other = Json.write(body("q = [1]\nr = remove_list_value_at_index(list: q, index: 1)"));
        assertEquals(other, Json.write(body("q = [1]\nr = remove_list_value_at_index(q, 1)")));
    }

    @Test
    void removeAtIndexFullFormStays() {
        List<Object> ops = body("q = [1]\nw = 0\nq = remove_list_value_at_index(w, q, 0)");
        assertEquals("w", arg(op(ops, 2), "removed_value").get("variable"));
        assertEquals("q", arg(op(ops, 2), "list").get("variable"));
    }

    @Test
    void removeMapEntryPositional() {
        String named = Json.write(body("m = {\"a\": 1}\nm = remove_map_entry(map: m, key: \"a\")"));
        assertEquals(named, Json.write(body("m = {\"a\": 1}\nm = remove_map_entry(m, \"a\")")));
        assertEquals(named, Json.write(body("m = {\"a\": 1}\nremove_map_entry(m, \"a\")")));
    }

    @Test
    void getterIsNotInPlace() {
        assertFalse(errors("when player_join {\nl = [1]\nget_list_value(l, 0)\n}").isEmpty());
    }

    @Test
    void oldFormsStillBuild() {
        good("when player_join {\nx = multiple()\n}");
        good("when player_join {\nx = 1\nx = var.remove()\n}");
        good("when player_join {\nq = [1]\nx = 1\nremove_list_value(q, x)\n}");
        good("when player_join {\nq = 1\nw = var.value(q)\nsend_message(\"${w}\")\n}");
    }

    @Test
    void wordsAssignEvenly() {
        for (String word : List.of("for", "if", "while", "repeat", "forever", "return", "break", "continue", "start", "when", "func",
                "process", "import", "else", "not", "and", "or", "и", "или", "не", "для", "если", "пока", "иначе")) {
            String text = "when player_join {\n" + word + " = 1\n}";
            assertEquals("set_variable_value", action(ops(text), 0), word);
            assertTrue(warned(text, "слово языка"), word);
        }
    }

    @Test
    void wordFixQuotes() {
        Diagnostic d = good("when player_join {\nfor = 1\n}").diagnostics().stream()
                .filter(x -> x.message().contains("слово языка")).findFirst().orElseThrow();
        assertEquals("`for`", d.fixes().get(0).text());
    }

    @Test
    void wordCompoundAndPairAssign() {
        assertEquals("set_variable_add", action(ops("when player_join {\nfor += 1\n}"), 0));
        assertEquals("set_variable_multiple", action(ops("when player_join {\nif, b = 1, 2\n}"), 0));
    }

    @Test
    void contextualWordsDoNotWarn() {
        assertFalse(warned("when player_join {\nstep = 1\nin = 2\nsend_message(\"${step}${in}\")\n}", "слово языка"));
    }

    @Test
    void quotedWordDoesNotWarn() {
        assertFalse(warned("when player_join {\n`for` = 1\nsend_message(\"${`for`}\")\n}", "слово языка"));
    }

    @Test
    void wordsStayWordsElsewhere() {
        assertEquals("repeat_multi_times", action(body("repeat 2 {\nwait(1)\n}"), 0));
        assertEquals("repeat_while", action(body("x = 1\nwhile x < 2 {\nx += 1\n}"), 1));
        assertEquals("control_end_thread", action(body("return"), 0));
    }

    @Test
    void formatterKeepsWordAssignment() {
        Formatter.Result r = Formatter.format("when player_join {\n    for = 1\n}\n");
        assertTrue(r.ok(), () -> r.errors().toString());
        assertNotNull(r.text());
        assertTrue(r.text().contains("for = 1"), r.text());
    }

    @Test
    void foreverWithoutPauseWarns() {
        assertTrue(warned("when player_join {\nx = 0\nforever {\nx += 1\n}\n}", "в forever нет wait"));
        assertFalse(warned("when player_join {\nforever {\nwait(1)\n}\n}", "в forever нет wait"));
        assertFalse(warned("when player_join {\nx = 0\nforever {\nif x > 5 {\nbreak\n}\nx += 1\n}\n}", "в forever нет wait"));
        assertFalse(warned("func f() {\nwait(1)\n}\nwhen player_join {\nforever {\nf()\n}\n}", "в forever нет wait"));
        assertFalse(warned("func f() {\nreturn 1\n}\nwhen player_join {\nforever {\nx = f()\nsend_message(\"${x}\")\n}\n}", "в forever нет wait"));
    }

    @Test
    void valuesInText() {
        List<Object> ops = body("send_message(\"${loc(1, 2, 3)} ${[1, 2]} ${item(stone)}\")");
        assertEquals(4, ops.size());
        assertEquals("set_variable_value", action(ops, 0));
        assertTrue(Json.write(ops.get(3)).contains("%var_line(__xero1)"));
    }

    @Test
    void lengthOfAnyList() {
        List<Object> ops = body("n = len(value.selection_target_names)\nsend_message(\"${n}\")");
        assertEquals("set_variable_value", action(ops, 0));
        assertTrue(Json.write(ops.get(1)).contains("%length_line(__xero1)"), () -> Json.write(ops));
        assertTrue(errors("when player_join {\nx = len(\"abc\")\n}").get(0).contains("len(список)"));
    }

    @Test
    void markerParameterTakesBareWord() {
        assertEquals(Json.write(ops("func f(mode: enum(\"alpha\", \"beta\")) {\nsend_message(\"${mode}\")\n}\nwhen player_join {\nf(\"beta\")\n}")),
                Json.write(ops("func f(mode: enum(\"alpha\", \"beta\")) {\nsend_message(\"${mode}\")\n}\nwhen player_join {\nf(beta)\n}")));
    }

    @Test
    void blockStateExplains() {
        Diagnostic d = compile("when player_join {\nx = block(\"light[level=10]\")\n}").diagnostics().get(0);
        assertTrue(d.message().contains("только id"), d.message());
        assertTrue(d.hint().contains("light[level=10]"), d.hint());
    }

    @Test
    void loopNamedUnderscoreDoesNotWarn() {
        assertFalse(warned("when player_join {\nfor i, _ in [1] {\nsend_message(\"${i}\")\n}\n}", "слово языка"));
    }

    private static String wire(String text) {
        return Json.write(good(text).module());
    }

    private static final String VECTORS = "game l\nwhen world_start {\nl = [vec(1, 0, 0), vec(0, 2, 0)]\n}\n";

    @Test
    void vectorFromListIsNotFormula() {
        String w = wire(VECTORS + "when player_join {\nk = 3\na = l[1] * k\nb = l[0] + l[1]\nsend_message(\"${a}${b}\")\n}");
        assertTrue(w.contains("set_variable_multiply_vector"), w);
        assertTrue(w.contains("set_variable_add_vectors"), w);
        assertFalse(w.contains("%math"), w);
    }

    @Test
    void vectorFromLoopIsNotFormula() {
        String w = wire(VECTORS + "when player_join {\nfor v in l {\na = v * 2\nsend_message(\"${a}\")\n}\n}");
        assertTrue(w.contains("set_variable_multiply_vector"), w);
    }

    @Test
    void vectorKindComesFromEveryWayToFillList() {
        assertTrue(wire("when player_join {\nl = create_list()\nl = append_value(l, vec(1, 0, 0))\na = l[0] * 2\nsend_message(\"${a}\")\n}")
                .contains("set_variable_multiply_vector"));
        assertTrue(wire("game m\nwhen world_start {\nm = {\"a\": vec(1, 0, 0)}\n}\nwhen player_join {\na = m[\"a\"] * 2\nsend_message(\"${a}\")\n}")
                .contains("set_variable_multiply_vector"));
        assertTrue(wire("const L = [vec(1, 0, 0), vec(0, 1, 0)]\nwhen player_join {\ni = 1\na = L[i] * 2\nsend_message(\"${a}\")\n}")
                .contains("set_variable_multiply_vector"));
    }

    @Test
    void otherListsStayFormula() {
        assertTrue(wire("when player_join {\nl = [1, 2]\na = l[0] * 2\nsend_message(\"${a}\")\n}").contains("%math"));
        assertTrue(wire("when player_join {\nl = [1, vec(0, 1, 0)]\na = l[0] * 2\nsend_message(\"${a}\")\n}").contains("%math"));
        assertTrue(wire("game l\nwhen player_join {\nk = 3\na = l[0] * k\nsend_message(\"${a}\")\n}").contains("%math"));
        assertTrue(wire(VECTORS + "when player_join {\nl = [1, 2]\na = l[0] * 2\nsend_message(\"${a}\")\n}").contains("%math"));
    }

    @Test
    void listChangedInPlaceStopsBeingVectors() {
        String number = "game l\nwhen world_start {\nl = [vec(1, 0, 0)]\n%s\n}\nwhen player_join {\na = l[1] * 2\nsend_message(\"${a}\")\n}";
        assertTrue(wire(number.formatted("append_value(l, 5)")).contains("%math"));
        assertTrue(wire(number.formatted("l = append_value(l, 5)")).contains("%math"));
        assertTrue(wire(number.formatted("set_list_value(l, 0, 7)")).contains("%math"));
        assertTrue(wire(number.formatted("l[0] = 7")).contains("%math"));
    }

    @Test
    void listStaysVectorsWhenChangeKeepsThem() {
        String vectors = "game l\nwhen world_start {\nl = [vec(1, 0, 0), vec(0, 1, 0)]\n%s\n}\nwhen player_join {\na = l[0] * 2\nsend_message(\"${a}\")\n}";
        assertTrue(wire(vectors.formatted("append_value(l, vec(0, 0, 1))")).contains("set_variable_multiply_vector"));
        assertTrue(wire(vectors.formatted("set_list_value(l, 0, vec(0, 0, 1))")).contains("set_variable_multiply_vector"));
        assertTrue(wire(vectors.formatted("remove_list_value_at_index(l, 1)")).contains("set_variable_multiply_vector"));
        assertTrue(wire(vectors.formatted("l = reverse_list(l)")).contains("set_variable_multiply_vector"));
    }

    @Test
    void whileReadsLeftSideBeforeCall() {
        String inc = "game c\nfunc inc() {\nc += 1\nreturn c\n}\n";
        Map<String, Object> loop = Json.object(ops(inc + "when player_join {\nwhile c == inc() {\nsend_message(\"x\")\n}\n}").get(0));
        assertEquals("repeat_forever", loop.get("action"));
        Map<String, Object> first = Json.object(Json.array(loop.get("operations")).get(0));
        assertEquals("set_variable_value", first.get("action"));
        assertTrue(Json.write(first).contains("__xero"), Json.write(first));
        String both = Json.write(ops(inc + "when player_join {\nwhile c == inc() or c < 2 {\nsend_message(\"x\")\n}\n}"));
        assertTrue(both.indexOf("__xero") < both.indexOf("call_function"), both);
    }

    @Test
    void whileWithoutCallStaysOneBlock() {
        assertEquals("repeat_while", Json.object(body("x = 1\nwhile x < 3 {\nx += 1\nwait(1)\n}").get(1)).get("action"));
    }

    @Test
    void pinnedScopeHasItsOwnKind() {
        String w = wire("game t\nwhen player_join {\nt = 1\nlocal.t = vec(1, 0, 0)\nr = local.t * 2\nsend_message(\"${r}\")\n}");
        assertTrue(w.contains("set_variable_multiply_vector"), w);
        assertFalse(w.contains("%math"), w);
    }

    @Test
    void pinnedVariableChangingKindIsUnknown() {
        String text = "func f(size) {\nlocal.size = line.size\nh = local.size * 2\nlocal.size = vec(local.size, local.size, local.size)\nsend_message(\"${h}\")\nset_display_scale(local.size)\n}\nwhen player_join {\nf(2)\n}";
        assertFalse(warned(text, "вектор, а здесь"));
        assertTrue(wire(text).contains("%math"));
    }

    @Test
    void otherScopeDoesNotEraseKind() {
        String w = wire("game t\nwhen player_join {\nt = vec(1, 0, 0)\nlocal.t = 1\nr = t * 2\nsend_message(\"${r}${local.t}\")\n}");
        assertTrue(w.contains("set_variable_multiply_vector"), w);
    }

    @Test
    void kindAndValueOnOneLine() {
        assertEquals(Json.write(body("line items: list\nitems = [1, 2]\nsend_message(\"${items}\")")),
                Json.write(body("line items: list = [1, 2]\nsend_message(\"${items}\")")));
        String text = "when player_join {\n    line items: list = [1, 2]\n}\n";
        assertEquals(text, Formatter.format(text).text());
    }

    @Test
    void wholeOtherWordIsNotTypo() {
        Diagnostic d = compile("when player_join {\np = lerp_location(loc(0, 0, 0), loc(1, 1, 1), 0.5)\n}").diagnostics().get(0);
        assertTrue(d.fixes() == null || d.fixes().isEmpty(), d.toString());
        Diagnostic typo = compile("when player_join {\nsend_mesage(\"x\")\n}").diagnostics().get(0);
        assertTrue(typo.hint().contains("send_message"), typo.toString());
        assertFalse(typo.fixes().isEmpty());
    }

    @Test
    void indexHintFitsTheName() {
        assertTrue(errors("const STEPS = 3\nwhen player_join {\ni = value.name\nx = STEPS[i]\n}").get(0).contains("постоянная"));
        Diagnostic local = compile("game k\nwhen player_join {\nds = value.name\nx = ds[k]\n}").diagnostics().stream().filter(Diagnostic::error).findFirst().orElseThrow();
        assertTrue(local.hint().contains("line ds: list"), local.toString());
        Diagnostic param = compile("func f(ds, i) {\nx = ds[i]\n}").diagnostics().stream().filter(Diagnostic::error).findFirst().orElseThrow();
        assertTrue(param.hint().contains("ds: array"), param.toString());
    }

    @Test
    void variableIsNotItemId() {
        Diagnostic d = compile("when player_join {\nkind = \"stone\"\ngive_items(item(kind))\n}").diagnostics().get(0);
        assertTrue(d.message().contains("переменная"), d.toString());
        assertTrue(d.fixes() == null || d.fixes().isEmpty(), d.toString());
    }

    @Test
    void coordinateAsValueExplains() {
        Diagnostic d = compile("when player_join {\nsend_message(\"${value.y}\")\n}").diagnostics().get(0);
        assertTrue(d.hint().contains("get_coordinate"), d.toString());
    }

    @Test
    void particleFieldsExplain() {
        Diagnostic d = compile("when player_join {\nn = 5\ndisplay_particle(particle(flame, count: n), value.location)\n}").diagnostics().get(0);
        assertTrue(d.hint().contains("set_particle_amount"), d.toString());
    }

    @Test
    void bareAttributeWarnsAndStillBuilds() {
        Diagnostic d = compile("when player_join {\nplayer.set_attribute(1, attribute_type: movement_speed)\n}").diagnostics().get(0);
        assertFalse(d.error());
        assertEquals("generic_movement_speed", d.fixes().get(0).text());
        assertFalse(warned("when player_join {\nplayer.set_attribute(1, attribute_type: generic_movement_speed)\n}", "собирается"));
        assertFalse(warned("when player_join {\nplayer.set_attribute(1, attribute_type: zombie_spawn_reinforcements)\n}", "собирается"));
    }

    @Test
    void parameterInNameIsLinePlaceholder() {
        Diagnostic d = compile("func f(who) {\ngame `a %var_local(who)` = 1\n}\nwhen player_join {\nf(\"x\")\n}").diagnostics().get(0);
        assertTrue(d.message().contains("%var_line(who)"), d.toString());
        assertEquals("`a %var_line(who)`", d.fixes().get(0).text());
        assertFalse(warned("func f(who) {\ngame `a %var_line(who)` = 1\n}\nwhen player_join {\nf(\"x\")\n}", "читает не параметр"));
        assertFalse(warned("func f(who) {\nother = who\nsave `a %var_local(other)` = 1\n}\nwhen player_join {\nf(\"x\")\n}", "читает не параметр"));
    }

    @Test
    void placeholderOfGameVariableWithParameterNameIsFine() {
        assertFalse(warned("game coins\nfunc f(coins) {\nsend_message(\"%var(coins) ${coins}\")\n}\nwhen player_join {\nf(1)\n}", "читает не параметр"));
    }

    @Test
    void parameterInTextIsLinePlaceholder() {
        assertTrue(warned("func f(who) {\nsend_message(\"%var_local(who)\")\n}\nwhen player_join {\nf(\"x\")\n}", "читает не параметр"));
        assertTrue(warned("func f(who) {\nsend_message(\"%var(who)\")\n}\nwhen player_join {\nf(\"x\")\n}", "читает не параметр"));
        assertFalse(warned("func f(who) {\nsend_message(\"${who} %var_line(who)\")\n}\nwhen player_join {\nf(\"x\")\n}", "читает не параметр"));
    }

    @Test
    void nameOfPlaceholderOnlyHasNothingToCompare() {
        assertFalse(warned("game `%var(key)`\nwhen player_join {\nkey = \"a\"\nother = \"b\"\n`%var_local(other)` = key\n}", "другим плейсхолдером"));
    }

    @Test
    void declaredNameWithOtherPlaceholderWarns() {
        assertTrue(warned("game `score %var_line(winner)`\nfunc f(person) {\n`score %var_line(person)` += 1\n}\nwhen player_join {\nf(\"x\")\n}", "другим плейсхолдером"));
        assertFalse(warned("game `score %var_line(person)`\nfunc f(person) {\n`score %var_line(person)` += 1\n}\nwhen player_join {\nf(\"x\")\n}", "другим плейсхолдером"));
    }

    @Test
    void declaredKindsParse() {
        assertEquals("game base: location\nsave m: map\nline v: vector\n", Formatter.format("game  base: location\nsave m:  map\nline v: vector\n").text());
        assertTrue(errors("game x: foo\n").get(0).contains("после : идёт вид"));
    }
}
