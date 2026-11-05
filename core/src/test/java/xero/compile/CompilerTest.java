package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilerTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    static Compiler.Result compile(String text) {
        return Compiler.compile(DATA, NAMES, text);
    }

    static List<Object> handlers(String text) {
        Compiler.Result r = compile(text);
        assertTrue(r.ok(), () -> "ошибки: " + r.diagnostics());
        return Json.array(r.module().get("handlers"));
    }

    static List<Object> ops(String body) {
        return Json.array(Json.object(handlers("when world_start {\n" + body + "\n}").get(0)).get("operations"));
    }

    static String one(String body) {
        return Json.write(ops(body).get(0));
    }

    static List<String> errors(String text) {
        return compile(text).diagnostics().stream().filter(Diagnostic::error).map(Diagnostic::message).toList();
    }

    @Test
    void eventHandlerShape() {
        assertEquals("{\"type\":\"event\",\"event\":\"player_join\",\"position\":0,\"operations\":[]}",
                Json.write(handlers("when player_join {}").get(0)));
    }

    @Test
    void messageWithTarget() {
        assertEquals("{\"action\":\"player_send_message\",\"values\":[{\"name\":\"messages\",\"value\":"
                        + "{\"type\":\"array\",\"values\":[{\"type\":\"text\",\"text\":\"hi\",\"parsing\":\"legacy\"}]}}],"
                        + "\"selection\":{\"type\":\"all_players\"}}",
                one("@all send_message(\"hi\")"));
    }

    @Test
    void textParsings() {
        assertTrue(one("send_message(mm\"<red>a\")").contains("\"parsing\":\"minimessage\""));
        assertTrue(one("send_message(plain\"a\")").contains("\"parsing\":\"plain\""));
        assertTrue(one("send_message(\"a\\\"b\\\\n\")").contains("\"text\":\"a\\\"b\\\\n\""));
    }

    @Test
    void variablesKeepTheirScope() {
        String s = Json.write(ops("save coins = 5\ncoins += 1"));
        assertTrue(s.contains("{\"action\":\"set_variable_value\",\"values\":[{\"name\":\"variable\",\"value\":"
                + "{\"type\":\"variable\",\"variable\":\"coins\",\"scope\":\"save\"}},{\"name\":\"value\",\"value\":"
                + "{\"type\":\"number\",\"number\":5.0}}]}"), s);
        assertTrue(s.contains("\"action\":\"set_variable_add\""), s);
        assertTrue(s.contains("{\"type\":\"array\",\"values\":[{\"type\":\"variable\",\"variable\":\"coins\","
                + "\"scope\":\"save\"},{\"type\":\"number\",\"number\":1.0}]}"), s);
    }

    @Test
    void assignFromAction() {
        assertEquals("{\"action\":\"set_variable_get_list_value\",\"values\":["
                        + "{\"name\":\"variable\",\"value\":{\"type\":\"variable\",\"variable\":\"x\",\"scope\":\"local\"}},"
                        + "{\"name\":\"list\",\"value\":{\"type\":\"array\",\"values\":[{\"type\":\"number\",\"number\":1.0}]}},"
                        + "{\"name\":\"number\",\"value\":{\"type\":\"number\",\"number\":1.0}}]}",
                one("x = get_list_value([1], 1)"));
    }

    @Test
    void ifElseChain() {
        List<Object> ops = ops("x = 1\nif x == 1 {\n} else if not x > 2 {\n} else {\n}");
        assertEquals(4, ops.size());
        Map<String, Object> first = Json.object(ops.get(1));
        assertEquals("if_variable_equals", first.get("action"));
        assertFalse(first.containsKey("is_inverted"));
        Map<String, Object> second = Json.object(ops.get(2));
        assertEquals("else", second.get("action"));
        assertEquals(Map.of("action", "if_variable_greater", "is_inverted", true), second.get("conditional"));
        assertEquals("{\"action\":\"else\",\"values\":[],\"operations\":[]}", Json.write(ops.get(3)));
    }

    @Test
    void notEqualsIsInvertedEquals() {
        Map<String, Object> op = Json.object(ops("x = 1\nif x != 2 {}").get(1));
        assertEquals("if_variable_equals", op.get("action"));
        assertEquals(true, op.get("is_inverted"));
    }

    @Test
    void conditionWithTarget() {
        Map<String, Object> op = Json.object(ops("if @victim has_item(item(bread)) {}").get(0));
        assertEquals("if_player_has_item", op.get("action"));
        assertEquals(Map.of("type", "victim_player"), op.get("selection"));
    }

    @Test
    void containersAndLoops() {
        String s = one("repeat.multi_times(i, 3) {\nwait(1, time_unit: seconds)\nbreak\n}");
        assertTrue(s.startsWith("{\"action\":\"repeat_multi_times\""), s);
        assertTrue(s.contains("{\"name\":\"time_unit\",\"value\":{\"type\":\"enum\",\"enum\":\"SECONDS\"}}"), s);
        assertTrue(s.contains("\"action\":\"control_stop_repeat\""), s);
    }

    @Test
    void whileTakesCondition() {
        Map<String, Object> op = Json.object(ops("x = 1\nrepeat.while(x < 5) {\nx += 1\n}").get(1));
        assertEquals("repeat_while", op.get("action"));
        assertEquals(Map.of("action", "if_variable_less", "is_inverted", false), op.get("conditional"));
        assertEquals(1, Json.array(op.get("operations")).size());
    }

    @Test
    void craftingGridKeepsEmptyCells() {
        String s = one("add_custom_crafting_recipe(\"r\", item(diamond_sword), [_, item(emerald), _], recipe_type: shaped)");
        assertTrue(s.contains("{\"type\":\"item\",\"item\":\"AAAAAAAAAAA=\"},{\"type\":\"item\",\"item\":\"H4sI"), s);
        assertTrue(s.contains("{\"name\":\"recipe_type\",\"value\":{\"type\":\"enum\",\"enum\":\"SHAPED\"}}"), s);
    }

    @Test
    void plainListHolesAreEmptyCells() {
        assertTrue(one("send_message([\"a\", _, \"b\"])").contains("{\"type\":\"text\",\"text\":\"a\",\"parsing\":\"legacy\"},{},"));
    }

    @Test
    void valueKindsLikeServer() {
        String s = Json.write(ops("""
                x = loc(1, 2, 3)
                x = vec(0, -1, 0.5)
                x = block(stone)
                x = sound(entity.player.levelup, pitch: 2)
                x = particle(flame, count: 3)
                x = potion(speed, amplifier: 1, duration: 600)
                x = @killer value.current_health
                x = {"k": "v"}
                x = [1, _, _]
                """));
        assertTrue(s.contains("{\"type\":\"location\",\"x\":1.0,\"y\":2.0,\"z\":3.0,\"yaw\":0.0,\"pitch\":0.0}"), s);
        assertTrue(s.contains("{\"type\":\"vector\",\"x\":0.0,\"y\":-1.0,\"z\":0.5}"), s);
        assertTrue(s.contains("{\"type\":\"block\",\"block\":\"minecraft:stone\"}"), s);
        assertTrue(s.contains("{\"type\":\"sound\",\"sound\":\"minecraft:entity.player.levelup\",\"pitch\":2.0,"
                + "\"volume\":1.0,\"variation\":\"\",\"source\":\"MASTER\"}"), s);
        assertTrue(s.contains("{\"type\":\"particle\",\"particle_type\":\"FLAME\",\"count\":3,\"first_spread\":0.0,"
                + "\"second_spread\":0.0,\"x_motion\":0.0,\"y_motion\":0.0,\"z_motion\":0.0}"), s);
        assertTrue(s.contains("{\"type\":\"potion\",\"potion\":\"minecraft:speed\",\"duration\":600,\"amplifier\":1}"), s);
        assertTrue(s.contains("{\"type\":\"game_value\",\"game_value\":\"current_health\","
                + "\"selection\":\"{\\\"type\\\":\\\"killer_entity\\\"}\"}"), s);
        assertTrue(s.contains("{\"type\":\"map\",\"values\":{\"{\\\"type\\\":\\\"text\\\",\\\"text\\\":\\\"k\\\","
                + "\\\"parsing\\\":\\\"legacy\\\"}\":{\"type\":\"text\",\"text\":\"v\",\"parsing\":\"legacy\"}}}"), s);
        assertTrue(s.contains("{\"type\":\"array\",\"values\":[{\"type\":\"number\",\"number\":1.0}]}"), s);
    }

    @Test
    void itemBytesMatchServer() throws IOException {
        String server = "H4sIAAAAAAAA/+NiYGBm4HZJLEkMSy0qzszPY2AQVudgYMpMYRDJzcxLTS5KTCuxyizKz4vPzEvPL2FmYE3OL80rYWBgYGQAAEUtuqc9AAAA";
        assertArrayEquals(gunzip(server), gunzip(Items.encode("minecraft:iron_ingot", 1)));
    }

    @Test
    void functionWithParameters() {
        List<Object> hs = handlers("func kit(amount: number = 1, who) {\nreturn\n}\nwhen player_join {\nkit(5, who: \"a\")\n}");
        Map<String, Object> f = Json.object(hs.get(0));
        assertEquals("function", f.get("type"));
        assertEquals("kit", f.get("name"));
        String values = Json.write(f.get("values"));
        assertTrue(values.contains("{\"type\":\"parameter\",\"type_key\":\"singular\",\"description\":"
                + "\"{\\\"translations\\\":{}}\",\"name\":\"amount\",\"value_type\":\"number\",\"is_required\":\"false\","
                + "\"default_value\":\"{\\\"type\\\":\\\"number\\\",\\\"number\\\":1.0}\",\"slot\":0,\"description_slot\":9}"), values);
        assertTrue(values.contains("\"name\":\"who\",\"value_type\":\"any\",\"is_required\":\"true\",\"default_value\":\"{}\","
                + "\"slot\":1,\"description_slot\":10"), values);
        assertTrue(values.endsWith("{\"name\":\"is_hidden\",\"value\":{\"type\":\"enum\",\"enum\":\"FALSE\"}}]"), values);
        String call = Json.write(Json.array(Json.object(hs.get(1)).get("operations")).get(0));
        assertTrue(call.contains("\"function_name\",\"value\":{\"type\":\"text\",\"text\":\"kit\",\"parsing\":\"legacy\"}"), call);
        assertTrue(call.contains("\"{\\\"type\\\":\\\"text\\\",\\\"text\\\":\\\"amount\\\",\\\"parsing\\\":\\\"plain\\\"}\":"
                + "{\"type\":\"number\",\"number\":5.0}"), call);
    }

    @Test
    void parametersAreLineVariablesInsideFunction() {
        String body = Json.write(Json.object(handlers("func f(a) {\nsend_message(a)\n}").get(0)).get("operations"));
        assertTrue(body.contains("{\"type\":\"variable\",\"variable\":\"a\",\"scope\":\"line\"}"), body);
    }

    @Test
    void processAndStart() {
        List<Object> hs = handlers("process timer() {\nrepeat.forever() {\nwait(1)\n}\n}\nwhen world_start {\nstart timer()\n}");
        assertEquals("process", Json.object(hs.get(0)).get("type"));
        assertTrue(Json.write(hs.get(1)).contains("\"action\":\"start_process\""));
    }

    @Test
    void quotedNamesAndWorldFunctions() {
        Compiler.Result r = compile("when world_start {\n`WE Pos Min Max`()\n}");
        assertTrue(r.ok(), r.diagnostics().toString());
        assertEquals(1, r.diagnostics().size());
        assertTrue(Json.write(r.module()).contains("\"text\":\"WE Pos Min Max\""));
    }

    @Test
    void positionsFollowFileOrder() {
        List<Object> hs = handlers("when player_join {}\nfunc a() {}\nwhen player_quit {}");
        for (int i = 0; i < hs.size(); i++) assertEquals(i, Json.object(hs.get(i)).get("position"));
    }

    @Test
    void typicalMistakes() {
        assertEquals(List.of("нет события «player_jion»"), errors("when player_jion {}"));
        assertEquals(List.of("нет действия «send_mesage»"), errors("when player_join {\nsend_mesage(\"a\")\n}"));
        assertTrue(errors("when player_join {\nteleport(loc(0,0,0))\n}").get(0).contains("player.teleport или entity.teleport"));
        assertEquals(List.of("переменная «scroe» нигде не задана"), errors("when player_join {\nsend_message(scroe)\n}"));
        assertEquals(List.of("у «time_unit» нет варианта «second»"), errors("when player_join {\nwait(1, time_unit: second)\n}"));
        assertEquals(List.of("break бывает только внутри повторения"), errors("when player_join {\nbreak\n}"));
        assertEquals(List.of("ожидалось when, func или process — код вне них не выполняется"), errors("send_message(\"a\")"));
        assertEquals(List.of("«has_item» — это условие, его пишут после if"), errors("when player_join {\nhas_item(item(stone))\n}"));
        assertEquals(List.of("«send_message» — это действие, а не условие"), errors("when player_join {\nif send_message(\"a\") {}\n}"));
    }

    @Test
    void manyErrorsAtOnce() {
        List<String> e = errors("when player_join {\nsend_mesage(\"a\")\nfoo(\nsend_message(x)\n}\nwhen nope {}");
        assertTrue(e.size() >= 3, e.toString());
    }

    @Test
    void unknownNameSuggestions() {
        Compiler.Result r = compile("when player_join {\nsave score = 0\nsend_message(scroe)\n}");
        assertEquals("возможно, вы имели в виду «score»", r.diagnostics().get(0).hint());
    }

    private static byte[] gunzip(String base64) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(base64)))) {
            return in.readAllBytes();
        }
    }

    @Test
    void markerWordFindsItsSetting() {
        String positional = Json.write(handlers("when player_join {\n    wait(1, seconds)\n}\n"));
        assertTrue(positional.contains("\"enum\":\"SECONDS\""), positional);
        String word = Json.write(handlers("when player_join {\n    wait(seconds, 1)\n}\n"));
        assertTrue(word.contains("\"enum\":\"SECONDS\"") && word.contains("\"number\":1.0"), word);
        String variable = Json.write(handlers("when player_join {\n    seconds = 3\n    wait(seconds)\n}\n"));
        assertTrue(variable.contains("\"variable\":\"seconds\"") && !variable.contains("\"enum\":\"SECONDS\""), variable);
    }
}
