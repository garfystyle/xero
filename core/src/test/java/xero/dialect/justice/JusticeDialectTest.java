package xero.dialect.justice;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import xero.compile.Compiler;
import xero.compile.Delivery;
import xero.compile.Items;
import xero.compile.Nbt;
import xero.dialect.Dialect;
import xero.dialect.Ink;
import xero.ide.Service;
import xero.json.Json;
import xero.lang.Diagnostic;

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

class JusticeDialectTest {
    private static final Service SERVICE = Service.load();
    private static final JusticeDialect JI = new JusticeDialect(SERVICE);
    private static final List<Path> DUMPS = List.of(Path.of("..", "..", "dump"), Path.of("C:/apps/justmc/dump"));
    private static final Path CORPUS = Path.of("src", "test", "resources", "xero", "corpus");

    private static final String README = """
            event player_join {
                player_send_message(messages={ l"&b&oДобро пожаловать!" });
                not if_player_name_equals(names={ "lemonhead2000" }) {
                    call_function(function_name="gift");
                }
            }

            gift {
                repeat_while if_game_has_player(names_or_uuids={ <default>name }) {
                    control_wait(duration=2);
                    player_give_items(items={ item{ id: diamond } });
                }
            }
            """;

    private static final String VALUES = """
            function all_values(is_hidden='TRUE') {
                set_variable_value(variable=x, value=0.123);
                set_variable_value(variable=`Переменная 24`, value=1e-6);
                set_variable_value(variable=g`%player%@balance`, value=%players_online%);
                set_variable_value(variable=s`a`, value=%math(%var(x)+1));
                set_variable_value(variable=line`b`, value="простой");
                player_send_message(messages={ p"p", l"&cl", m"<red>m", j"{}" });
                game_cancel_event;
                set_variable_value(variable=loc, value=location{ 0, -64, 0 });
                set_variable_value(variable=loc, value=location{ -212.5, 31, 12.5, 90, 0 });
                set_variable_value(variable=vec, value=vector{ 0, 1, 2 });
                set_variable_value(variable=it, value=item{ id: diamond, count: 1, components: { item_name: "Изумруд" } });
                set_variable_value(variable=it, value=item{});
                set_variable_value(variable=s1, value=sound{ "entity.player.death" });
                set_variable_value(variable=s2, value=sound{ "block.note_block.ping", pitch=2, source="BLOCKS" });
                set_variable_value(variable=p1, value=potion{ "speed" });
                set_variable_value(variable=p2, value=potion{ "strength", amplifier=1, duration=400 });
                set_variable_value(variable=e1, value=particle{ "DUST", color=#f0f0f0, count=5 });
                set_variable_value(variable=e2, value=particle{ "CLOUD", motion={ 0, 1, 0 }, spread={ 1, 1 }, count=10 });
                set_variable_value(variable=b, value=block{ "composter" });
                set_variable_value(variable=b, value=block{});
                set_variable_value(variable=m, value=map{ "a"=1, l"b"=<>selected_target_names });
                set_variable_value(variable=l1, value={ 1, _, { 2, 3 } });
                set_variable_value(variable=l2, value={ });
                set_variable_value(variable=n, value=number{ "1" });
                if_variable_exists(variable=x) {
                } else {
                    control_wait(duration=2, time_unit=enum{ 'TICKS', g`mode` });
                }
                else not if_variable_exists(variable=x) {
                }
            }
            """;

