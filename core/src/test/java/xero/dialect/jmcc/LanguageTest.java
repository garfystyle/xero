package xero.dialect.jmcc;

import org.junit.jupiter.api.Test;
import xero.compile.Compiler;
import xero.dialect.Dialect;
import xero.dialect.Ink;
import xero.ide.Service;
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

class LanguageTest {
    private static final Service SERVICE = Service.load();
    private static final JmccDialect JMCC = new JmccDialect(SERVICE);

    private static Object module(Dialect.Built b, String code) {
        assertTrue(b.ok(), code + "\n" + b.diagnostics());
        return Json.parse(Json.write(b.module()));
    }

    private static void same(String jc, String xero) {
        Object mine = module(JMCC.build(null, jc, null), jc);
        Compiler.Result r = SERVICE.build(null, xero, null);
        assertTrue(r.ok(), xero + "\n" + r.diagnostics());
        Object theirs = Json.parse(Json.write(r.module()));
        assertEquals(CircleTest.normal(Json.object(theirs)).toString(), CircleTest.normal(Json.object(mine)).toString(), jc);
    }

    private static void inJoin(String jc, String xero) {
        same("event<player_join> {\n" + jc + "\n}\n", "when player_join {\n" + xero + "\n}\n");
    }

    private static List<String> errors(String jc) {
        List<String> out = new ArrayList<>();
        for (Diagnostic d : JMCC.build(null, jc, null).diagnostics()) if (d.error()) out.add(d.message());
        return out;
    }

    private static List<String> warnings(String jc) {
        List<String> out = new ArrayList<>();
        for (Diagnostic d : JMCC.build(null, jc, null).diagnostics()) if (!d.error()) out.add(d.message());
        return out;
    }

    @Test
    void actionsTargetsAndMarkers() {
        inJoin("player::message(\"a\");", "send_message(\"a\")");
        inJoin("player::message<all_players>(\"a\")", "@all send_message(\"a\")");
        inJoin("player::message<all>(\"a\")", "@all send_message(\"a\")");
        inJoin("player::message([\"a\", \"b\"], merging = \"SEPARATE_LINES\");", "send_message([\"a\", \"b\"], merging: separate_lines)");
        inJoin("player::message(\"a\", \"SEP\");", "send_message(\"a\", merging: separate_lines)");
        inJoin("player::message(merging = \"SPACES\", messages = \"a\");", "send_message(\"a\", merging: spaces)");
        inJoin("player::message(\"SPACES\", messages = \"a\");", "send_message(\"a\", merging: spaces)");
        inJoin("code::wait(10, time_unit = \"SECONDS\");", "wait(10, time_unit: seconds)");
        inJoin("inline var a = \"current\";\nplayer::message<$a>(\"x\");", "@current send_message(\"x\")");
        inJoin("world::cancel_event();", "cancel_event()");
    }

    @Test
    void valuesAndSelectors() {
        inJoin("var a = value::current_health;", "a = value.current_health");
        inJoin("var a = value::current_health<current>;", "a = @current value.current_health");
        inJoin("var a = value::health;", "a = value.current_health");
        inJoin("var a = value::current_health<default_entity>;", "a = @default_entity value.current_health");
        inJoin("player::message(value::name);", "send_message(value.name)");
    }

    @Test
    void variablesAndScopes() {
        inJoin("var a = 1;", "a = 1");
        inJoin("game var a = 1;\nplayer::message(a);", "game a = 1\nsend_message(a)");
        inJoin("save var a = 1;", "save a = 1");
        inJoin("line var a = 1;", "line a = 1");
        inJoin("local var a = 1;", "local a = 1");
        inJoin("g`a` = 1;", "game a = 1");
        inJoin("s`a` = 1;", "save a = 1");
        inJoin("l`a` = 1;", "local a = 1");
        inJoin("line`a` = 1;", "line a = 1");
        inJoin("var `странная переменная` = 2;", "`странная переменная` = 2");
        inJoin("var `%player%_x` = 2;", "`%player%_x` = 2");
        inJoin("%player%_gold = 2;\n%player%_gold -= 1;", "`%player%_gold` = 2\n`%player%_gold` -= 1");
        inJoin("var a = 0;\nvar `b ${a}` = 3;", "a = 0\n`b %var_local(a)` = 3");
        inJoin("var a, b = 1, 2;", "a, b = 1, 2");
        inJoin("var a: text = \"x\";", "a = \"x\"");
        inJoin("a: map = {\"k\": 1};", "a = {\"k\": 1}");
        same("game var a = 1;\nevent<player_join> { a += 2; }", "game a\nwhen world_start {\n a = 1\n}\nwhen player_join {\n a += 2\n}");
        same("game var x;\nevent<player_join> { player::message(x); }", "game x\nwhen player_join {\n send_message(x)\n}");
    }

