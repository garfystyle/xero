package xero.dialect.jmscript;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import xero.compile.Compiler;
import xero.compile.Decompiler;
import xero.compile.Delivery;
import xero.dialect.Dialect;
import xero.dialect.Ink;
import xero.ide.Service;
import xero.json.Json;
import xero.lang.Diagnostic;
import xero.lang.Parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JmscriptDialectTest {
    private static final Service SERVICE = Service.load();
    private static final JmscriptDialect JMS = new JmscriptDialect(SERVICE);
    private static final List<Path> DUMPS = List.of(Path.of("..", "..", "dump"), Path.of("C:/apps/justmc/dump"));
    private static final Path CORPUS = Path.of("src", "test", "resources", "xero", "corpus");

    private static final String COMPASS = """
            package compass

            @(name="Update compass for player")
            @(desc="My awesome compass function!")
            func update_compass(yaw: number) {
            	bar_content := ""
            	bar_size :: 35
            	step :: 5

            	for i := 0; i < bar_size; i += 1 {
            		curr_angle := yaw + (i - bar_size / 2) * step
            		norm_angle := (curr_angle % 360 + 360) % 360

            		symbol := "&8o"

            		distance_n := abs(norm_angle - 180)
            		if distance_n < 2.5 {
            			symbol := "&fN"
            		}

            		distance_s := abs(norm_angle - 360)
            		distance_s2 := abs(norm_angle - 0)
            		if distance_s < 2.5 || distance_s2 < 2.5 {
            			symbol := "&fS"
            		}

            		distance_e := abs(norm_angle - 270)
            		if distance_e < 2.5 {
            			symbol := "&fE"
            		}

            		distance_w := abs(norm_angle - 90)
            		if distance_w < 2.5 {
            			symbol := "&fW"
            		}

            		bar_content += symbol
            	}

            	player_set_boss_bar("compass", bar_content, 0, "WHITE", "PROGRESS", "FOG_AND_DARK_SKY")
            }

            event player_join() {
            	for {
            		update_compass(10) // value::yaw
            		control_wait(1, "TICKS")
            	}
            }
            """;

    private static Dialect.Built build(String text) {
        return JMS.build("test.jms", text, null);
    }

    private static List<String> errors(Dialect.Built b) {
        return b.diagnostics().stream().filter(Diagnostic::error).map(Diagnostic::message).toList();
    }

    private static Map<String, Object> built(String text) {
        Dialect.Built b = build(text);
        assertEquals(List.of(), errors(b), text);
        return Json.object(Json.parse(Json.write(b.module())));
    }

    private static List<Object> handlers(Map<String, Object> module) {
        return Json.array(module.get("handlers"));
    }

    private static List<Object> ops(Object handler) {
        return Json.array(Json.object(handler).get("operations"));
    }

    private static List<Object> ops(String body) {
        return ops(handlers(built("package main\nevent player_join() {\n" + body + "\n}\n")).get(0));
    }

    private static Object field(Object op, String name) {
        for (Object v : Json.array(Json.object(op).get("values"))) {
            Map<String, Object> e = Json.object(v);
            if (name.equals(e.get("name"))) return e.get("value");
        }
        return null;
    }

    private static String action(Object op) {
        return Json.string(Json.object(op).get("action"));
    }

    private static List<Object> inner(Object op) {
        return Json.array(Json.object(op).get("operations"));
    }

    private static Object normal(Map<String, Object> module) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object h : Json.array(module.get("handlers"))) list.add(Json.object(h));
        list.sort(Comparator.comparingInt(h -> h.get("position") instanceof Json.Num n ? n.intValue() : 0));
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> h : list) {
            Map<String, Object> copy = new TreeMap<>(Json.object(Delivery.normal(SERVICE.data(), h)));
            if (h.get("position") instanceof Json.Num n) copy.put("position", n.intValue());
            out.add(copy);
        }
        return out;
    }

    private static Path world(String name) {
        for (Path dump : DUMPS) if (Files.isRegularFile(dump.resolve(name))) return dump.resolve(name);
        return null;
    }

    private static Map<String, Object> load(Path file) throws IOException {
        return Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
    }

    private static void circle(Map<String, Object> module, String title) {
        Dialect.Decompiled text = JMS.decompile(module);
        Dialect.Built back = build(text.text());
        assertEquals(List.of(), errors(back), title);
        Decompiler.Result xero = Decompiler.decompile(SERVICE.data(), SERVICE.names(), module);
        Compiler.Result direct = Compiler.compile(SERVICE.data(), SERVICE.names(), xero.text());
        assertEquals(Json.pretty(direct.module()), Json.pretty(back.module()), title + ": расходится с кругом через Xero");
        assertEquals(normal(module), normal(Json.object(Json.parse(Json.write(back.module())))), title + ": смысл изменился");
        assertEquals(text.text(), JMS.decompile(Json.object(Json.parse(Json.write(back.module())))).text(), title + ": печать не устоялась");
    }

    @Test
    void namesAndSample() {
        assertEquals("jmscript", JMS.id());
        assertEquals("JMScript", JMS.title());
        assertEquals("jms", JMS.extension());
        Dialect.Built b = build(JMS.sample());
        assertTrue(b.ok(), b.diagnostics().toString());
        assertEquals(List.of(), b.diagnostics());
    }

    @Test
    void compassCompilesToTheExpectedBlocks() {
        Map<String, Object> module = built(COMPASS);
        List<Object> lines = handlers(module);
        assertEquals(2, lines.size());
        Map<String, Object> func = Json.object(lines.get(0));
        assertEquals("update_compass", func.get("name"));
        assertTrue(Json.write(func.get("values")).contains("Update compass for player"));
        assertTrue(Json.write(func.get("values")).contains("My awesome compass function!"));
        List<Object> body = ops(func);
        assertEquals("repeat_while", action(body.get(2)));
        assertEquals(35.0, ((Json.Num) Json.object(field(body.get(2), "compare")).get("number")).value());
        List<Object> loop = inner(body.get(2));
        assertEquals("set_variable_add", action(loop.get(loop.size() - 1)));
        Object concat = loop.get(loop.size() - 2);
        assertEquals("set_variable_text", action(concat));
        assertEquals("CONCATENATION", Json.object(field(concat, "merging")).get("enum"));
        Object bar = body.get(3);
        assertEquals("player_set_boss_bar", action(bar));
        assertEquals("WHITE", Json.object(field(bar, "color")).get("enum"));
        assertEquals("PROGRESS", Json.object(field(bar, "style")).get("enum"));
        assertEquals("FOG_AND_DARK_SKY", Json.object(field(bar, "sky_effect")).get("enum"));
        Object forever = ops(lines.get(1)).get(0);
        assertEquals("repeat_forever", action(forever));
        assertEquals("call_function", action(inner(forever).get(0)));
        assertEquals("TICKS", Json.object(field(inner(forever).get(1), "time_unit")).get("enum"));
    }

    @Test
    void textMarkupIsDetectedLikeTheOriginal() {
        List<Object> list = ops("""
                player_send_message("обычный")
                player_send_message("&cцвет")
                player_send_message("<red>тег")
                player_send_message('{"text":"x"}')
                player_send_message("Том & Джерри")
                player_send_message(plain"&cкак есть")
                player_send_message(legacy"Том & Джерри")
                """);
        List<String> parsings = new ArrayList<>();
        for (Object op : list) {
            Object v = field(op, "messages");
            Map<String, Object> text = Json.object(Json.array(Json.object(v).get("values")).get(0));
            parsings.add(Json.string(text.get("parsing")));
        }
        assertEquals(List.of("legacy", "legacy", "minimessage", "json", "plain", "plain", "legacy"), parsings);
    }

    @Test
    void stringsKeepEscapesAndBothQuotes() {
        Object op = ops("player_send_message(['он сказал \"да\"', \"it's \\\"ok\\\"\", \"строка\\nдальше\"])").get(0);
        assertEquals("он сказал \"да\"", Json.object(Json.array(Json.object(field(op, "messages")).get("values")).get(0)).get("text"));
        Dialect.Built b = build("package main\nevent player_join() { x := 1\n player_send_message(\"значение ${x + 1}\") }");
        assertTrue(b.ok(), b.diagnostics().toString());
        assertTrue(Json.write(b.module()).contains("%math(x+1)"));
    }

    @Test
    void markersBindByOptionNotByOrder() {
        Object wait = ops("control_wait(1, \"SECONDS\")").get(0);
        assertEquals("SECONDS", Json.object(field(wait, "time_unit")).get("enum"));
        Object named = ops("control_wait(1, time_unit=\"MINUTES\")").get(0);
        assertEquals("MINUTES", Json.object(field(named, "time_unit")).get("enum"));
        Dialect.Built wrong = build("package main\nevent player_join() { control_wait(1, \"HOURS\") }");
        assertFalse(wrong.ok());
    }

    @Test
    void classicLoopRunsThePostStatementOnContinue() {
        List<Object> list = ops("""
                for i := 0; i < 3; i += 1 {
                    if i == 1 {
                        continue
                    }
                    player_send_message("шаг")
                }
                """);
        assertEquals("set_variable_value", action(list.get(0)));
        List<Object> loop = inner(list.get(1));
        List<Object> skip = inner(loop.get(0));
        assertEquals("set_variable_add", action(skip.get(0)));
        assertEquals("control_skip_iteration", action(skip.get(1)));
        assertEquals("set_variable_add", action(loop.get(loop.size() - 1)));
    }

    @Test
    void loopsMapToTheServerBlocks() {
        assertEquals("repeat_forever", action(ops("for { break }").get(0)));
        assertEquals("control_stop_repeat", action(inner(ops("for { break }").get(0)).get(0)));
        Object range = ops("for i in 0..<5 { player_send_message(\"x\") }").get(0);
        assertEquals("repeat_on_range", action(range));
        assertEquals(4.0, ((Json.Num) Json.object(field(range, "end")).get("number")).value());
        Object inclusive = ops("for i in 1..=5 step 2 { player_send_message(\"x\") }").get(0);
        assertEquals(5.0, ((Json.Num) Json.object(field(inclusive, "end")).get("number")).value());
        assertEquals(2.0, ((Json.Num) Json.object(field(inclusive, "interval")).get("number")).value());
        Object each = ops("for value, index in [1, 2] { player_send_message(value) }").get(0);
        assertEquals("repeat_for_each_in_list", action(each));
        assertEquals("index", Json.object(field(each, "index_variable")).get("variable"));
        assertEquals("value", Json.object(field(each, "value_variable")).get("variable"));
        Object letters = ops("for c in \"ab\" { player_send_message(c) }").get(0);
        assertEquals(2, Json.array(Json.object(field(letters, "list")).get("values")).size());
        Object map = ops("m := {\"a\": 1}\nfor k: v in m { player_send_message([k, v]) }").get(1);
        assertEquals("repeat_for_each_map_entry", action(map));
        assertEquals("repeat_multi_times", action(ops("repeat_multi_times(amount=3) { control_wait(1) }").get(0)));
        assertEquals(3, Json.array(Json.object(field(ops("l := 0..<3").get(0), "value")).get("values")).size());
        assertEquals(4, Json.array(Json.object(field(ops("l := 0..=3").get(0), "value")).get("values")).size());
        survives("package main\nx := 100000000000000000..<100000000000000100\n");
    }

    @Test
    void deferredStatementsGoToTheEndInReverse() {
        List<Object> list = ops("""
                defer player_send_message("первое")
                defer player_send_message("второе")
                player_send_message("сразу")
                """);
        assertEquals("сразу", text(list.get(0)));
        assertEquals("второе", text(list.get(1)));
        assertEquals("первое", text(list.get(2)));
    }

    private static Object text(Object op) {
        return Json.object(Json.array(Json.object(field(op, "messages")).get("values")).get(0)).get("text");
    }

    @Test
    void topLevelCodeGoesToWorldStartAndItsVariablesAreGame() {
        Map<String, Object> module = built("""
                package main
                counter := 0
                player_send_message("старт", selection="all")
                event player_join() {
                    counter += 1
                }
                """);
        List<Object> lines = handlers(module);
        assertEquals("player_join", Json.object(lines.get(0)).get("event"));
        assertEquals("world_start", Json.object(lines.get(1)).get("event"));
        assertEquals("game", Json.object(field(ops(lines.get(0)).get(0), "variable")).get("scope"));
        assertEquals("game", Json.object(field(ops(lines.get(1)).get(0), "variable")).get("scope"));
    }

    @Test
    void constantsAreInlined() {
        Map<String, Object> module = built("""
                package main
                LIMIT :: 5
                name: text : "имя"
                event player_join() {
                    local_limit :: LIMIT * 2
                    x := local_limit
                    player_send_message([name, x])
                }
                """);
        Object set = ops(handlers(module).get(0)).get(0);
        assertEquals(10.0, ((Json.Num) Json.object(field(set, "value")).get("number")).value());
        assertFalse(errors(build("package main\nevent player_join() { n :: 1\n n = 2 }")).isEmpty());
    }

    @Test
    void selectionsAndValues() {
        Object op = ops("player_send_message(\"x\", selection=\"killer\")").get(0);
        assertEquals("killer_player", Json.object(Json.object(op).get("selection")).get("type"));
        Object byId = ops("player_send_message(\"x\", selection=\"killer_player\")").get(0);
        assertEquals("killer_player", Json.object(Json.object(byId).get("selection")).get("type"));
        Object value = ops("x := game_value(\"current_health\", \"default\")").get(0);
        assertEquals("current_health", Json.object(field(value, "value")).get("game_value"));
        Object condition = ops("if !if_player_is_sneaking(selection=\"default\") { player_send_message(\"x\") }").get(0);
        assertEquals("if_player_is_sneaking", action(condition));
        assertEquals(Boolean.TRUE, Json.object(condition).get("is_inverted"));
    }

    @Test
    void builtinsBecomeServerValues() {
        Object op = ops("""
                player_teleport(location(1, 2, 3, 90, 0))
                """).get(0);
        assertEquals("location", Json.object(field(op, "location")).get("type"));
        assertEquals("vector", Json.object(field(ops("x := vec3(1, 2, 3)").get(0), "value")).get("type"));
        assertEquals("item", Json.object(field(ops("x := item(\"diamond\", 5)").get(0), "value")).get("type"));
        assertEquals("block", Json.object(field(ops("x := block(\"stone\")").get(0), "value")).get("type"));
        Map<String, Object> sound = Json.object(field(ops("x := sound(\"entity.player.levelup\", 2, 0.5)").get(0), "value"));
        assertEquals(2.0, ((Json.Num) sound.get("pitch")).value());
        assertEquals(0.5, ((Json.Num) sound.get("volume")).value());
        assertEquals("potion", Json.object(field(ops("x := potion(\"speed\", 1, 200)").get(0), "value")).get("type"));
        assertEquals("particle", Json.object(field(ops("x := particle(\"FLAME\", 3)").get(0), "value")).get("type"));
        assertEquals("%math(1+1)", Json.object(field(ops("x := number(\"%math(1+1)\")").get(0), "value")).get("number"));
        assertEquals("array", Json.object(field(ops("x := array(1, 2)").get(0), "value")).get("type"));
        assertEquals("map", Json.object(field(ops("x := dict(\"a\", 1)").get(0), "value")).get("type"));
    }

    @Test
    void functionsAndProcesses() {
        Map<String, Object> module = built("""
                package main
                func plus(a: number, b: number) -> number {
                    return a + b
                }
                process tick(who: text = "все") {
                    for { control_wait(20) }
                }
                event player_join() {
                    s := plus(2, 3)
                    start tick(who="я", target_mode="NO_TARGET")
                    player_send_message(s)
                }
                """);
        assertEquals("function", Json.object(handlers(module).get(0)).get("type"));
        assertEquals("process", Json.object(handlers(module).get(1)).get("type"));
        Object started = ops(handlers(module).get(2)).get(2);
        assertEquals("start_process", action(started));
        assertEquals("NO_TARGET", Json.object(field(started, "target_mode")).get("enum"));
    }

    @Test
    void errorsAreInRussianAndInPlace() {
        String unknown = "package main\nevent player_jion() {\n}\n";
        Dialect.Built b = build(unknown);
        assertFalse(b.ok());
        Diagnostic d = b.diagnostics().get(0);
        assertEquals("player_jion", unknown.substring(d.start(), d.end()));
        assertTrue(d.message().contains("нет события"), d.message());

        String nested = "package main\nevent player_join() {\n    func f() {}\n}\n";
        Diagnostic n = build(nested).diagnostics().get(0);
        assertEquals("func", nested.substring(n.start(), n.end()));

        String field = "package main\nevent player_join() {\n    x := foo().bar\n}\n";
        assertTrue(errors(build(field)).stream().anyMatch(m -> m.contains("полей у значений нет")), errors(build(field)).toString());

        String kind = "package main\nevent player_join() {\n    player_teleport(5)\n}\n";
        Diagnostic k = build(kind).diagnostics().get(0);
        assertEquals("5", kind.substring(k.start(), k.end()));

        assertFalse(errors(build("package main\nevent player_join() {")).isEmpty());
        assertFalse(errors(build("package main\nx := 1..<n")).isEmpty());
    }

    @Test
    void annotationsWithoutEffectAreOnlyWarnings() {
        Dialect.Built b = build("""
                package main
                @deprecated
                @(test="x")
                @pure
                @export
                func old_func() {
                }
                @(hidden=true, line=5)
                func hidden_func() {
                }
                """);
        assertEquals(List.of(), errors(b));
        assertTrue(Json.write(b.module()).contains("\"TRUE\""));
        assertEquals(2, b.diagnostics().size());
    }

    private static final String EVERY_FORM = """
            const MAX = 5
            const SWORD = item(diamond_sword, name: "&bМеч", lore: ["Первая", "&6Вторая"], enchantments: {sharpness: 5},
                unbreakable: true, glint: false, damage: 3, tags: {kind: "hero"}, components: "{max_stack_size:1}")
            const HOME = loc(0, 64, 0, 90, -45)
            const DEBUG = false
            game kills
            save `монеты %player%`
            local tmp
            line row

            @display_name("Имя функции")
            @display_description("Первая строка", "Вторая строка")
            @description("старое", _)
            @icon(item(iron_ingot))
            @hidden
            func plus(a: number = 1, b: text = "x", c: vector = vec(1, 2, 3), m: map = {"k": 1}, mode: enum("alpha", "beta") = "beta", who) {
                return a + 1
            }

            @line(10)
            func `WE Load`(region) {
                save.coins = 1
                game.commands = [1, 2]
                local.`с пробелом` = 2
            }

            process timer(what: any) {
                forever {
                    wait(1, time_unit: seconds)
                    if what == 1 {
                        break
                    }
                    continue
                }
            }

            when player_join {
                x = 1
                game round = 0
                save coins = 100
                line t = 0
                save other
                coins += 10
                coins -= 1
                coins *= 2
                coins /= 2
                coins %= 3
                a, b = value.current_health, value.food_level
                save c, d = 1, 2
                l = [10, 20, _, 30]
                m = {"hp": 20, "mp": [1, 2]}
                y = l[1]
                l[0] = 5
                l[2] += 1
                m["hp"] = m["hp"] - 5
                g = [[1, 2], [3, 4]]
                g[1][0] = 9
                h = x * 2 + (a - 1) / 3 % 2
                i = -x + -5
                j = sqrt(x * x) + clamp(a, 0, 20) + pow(2, 3)
                k = clamp(a, 0, 20)
                n = abs(x)
                o = x > 3 and a == 1 or not b != 2
                p = x > 3 ? "да" : "нет"
                q = x in [1, 2, 3]
                r = has_item(item(diamond))
                s = @killer value.current_health
                u = value.player_count
                v = "Счёт: ${coins}, осталось ${x + 1}, здоровье ${value.current_health} ${l[0]} ${m["hp"]} ${len(l)} \\${нет}"
                w = len(l) - 1
                z = plus(2, "y", who: 1)
                aa = plus(1, who: 2) + plus(3, who: 4)
                ab = get_list_value([1, 2, 3], 1)
                ac = num("%math(%var(x)+1)")
                ad = vec(1, 2, 3) + vec(0, 1, 0)
                ae = HOME + vec(1, 0, 0)
                af = mm"<red>текст"
                ag = plain"&cкак есть"
                ah = json"{\\"text\\":\\"x\\"}"
                ai = legacy"&cцвет"
                aj = sound(entity.player.levelup, volume: 1, pitch: 2, variation: "", source: master)
                ak = sound("block.lava.extinguish", pitch: 2)
                al = particle(flame, count: 3, first_spread: 0.5, second_spread: 0.5)
                am = particle(dust, color: "#ff0000", size: 2)
                an = potion(speed, amplifier: 1, duration: 600)
                ao = block(composter)
                ap = raw("{\\"type\\":\\"text\\",\\"text\\":\\"x\\",\\"parsing\\":\\"plain\\"}")
                aq = enum("ERROR", x)
                ar = "кавычка \\" и слэш \\\\ и перенос \\n"
                if coins >= 100 {
                    send_message("Богач")
                } else if not coins > 0 {
                    send_message("Пусто")
                } else {
                    send_message(["Есть", "немного"])
                }
                if @killer has_item(item(diamond)) and x == 1 {
                    @all send_message("Всем")
                }
                if x in 1..5 or x in [7, 8] {
                    _
                }
                if x not in l and "hp" in m {
                    give_items([item(diamond, 5), SWORD])
                }
                if x == 2 {
                    player.teleport(HOME)
                }
                if DEBUG {
                    send_message("никогда")
                }
                repeat 10 {
                    wait(5)
                }
                for i2 in 1..10 {
                    send_message("${i2}")
                }
                for i3 in 0..100 step 5 {
                    send_message("${i3}")
                }
                for e in l {
                    send_message("${e}")
                }
                for i4, e2 in l {
                    send_message("${i4} ${e2}")
                }
                for k2: v2 in m {
                    send_message("${k2} ${v2}")
                }
                while x < 5 and a == 0 {
                    x += 1
                }
                while x < 5 {
                    x += 1
                }
                while x in [1, 2] {
                    x += 1
                }
                repeat.on_sphere(`точка`, HOME, 5, 10) {
                    wait(1)
                }
                filter_by_conditional(not player.name_equals(value.uuid))
                exception(`#ошибка`, exception_type: enum("ERROR")) {
                    wait(1)
                }
                raw("{\\"action\\":\\"control_wait\\",\\"values\\":[]}")
                raw("{\\"action\\":\\"repeat_forever\\",\\"values\\":[]}") {
                    wait(1)
                }
                start timer(1)
                start timer(what: 2, local_variables_mode: copy, target_mode: no_target)
                `WE Load`(region: "a")
                `Чужая функция`(region: "a")
                call(x, a: 1)
                plus(1, who: 3)
            }
            """;

    @Test
    void everyFormOfTheAstPrintsAndCompilesTheSame() {
        Compiler.Result direct = Compiler.compile(SERVICE.data(), SERVICE.names(), EVERY_FORM);
        assertEquals(List.of(), direct.diagnostics().stream().filter(Diagnostic::error).map(Diagnostic::message).toList());
        String printed = Printer.print(SERVICE.names(), Parser.parse(EVERY_FORM, new ArrayList<>()));
        Dialect.Built jms = build(printed);
        assertEquals(List.of(), errors(jms), printed);
        assertEquals(Json.pretty(direct.module()), Json.pretty(jms.module()), printed);
    }

    @Test
    void worldSurvivesTheCircle() throws IOException {
        Path file = world("world-5c2d3902-2026-10-22.json");
        Assumptions.assumeTrue(file != null, "нет снимка мира");
        Map<String, Object> module = load(file);
        assertEquals(76, handlers(module).size());
        circle(module, "мир 22.10");
    }

    @Test
    void otherSnapshotsSurviveTheCircle() throws IOException {
        Path file = world("world-5c2d3902-2026-10-16.json");
        if (file != null) circle(load(file), "мир 16.10");
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(CORPUS, "*.json")) {
            for (Path p : dir) circle(load(p), p.getFileName().toString());
        }
    }

    private static void survives(String text) {
        Dialect.Built b = build(text);
        assertNotNull(b.diagnostics());
        for (Diagnostic d : b.diagnostics()) {
            assertFalse(d.message().startsWith("сборка не удалась"), d.message() + "\n--- на тексте ---\n" + text);
            assertTrue(d.start() >= 0 && d.start() <= text.length() + 1, d.toString());
        }
        JMS.symbols(text);
        JMS.format(text);
    }

    @Test
    void cutsNeverBreakTheParser() throws IOException {
        for (int i = 0; i <= COMPASS.length(); i++) survives(COMPASS.substring(0, i));
        for (int i = 0; i <= COMPASS.length(); i += 3) survives(COMPASS.substring(i));
        Path file = world("world-5c2d3902-2026-10-22.json");
        String big = file == null ? COMPASS.repeat(20) : JMS.decompile(load(file)).text();
        String part = big.substring(0, Math.min(big.length(), 60_000));
        Random random = new Random(42);
        for (int i = 0; i < 300; i++) survives(part.substring(0, random.nextInt(part.length())));
    }

    @Test
    void noiseNeverBreaksTheParser() {
        String alphabet = "(){}[]\"'`@#$%:;,.=+-*/!?<>&|\\ \n\tabc019_фя";
        Random random = new Random(7);
        for (int i = 0; i < 3000; i++) {
            StringBuilder s = new StringBuilder("package main\n");
            int length = random.nextInt(200);
            for (int k = 0; k < length; k++) s.append(alphabet.charAt(random.nextInt(alphabet.length())));
            survives(s.toString());
        }
        for (int i = 0; i < 1000; i++) {
            StringBuilder s = new StringBuilder(COMPASS);
            for (int k = 0; k < 8; k++) {
                int at = random.nextInt(s.length());
                if (random.nextBoolean()) s.deleteCharAt(at);
                else s.insert(at, alphabet.charAt(random.nextInt(alphabet.length())));
            }
            survives(s.toString());
        }
    }

    @Test
    void deepNestingIsAnErrorNotACrash() {
        for (String piece : List.of("(", "[", "{", "-", "!", "for {", "if x {", "f(", "a[")) {
            String text = "package main\nevent player_join() {\n" + piece.repeat(20_000);
            survives(text);
            assertFalse(errors(build(text)).isEmpty(), piece);
        }
        survives("package main\nx := " + "1 + ".repeat(50_000) + "1\n");
    }

    @Test
    void vocabularyIsInTheNotationOfTheLanguage() {
        Map<String, Byte> lexicon = JMS.lexicon();
        assertEquals(Byte.valueOf(Ink.ACTION), lexicon.get("player_send_message"));
        assertEquals(Byte.valueOf(Ink.CONDITION), lexicon.get("if_player_is_sneaking"));
        assertEquals(Byte.valueOf(Ink.EVENT), lexicon.get("player_join"));
        assertEquals(Byte.valueOf(Ink.VALUE), lexicon.get("current_health"));
        assertEquals(Byte.valueOf(Ink.KEYWORD), lexicon.get("func"));
        Service.Item send = find("player_send_message");
        assertEquals("action", send.kind());
        assertTrue(send.detail().contains("Отправить сообщение"), send.detail());
        assertTrue(send.doc().contains("messages"), send.doc());
        assertEquals("repeat_on_range($1) {\n\t$0\n}", find("repeat_on_range").snippet());
        assertEquals("player_join() {\n\t$0\n}", find("player_join").snippet());
        assertEquals("game_value(\"current_health\")", find("current_health").snippet());
    }

    private static Service.Item find(String label) {
        for (Service.Item i : JMS.vocabulary()) if (i.label().equals(label)) return i;
        throw new AssertionError("нет в словаре: " + label);
    }

    @Test
    void editorQueries() {
        String text = "package main\n\nfunc greet(who: text) {\n}\n\nprocess tick() {\n}\n\nevent player_join() {\n    player_send_message(\"a\", )\n}\n\nx := 1\n";
        List<Service.Symbol> symbols = JMS.symbols(text);
        assertEquals(List.of("greet", "tick", "player_join"), symbols.stream().map(Service.Symbol::name).toList());
        assertEquals(List.of("function", "process", "event"), symbols.stream().map(Service.Symbol::kind).toList());
        Service.Symbol greet = symbols.get(0);
        assertEquals("greet", text.substring(greet.nameStart(), greet.nameEnd()));
        int at = text.indexOf(", )") + 2;
        Service.Signature signature = JMS.signature(text, at);
        assertNotNull(signature);
        assertTrue(signature.label().startsWith("player_send_message(messages"), signature.label());
        assertEquals(1, signature.active());
        assertEquals(2 + SERVICE.data().actions().get("player_send_message").settings().size() - 1, signature.params().size());
        assertNotNull(JMS.hover(text, text.indexOf("player_send_message") + 3));
        assertEquals(text.indexOf("greet"), JMS.definition(text, text.indexOf("greet") + 1).start());
        assertTrue(JMS.complete("package main\nplayer_send_mes", 28).items().stream().anyMatch(i -> i.label().equals("player_send_message")));
    }
}
