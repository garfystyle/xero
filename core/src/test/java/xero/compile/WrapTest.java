package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WrapTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);
    private static final String[] LINE_NAMES = {"p0", "p1", "l0", "l1"};
    private static final String[] LOCAL_NAMES = {"a", "b"};

    private enum Flow { NEXT, BREAK, CONTINUE, RETURN, END }

    private static List<Object> lines(String source) {
        Compiler.Result r = Compiler.compile(DATA, NAMES, source);
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        return Json.array(Wrap.fitted(r.module(), DATA).get("handlers"));
    }

    private static Map<String, Object> named(List<Object> handlers, String name) {
        for (Object raw : handlers) {
            Map<String, Object> h = Json.object(raw);
            if (name.equals(h.get("name")) || name.equals(h.get("event"))) return h;
        }
        throw new AssertionError("нет строки " + name);
    }

    private static String messages(int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append("    send_message(\"n").append(i).append("\")\n");
        return out.toString();
    }

    private static void allFit(List<Object> handlers) {
        for (Object h : handlers) assertTrue(Delivery.width(Json.object(h)) <= Wrap.WIDEST, () -> Json.write(h));
    }

    private static boolean mentions(Object node, String action) {
        return Json.write(node).contains("\"action\":\"" + action + "\"");
    }

    @Test
    void compilerKeepsTheLineAsWritten() {
        Compiler.Result r = Compiler.compile(DATA, NAMES, "when player_jump {\n" + messages(200) + "}\n");
        List<Object> handlers = Json.array(r.module().get("handlers"));
        assertEquals(1, handlers.size());
        assertEquals(200, Delivery.width(Json.object(handlers.get(0))));
    }

    @Test
    void lineAtTheLimitStaysWhole() {
        assertEquals(1, lines("when player_jump {\n" + messages(Wrap.WIDEST) + "}\n").size());
    }

    @Test
    void wideLineIsCarriedInOrder() {
        List<Object> handlers = lines("when player_jump {\n" + messages(200) + "}\n");
        allFit(handlers);
        StringBuilder order = new StringBuilder();
        collect(named(handlers, "player_jump"), handlers, order);
        for (int i = 0; i < 200; i++) assertTrue(order.indexOf("n" + i + ",") >= 0, "потерян n" + i);
        assertEquals(sequence(200), order.toString());
    }

    private static String sequence(int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append('n').append(i).append(',');
        return out.toString();
    }

    private static void collect(Map<String, Object> handler, List<Object> handlers, StringBuilder out) {
        for (Object raw : Json.array(handler.get("operations"))) {
            Map<String, Object> op = Json.object(raw);
            if ("call_function".equals(op.get("action"))) {
                String name = String.valueOf(Json.object(cell(op, "function_name")).get("text"));
                collect(named(handlers, name), handlers, out);
            } else {
                String said = Json.write(cell(op, "messages"));
                int from = said.indexOf('n', said.indexOf("\"text\":\"") + 8);
                out.append(said, from, said.indexOf('"', from)).append(',');
            }
        }
    }

    private static Object cell(Map<String, Object> op, String name) {
        for (Object raw : Json.array(op.get("values"))) {
            Map<String, Object> entry = Json.object(raw);
            if (name.equals(entry.get("name"))) return entry.get("value");
        }
        throw new AssertionError("нет аргумента " + name + " в " + op);
    }

    @Test
    void newLinesAreHiddenFunctionsAfterTheProject() {
        List<Object> handlers = lines("when player_jump {\n" + messages(100) + "}\nfunc other() {\n    send_message(\"x\")\n}\n");
        Map<String, Object> carried = named(handlers, "player_jump.1");
        assertEquals("function", carried.get("type"));
        assertTrue(Json.write(carried.get("values")).contains("\"is_hidden\""));
        assertTrue(Json.write(carried.get("values")).contains("\"TRUE\""));
        Set<String> positions = new HashSet<>();
        for (Object h : handlers) assertTrue(positions.add(String.valueOf(Json.object(h).get("position"))));
    }

    @Test
    void twoEventsOfOneKindGetDifferentNames() {
        List<Object> handlers = lines("when player_jump {\n" + messages(100) + "}\nwhen player_jump {\n" + messages(100) + "}\n");
        Set<String> names = new HashSet<>();
        for (Object h : handlers) {
            Object name = Json.object(h).get("name");
            if (name != null) assertTrue(names.add(String.valueOf(name)), () -> "имя занято дважды: " + name);
        }
        assertEquals(2, names.size());
    }

    @Test
    void ownFunctionNameIsNotTaken() {
        List<Object> wide = new ArrayList<>();
        for (int i = 0; i < 100; i++) wide.add(Map.of("action", "control_wait", "values", List.of()));
        Map<String, Object> own = Context.obj("type", "function", "name", "f.1", "position", 0, "operations", List.of());
        Map<String, Object> line = Context.obj("type", "function", "name", "f", "position", 1, "operations", wide);
        String module = Wrap.fitted(Json.write(Map.of("handlers", List.of(own, line))), DATA);
        List<Object> handlers = Json.array(Json.object(Json.parse(module)).get("handlers"));
        assertEquals(1, handlers.stream().filter(h -> "f.1".equals(Json.object(h).get("name"))).count());
        named(handlers, "f.2");
    }

    @Test
    void returnStaysInItsFunction() {
        String source = "func f(x) {\n    if x == 1 {\n" + messages(40) + "        return\n    }\n" + messages(40) + "}\n";
        List<Object> handlers = lines(source);
        allFit(handlers);
        for (Object h : handlers)
            if (!"f".equals(Json.object(h).get("name"))) assertFalse(mentions(h, "control_return_function"));
        assertTrue(mentions(named(handlers, "f"), "control_return_function"));
    }

    @Test
    void breakAndContinueStayInTheirLoop() {
        String source = "when player_jump {\n    repeat 3 {\n" + messages(40) + "        if value.name == \"a\" {\n            break\n        }\n"
                + messages(40) + "        if value.name == \"b\" {\n            continue\n        }\n" + messages(5) + "    }\n}\n";
        List<Object> handlers = lines(source);
        allFit(handlers);
        for (Object h : handlers) {
            if ("player_jump".equals(Json.object(h).get("event"))) continue;
            assertFalse(mentions(h, "control_stop_repeat"));
            assertFalse(mentions(h, "control_skip_iteration"));
        }
    }

    @Test
    void wholeLoopMovesWithItsBreak() {
        String source = "when player_jump {\n" + messages(60) + "    repeat 3 {\n        if value.name == \"a\" {\n            break\n        }\n"
                + messages(5) + "    }\n" + messages(5) + "}\n";
        allFit(lines(source));
    }

    @Test
    void elseStaysWithItsIf() {
        String source = "when player_jump {\n" + messages(50) + "    if value.name == \"a\" {\n" + messages(20) + "    } else {\n" + messages(20) + "    }\n" + messages(50) + "}\n";
        List<Object> handlers = lines(source);
        allFit(handlers);
        for (Object h : handlers) adjacent(Json.array(Json.object(h).get("operations")));
    }

    private static void adjacent(List<Object> ops) {
        for (int i = 0; i < ops.size(); i++) {
            Map<String, Object> op = Json.object(ops.get(i));
            if ("else".equals(op.get("action"))) {
                assertTrue(i > 0, "else без if");
                String before = String.valueOf(Json.object(ops.get(i - 1)).get("action"));
                assertTrue(before.startsWith("if_") || before.equals("else"), () -> "перед else стоит " + before);
            }
            if (op.get("operations") instanceof List<?>) adjacent(Json.array(op.get("operations")));
        }
    }

    @Test
    void parameterReachesTheCarriedPart() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 100; i++) body.append("    send_message(\"${who} ").append(i).append("\")\n");
        List<Object> handlers = lines("func f(who) {\n" + body + "}\n");
        allFit(handlers);
        Map<String, Object> carried = named(handlers, "f.1");
        assertTrue(Json.write(carried.get("values")).contains("\"who\""));
        assertTrue(Json.write(named(handlers, "f").get("operations")).contains("\\\"who\\\""));
    }

    @Test
    void lineThatCannotBeCarriedWarns() {
        StringBuilder source = new StringBuilder("func f() {\n");
        for (int i = 0; i < 40; i++) source.append("    if value.name == \"a\" {\n        return\n    }\n");
        source.append("}\n");
        Compiler.Result r = Compiler.compile(DATA, NAMES, source.toString());
        assertTrue(r.ok());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.message().contains("перенести её нельзя")), r.diagnostics()::toString);
    }

    @Test
    void fittedLeavesGoodModuleAsItIs() {
        String module = "{\"handlers\":[{\"type\":\"event\",\"event\":\"player_jump\",\"position\":0,\"operations\":[{\"action\":\"control_wait\",\"values\":[]}]}]}";
        assertEquals(module, Wrap.fitted(module, DATA));
    }

    @Test
    void fittedCarriesAndStaysStable() {
        List<Object> ops = new ArrayList<>();
        for (int i = 0; i < 150; i++) ops.add(Map.of("action", "control_wait", "values", List.of()));
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "function");
        line.put("name", "f");
        line.put("position", 7);
        line.put("operations", ops);
        String module = Json.write(Map.of("handlers", List.of(line)));
        String once = Wrap.fitted(module, DATA);
        assertNotEquals(module, once);
        allFit(Json.array(Json.object(Json.parse(once)).get("handlers")));
        assertEquals(once, Wrap.fitted(once, DATA));
        assertTrue(once.contains("\"position\":8"));
    }

    @Test
    void lineWrittenAtTheEndIsCarriedWhenReadOnlyBefore() {
        List<Object> ops = new ArrayList<>();
        Map<String, Object> y = variable("y", "line");
        ops.add(Context.obj("action", "player_send_message", "values", new ArrayList<Object>(List.of(Context.entry("messages", y)))));
        for (int i = 0; i < 80; i++) {
            ops.add(Context.obj("action", "set_variable_value", "values",
                    new ArrayList<Object>(List.of(Context.entry("variable", y), Context.entry("value", number(i))))));
            ops.add(Context.obj("action", "player_send_message", "values", new ArrayList<Object>(List.of(Context.entry("messages", y)))));
        }
        Map<String, Object> handler = handler(ops);
        String source = Json.write(handler);
        Wrap wrap = new Wrap(DATA, Set.of());
        Map<String, Object> changed = Json.object(Json.parse(source));
        assertTrue(wrap.line(changed));
        assertTrue(Delivery.width(changed) <= Wrap.WIDEST);
        sameBehaviour(source, changed, wrap, "y в хвосте");
    }

    @Test
    void randomProgramsBehaveTheSame() {
        int[] counts = randomPrograms(80, 400);
        int carried = counts[0], stuck = counts[1], passed = counts[2];
        assertTrue(carried > stuck, carried + " перенесено, " + stuck + " не вышло");
        assertTrue(passed > carried, passed + " функций с параметрами на " + carried + " строк");
    }

    @Test
    void randomProgramsWritingLineVariablesBehaveTheSame() {
        int[] counts = randomPrograms(3, 400);
        assertTrue(counts[0] > 0, counts[0] + " перенесено, " + counts[1] + " не вышло");
    }

    private static void sameBehaviour(String source, Map<String, Object> changed, Wrap wrap, String what) {
        Machine was = new Machine(Map.of());
        was.start(Json.object(Json.parse(source)));
        Map<String, Map<String, Object>> functions = new HashMap<>();
        for (Map<String, Object> line : wrap.added()) functions.put(String.valueOf(line.get("name")), line);
        Machine now = new Machine(functions);
        now.start(changed);
        assertEquals(was.trace, now.trace, what);
        assertEquals(was.locals, now.locals, what);
    }

    private static int[] randomPrograms(int lineOdds, int seeds) {
        int carried = 0, stuck = 0, passed = 0;
        for (int seed = 0; seed < seeds; seed++) {
            Random random = new Random(seed);
            Program program = new Program(random, lineOdds);
            List<Object> ops = program.list(0, 0, 30 + random.nextInt(50));
            Map<String, Object> handler = handler(ops);
            String source = Json.write(handler);
            Wrap wrap = new Wrap(DATA, Set.of());
            Map<String, Object> changed = Json.object(Json.parse(source));
            boolean fits = wrap.line(changed);
            if (fits) {
                carried++;
                assertTrue(Delivery.width(changed) <= Wrap.WIDEST, "сид " + seed);
            } else {
                stuck++;
            }
            for (Map<String, Object> line : wrap.added()) {
                assertTrue(Delivery.width(line) <= Wrap.WIDEST, "сид " + seed);
                if (Json.write(line.get("values")).contains("parameters")) passed++;
            }
            sameBehaviour(source, changed, wrap, "сид " + seed + ", строчные 1/" + lineOdds);
        }
        return new int[] {carried, stuck, passed};
    }

    private static Map<String, Object> handler(List<Object> ops) {
        List<Object> declared = new ArrayList<>();
        for (String name : List.of("p0", "p1")) declared.add(Context.obj("type", "parameter", "name", name));
        List<Object> values = new ArrayList<>();
        values.add(Context.entry("parameters", Context.array(declared)));
        return Context.obj("type", "function", "name", "f", "values", values, "position", 0, "operations", ops);
    }

    private static Map<String, Object> variable(String name, String scope) {
        return Context.obj("type", "variable", "variable", name, "scope", scope);
    }

    private static Map<String, Object> number(int value) {
        return Context.obj("type", "number", "number", new Json.Num(String.valueOf(value)));
    }

    private static final class Program {
        private final Random random;
        private final int lineOdds;
        private int temps;

        Program(Random random, int lineOdds) {
            this.random = random;
            this.lineOdds = lineOdds;
        }

        private Map<String, Object> anyVariable() {
            return random.nextBoolean() ? variable(LINE_NAMES[random.nextInt(LINE_NAMES.length)], "line")
                    : variable(LOCAL_NAMES[random.nextInt(LOCAL_NAMES.length)], "local");
        }

        private Map<String, Object> target() {
            return random.nextInt(lineOdds) == 0 ? variable(LINE_NAMES[random.nextInt(LINE_NAMES.length)], "line")
                    : variable(LOCAL_NAMES[random.nextInt(LOCAL_NAMES.length)], "local");
        }

        private Map<String, Object> set(Object target, Object value) {
            return Context.obj("action", "set_variable_value", "values",
                    new ArrayList<Object>(List.of(Context.entry("variable", target), Context.entry("value", value))));
        }

        private Map<String, Object> print(Object value) {
            return Context.obj("action", "player_send_message", "values", new ArrayList<Object>(List.of(Context.entry("messages", value))));
        }

        private Map<String, Object> plain(String action) {
            return Context.obj("action", action, "values", new ArrayList<Object>());
        }

        private Map<String, Object> container(String action, List<Object> values, List<Object> body) {
            return Context.obj("action", action, "values", values, "operations", body);
        }

        List<Object> list(int depth, int loops, int size) {
            List<Object> out = new ArrayList<>();
            while (out.size() < size) add(out, depth, loops);
            return out;
        }

        private void add(List<Object> out, int depth, int loops) {
            if (loops > 0 && random.nextInt(150) == 0) {
                out.add(plain(random.nextBoolean() ? "control_stop_repeat" : "control_skip_iteration"));
                return;
            }
            if (random.nextInt(400) == 0) {
                out.add(plain(random.nextBoolean() ? "control_return_function" : "control_end_thread"));
                return;
            }
            int roll = random.nextInt(100);
            if (roll < 30) {
                out.add(set(target(), random.nextBoolean() ? number(random.nextInt(5)) : anyVariable()));
            } else if (roll < 55) {
                out.add(print(anyVariable()));
            } else if (roll < 65) {
                Map<String, Object> temp = variable(Wire.TEMP + (++temps), "line");
                out.add(set(temp, anyVariable()));
                out.add(print(temp));
            } else if (roll < 80 && depth < 3) {
                List<Object> values = new ArrayList<>(List.of(Context.entry("value", anyVariable()), Context.entry("compare", number(random.nextInt(5)))));
                out.add(container("if_variable_equals", values, list(depth + 1, loops, random.nextInt(depth == 0 ? 40 : 8))));
                if (random.nextBoolean()) out.add(container("else", new ArrayList<>(), list(depth + 1, loops, random.nextInt(depth == 0 ? 40 : 8))));
            } else if (roll < 90 && depth < 3) {
                List<Object> values = new ArrayList<>(List.of(Context.entry("amount", number(1 + random.nextInt(3)))));
                out.add(container("repeat_multi_times", values, list(depth + 1, loops + 1, random.nextInt(depth == 0 ? 50 : 8))));
            } else {
                out.add(print(number(random.nextInt(9))));
            }
        }
    }

    private static final class Machine {
        final List<String> trace = new ArrayList<>();
        final Map<String, String> locals = new HashMap<>();
        private final Map<String, Map<String, Object>> functions;

        Machine(Map<String, Map<String, Object>> functions) {
            this.functions = functions;
        }

        void start(Map<String, Object> handler) {
            Map<String, String> frame = new HashMap<>();
            frame.put("p0", "1");
            frame.put("p1", "2");
            run(Json.array(handler.get("operations")), frame);
        }

        private String read(Object raw, Map<String, String> frame) {
            Map<String, Object> value = Json.object(raw);
            if ("number".equals(value.get("type"))) return String.valueOf(value.get("number"));
            String name = String.valueOf(value.get("variable"));
            return ("line".equals(value.get("scope")) ? frame : locals).get(name);
        }

        private void write(Object raw, String value, Map<String, String> frame) {
            Map<String, Object> target = Json.object(raw);
            Map<String, String> scope = "line".equals(target.get("scope")) ? frame : locals;
            scope.put(String.valueOf(target.get("variable")), value);
        }

        private Flow run(List<Object> ops, Map<String, String> frame) {
            boolean matched = false;
            for (Object raw : ops) {
                Map<String, Object> op = Json.object(raw);
                String id = String.valueOf(op.get("action"));
                Flow flow = Flow.NEXT;
                switch (id) {
                    case "set_variable_value" -> write(cell(op, "variable"), read(cell(op, "value"), frame), frame);
                    case "player_send_message" -> trace.add(String.valueOf(read(cell(op, "messages"), frame)));
                    case "control_stop_repeat" -> flow = Flow.BREAK;
                    case "control_skip_iteration" -> flow = Flow.CONTINUE;
                    case "control_return_function" -> flow = Flow.RETURN;
                    case "control_end_thread" -> flow = Flow.END;
                    case "if_variable_equals" -> {
                        matched = String.valueOf(read(cell(op, "value"), frame)).equals(String.valueOf(read(cell(op, "compare"), frame)));
                        if (matched) flow = run(Json.array(op.get("operations")), frame);
                    }
                    case "else" -> {
                        if (!matched) flow = run(Json.array(op.get("operations")), frame);
                    }
                    case "repeat_multi_times" -> {
                        int times = Integer.parseInt(read(cell(op, "amount"), frame));
                        for (int i = 0; i < times; i++) {
                            Flow inner = run(Json.array(op.get("operations")), frame);
                            if (inner == Flow.BREAK) break;
                            if (inner == Flow.RETURN || inner == Flow.END) {
                                flow = inner;
                                break;
                            }
                        }
                    }
                    case "call_function" -> flow = call(op, frame);
                    default -> throw new AssertionError("неизвестный блок " + id);
                }
                if (flow != Flow.NEXT) return flow;
            }
            return Flow.NEXT;
        }

        private Flow call(Map<String, Object> op, Map<String, String> frame) {
            String name = String.valueOf(Json.object(cell(op, "function_name")).get("text"));
            Map<String, String> inner = new HashMap<>();
            for (Object rawEntry : Json.array(op.get("values"))) {
                Map<String, Object> entry = Json.object(rawEntry);
                if (!"args".equals(entry.get("name"))) continue;
                Map<String, Object> args = Json.object(Json.object(entry.get("value")).get("values"));
                for (Map.Entry<String, Object> arg : args.entrySet()) {
                    String key = String.valueOf(Json.object(Json.parse(arg.getKey())).get("text"));
                    inner.put(key, read(arg.getValue(), frame));
                }
            }
            Flow flow = run(Json.array(functions.get(name).get("operations")), inner);
            assertNotEquals(Flow.BREAK, flow, "break вышел из функции");
            assertNotEquals(Flow.CONTINUE, flow, "continue вышел из функции");
            assertNotEquals(Flow.END, flow, "остановка потока ушла в функцию");
            return Flow.NEXT;
        }
    }
}