    @Test
    void sameNameInTwoScopes() {
        Dialect.Built b = JMCC.build(null, "event<player_join> {\n g`x` = 1;\n s`x` = 2;\n l`x` = 3;\n player::message(g`x`);\n}\n", null);
        assertTrue(b.ok(), String.valueOf(b.diagnostics()));
        String json = Json.write(b.module());
        assertTrue(json.contains("\"scope\":\"game\"") && json.contains("\"scope\":\"save\"") && json.contains("\"scope\":\"local\""));
    }

    @Test
    void unknownVariableIsAWarningAndNotAnError() {
        String code = "event<player_join> {\n player::message(scroe);\n}\n";
        assertEquals(List.of(), errors(code));
        assertTrue(warnings(code).toString().contains("нигде не задаётся"));
    }

    @Test
    void strings() {
        inJoin("player::message(m\"<red>a\");", "send_message(mm\"<red>a\")");
        inJoin("player::message(p\"a\");", "send_message(plain\"a\")");
        inJoin("player::message(j\"a\");", "send_message(json\"a\")");
        inJoin("player::message(l\"&aa\");", "send_message(\"&aa\")");
        inJoin("player::message('a');", "send_message(\"a\")");
        inJoin("var x = 1;\nplayer::message(\"значение $x!\");", "x = 1\nsend_message(\"значение ${x}!\")");
        inJoin("var x = 1;\nplayer::message(\"${x + 1}\");", "x = 1\nsend_message(\"${x + 1}\")");
        inJoin("player::message(\"цена \\${x} и \\$y\");", "send_message(\"цена \\${x} и $y\")");
        inJoin("player::message(\"Привет, %player%!\");", "send_message(\"Привет, %player%!\")");
        inJoin("player::message(\"строка\\nвторая\");", "send_message(\"строка\\nвторая\")");
        inJoin("var a = \"x\" + \"y\";", "a = \"xy\"");
        inJoin("var b = 1;\nvar a = \"x\" + b;", "b = 1\na = \"x${b}\"");
        inJoin("player::message(\"${variable::add([1, 2])}\");", "send_message(\"${add([1, 2])}\")");
    }

    @Test
    void conditions() {
        inJoin("var a = 1;\nif (a.equals(1)) { player::message(\"x\"); }", "a = 1\nif a == 1 {\n send_message(\"x\")\n}");
        inJoin("var a = 1;\nif a == 1 { player::message(\"x\") } else { player::message(\"y\") }",
                "a = 1\nif a == 1 {\n send_message(\"x\")\n} else {\n send_message(\"y\")\n}");
        inJoin("var a = 1;\nif (a == 1) {\n a = 2;\n} elif (a == 2) {\n a = 3;\n} else if (a == 3) {\n a = 4;\n} else {\n a = 5;\n}",
                "a = 1\nif a == 1 {\n a = 2\n} else if a == 2 {\n a = 3\n} else if a == 3 {\n a = 4\n} else {\n a = 5\n}");
        inJoin("var a = 1;\nif not (a.equals(2)) { a = 3; }", "a = 1\nif a != 2 {\n a = 3\n}");
        inJoin("var a = 1;\nif a.equals(1) a = 2 else a = 3;", "a = 1\nif a == 1 {\n a = 2\n} else {\n a = 3\n}");
        inJoin("var a = 1;\nif (a.equals([1, 2])) { a = 3; }", "a = 1\nif equals(a, [1, 2]) {\n a = 3\n}");
        inJoin("if (player::is_sneaking()) { player::message(\"x\"); }", "if is_sneaking() {\n send_message(\"x\")\n}");
        inJoin("if (player::is_sneaking<killer_player>()) { player::message(\"x\"); }", "if @killer is_sneaking() {\n send_message(\"x\")\n}");
        inJoin("var a = 1;\nvar b = 2;\nif (a == 1 and b == 2 or a == 3) { a = 9; }", "a = 1\nb = 2\nif a == 1 and b == 2 or a == 3 {\n a = 9\n}");
        inJoin("var a = 1;\nvar b = 2;\nif (a == 1 && b == 2 || !(a == 3)) { a = 9; }", "a = 1\nb = 2\nif a == 1 and b == 2 or not a == 3 {\n a = 9\n}");
        inJoin("var a = 1;\nvar ok = a > 0;", "a = 1\nok = a > 0");
        inJoin("var a = 1;\nvar x = if a.equals(1) 10 else 20;", "a = 1\nx = a == 1 ? 10 : 20");
        inJoin("var a = 1;\nvar x = 10 if a.equals(1) else 20;", "a = 1\nx = a == 1 ? 10 : 20");
        inJoin("var a = 1;\nvar x = a.equals(1) ? 10 : 20;", "a = 1\nx = a == 1 ? 10 : 20");
        inJoin("var a = 1;\nif (a in [1, 2]) { a = 3; }", "a = 1\nif a in [1, 2] {\n a = 3\n}");
        inJoin("var a = 1;\nif (a in 1..=5) { a = 3; }", "a = 1\nif a in 1..5 {\n a = 3\n}");
        inJoin("var a = 1;\nif (a in 1..5) { a = 3; }", "a = 1\nif a >= 1 and a < 5 {\n a = 3\n}");
        inJoin("var l = [1];\nif (1 in l) { l = []; }", "l = [1]\nif 1 in l {\n l = []\n}");
        inJoin("var m = {\"a\": 1};\nif (\"a\" in m) { m = {}; }", "m = {\"a\": 1}\nif \"a\" in m {\n m = {}\n}");
    }

