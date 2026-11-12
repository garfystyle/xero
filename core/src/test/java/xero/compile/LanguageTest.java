package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private static Compiler.Result compile(String text) {
        return Compiler.compile(DATA, NAMES, text);
    }

    private static List<Object> ops(String body) {
        Compiler.Result r = compile("when world_start {\n" + body + "\n}");
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        return Json.array(Json.object(Json.array(r.module().get("handlers")).get(0)).get("operations"));
    }

    private static String json(String body) {
        return Json.write(ops(body));
    }

    private static final class Machine {
        final Map<String, Double> vars = new HashMap<>();
        final List<String> out = new ArrayList<>();
        int steps;

        void run(List<Object> ops) {
            boolean last = true;
            for (Object o : ops) {
                if (++steps > 10000) throw new IllegalStateException("слишком долго");
                Map<String, Object> op = Json.object(o);
                String action = (String) op.get("action");
                List<Object> values = Json.array(op.get("values"));
                switch (action) {
                    case "set_variable_value" -> vars.put(name(arg(values, "variable")), number(arg(values, "value")));
                    case "player_send_message" -> out.add(String.valueOf(
                            Json.object(Json.array(Json.object(arg(values, "messages")).get("values")).get(0)).get("text")));
                    case "else" -> {
                        if (last) continue;
                        boolean go = true;
                        if (op.get("conditional") instanceof Map<?, ?> c) {
                            go = test((String) c.get("action"), values) != Boolean.TRUE.equals(c.get("is_inverted"));
                        }
                        if (go) run(Json.array(op.get("operations")));
                        last = go;
                    }
                    default -> {
                        if (!action.startsWith("if_variable_")) throw new IllegalStateException(action);
                        boolean go = test(action, values) != Boolean.TRUE.equals(op.get("is_inverted"));
                        if (go) run(Json.array(op.get("operations")));
                        last = go;
                    }
                }
            }
        }

        boolean test(String action, List<Object> values) {
            if (action.equals(Conditions.PAIRS)) {
                List<Object> lefts = Json.array(Json.object(arg(values, "values")).get("values"));
                List<Object> rights = Json.array(Json.object(arg(values, "compares")).get("values"));
                boolean every = false;
                for (Object v : values)
                    if ("compare_type".equals(Json.object(v).get("name")))
                        every = "ALL".equals(Json.object(Json.object(v).get("value")).get("enum"));
                int same = 0;
                for (int i = 0; i < lefts.size(); i++) if (number(lefts.get(i)) == number(rights.get(i))) same++;
                return every ? same == lefts.size() : same > 0;
            }
            double v = number(arg(values, "value"));
            Object compare = arg(values, "compare");
            Map<String, Object> c = Json.object(compare);
            double w = "array".equals(c.get("type")) ? number(Json.array(c.get("values")).get(0)) : number(compare);
            if (action.equals("if_variable_equals") && "array".equals(c.get("type")))
                for (Object cell : Json.array(c.get("values"))) if (number(cell) == v) return true;
            return switch (action) {
                case "if_variable_equals" -> v == w;
                case "if_variable_greater" -> v > w;
                case "if_variable_less" -> v < w;
                default -> throw new IllegalStateException(action);
            };
        }

        double number(Object raw) {
            Map<String, Object> m = Json.object(raw);
            if ("variable".equals(m.get("type"))) return vars.getOrDefault(name(raw), 0.0);
            return Double.parseDouble(m.get("number").toString());
        }

        static String name(Object raw) {
            return (String) Json.object(raw).get("variable");
        }

        static Object arg(List<Object> values, String name) {
            for (Object v : values) if (name.equals(Json.object(v).get("name"))) return Json.object(v).get("value");
            throw new IllegalStateException("нет " + name);
        }
    }

    private interface Bool {
        boolean eval(boolean[] v);
        String text();
    }

    private static Bool leaf(int i) {
        String n = "abc".substring(i, i + 1);
        return new Bool() {
            public boolean eval(boolean[] v) { return v[i]; }
            public String text() { return n + " == 1"; }
        };
    }

    private static Bool random(Random r, int depth) {
        int kind = depth == 0 ? 0 : r.nextInt(4);
        if (kind == 0) {
            Bool l = leaf(r.nextInt(3));
            if (!r.nextBoolean()) return l;
            return new Bool() {
                public boolean eval(boolean[] v) { return !l.eval(v); }
                public String text() { return "not " + l.text(); }
            };
        }
        Bool a = random(r, depth - 1), b = random(r, depth - 1);
        if (kind == 1) return new Bool() {
            public boolean eval(boolean[] v) { return a.eval(v) && b.eval(v); }
            public String text() { return "(" + a.text() + " and " + b.text() + ")"; }
        };
        if (kind == 2) return new Bool() {
            public boolean eval(boolean[] v) { return a.eval(v) || b.eval(v); }
            public String text() { return "(" + a.text() + " or " + b.text() + ")"; }
        };
        return new Bool() {
            public boolean eval(boolean[] v) { return !(a.eval(v) && b.eval(v)); }
            public String text() { return "not (" + a.text() + " and " + b.text() + ")"; }
        };
    }

    @Test
    void andOrNotMatchTruthTables() {
        Random r = new Random(1610);
        List<String> failed = new ArrayList<>();
        for (int n = 0; n < 300 && failed.size() < 5; n++) {
            Bool e = random(r, 1 + r.nextInt(3));
            boolean withElse = r.nextBoolean();
            for (int mask = 0; mask < 8; mask++) {
                boolean[] v = {(mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0};
                String program = "a = " + (v[0] ? 1 : 0) + "\nb = " + (v[1] ? 1 : 0) + "\nc = " + (v[2] ? 1 : 0)
                        + "\nif " + e.text() + " {\nsend_message(\"T\")\n}" + (withElse ? " else {\nsend_message(\"F\")\n}" : "");
                Machine m = new Machine();
                m.run(ops(program));
                String expected = e.eval(v) ? "T" : withElse ? "F" : "";
                String got = String.join("", m.out);
                if (!expected.equals(got)) failed.add(e.text() + " при " + mask + ": ждали " + expected + ", вышло " + got);
            }
        }
        assertEquals(List.of(), failed);
    }

    @Test
    void elseIfChainsWithCompoundConditions() {
        String[] exprs = {"a == 1 and b == 1", "a == 1 or c == 1", "not (b == 1 or c == 1)"};
        for (int mask = 0; mask < 8; mask++) {
            boolean a = (mask & 1) != 0, b = (mask & 2) != 0, c = (mask & 4) != 0;
            String program = "a = " + (a ? 1 : 0) + "\nb = " + (b ? 1 : 0) + "\nc = " + (c ? 1 : 0)
                    + "\nif " + exprs[0] + " {\nsend_message(\"1\")\n} else if " + exprs[1] + " {\nsend_message(\"2\")\n}"
                    + " else if " + exprs[2] + " {\nsend_message(\"3\")\n} else {\nsend_message(\"4\")\n}";
            Machine m = new Machine();
            m.run(ops(program));
            String expected = a && b ? "1" : a || c ? "2" : !(b || c) ? "3" : "4";
            assertEquals(expected, String.join("", m.out), "маска " + mask);
        }
    }

    @Test
    void plainAndChainIsNestedWithoutFlags() {
        String s = json("a = 1\nb = 1\nif a == 1 and b == 1 and not a == 2 {\nsend_message(\"x\")\n}");
        assertTrue(!s.contains("__xero"), s);
        assertTrue(s.contains("\"is_inverted\":true"), s);
    }

    @Test
    void arithmeticBecomesOneMathBlock() {
        List<Object> o = ops("save coins = 1\nb = 2\nx = coins * 2 + b\ny = (coins + b) * 2\nz = coins - (b - 1)\nw = -coins + sqrt(b)");
        String s = Json.write(o);
        assertTrue(s.contains("\"number\":\"%math(coins*2+b)\""), s);
        assertTrue(s.contains("\"number\":\"%math((coins+b)*2)\""), s);
        assertTrue(s.contains("\"number\":\"%math(coins-(b-1))\""), s);
        assertTrue(s.contains("\"number\":\"%math(-coins+sqrt(b))\""), s);
        assertEquals(6, o.size());
    }

    @Test
    void constantsFold() {
        assertTrue(json("x = 2 * 3 + 1").contains("\"number\":7.0"));
        assertTrue(json("x = 1 / 0").contains("%math(1/0)"));
        assertEquals(List.of("деление на ноль — сервер молча даст 0"),
                compile("when world_start {\nx = 1 / 0\n@all send_message(\"${x}\")\n}").diagnostics().stream().map(Diagnostic::message).toList());
        Compiler.Result huge = compile("when world_start {\nx = " + "9".repeat(200) + " * " + "9".repeat(200) + "\n}");
        assertEquals(List.of("число слишком большое — в код мира его не записать"),
                huge.diagnostics().stream().map(Diagnostic::message).toList());
        assertTrue(!Json.write(huge.module()).contains("Infinity"));
    }

    @Test
    void onlyPlainAsciiNamesGoBareIntoMath() {
        String s = json("монеты = 2\npi = 3\nAbs = 1\nscore = 4\nx = монеты * 2 + pi + Abs + score");
        assertTrue(s.contains("\"number\":\"%math((%var_local(монеты))*2+(%var_local(pi))+(%var_local(Abs))+score)\""), s);
        String t = json("game счёт = 1\nsave E2 = 2\nsend_message(\"${счёт + E2}\")");
        assertTrue(t.contains("%math((%var(счёт))+E2)"), t);
    }

    @Test
    void oddNamesAndGameValuesInFormulas() {
        String s = json("save `монеты %player%` = 1\nx = `монеты %player%` + value.current_health");
        assertTrue(s.contains("%math((%var_save(монеты %player%))+%val(current_health))"), s);
    }

    @Test
    void mathFunctionsAndActionsShareNames() {
        String top = json("a = 5\nx = clamp(a, 0, 20)");
        assertTrue(top.contains("\"action\":\"set_variable_clamp\""), top);
        String inside = json("a = 5\nx = clamp(a, 0, 20) + 1");
        assertTrue(inside.contains("%math(clamp(a,0,20)+1)"), inside);
        String sqrt = json("a = 4\nx = sqrt(a)");
        assertTrue(sqrt.contains("\"action\":\"set_variable_value\"") && sqrt.contains("%math(sqrt(a))"), sqrt);
    }

    @Test
    void interpolation() {
        String s = json("save coins = 1\nline t = 2\nsend_message(\"Счёт: ${coins}, t=${t}, ${coins + 1}, ${value.current_health} \\${нет}\")");
        assertTrue(s.contains("Счёт: %var_save(coins), t=%var_line(t), %math(coins+1), %val(current_health) ${нет}"), s);
    }

    @Test
    void loops() {
        String s = json("""
                l = [1, 2]
                m = {"a": 1}
                repeat 3 {
                    break
                }
                for i in 1..10 step 2 {}
                for x in l {}
                for i, x in l {
                    continue
                }
                for k: v in m {}
                forever {}
                """);
        assertTrue(s.contains("{\"action\":\"repeat_multi_times\",\"values\":[{\"name\":\"amount\""), s);
        assertTrue(s.contains("\"action\":\"repeat_on_range\""), s);
        assertTrue(s.contains("\"name\":\"interval\""), s);
        assertTrue(s.contains("\"action\":\"repeat_for_each_in_list\",\"values\":[{\"name\":\"value_variable\""), s);
        assertTrue(s.contains("\"action\":\"repeat_for_each_in_list\",\"values\":[{\"name\":\"index_variable\""), s);
        assertTrue(s.contains("\"action\":\"repeat_for_each_map_entry\""), s);
        assertTrue(s.contains("\"action\":\"repeat_forever\""), s);
        assertTrue(s.contains("control_stop_repeat") && s.contains("control_skip_iteration"), s);
    }

    @Test
    void whileLoops() {
        String leaf = json("x = 0\nwhile x < 5 {\nx += 1\n}");
        assertTrue(leaf.contains("\"action\":\"repeat_while\"") && leaf.contains("\"conditional\":{\"action\":\"if_variable_less\""), leaf);
        String compound = json("x = 0\ny = 0\nwhile x < 5 and y == 0 {\nx += 1\n}");
        assertTrue(compound.contains("\"action\":\"repeat_forever\"") && compound.contains("control_stop_repeat"), compound);
    }

    @Test
    void decompilerPrintsLoopsBack() {
        String text = """
                local l
                local m

                when world_start {
                    repeat 3 {}
                    for i in 1..10 step 2 {}
                    for x in l {}
                    for i, x in l {}
                    for k: v in m {}
                    while i < 5 {}
                    forever {}
                    send_message("цена \\${x}")
                }
                """;
        Compiler.Result first = compile(text);
        assertTrue(first.ok(), first.diagnostics().toString());
        String printed = Decompiler.decompile(DATA, NAMES, first.module()).text();
        for (String line : List.of("repeat 3 {}", "for i in 1..10 step 2 {}", "for x in l {}", "for i, x in l {}",
                "for k: v in m {}", "while i < 5 {}", "forever {}", "send_message(\"цена \\${x}\")"))
            assertTrue(printed.contains(line), line + "\n" + printed);
        Compiler.Result second = compile(printed);
        assertEquals(Json.write(Json.parse(Json.write(first.module()))), Json.write(Json.parse(Json.write(second.module()))));
    }

    @Test
    void severalVariablesAtOnce() {
        String s = json("a, b, c = value.current_health, 2 * 3, \"x\"");
        assertTrue(s.startsWith("[{\"action\":\"set_variable_multiple\",\"values\":[{\"name\":\"variables\",\"value\":{\"type\":\"array\",\"values\":[{\"type\":\"variable\",\"variable\":\"a\""), s);
        assertTrue(s.contains("{\"type\":\"number\",\"number\":6.0}"), s);
        Compiler.Result bad = compile("when world_start {\na, b = 1\n}");
        assertTrue(bad.diagnostics().get(0).message().contains("слева 2 переменных, а справа 1"), bad.diagnostics().toString());
        Compiler.Result first = compile("when world_start {\na, b = 1, 2\nsend_message(\"${a}${b}\")\n}");
        String printed = Decompiler.decompile(DATA, NAMES, first.module()).text();
        assertTrue(printed.contains("a, b = 1, 2"), printed);
    }

    @Test
    void importsAcrossFiles() {
        Map<String, String> files = Map.of(
                "lib/kits.xero", "import \"../shared.xero\"\nfunc kit(n: number = 1) {\ngive_items(item(diamond), amount: n)\n}",
                "shared.xero", "save coins\nimport \"main.xero\"\nfunc pay() {\ncoins += 1\n}");
        Compiler.Result r = Compiler.compile(DATA, NAMES, "main.xero",
                "import \"lib/kits.xero\"\nwhen player_join {\nkit(2)\npay()\nsend_message(\"${coins}\")\n}", files::get);
        assertTrue(r.ok(), r.diagnostics().toString());
        List<Object> handlers = Json.array(r.module().get("handlers"));
        assertEquals(3, handlers.size());
        assertEquals("player_join", Json.object(handlers.get(0)).get("event"));
        assertEquals("kit", Json.object(handlers.get(1)).get("name"));
        assertEquals("pay", Json.object(handlers.get(2)).get("name"));
        Compiler.Result bad = Compiler.compile(DATA, NAMES, "main.xero", "import \"lib/kits.xero\"", Map.of(
                "lib/kits.xero", "when player_join {\nsend_mesage(\"x\")\n}")::get);
        Diagnostic d = bad.diagnostics().get(0);
        assertEquals("lib/kits.xero", d.file());
    }

    @Test
    void resolvingPaths() {
        assertEquals("lib/a.xero", Compiler.resolve("main.xero", "lib/a.xero"));
        assertEquals("shared.xero", Compiler.resolve("lib/kits.xero", "../shared.xero"));
        assertEquals("C:/p/lib/a.xero", Compiler.resolve("C:/p/main.xero", "./lib/a.xero"));
        assertEquals("/home/x/b.xero", Compiler.resolve("/home/x/a.xero", "b.xero"));
    }
}