    private static Dialect.Built build(String text) {
        return JI.build("test.ji", text, null);
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

    private static Object field(Object op, String name) {
        for (Object v : Json.array(Json.object(op).get("values"))) {
            Map<String, Object> e = Json.object(v);
            if (name.equals(e.get("name"))) return e.get("value");
        }
        return null;
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

    private static void collect(Object o, List<String> texts, List<String> items) {
        if (o instanceof Map<?, ?> m) {
            if ("text".equals(m.get("type"))) texts.add(m.get("parsing") + ":" + m.get("text"));
            if ("item".equals(m.get("type")) && m.get("item") instanceof String s && !s.equals(Items.EMPTY)) {
                Nbt.Compound root = Stacks.decode(s);
                items.add(root == null ? s : Nbt.print(root));
            }
            for (Object v : m.values()) collect(v, texts, items);
        } else if (o instanceof List<?> l) {
            for (Object v : l) collect(v, texts, items);
        }
    }

    private static Path world(String name) {
        for (Path dump : DUMPS) if (Files.isRegularFile(dump.resolve(name))) return dump.resolve(name);
        return null;
    }

    private static Map<String, Object> load(Path file) throws IOException {
        return Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
    }

    private static void circle(Map<String, Object> given, String title) {
        Map<String, Object> module = Json.object(Json.parse(Json.write(given)));
        Dialect.Decompiled text = JI.decompile(module);
        Dialect.Built back = build(text.text());
        assertEquals(List.of(), errors(back), title);
        Map<String, Object> parsed = Json.object(Json.parse(Json.write(back.module())));
        assertEquals(normal(module), normal(parsed), title + ": смысл изменился");
        List<String> texts = new ArrayList<>(), items = new ArrayList<>(), textsBack = new ArrayList<>(), itemsBack = new ArrayList<>();
        collect(module, texts, items);
        collect(parsed, textsBack, itemsBack);
        texts.sort(null);
        textsBack.sort(null);
        items.sort(null);
        itemsBack.sort(null);
        assertEquals(texts, textsBack, title + ": текст изменился");
        assertEquals(items, itemsBack, title + ": предметы изменились");
        assertEquals(text.text(), JI.decompile(parsed).text(), title + ": печать не устоялась");
    }

    @Test
    void namesAndSample() {
        assertEquals("justice", JI.id());
        assertEquals("JustIce", JI.title());
        assertEquals("ji", JI.extension());
        Dialect.Built b = build(JI.sample());
        assertTrue(b.ok(), b.diagnostics().toString());
        assertEquals(List.of(), b.diagnostics());
    }

    @Test
    void readmeExampleBuilds() {
        Map<String, Object> module = built(README);
        List<Object> lines = handlers(module);
        assertEquals("event", Json.object(lines.get(0)).get("type"));
        assertEquals("player_join", Json.object(lines.get(0)).get("event"));
        assertEquals("function", Json.object(lines.get(1)).get("type"));
        assertEquals("gift", Json.object(lines.get(1)).get("name"));
        Object negated = ops(lines.get(0)).get(1);
        assertEquals(Boolean.TRUE, Json.object(negated).get("is_inverted"));
        Object loop = ops(lines.get(1)).get(0);
        assertEquals("repeat_while", Json.object(loop).get("action"));
        assertEquals("if_game_has_player", Json.object(Json.object(loop).get("conditional")).get("action"));
        Object cell = Json.array(Json.object(field(loop, "names_or_uuids")).get("values")).get(0);
        assertEquals("{\"type\":\"default\"}", Json.object(cell).get("selection"));
    }

    @Test
    void everyKindOfValueSurvives() {
        Map<String, Object> module = built(VALUES);
        circle(module, "значения");
        List<Object> list = ops(handlers(module).get(0));
        assertEquals("game", Json.object(field(list.get(2), "variable")).get("scope"));
        assertEquals("%players_online%", Json.object(field(list.get(2), "value")).get("number"));
        assertEquals("%math(%var(x)+1)", Json.object(field(list.get(3), "value")).get("number"));
        assertEquals("line", Json.object(field(list.get(4), "variable")).get("scope"));
        assertEquals("plain", Json.object(field(list.get(4), "value")).get("parsing"));
        assertFalse(JI.decompile(module).text().contains("raw{"), JI.decompile(module).text());
    }

    @Test
    void itemsAreTheServerStack() {
        Object value = field(ops(handlers(built(
                "function f { set_variable_value(variable=x, value=item{ id: diamond, count: 2, components: { unbreakable: {} } }); }")).get(0)).get(0), "value");
        String base64 = Json.string(Json.object(value).get("item"));
        Items.Stack stack = Items.decode(base64);
        assertNotNull(stack);
        assertEquals("minecraft:diamond", stack.id());
        assertEquals(2, stack.count());
        assertEquals(Items.encode("minecraft:stick", 1), Json.object(field(ops(handlers(built(
                "function f { set_variable_value(variable=x, value=item{ id: stick }); }")).get(0)).get(0), "value")).get("item"));
    }

    @Test
    void acceptsAModuleStraightFromTheCompiler() {
        Compiler.Result r = Compiler.compile(SERVICE.data(), SERVICE.names(), """
                when player_join {
                    x = particle(dust, count: 3, color: "#ff0000", size: 2)
                    send_message("Привет")
                }
                """);
        assertTrue(r.ok(), r.diagnostics().toString());
        circle(r.module(), "из компилятора");
        assertTrue(JI.decompile(r.module()).text().contains("particle{ \"DUST\""), JI.decompile(r.module()).text());
    }

    @Test
    void unknownFieldsAreNotLost() {
        Map<String, Object> module = Json.object(Json.parse("""
                {"handlers":[
                {"type":"event","event":"player_join","position":3,"operations":[
                  {"action":"player_send_message","weird":7,"values":[{"name":"messages","value":{"type":"text","text":"a","parsing":"plain","extra":1}}],"operations":[
                    {"action":"control_wait","values":[{"name":"duration","value":{"type":"future","a":1,"b":"x","c":true}}]}
                  ]},
                  {"action":"control_wait","values":[{"name":"duration","value":{"type":"number","number":{"odd":[1]}}}]}
                ]},
                {"type":"strange","position":7,"payload":[1,2,3]},
                {"type":"function","name":"f g","values":[{"name":"parameters","value":{"type":"array","values":[
                  {"type":"parameter","type_key":"singular","name":"a","value_type":"number","is_required":"true","default_value":"{}","slot":0,"description_slot":9}
                ]}}],"position":8,"operations":[]}
                ]}
                """));
        Dialect.Decompiled text = JI.decompile(module);
        assertEquals(List.of(), text.notes());
        Map<String, Object> back = built(text.text());
        assertEquals(normal(module), normal(back), text.text());
        assertTrue(text.text().contains("@line(3)"), text.text());
        assertTrue(text.text().contains("parameter{"), text.text());
        assertTrue(text.text().contains("future{"), text.text());
    }

    @Test
    void textEscapesSurvive() {
        Map<String, Object> module = Json.object(Json.parse("""
                {"handlers":[{"type":"event","event":"player_join","position":0,"operations":[
                  {"action":"player_send_message","values":[{"name":"messages","value":{"type":"array","values":[
                    {"type":"text","text":"кавычка \\" и слэш \\\\ и \\\\n и настоящий\\nперенос\\tтаб","parsing":"plain"},
                    {"type":"variable","variable":"a`b","scope":"game"},
                    {"type":"enum","enum":"it's"}
                  ]}}]}
                ]}]}
                """));
        circle(module, "экранирование");
    }

    @Test
    void errorsAreInRussianAndInPlace() {
        String broken = "event player_join {\n    player_send_message(messages=);\n}\n";
        Diagnostic d = build(broken).diagnostics().stream().filter(Diagnostic::error).findFirst().orElseThrow();
        assertEquals(")", broken.substring(d.start(), d.end()));
        assertTrue(d.message().contains("значение"), d.message());
        assertFalse(errors(build("event player_join {")).isEmpty());
        assertFalse(errors(build("function f { game_cancel_event }")).isEmpty());
        assertFalse(errors(build("function f { x(a=item{ id: }); }")).isEmpty());
        assertFalse(errors(build("function f { x(a=location{ 1 }); }")).isEmpty());
        assertFalse(errors(build("function f { x(a=\"\\q\"); }")).isEmpty());
        assertFalse(errors(build("function f { x(a=z\"t\"); }")).isEmpty());
    }

    @Test
    void parserRecoversAfterAnError() {
        Dialect.Built b = build("""
                event player_join {
                    player_send_message(messages=);
                    game_cancel_event;
                }
                event player_quit {
                    game_cancel_event;
                }
                """);
        assertEquals(1, errors(b).size());
        List<Object> lines = handlers(b.module());
        assertEquals(2, lines.size());
        assertEquals(1, ops(lines.get(0)).size());
    }

    @Test
    void unknownNamesAreWarningsWithHints() {
        Dialect.Built b = build("event player_jion { player_send_mesage(); player_send_message(mesages={ \"a\" }); }");
        assertTrue(b.ok());
        assertEquals(3, b.diagnostics().size());
        assertEquals("возможно, вы имели в виду «player_join»", b.diagnostics().get(0).hint());
        assertEquals("возможно, вы имели в виду «player_send_message»", b.diagnostics().get(1).hint());
        assertEquals("возможно, вы имели в виду «messages»", b.diagnostics().get(2).hint());
    }

    @Test
    void worldSurvivesTheCircle() throws IOException {
        Path file = world("world-5c2d3902-2026-10-22.json");
        Assumptions.assumeTrue(file != null, "нет снимка мира");
        Map<String, Object> module = load(file);
        assertEquals(76, handlers(module).size());
        circle(module, "мир 22.10");
        assertFalse(JI.decompile(module).text().contains("raw{"));
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
        assertNotNull(b.module());
        for (Diagnostic d : b.diagnostics()) {
            assertFalse(d.message().startsWith("сборка не удалась"), d.message() + "\n--- на тексте ---\n" + text);
            assertTrue(d.start() >= 0 && d.start() <= text.length() + 1, d.toString());
        }
        JI.symbols(text);
        JI.format(text);
    }

    @Test
    void cutsNeverBreakTheParser() throws IOException {
        for (int i = 0; i <= VALUES.length(); i++) survives(VALUES.substring(0, i));
        for (int i = 0; i <= VALUES.length(); i += 3) survives(VALUES.substring(i));
        Path file = world("world-5c2d3902-2026-10-22.json");
        String big = file == null ? VALUES.repeat(20) : JI.decompile(load(file)).text();
        String part = big.substring(0, Math.min(big.length(), 60_000));
        Random random = new Random(42);
        for (int i = 0; i < 300; i++) survives(part.substring(0, random.nextInt(part.length())));
    }

    @Test
    void noiseNeverBreaksTheParser() {
        String alphabet = "(){}[]\"'`@#$%:;,.=+-*/!?<>&|\\ \n\tabc019_фя";
        Random random = new Random(7);
        for (int i = 0; i < 3000; i++) {
            StringBuilder s = new StringBuilder();
            int length = random.nextInt(200);
            for (int k = 0; k < length; k++) s.append(alphabet.charAt(random.nextInt(alphabet.length())));
            survives(s.toString());
        }
        for (int i = 0; i < 1000; i++) {
            StringBuilder s = new StringBuilder(VALUES);
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
        for (String piece : List.of("{", "x {", "a(b=", "a(b={ ", "map{ ", "enum{ 'A', ", "item{ a: ")) {
            String text = "function f {\n" + piece.repeat(20_000);
            survives(text);
            assertFalse(errors(build(text)).isEmpty(), piece);
        }
    }

    @Test
    void vocabularyIsInTheNotationOfTheLanguage() {
        Map<String, Byte> lexicon = JI.lexicon();
        assertEquals(Byte.valueOf(Ink.ACTION), lexicon.get("player_send_message"));
        assertEquals(Byte.valueOf(Ink.CONDITION), lexicon.get("if_player_is_sneaking"));
        assertEquals(Byte.valueOf(Ink.EVENT), lexicon.get("player_join"));
        assertEquals(Byte.valueOf(Ink.VALUE), lexicon.get("current_health"));
        assertEquals(Byte.valueOf(Ink.KEYWORD), lexicon.get("function"));
        Service.Item send = find("player_send_message");
        assertEquals("action", send.kind());
        assertTrue(send.detail().contains("Отправить сообщение"), send.detail());
        assertEquals("player_send_message($1);$0", send.snippet());
        assertEquals("repeat_on_range($1) {\n\t$0\n}", find("repeat_on_range").snippet());
        assertEquals("player_join {\n\t$0\n}", find("player_join").snippet());
        assertEquals("<default>current_health", find("current_health").snippet());
    }

    private static Service.Item find(String label) {
        for (Service.Item i : JI.vocabulary()) if (i.label().equals(label)) return i;
        throw new AssertionError("нет в словаре: " + label);
    }

    @Test
    void editorQueries() {
        String text = "event player_join {\n    player_send_message(messages={ \"a\" }, );\n}\n\nfunction greet {\n}\n\nprocess `my tick` {\n}\n\nbare {\n}\n";
        List<Service.Symbol> symbols = JI.symbols(text);
        assertEquals(List.of("player_join", "greet", "my tick", "bare"), symbols.stream().map(Service.Symbol::name).toList());
        assertEquals(List.of("event", "function", "process", "function"), symbols.stream().map(Service.Symbol::kind).toList());
        Service.Symbol greet = symbols.get(1);
        assertEquals("greet", text.substring(greet.nameStart(), greet.nameEnd()));
        Service.Signature signature = JI.signature(text, text.indexOf(", )") + 2);
        assertNotNull(signature);
        assertTrue(signature.label().startsWith("player_send_message(messages="), signature.label());
        assertEquals(1, signature.active());
        assertNotNull(JI.hover(text, text.indexOf("player_send_message") + 3));
        assertTrue(JI.complete("function f { player_send_mes", 28).items().stream().anyMatch(i -> i.label().equals("player_send_message")));
    }
}