    @Test
    void loops() {
        inJoin("repeat::forever() {\n code::wait(1);\n}", "forever {\n wait(1)\n}");
        inJoin("var a = 1;\nrepeat::while(a.less(10)) {\n a += 1;\n}", "a = 1\nwhile a < 10 {\n a += 1\n}");
        inJoin("var a = 1;\nwhile a < 10 {\n a += 1;\n}", "a = 1\nwhile a < 10 {\n a += 1\n}");
        inJoin("repeat::on_range(0, 10) { i ->\n player::message(i);\n}", "for i in 0..10 {\n send_message(i)\n}");
        inJoin("repeat::multi_times(amount = 5) {\n code::wait(1);\n}", "repeat 5 {\n wait(1)\n}");
        inJoin("repeat::multi_times(5) {\n code::wait(1);\n}", "repeat 5 {\n wait(1)\n}");
        inJoin("for x in [1, 2] {\n player::message(x);\n}", "for x in [1, 2] {\n send_message(x)\n}");
        inJoin("var l = [1];\nfor i, x in l {\n player::message(x);\n}", "l = [1]\nfor i, x in l {\n send_message(x)\n}");
        inJoin("var m = {\"a\": 1};\nfor k, v in m {\n player::message(v);\n}", "m = {\"a\": 1}\nfor k: v in m {\n send_message(v)\n}");
        inJoin("for x in 0..5 {\n player::message(x);\n}", "for x in 0..4 {\n send_message(x)\n}");
        inJoin("for x in 0..=5 {\n player::message(x);\n}", "for x in 0..5 {\n send_message(x)\n}");
        inJoin("for x in (0..=10).step_by(2) {\n player::message(x);\n}", "for x in 0..10 step 2 {\n send_message(x)\n}");
        inJoin("for x in [1] {\n if x == 1 { break; }\n continue;\n}", "for x in [1] {\n if x == 1 {\n break\n }\n continue\n}");
        inJoin("if not (1 == 1) { break };\nplayer::message(\"x\");", "if 1 != 1 {\n control.end_thread()\n}\nsend_message(\"x\")");
    }

    @Test
    void labelsLeaveOuterLoops() {
        Dialect.Built b = JMCC.build(null, "event<player_join> {\n 'outer: while true {\n  for x in [1] {\n   if x == 1 { break 'outer; }\n  }\n  code::wait(1);\n }\n}\n", null);
        assertTrue(b.ok(), String.valueOf(b.diagnostics()));
        String json = Json.write(b.module());
        assertTrue(json.contains("__jc_break_outer"), json);
        assertEquals(List.of("нет цикла с меткой 'nope"), errors("event<player_join> {\n while true { break 'nope; }\n}\n"));
    }

