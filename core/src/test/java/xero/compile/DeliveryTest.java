package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private static Compiler.Result compile(String text) {
        return Compiler.compile(DATA, NAMES, text);
    }

    private static Map<String, Object> module(String text) {
        Compiler.Result r = compile(text);
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        return Json.object(Json.parse(Json.write(r.module())));
    }

    private static List<Integer> positions(Map<String, Object> module) {
        List<Integer> out = new ArrayList<>();
        for (Object h : Json.array(module.get("handlers"))) out.add(((Json.Num) Json.object(h).get("position")).intValue());
        return out;
    }

    private static List<String> errors(String text) {
        return compile(text).diagnostics().stream().filter(Diagnostic::error).map(Diagnostic::message).toList();
    }

    @Test
    void linesGoInOrderWithoutMarks() {
        assertEquals(List.of(0, 1, 2), positions(module("""
                when player_join { }
                func a() { }
                process b() { }
                """)));
    }

    @Test
    void lineMarkLeavesGapsAndNextFollows() {
        assertEquals(List.of(0, 5, 6, 30), positions(module("""
                when world_start { }
                @line(5)
                when player_join { }
                func a() { }
                @line(30)
                @hidden
                process b() { }
                """)));
    }

    @Test
    void decompilerMarksOnlyJumps() {
        String world = """
                {"handlers":[
                {"type":"event","event":"player_join","position":1,"operations":[]},
                {"type":"event","event":"world_start","position":0,"operations":[]},
                {"type":"function","name":"a","position":7,"values":[{"name":"is_hidden","value":{"type":"enum","enum":"FALSE"}}],"operations":[]},
                {"type":"function","name":"b","position":8,"values":[{"name":"is_hidden","value":{"type":"enum","enum":"TRUE"}}],"operations":[]},
                {"type":"event","event":"player_quit","position":23,"operations":[]}
                ]}""";
        Map<String, Object> module = Json.object(Json.parse(world));
        String text = Decompiler.decompile(DATA, NAMES, module).text();
        assertEquals("""
                when world_start {}

                when player_join {}

                @line(7)
                func a() {}

                @hidden
                func b() {}

                @line(23)
                when player_quit {}
                """, text);
        assertEquals(List.of(0, 1, 7, 8, 23), positions(module(text)));
    }

    @Test
    void lineMarkMistakes() {
        assertEquals(List.of("строка 0 уже позади: номера строк идут по порядку, предыдущая — 0"),
                errors("when world_start { }\n@line(0)\nwhen player_join { }"));
        assertEquals(List.of("@line(номер): номер строки — целое число от 0"), errors("@line(x)\nwhen player_join { }"));
        assertEquals(List.of("@line(номер): номер строки — целое число от 0"), errors("@line(1.5)\nwhen player_join { }"));
        assertEquals(List.of("пометка @line повторяется"), errors("@line(2)\n@line(3)\nfunc a() { }"));
        assertEquals(List.of("у события бывают только пометки @line и @custom_data"), errors("@hidden\nwhen player_join { }"));
        assertTrue(errors("@line(3)\nfunc a() { }").isEmpty());
    }

    @Test
    void valuesMarkStillStandsAloneWithLine() {
        assertTrue(errors("@line(4)\n@values(\"[{\\\"name\\\":\\\"is_hidden\\\",\\\"value\\\":{\\\"type\\\":\\\"enum\\\",\\\"enum\\\":\\\"FALSE\\\"}}]\")\nfunc a() { }").isEmpty());
    }

    private static final String WORLD = """
            when world_start { send_message("старт") }
            when player_join { send_message("привет") }
            when player_join { send_message("ещё привет") }
            func greet() { send_message("hi") }
            process loop() { }
            """;

    @Test
    void planSeesSameChangedAddedMissing() {
        List<Delivery.Line> world = Delivery.lines(module(WORLD));
        List<Delivery.Line> project = Delivery.lines(module("""
                when world_start { send_message("старт") }
                when player_join { send_message("ещё привет") }
                when player_join { send_message("привет") }
                func greet() { send_message("hello") }
                func fresh() { }
                """));
        Delivery.Plan plan = Delivery.plan(DATA, world, project);
        assertEquals(List.of("event:world_start"), plan.same().stream().map(Delivery.Line::key).toList());
        assertEquals(List.of("event:player_join", "event:player_join", "function:greet"),
                plan.changed().stream().map(Delivery.Line::key).toList());
        assertEquals(List.of("function:fresh"), plan.added().stream().map(Delivery.Line::key).toList());
        assertEquals(List.of("process:loop"), plan.missing().stream().map(Delivery.Line::key).toList());
        assertEquals("процесс «loop» (строка 4)", plan.missing().get(0).title());
    }

    @Test
    void planIgnoresValueOrderAndNumberSpelling() {
        String a = """
                {"handlers":[{"type":"event","event":"world_start","position":0,"operations":[
                {"action":"set_variable_value","values":[
                {"name":"value","value":{"type":"number","number":1.0}},
                {"name":"variable","value":{"type":"variable","variable":"x","scope":"game"}}]}]}]}""";
        String b = """
                {"handlers":[{"type":"event","event":"world_start","position":0,"operations":[
                {"action":"set_variable_value","values":[
                {"name":"variable","value":{"type":"variable","variable":"x","scope":"game"}},
                {"name":"value","value":{"type":"number","number":1}}]}]}]}""";
        Delivery.Plan plan = Delivery.plan(DATA, Delivery.lines(Json.object(Json.parse(a))), Delivery.lines(Json.object(Json.parse(b))));
        assertEquals(1, plan.same().size());
        assertTrue(plan.changed().isEmpty());
    }

    @Test
    void movedLineIsChanged() {
        List<Delivery.Line> world = Delivery.lines(module("when world_start { }"));
        List<Delivery.Line> project = Delivery.lines(module("@line(3)\nwhen world_start { }"));
        Delivery.Plan plan = Delivery.plan(DATA, world, project);
        assertEquals(1, plan.changed().size());
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void wholeWorldFromDecompilerIsSame() {
        Map<String, Object> world = module(WORLD.replace("process loop", "@line(9)\nprocess loop"));
        String text = Decompiler.decompile(DATA, NAMES, world).text();
        Delivery.Plan plan = Delivery.plan(DATA, Delivery.lines(world), Delivery.lines(module(text)));
        assertEquals(5, plan.same().size());
        assertTrue(plan.changed().isEmpty() && plan.added().isEmpty() && plan.missing().isEmpty());
    }

    @Test
    void clashesDoubleFunctionsButNotEvents() {
        List<Delivery.Line> world = Delivery.lines(module(WORLD));
        List<Delivery.Clash> clashes = Delivery.clashes(world, Delivery.lines(module("""
                when player_join { }
                when player_quit { }
                func greet() { }
                func other() { }
                process loop() { }
                """)));
        assertEquals(List.of("event:player_join", "function:greet", "process:loop"),
                clashes.stream().map(c -> c.project().key()).toList());
        assertFalse(clashes.get(0).doubled());
        assertTrue(clashes.get(1).doubled() && clashes.get(2).doubled());
    }

    @Test
    void oddHandlersAreKeptAsLines() {
        Map<String, Object> world = Json.object(Json.parse("""
                {"handlers":[{"type":"mystery","position":2},{"type":"event","event":"world_start","position":0,"operations":[]}]}"""));
        List<Delivery.Line> lines = Delivery.lines(world);
        assertEquals(List.of(Delivery.Kind.EVENT, Delivery.Kind.OTHER), lines.stream().map(Delivery.Line::kind).toList());
        Delivery.Plan plan = Delivery.plan(DATA, lines, Delivery.lines(module("when world_start { }")));
        assertEquals(1, plan.missing().size());
    }
}
