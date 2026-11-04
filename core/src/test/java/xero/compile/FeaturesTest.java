package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.format.Formatter;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeaturesTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private static final String PLUS = "func plus(a, b) {\n    return a + b\n}\n\n";

    private static List<Object> event(String head, String body) {
        Compiler.Result r = Compiler.compile(DATA, NAMES, head + "when player_join {\n" + body + "\n}\n");
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        List<Object> handlers = Json.array(r.module().get("handlers"));
        return Json.array(Json.object(handlers.get(handlers.size() - 1)).get("operations"));
    }

    private static List<String> actions(List<Object> ops) {
        return ops.stream().map(o -> Json.string(Json.object(o).get("action"))).toList();
    }

    private static List<String> errors(String text) {
        return Compiler.compile(DATA, NAMES, text).diagnostics().stream().filter(Diagnostic::error)
                .map(Diagnostic::message).toList();
    }

    private static List<String> warnings(String text) {
        return Compiler.compile(DATA, NAMES, text).diagnostics().stream().filter(d -> !d.error())
                .map(Diagnostic::message).toList();
    }

    private static String circle(String text) {
        Compiler.Result r = Compiler.compile(DATA, NAMES, text);
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        String back = Decompiler.decompile(DATA, NAMES, r.module()).text();
        Compiler.Result again = Compiler.compile(DATA, NAMES, back);
        assertTrue(again.ok(), () -> back + again.diagnostics());
        assertEquals(Json.write(r.module()), Json.write(again.module()), back);
        return back;
    }

    @Test
    void functionResult() {
        List<Object> ops = event(PLUS, "s = plus(2, 3)");
        assertEquals(List.of("call_function", "set_variable_value"), actions(ops));
        assertTrue(Json.write(ops.get(1)).contains("\"variable\":\"__xero_plus\",\"scope\":\"local\""));
        Compiler.Result r = Compiler.compile(DATA, NAMES, PLUS + "when player_join {}");
        Map<String, Object> func = Json.object(Json.array(r.module().get("handlers")).get(0));
        assertEquals(List.of("set_variable_value", "control_return_function"), actions(Json.array(func.get("operations"))));
    }

    @Test
    void twoCallsInOneExpressionDoNotOverwrite() {
        List<Object> ops = event(PLUS, "t = plus(1, 2) + plus(3, 4)");
        assertEquals(List.of("call_function", "set_variable_value", "call_function", "set_variable_value",
                "set_variable_value"), actions(ops));
        assertTrue(Json.write(ops.get(4)).contains("%math(__xero1+__xero2)"));
    }

    @Test
    void callInTextAndCondition() {
        List<Object> ops = event(PLUS, "send_message(\"${plus(1, 2)}\")\nif plus(1, 1) == 2 {\n    send_message(\"ok\")\n}");
        assertEquals(List.of("call_function", "player_send_message", "call_function", "if_variable_equals"), actions(ops));
        assertTrue(Json.write(ops.get(1)).contains("%var_local(__xero_plus)"));
    }

    @Test
    void elseIfWithCallIsLazy() {
        List<Object> ops = event(PLUS, "if 1 == 1 {\n    a = 1\n} else if plus(1, 1) == 2 {\n    a = 2\n} else {\n    a = 3\n}");
        assertEquals(List.of("if_variable_equals", "else"), actions(ops));
        Map<String, Object> otherwise = Json.object(ops.get(1));
        assertEquals(List.of("call_function", "if_variable_equals", "else"), actions(Json.array(otherwise.get("operations"))));
    }

    @Test
    void andOrWithCallsShortCircuit() {
        List<Object> and = event(PLUS, "if 1 == 2 and plus(1, 1) == 2 {\n    a = 1\n}");
        assertEquals(List.of("if_variable_equals"), actions(and));
        assertEquals(List.of("call_function", "if_variable_equals"),
                actions(Json.array(Json.object(and.get(0)).get("operations"))));
        List<Object> or = event(PLUS, "if 1 == 1 or plus(1, 1) == 2 {\n    a = 1\n}");
        String json = Json.write(or);
        assertTrue(json.indexOf("call_function") > json.indexOf("\"is_inverted\":true"), json);
    }

    @Test
    void whileWithCallChecksEachTime() {
        List<Object> ops = event(PLUS, "k = 0\nwhile plus(k, 0) < 3 {\n    k += 1\n}");
        assertEquals(List.of("set_variable_value", "repeat_forever"), actions(ops));
        assertEquals(List.of("call_function", "if_variable_less", "set_variable_add"),
                actions(Json.array(Json.object(ops.get(1)).get("operations"))));
    }

    @Test
    void returnRules() {
        assertEquals(List.of("«plus» возвращает значение — напиши return …"),
                errors("func plus(a) {\n    if a > 1 {\n        return\n    }\n    return a\n}\nwhen player_join {}"));
        assertEquals(List.of("значение возвращает только функция — из события его некому вернуть"),
                errors("when player_join {\n    return 1\n}"));
        assertEquals(List.of("функция «f» ничего не возвращает"),
                errors("func f() {}\nwhen player_join {\n    x = f()\n}"));
        assertEquals(List.of("«W» нет в этом файле — неизвестно, что она возвращает"),
                errors("when player_join {\n    x = `W`()\n}"));
        assertTrue(warnings("func f(a) {\n    if a > 1 {\n        return 1\n    }\n}\nwhen player_join {\n    x = f(1)\n}")
                .contains("«f» может дойти до конца без return — тогда вызов получит прошлое значение"));
        assertTrue(warnings(PLUS + "when player_join {\n    x = plus(1, 2)\n    send_message(\"${x}\")\n}").isEmpty());
    }

    @Test
    void indexes() {
        List<Object> ops = event("", "l = [1, 2]\nm = {\"hp\": 1}\nx = l[1]\nl[0] = 5\nm[\"hp\"] = 2\ny = m[\"hp\"]");
        assertEquals(List.of("set_variable_value", "set_variable_value", "set_variable_get_list_value",
                "set_variable_set_list_value", "set_variable_set_map_value", "set_variable_get_map_value_single"), actions(ops));
        String json = Json.write(ops.get(3));
        assertTrue(json.contains("{\"name\":\"variable\",\"value\":{\"type\":\"variable\",\"variable\":\"l\",\"scope\":\"local\"}}"));
        assertTrue(json.contains("{\"name\":\"list\",\"value\":{\"type\":\"variable\",\"variable\":\"l\",\"scope\":\"local\"}}"));
    }

    @Test
    void indexPlaceholdersInTextAndFormulas() {
        List<Object> ops = event("", "l = [1, 2]\nm = {\"hp\": 1}\nsend_message(\"${l[0]} ${m[\"hp\"]} ${l[1] * 2}\")\nl[1] += 1");
        String text = Json.write(ops.get(2));
        assertTrue(text.contains("%index_local(l,0) %entry_local(m,hp) %math((%index_local(l,1))*2)"), text);
        assertTrue(Json.write(ops.get(3)).contains("%math((%index_local(l,1))+1)"));
    }

    @Test
    void nestedWrite() {
        List<Object> ops = event("", "g = [[1, 2], [3, 4]]\ng[1][0] = 9");
        assertEquals(List.of("set_variable_value", "set_variable_get_list_value", "set_variable_set_list_value",
                "set_variable_set_list_value"), actions(ops));
    }

    @Test
    void unknownShape() {
        assertEquals(List.of("не понятно, «q» — список или словарь"),
                errors("when player_join {\n    save q\n    i = 1\n    x = q[i]\n}"));
    }

    @Test
    void ternaryConditionValueRemainder() {
        assertEquals(List.of("if_variable_greater", "else"), actions(event("", "s = 5\nbig = s > 3 ? \"да\" : \"нет\"")).subList(1, 3));
        assertEquals(List.of("set_variable_value", "set_variable_value", "if_variable_greater"),
                actions(event("", "s = 5\nok = s > 3 and s < 9")));
        assertEquals(List.of("set_variable_value", "set_variable_remainder"), actions(event("", "n = 7\nn %= 3")));
        assertEquals(List.of("set_variable_value", "set_variable_value", "if_variable_greater", "player_send_message"),
                actions(event("", "s = 5\nsend_message(\"${s > 3}\")")));
    }

    @Test
    void selfFormulaBecomesActionBecauseUnsetVariableZeroesTheFormula() {
        assertEquals(List.of("set_variable_value", "set_variable_add", "set_variable_subtract", "set_variable_value"),
                actions(event("", "save coins\nn = 1\nn = n + 5\ncoins = coins - n\nn = n * 2 + 1")));
    }

    @Test
    void resultArgumentNotFirst() {
        List<Object> ops = event("", "m = {\"k\": 1}\ny = get_map_value(map: m, key: \"k\")");
        assertEquals("set_variable_get_map_value", actions(ops).get(1));
        assertTrue(Json.write(ops.get(1)).contains("{\"name\":\"variable\",\"value\":{\"type\":\"array\",\"values\":[{\"type\":\"variable\",\"variable\":\"y\""));
    }

    @Test
    void actionAsValue() {
        List<Object> ops = event("", "l = [1, 2]\nsend_message(\"${get_list_length(l)}\")");
        assertEquals(List.of("set_variable_value", "set_variable_get_list_length", "player_send_message"), actions(ops));
    }

    @Test
    void decompilerPrintsTheNewForms() {
        String text = circle(PLUS + "when player_join {\n    s = plus(2, 3)\n    l = [1, 2]\n    m = {\"hp\": 1}\n"
                + "    x = l[1]\n    l[0] = 5\n    m[\"hp\"] = 2\n    y = m[\"hp\"]\n    n = 7\n    n %= 3\n}\n");
        for (String form : List.of("return a + b", "s = plus(a: 2, b: 3)", "x = l[1]", "l[0] = 5",
                "m[\"hp\"] = 2", "y = m[\"hp\"]", "n %= 3"))
            assertTrue(text.contains(form), form + "\n" + text);
        circle(PLUS + "when player_join {\n    t = plus(1, 2) + plus(3, 4)\n    big = t > 3 ? 1 : 2\n    ok = t > 3 or t < 0\n}\n");
    }

    @Test
    void formatter() {
        assertEquals("when player_join {\n    x = l[0] > 1 ? a[1][2] : `b c`[0]\n}\n",
                Formatter.format("when player_join {\n  x=l [0]>1?a [1] [2]:`b c` [0]\n}\n").text());
    }

    private static List<Diagnostic> locals(String text) {
        Compiler.Result r = Compiler.compile(DATA, NAMES, text);
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        return r.diagnostics().stream().filter(d -> d.message().contains("— локальная:")).toList();
    }

    @Test
    void localReadInAnotherEventIsAWarningWithAFix() {
        String text = "when player_join {\n    x = 1\n}\n\nwhen player_quit {\n    send_message(\"${x}\")\n}\n";
        List<Diagnostic> found = locals(text);
        assertEquals(1, found.size(), found::toString);
        Diagnostic d = found.get(0);
        assertEquals("«x» — локальная: её задают только в when player_join, а в when player_quit она пустая", d.message());
        assertEquals(text.indexOf("x}"), d.start());
        assertTrue(d.hint().endsWith("game x"), d.hint());
        assertEquals(1, d.fixes().size());
        Diagnostic.Fix fix = d.fixes().get(0);
        assertEquals("Объявить игровой: game x", fix.title());
        String fixed = text.substring(0, fix.start()) + fix.text() + text.substring(fix.end());
        assertTrue(fixed.startsWith("game x\n\nwhen player_join {"), fixed);
        assertEquals(List.of(), locals(fixed));
        String declared = "save coins\n\nwhen player_join {\n    `мой x` = 1\n}\n\nwhen player_quit {\n    y = `мой x` + 1\n}\n";
        Diagnostic.Fix after = locals(declared).get(0).fixes().get(0);
        assertEquals("save coins".length(), after.start());
        assertEquals("\ngame `мой x`", after.text());
    }

    @Test
    void readInAFunctionNamesTheEventItCameFrom() {
        String text = "func show() {\n    send_message(\"${n}\")\n}\nwhen player_join {\n    n = 1\n    show()\n}\n"
                + "when player_quit {\n    show()\n}\n";
        List<Diagnostic> found = locals(text);
        assertEquals(1, found.size(), found::toString);
        assertEquals("«n» — локальная: её задают только в when player_join, а в when player_quit она пустая"
                + " (читают в функции «show»)", found.get(0).message());
    }

    @Test
    void localsOfOneContextAreQuiet() {
        List<String> quiet = List.of(
                "when player_join {\n    x = 1\n    send_message(\"${x}\")\n}",
                "func set() {\n    x = 1\n}\nwhen player_join {\n    set()\n    send_message(\"${x}\")\n}",
                "func show() {\n    send_message(\"${x}\")\n}\nwhen player_join {\n    x = 1\n    show()\n}\n"
                        + "when player_quit {\n    x = 2\n    show()\n}",
                "func f(x) {\n    send_message(\"${x}\")\n}\nwhen player_join {\n    x = 1\n}\nwhen player_quit {\n    f(2)\n}",
                "save x\nwhen player_join {\n    x = 1\n}\nwhen player_quit {\n    send_message(\"${x}\")\n}",
                "when player_join {\n    game x = 1\n}\nwhen player_quit {\n    send_message(\"${x}\")\n}",
                "local x\nwhen player_join {\n    x = 1\n}\nwhen player_quit {\n    send_message(\"${x}\")\n}",
                "when player_join {\n    x = 1\n}\nwhen player_quit {\n    `World Init`()\n    send_message(\"${x}\")\n}",
                "when player_join {\n    x = 1\n}\nwhen player_quit {\n    call(\"f\")\n    send_message(\"${x}\")\n}",
                "when player_join {\n    for i in 1..3 {}\n}\nwhen player_quit {\n    for i in 1..2 {\n        send_message(\"${i}\")\n    }\n}",
                "process p() {\n    send_message(\"${x}\")\n}\nwhen player_join {\n    x = 1\n    start p(local_variables_mode: copy)\n}",
                "process p() {\n    send_message(\"${x}\")\n}\nwhen player_join {\n    x = 1\n    start p(local_variables_mode: share)\n}",
                "process p() {\n    x = 1\n}\nwhen player_join {\n    start p(local_variables_mode: share)\n    send_message(\"${x}\")\n}",
                "process p() {\n    send_message(\"${x}\")\n}\nwhen player_join {\n    x = 1\n}",
                "func setup() {\n    x = 1\n}\nwhen player_quit {\n    send_message(\"${x}\")\n}",
                "when player_join {\n    raw(\"{\\\"action\\\":\\\"set_variable_value\\\",\\\"values\\\":[{\\\"name\\\":\\\"variable\\\","
                        + "\\\"value\\\":{\\\"type\\\":\\\"variable\\\",\\\"variable\\\":\\\"x\\\",\\\"scope\\\":\\\"local\\\"}}]}\")\n"
                        + "    send_message(\"${x}\")\n}\nwhen player_quit {\n    x = 1\n}");
        for (String text : quiet) assertEquals(List.of(), locals(text), text);
    }

    @Test
    void processWithFreshLocalsDoesNotSeeTheCaller() {
        for (String mode : List.of("", "local_variables_mode: dont_copy")) {
            String text = "process p() {\n    send_message(\"${x}\")\n}\nwhen player_join {\n    x = 1\n    start p(" + mode + ")\n}";
            List<Diagnostic> found = locals(text);
            assertEquals(1, found.size(), text);
            assertTrue(found.get(0).message().endsWith("а в процессе «p» она пустая"), found.get(0).message());
        }
    }

    @Test
    void decompilerQuotesKeywordNames() {
        List<String> words = List.of("for", "while", "repeat", "forever", "if", "return", "start", "import", "in", "step",
                "для", "пока", "повторить", "всегда", "шаг", "в", "импорт", "и", "не", "value", "game", "_");
        StringBuilder body = new StringBuilder();
        for (String w : words) body.append("    `").append(w).append("` = 1\n");
        for (String w : words) body.append("    send_message(`").append(w).append("`)\n");
        body.append("    `step` = `step` - 9\n    for `in` in [1] {}\n");
        String back = circle("func `for`() {}\n\nwhen player_join {\n" + body + "    `for`()\n}\n");
        for (String w : words) assertTrue(back.contains("`" + w + "` = 1"), w + "\n" + back);
        assertTrue(back.contains("func `for`()"), back);
    }

    @Test
    void russianKeywordsMeanTheSame() {
        String english = """
                func f(a) {
                    if a > 1 and not a == 5 or a < 0 {
                        return
                    } else if a == 2 {
                        send_message("2")
                    } else {
                        send_message("3")
                    }
                }

                process p() {
                    forever {
                        wait(20)
                    }
                }

                when player_join {
                    l = [1, 2]
                    for i in 1..10 step 2 {
                        if i == 3 {
                            break
                        }
                        continue
                    }
                    for x in l {}
                    for k, v in l {}
                    while l[0] < 3 {
                        l[0] += 1
                    }
                    repeat 2 {}
                    f(1)
                    start p()
                }
                """;
        String russian = """
                функция f(a) {
                    если a > 1 и не a == 5 или a < 0 {
                        вернуть
                    } иначе если a == 2 {
                        send_message("2")
                    } иначе {
                        send_message("3")
                    }
                }

                процесс p() {
                    всегда {
                        wait(20)
                    }
                }

                когда player_join {
                    l = [1, 2]
                    для i в 1..10 шаг 2 {
                        если i == 3 {
                            прервать
                        }
                        продолжить
                    }
                    для x в l {}
                    для k, v в l {}
                    пока l[0] < 3 {
                        l[0] += 1
                    }
                    повторить 2 {}
                    f(1)
                    запустить p()
                }
                """;
        Compiler.Result en = Compiler.compile(DATA, NAMES, english);
        Compiler.Result ru = Compiler.compile(DATA, NAMES, russian);
        assertTrue(en.ok(), () -> en.diagnostics().toString());
        assertTrue(ru.ok(), () -> ru.diagnostics().toString());
        assertEquals(Json.write(en.module()), Json.write(ru.module()));
        Map<String, String> files = Map.of("lib.xero", "функция kit() {}");
        Compiler.Result imported = Compiler.compile(DATA, NAMES, "main.xero",
                "импорт \"lib.xero\"\nкогда player_join {\n    kit()\n}", files::get);
        Compiler.Result plain = Compiler.compile(DATA, NAMES, "main.xero",
                "import \"lib.xero\"\nwhen player_join {\n    kit()\n}", Map.of("lib.xero", "func kit() {}")::get);
        assertTrue(imported.ok(), () -> imported.diagnostics().toString());
        assertEquals(Json.write(plain.module()), Json.write(imported.module()));
        assertEquals(russian, Formatter.format(russian).text());
    }

    @Test
    void launchModes() {
        String text = "process p() {}\n\nwhen player_join {\n    start p(local_variables_mode: copy, target_mode: no_target)\n"
                + "    start p(target_mode: for_each_in_selection)\n}\n";
        List<Object> ops = event("process p() {}\n", "start p(local_variables_mode: share, target_mode: current_selection)");
        assertTrue(Json.write(ops.get(0)).contains("{\"name\":\"local_variables_mode\",\"value\":{\"type\":\"enum\",\"enum\":\"SHARE\"}},"
                + "{\"name\":\"target_mode\",\"value\":{\"type\":\"enum\",\"enum\":\"CURRENT_SELECTION\"}}"), Json.write(ops));
        String back = circle(text);
        assertTrue(back.contains("start p(local_variables_mode: copy, target_mode: no_target)"), back);
        assertTrue(back.contains("start p(target_mode: for_each_in_selection)"), back);
        assertEquals(List.of("у local_variables_mode варианты: dont_copy, copy, share"),
                errors("process p() {}\nwhen player_join {\n    start p(local_variables_mode: cpy)\n}"));
    }

    @Test
    void unknownLaunchModeComesBackAsRaw() {
        String world = "{\"handlers\":[{\"type\":\"process\",\"name\":\"p\",\"values\":[{\"name\":\"is_hidden\",\"value\":"
                + "{\"type\":\"enum\",\"enum\":\"FALSE\"}}],\"position\":0,\"operations\":[]},{\"type\":\"event\",\"event\":"
                + "\"player_join\",\"position\":1,\"operations\":[{\"action\":\"start_process\",\"values\":[{\"name\":"
                + "\"process_name\",\"value\":{\"type\":\"text\",\"text\":\"p\",\"parsing\":\"legacy\"}},{\"name\":"
                + "\"local_variables_mode\",\"value\":{\"type\":\"enum\",\"enum\":\"WEIRD\"}},{\"name\":\"target_mode\","
                + "\"value\":{\"type\":\"enum\",\"enum\":\"NO_TARGET\"}}]}]}]}";
        Map<String, Object> module = Json.object(Json.parse(world));
        String text = Decompiler.decompile(DATA, NAMES, module).text();
        assertTrue(text.contains("raw(\"{\\\"action\\\":\\\"start_process\\\""), text);
        Compiler.Result back = Compiler.compile(DATA, NAMES, text);
        assertTrue(back.ok(), () -> text + back.diagnostics());
        assertEquals(Json.write(Json.parse(world)), Json.write(Json.parse(Json.write(back.module()))));
    }

    @Test
    void textPrefixesSurviveTheCircle() {
        List<Object> ops = event("", "send_message([plain\"a\", mm\"<red>b\", json\"{}\", legacy\"&cd\", \"e\"])");
        String json = Json.write(ops.get(0));
        for (String parsing : List.of("plain", "minimessage", "json", "legacy"))
            assertTrue(json.contains("\"parsing\":\"" + parsing + "\""), json);
        String back = circle("when player_join {\n    send_message([plain\"a & \\${x}\", mm\"<red>b\", json\"{}\", legacy\"&cd\"])\n}\n");
        assertTrue(back.contains("plain\"a & \\${x}\""), back);
        assertTrue(back.contains("mm\"<red>b\""), back);
        assertTrue(back.contains("json\"{}\""), back);
        assertTrue(back.contains(", \"&cd\"]"), back);
    }

    @Test
    void particleMaterialIsABukkitName() {
        List<Object> ops = event("", "x = particle(falling_dust, material: sand)\ny = particle(block)\nz = particle(item, material: \"minecraft:cookie\")");
        String json = Json.write(ops);
        assertTrue(json.contains("\"material\":\"SAND\""), json);
        assertTrue(json.contains("\"material\":\"STONE\""), json);
        assertTrue(json.contains("\"material\":\"COOKIE\""), json);
        String world = "{\"handlers\":[{\"type\":\"event\",\"event\":\"player_join\",\"position\":0,\"operations\":["
                + "{\"action\":\"set_variable_value\",\"values\":[{\"name\":\"variable\",\"value\":{\"type\":\"variable\","
                + "\"variable\":\"x\",\"scope\":\"local\"}},{\"name\":\"value\",\"value\":{\"type\":\"particle\","
                + "\"particle_type\":\"FALLING_DUST\",\"count\":1,\"first_spread\":0.0,\"second_spread\":0.0,"
                + "\"material\":\"OAK_LOG\"}}]}]}]}";
        Decompiler.Result text = Decompiler.decompile(DATA, NAMES, Json.object(Json.parse(world)));
        assertTrue(text.text().contains("x = particle(falling_dust, material: oak_log)"), text.text());
        Compiler.Result back = Compiler.compile(DATA, NAMES, text.text());
        assertTrue(back.ok(), () -> back.diagnostics().toString());
        assertEquals(Json.write(Json.parse(world)), Json.write(Json.parse(Json.write(back.module()))));
        String legacy = Decompiler.decompile(DATA, NAMES, Json.object(Json.parse(world.replace("\"OAK_LOG\"",
                "\"minecraft:oak_log\"")))).text();
        assertEquals(text.text(), legacy);
        String stone = Decompiler.decompile(DATA, NAMES, Json.object(Json.parse(world.replace("\"OAK_LOG\"",
                "\"minecraft:stone\"")))).text();
        assertTrue(stone.contains("x = particle(falling_dust)"), stone);
        assertTrue(Decompiler.decompile(DATA, NAMES, Json.object(Json.parse(world.replace("\"OAK_LOG\"",
                "\"STONE\"")))).text().contains("x = particle(falling_dust)"));
    }

    @Test
    void spareIdGoesWhereItSaysAndWarns() {
        Compiler.Result r = Compiler.compile(DATA, NAMES, "when player_join {\n    x = 1\n    if in_range(x, 0, 10) {}\n}");
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        assertTrue(Json.write(r.module()).contains("\"action\":\"if_variable_in_range\""));
        assertEquals(List.of("«in_range» — запасной id if_variable_in_range; из палитры сервер ставит "
                + "if_variable_number_in_range («number_in_range»)"), r.diagnostics().stream().map(Diagnostic::message).toList());
        assertTrue(warnings("when player_join {\n    x = 1\n    if number_in_range(x, 0, 10) {}\n}").isEmpty());
    }

    @Test
    void piAndEAreNumbers() {
        assertEquals(List.of(), errors("when player_join {\n    a = 2\n    x = PI * 2\n    y = a * PI\n    z = sin(E)\n"
                + "    w = PI\n    send_message(\"${PI} ${x} ${y} ${z} ${w}\")\n    if a > E { }\n}\n"));
        String pi = Double.toString(Math.PI);
        assertTrue(Json.write(event("", "a = 2\nx = a * PI\nsend_message(\"${x}\")")).contains("a*" + pi));
    }

    @Test
    void ownVariableBeatsMathConstant() {
        String built = Json.write(event("", "E = 5\nx = E + 1\nsend_message(\"${x}\")"));
        assertTrue(built.contains("%var_local(E)"), built);
        assertTrue(!built.contains(Double.toString(Math.E)), built);
    }

    @Test
    void omittedMarkerGoesByItsDefault() {
        String head = "func f(mode: enum(\"alpha\", \"beta\") = \"beta\") { }\nfunc g(mode: enum(\"one\", \"two\")) { }\n";
        String built = Json.write(event(head, "f()\ng()"));
        assertTrue(built.contains("\"text\":\"beta\""), built);
        assertTrue(built.contains("\"text\":\"one\""), built);
    }

    @Test
    void omittedParameterIsAnEmptyCell() {
        String built = Json.write(event("func f(n: number = 5) { }\n", "f()"));
        assertTrue(built.contains("\"args\""), built);
        assertTrue(built.contains("\\\"text\\\":\\\"n\\\""), built);
        assertTrue(!Json.write(event("func g() { }\n", "g()")).contains("\"args\""));
    }

    @Test
    void defaultsArePrintedAsAPlainCall() {
        String text = "func f(a: number = 5, mode: enum(\"x\", \"y\") = \"y\") {}\n\nwhen player_join {\n    f()\n    f(a: 1, mode: \"x\")\n}\n";
        assertEquals(text, circle(text));
    }
}