    @Test
    void stepsAndOperators() {
        inJoin("var a = 1;\na++;", "a = 1\na += 1");
        inJoin("var a = 1;\n++a;", "a = 1\na += 1");
        inJoin("var a = 1;\na--;", "a = 1\na -= 1");
        inJoin("var a = 1;\nif ((++a).less(5)) { a = 9; }", "a = 1\na += 1\nif a < 5 {\n a = 9\n}");
        inJoin("var a = 1;\nvar b = a * 2 + 3;", "a = 1\nb = a * 2 + 3");
        inJoin("var a = 1;\nvar b = (a + 1) % 2;", "a = 1\nb = (a + 1) % 2");
        inJoin("var a = 2;\nvar b = a ^ 3 + 1;", "a = 2\nb = pow(a, 3) + 1");
        inJoin("var a = 2;\na ^= 3;", "a = 2\na = pow(a, 3)");
        inJoin("var a = 2;\nvar b = floor(a / 2) + 1;", "a = 2\nb = floor(a / 2) + 1");
        inJoin("var a = 2;\nvar b = abs(a);", "a = 2\nb = abs(a)");
        inJoin("var a = 2;\nvar b = min(a, 3);", "a = 2\nb = min([a, 3])");
        inJoin("var a = 2;\nvar b = min(a, 3) + 1;", "a = 2\nb = min(a, 3) + 1");
        inJoin("var a = \"ab\" * 3;", "a = repeat_text(\"ab\", 3)");
    }

    @Test
    void methods() {
        inJoin("var l = location(1, 2, 3);\nvar y = l.get_coordinate(\"Y\");", "l = loc(1, 2, 3)\ny = get_coordinate(l, y)");
        inJoin("var a = 1;\na.increment(2);", "a = 1\nincrement(a, 2)");
        inJoin("var a = [1];\na.append_value(2);", "a = [1]\nappend_value(a, 2)");
        inJoin("var a = [1];\na = a.set_list_value(0, 5);", "a = [1]\na[0] = 5");
        inJoin("var a = 5;\nvar b = a.clamp(1, 2);", "a = 5\nb = clamp(a, 1, 2)");
        inJoin("var b = (1 + 2).round();", "b = round(3)");
        inJoin("var x = 1;\nvar y = 2;\nvar m = [x, y].min();", "x = 1\ny = 2\nm = min([x, y])");
    }

    @Test
    void functionsAndProcesses() {
        same("function hi(name, times = 1) {\n player::message(name);\n}\nevent<player_join> {\n hi(\"a\");\n hi(\"b\", 2);\n hi(times = 3, name = \"c\");\n}\n",
                "func hi(name, times = 1) {\n send_message(name)\n}\nwhen player_join {\n hi(\"a\")\n hi(\"b\", 2)\n hi(times: 3, name: \"c\")\n}\n");
        same("def add(a: number, b: number = 1) -> number {\n return a + b;\n}\nevent<player_join> {\n var x = add(1, 2) + 1;\n}\n",
                "func `add`(a: number, b: number = 1) {\n return a + b\n}\nwhen player_join {\n x = `add`(1, 2) + 1\n}\n");
        same("function a { player::message(\"x\"); }\nevent<player_join> { a(); }\n", "func a() {\n send_message(\"x\")\n}\nwhen player_join {\n a()\n}\n");
        same("process p() { code::wait(1); }\nevent<player_join> { p(); }\n",
                "process p() {\n wait(1)\n}\nwhen player_join {\n start p(local_variables_mode: copy, target_mode: current_target)\n}\n");
        same("process p() { code::wait(1); }\nevent<world_start> { code::start_process(\"p\", \"DONT_COPY\", \"NO_TARGET\"); }\n",
                "process p() {\n wait(1)\n}\nwhen world_start {\n start p(local_variables_mode: dont_copy, target_mode: no_target)\n}\n");
        same("function f(a) { player::message(a); }\nevent<player_join> { code::call_function(\"f\", {\"a\": 1}); }\n",
                "func f(a) {\n send_message(a)\n}\nwhen player_join {\n f(a: 1)\n}\n");
        same("event<player_join> { var n = \"f\"; code::call_function(n); }\n", "when player_join {\n n = \"f\"\n call(n)\n}\n");
        same("@hidden\n@description(\"a\", \"b\")\n@item(\"stone\")\nfunction f() {}\n",
                "@hidden\n@description(\"a\", \"b\")\n@icon(item(stone))\nfunc f() {}\n");
        same("function `WE Load`() {}\nevent<player_join> { `WE Load`(); }\n", "func `WE Load`() {}\nwhen player_join {\n `WE Load`()\n}\n");
        same("function outer() {\n function inner() { code::wait(1); }\n inner();\n}\n",
                "func outer() {\n inner()\n}\nfunc inner() {\n wait(1)\n}\n");
        same("event<player_join> {\n event<player_quit> { player::message(\"x\"); }\n}\n",
                "when player_join {\n}\nwhen player_quit {\n send_message(\"x\")\n}\n");
    }

    @Test
    void outParametersBecomeReferences() {
        Dialect.Built b = JMCC.build(null, "function f(out) {\n out = 1;\n}\nevent<player_join> {\n var r = 0;\n f(r);\n}\n", null);
        assertTrue(b.ok(), String.valueOf(b.diagnostics()));
        assertTrue(Json.write(b.module()).contains("\\\"value_type\\\"") || Json.write(b.module()).contains("value_type"));
        assertTrue(Json.write(b.module()).contains("\"value_type\":\"variable\""), Json.write(b.module()));
    }

    @Test
    void topLevelCodeGoesToWorldStart() {
        same("var a = 1;\nplayer::message<all_players>(\"x\");\nevent<player_join> {}\n",
                "when world_start {\n a = 1\n @all send_message(\"x\")\n}\nwhen player_join {}\n");
    }

    @Test
    void factories() {
        inJoin("var a = location(1, 2, 3);", "a = loc(1, 2, 3)");
        inJoin("var a = location(1, 2, 3, 4, 5);", "a = loc(1, 2, 3, 4, 5)");
        inJoin("var a = местоположение(1, 2, 3);", "a = loc(1, 2, 3)");
        inJoin("var a = vector(1, 2, 3);", "a = vec(1, 2, 3)");
        inJoin("var a = вектор(1, 2, -3);", "a = vec(1, 2, -3)");
        inJoin("var a = item(\"stone\");", "a = item(stone)");
        inJoin("var a = предмет(\"minecraft:stone\", count = 5);", "a = item(stone, 5)");
        inJoin("var a = item(\"stone\", \"&aИмя\", 2);", "a = item(stone, 2, name: \"&aИмя\")");
        inJoin("var a = item(\"stone\", lore = [\"a\", \"b\"]);", "a = item(stone, lore: [\"a\", \"b\"])");
        inJoin("var a = item(\"stone\", nbt = m{\"minecraft:damage\": 1});", "a = item(stone, components: \"{\\\"minecraft:damage\\\": 1}\")");
        inJoin("var a = item(\"stone\", custom_tags = {\"kind\": \"hero\"});", "a = item(stone, tags: {kind: \"hero\"})");
        inJoin("var a = sound(\"minecraft:entity.player.levelup\", 2, 3, source = \"RECORD\");", "a = sound(entity.player.levelup, volume: 2, pitch: 3, source: record)");
        inJoin("var a = звук(\"entity.player.levelup\");", "a = sound(entity.player.levelup)");
        inJoin("var a = particle(\"FLAME\", 3, 0.5, 0.5);", "a = particle(flame, count: 3, first_spread: 0.5, second_spread: 0.5)");
        inJoin("var a = particle(\"BLOCK\", 10, 0, 0, 0, 0, 0, material = \"IRON_BLOCK\");", "a = particle(block, count: 10, material: iron_block)");
        inJoin("var a = potion(\"speed\", 1, 200);", "a = potion(speed, amplifier: 1, duration: 200)");
        inJoin("var a = зелье(\"minecraft:speed\");", "a = potion(speed)");
        inJoin("var a = block(\"stone\");", "a = block(stone)");
        inJoin("var a = text(\"x\");", "a = \"x\"");
        inJoin("var a = array(1, 2);", "a = [1, 2]");
        inJoin("var a = map(x = 1, y = 2);", "a = {\"x\": 1, \"y\": 2}");
        inJoin("var x = 1;\nvar a = location(x, 2, 3);", "x = 1\na = set_all_coordinates(x: x, y: 2, z: 3)");
    }

    @Test
    void customSoundsPassThrough() {
        String code = "event<player_join> {\n var a = sound(\"minecraft:cubed.music.x\", 1, 2);\n}\n";
        assertEquals(List.of(), errors(code));
        assertTrue(warnings(code).toString().contains("нет в данных сервера"));
        assertTrue(Json.write(JMCC.build(null, code, null).module()).contains("cubed.music.x"));
    }

    @Test
    void listsAndMaps() {
        inJoin("var l = [1, 2];\nvar x = l[0];", "l = [1, 2]\nx = l[0]");
        inJoin("var l = [1, 2];\nl[0] = 3;", "l = [1, 2]\nl[0] = 3");
        inJoin("var m = {\"a\": 1};\nvar x = m[\"a\"];", "m = {\"a\": 1}\nx = m[\"a\"]");
        inJoin("var m = {\"a\": 1};\nm[\"a\"] = 2;", "m = {\"a\": 1}\nm[\"a\"] = 2");
        inJoin("var l = [1, 2];\nvar i = 1;\nvar x = l[i];", "l = [1, 2]\ni = 1\nx = get_list_value(l, i)");
        inJoin("var m = {\"a\": 1};\nvar k = \"a\";\nvar x = m[k];", "m = {\"a\": 1}\nk = \"a\"\nx = get_map_value_single(m, k)");
        inJoin("var t: text = \"ab\";\nvar c = t[0];", "t = \"ab\"\nc = get_char_at(t, 0)");
        inJoin("var l = [1, 2, 3];\nvar s = l[0:2];", "l = [1, 2, 3]\ns = trim_list(l, 0, 2)");
    }

    @Test
    void inlineConstEnum() {
        inJoin("inline var a = 5;\nplayer::message(a);", "send_message(5)");
        inJoin("const A = 5;\nplayer::message(A + 1);", "send_message(6)");
        same("inline var red = \"&c\";\ninline var sword = item(\"stone\", name = \"${red}x\");\nevent<player_join> { player::give_items(sword); }\n",
                "when player_join {\n give_items(item(stone, name: \"&cx\"))\n}\n");
        same("inline var _ = item(\"air\");\ninline var c = item(\"stone\");\nevent<player_join> { player::set_items([_, c]); }\n",
                "when player_join {\n set_items([item(air), item(stone)])\n}\n");
        same("enum Color { RED, GREEN }\nevent<player_join> { var c = Color.RED; }\n", "when player_join {\n c = \"RED\"\n}\n");
        same("enum Mode { A, B }\nfunction f(m: Mode) {}\nevent<player_join> { f(\"B\"); }\n",
                "func f(m: enum(\"A\", \"B\")) {}\nwhen player_join {\n f(\"B\")\n}\n");
        assertEquals(List.of("в «Color» нет варианта «BLUE»"), errors("enum Color { RED }\nevent<player_join> { var c = Color.BLUE; }\n"));
    }

    @Test
    void matchTryThrow() {
        same("function d(n: number) -> text {\n match n {\n  0 => \"zero\",\n  1 | 2 => \"small\",\n  _ if n > 10 => \"big\",\n  _ => \"other\",\n }\n}\n",
                "func d(n: number) {\n if n == 0 {\n return \"zero\"\n } else if n == 1 or n == 2 {\n return \"small\"\n } else if n > 10 {\n return \"big\"\n } else {\n return \"other\"\n }\n}\n");
        inJoin("var n = 1;\nvar s = match n { 0 => \"a\", _ => \"b\" };", "n = 1\ns = n == 0 ? \"a\" : \"b\"");
        inJoin("var n = 1;\nmatch n {\n 0 => { player::message(\"a\"); }\n _ => { player::message(\"b\"); }\n}",
                "n = 1\nif n == 0 {\n send_message(\"a\")\n} else {\n send_message(\"b\")\n}");
        inJoin("try {\n throw ERROR \"boom\";\n} catch (err) {\n player::message(\"x\");\n}",
                "controller.exception(err) {\n control.call_exception(message: \"boom\", type: error)\n}\nif var.exists(err) {\n send_message(\"x\")\n}");
    }

    @Test
    void importsAreFiles() {
        Map<String, String> files = Map.of("lib/items.jc", "inline var sword = item(\"stone\");\nfunction hi() { player::message(\"x\"); }\ngame var coins = 1;\n",
                "lib/main.jc", "import './items';\nevent<player_join> { hi(); player::give_items(sword); coins += 1; }\n");
        Dialect.Built b = JMCC.build("lib/main.jc", files.get("lib/main.jc"), files::get);
        assertTrue(b.ok(), String.valueOf(b.diagnostics()));
        String json = Json.write(b.module());
        assertTrue(json.contains("\"scope\":\"game\"") && json.contains("\"event\":\"world_start\""), json);
        Dialect.Built lost = JMCC.build("a.jc", "import './nope';\n", p -> null);
        assertFalse(lost.ok());
        assertTrue(lost.diagnostics().get(0).message().contains("нет файла"), String.valueOf(lost.diagnostics()));
    }

    @Test
    void commentsAndSeparators() {
        inJoin("/* внешний /* вложенный */ внешний */\n// строка\n# ещё\nplayer::message(\"a\"); player::message(\"b\")\n;;",
                "send_message(\"a\")\nsend_message(\"b\")");
        inJoin("var a = 1 + \\\n 2;", "a = 3");
        inJoin("var a =\n 1\n + 2;", "a = 3");
    }

    @Test
    void russianKeywords() {
        same("функция привет(имя) {\n player::message(имя);\n}\nсобытие<player_join> {\n перем а = 1;\n если а == 1 {\n  привет(\"x\");\n } иначе {\n  привет(\"y\");\n }\n}\n",
                "func привет(имя) {\n send_message(имя)\n}\nwhen player_join {\n а = 1\n if а == 1 {\n привет(\"x\")\n } else {\n привет(\"y\")\n }\n}\n");
    }

    @Test
    void foreignThingsAreExplained() {
        assertTrue(errors("class A {}\n").get(0).contains("классы не поддерживаются"));
        assertTrue(errors("interface A {}\n").get(0).contains("интерфейсы не поддерживаются"));
        assertTrue(errors("event<player_join> { var f = (x) => x + 1; }\n").get(0).contains("лямбды"));
        assertTrue(errors("function f(*a) {}\n").get(0).contains("*a не поддерживается"));
        assertTrue(errors("function f(a) -> number { return 1; }\n@overload\nfunction f(b) {}\n").toString().contains("перегрузки"));
        assertTrue(errors("event<player_join> { var r = 0..5; }\n").get(0).contains("диапазон"));
    }

    @Test
    void errorsPointAtTheRightPlace() {
        String code = "event<player_join> {\n  player::mesage(\"x\");\n}\n";
        Dialect.Built b = JMCC.build(null, code, null);
        Diagnostic d = b.diagnostics().get(0);
        assertEquals("mesage", code.substring(d.start(), d.end()));
        assertTrue(d.message().contains("нет действия"));
        assertTrue(d.hint().contains("player::message"), d.hint());
        String wrong = "event<player_join> {\n  player::teleport(5);\n}\n";
        Diagnostic k = JMCC.build(null, wrong, null).diagnostics().get(0);
        assertEquals("5", wrong.substring(k.start(), k.end()));
        String unknown = "event<player_jon> {}\n";
        Diagnostic e = JMCC.build(null, unknown, null).diagnostics().get(0);
        assertEquals("player_jon", unknown.substring(e.start(), e.end()));
        assertTrue(e.message().contains("нет события"));
    }

    @Test
    void everyServerActionIsReachable() {
        Table t = new Table(SERVICE.data(), SERVICE.names());
        for (String id : SERVICE.data().actions().keySet()) {
            Table.Entry e = t.byId(id);
            assertNotNull(e, id);
            assertEquals(id, t.action(e.object(), e.name()).id(), e.label());
        }
        assertEquals("entity_set_pathfinder_settings", t.action("entity", "set_pathfinder_settings").id());
        assertEquals("if_variable_block_equals", t.action("variable", "block_equals").id());
        inJoin("player::send_message(\"a\");", "send_message(\"a\")");
        inJoin("world::spawn_item_display(location(1, 2, 3), \"n\", item(\"stone\"));", "spawn_item_display(loc(1, 2, 3), \"n\", item(stone))");
    }

    @Test
    void staleIdsWarn() {
        String code = "event<player_join> {\n player::send_hover(\"a\");\n player::message(\"b\");\n}\n";
        assertEquals(List.of(), errors(code));
        assertTrue(warnings(code).toString().contains("устарело"), warnings(code).toString());
        String event = "event<player_vehicle_jump> {\n player::message(\"b\");\n}\n";
        assertEquals(List.of(), errors(event));
        assertTrue(warnings(event).toString().contains("устарело"));
        assertEquals(List.of(), errors("event<player_join> { var a = value::server_stopped_time; }\n"));
    }

    @Test
    void oldOrderOfArguments() {
        String code = "event<player_join> {\n var l = location(1, 2, 3);\n if (world::block_equals(l, [\"stone\", \"dirt\"])) { player::message(\"x\"); }\n}\n";
        assertEquals(List.of(), errors(code));
        assertTrue(warnings(code).toString().contains("порядок аргументов"), warnings(code).toString());
    }

    @Test
    void circleKeepsMeaning() {
        for (String code : List.of(
                "event<player_join> {\n game var a = 1;\n s`b` = 2;\n l`b` = 3;\n if (a == 1 and player::is_sneaking()) {\n  player::message<all_players>(\"x ${a}\", merging = \"SEPARATE_LINES\");\n } else {\n  repeat::on_range(0, 10) { i ->\n   code::wait(1);\n  }\n }\n}\n",
                "function f(a: number, b = 2) -> number {\n return a + b;\n}\nprocess p(x) {\n repeat::forever() {\n  code::wait(1, time_unit = \"SECONDS\");\n }\n}\nevent<world_start> {\n var t = f(1);\n p(t);\n code::start_process(\"p\", \"DONT_COPY\", args = {\"x\": 1});\n}\n",
                "event<player_chat> {\n var m = {\"a\": 1};\n var l = [1, 2, 3];\n for k, v in m { player::message(k); }\n for i, x in l { player::message(x); }\n for x in l { continue; }\n for i in (0..=10).step_by(2) { break; }\n var q = l[0] + m[\"a\"];\n l[1] = 5;\n m[\"b\"] = item(\"stone\", name = \"&ax\", count = 2);\n var s = sound(\"entity.player.levelup\", 2, 1);\n var p = particle(\"FLAME\", 3);\n var e = potion(\"speed\", 1, 20);\n var b = block(\"stone\");\n var v = vector(1, 2, 3);\n var w = value::current_health<current>;\n var z = w > 1 ? \"a\" : \"b\";\n}\n")) {
            Dialect.Built first = JMCC.build(null, code, null);
            Object one = module(first, code);
            String printed = JMCC.decompile(Json.object(one)).text();
            Dialect.Built back = JMCC.build(null, printed, null);
            Object two = module(back, printed);
            assertNull(CircleTest.difference(CircleTest.normal(Json.object(one)), CircleTest.normal(Json.object(two)), ""), printed);
        }
    }

    @Test
    void dialectFace() {
        assertEquals("jmcc", JMCC.id());
        assertEquals("JMCC", JMCC.title());
        assertEquals("jc", JMCC.extension());
        assertTrue(JMCC.build(null, JMCC.sample(), null).ok());
        assertTrue(JMCC.syntax().keywords().contains("функция"));
        assertEquals("::", JMCC.syntax().joiner());
        List<Service.Item> words = JMCC.vocabulary();
        assertTrue(words.size() > 1300, "слов " + words.size());
        Service.Item message = null;
        for (Service.Item i : words) if (i.label().equals("player::message")) message = i;
        assertNotNull(message);
        assertEquals("action", message.kind());
        assertTrue(message.snippet().startsWith("player::message(${1:messages}"), message.snippet());
        boolean condition = false, event = false, value = false;
        for (Service.Item i : words) {
            condition |= i.kind().equals("condition");
            event |= i.kind().equals("event");
            value |= i.label().equals("value::current_health");
        }
        assertTrue(condition && event && value);
    }

    @Test
    void ideHelpers() {
        String code = "function hi(name) {\n player::message(name, merging = \"SPACES\");\n}\nprocess p() {}\nevent<player_join> {\n hi(\"a\");\n}\n";
        List<Service.Symbol> symbols = JMCC.symbols(code);
        assertEquals(List.of("hi", "p", "player_join"), symbols.stream().map(Service.Symbol::name).toList());
        assertEquals(List.of("function", "process", "event"), symbols.stream().map(Service.Symbol::kind).toList());
        assertEquals("hi", code.substring(symbols.get(0).nameStart(), symbols.get(0).nameEnd()));
        Service.Signature s = JMCC.signature(code, code.indexOf("name,") + 5);
        assertNotNull(s);
        assertTrue(s.label().startsWith("player::message(messages"), s.label());
        assertEquals(1, s.active());
        Service.Signature target = JMCC.signature("player::message<all_players>(", 29);
        assertNotNull(target);
        Service.Signature method = JMCC.signature("x.equals(", 9);
        assertNotNull(method);
        assertTrue(method.label().startsWith("equals("), method.label());
        assertNotNull(JMCC.hover(code, code.indexOf("message") + 2));
        assertTrue(JMCC.complete("player::mess", 12).items().stream().anyMatch(i -> i.label().equals("player::message")));
        assertEquals(new Service.Span(code.indexOf("hi"), code.indexOf("hi") + 2), JMCC.definition(code, code.lastIndexOf("hi") + 1));
        assertEquals("event<player_join> {\n    player::message(\"a\");\n}\n", JMCC.format("event<player_join> {\nplayer::message(\"a\");\n}\n"));
    }

    @Test
    void paint() {
        String line = "player::message<all_players>(g`x`, m\"a${b}\", %player%_x) // c";
        byte[] ink = new byte[line.length()];
        Ink.scan(JMCC.syntax(), JMCC.lexicon(), line, Ink.NORMAL, ink);
        assertEquals(Ink.ACTION, ink[0]);
        assertEquals(Ink.VARIABLE, ink[line.indexOf('g') + 1 > 0 ? line.indexOf("g`") : 0]);
        assertEquals(Ink.COMMENT, ink[line.length() - 1]);
        byte[] more = new byte[3];
        assertEquals(Ink.NORMAL, Ink.scan(JMCC.syntax(), JMCC.lexicon(), "# a", Ink.NORMAL, more));
        assertEquals(Ink.COMMENT, more[0]);
        int state = Ink.scan(JMCC.syntax(), JMCC.lexicon(), "/* /* */", Ink.NORMAL, new byte[8]);
        assertTrue(Ink.inComment(state));
    }
}
