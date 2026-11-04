package xero.compile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemsTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private static Items.Stack stack(String item) {
        List<Object> ops = CompilerTest.ops("give_items(" + item + ")");
        Map<String, Object> op = Json.object(ops.get(0));
        Map<String, Object> arg = Json.object(Json.array(op.get("values")).get(0));
        Map<String, Object> cells = Json.object(arg.get("value"));
        String b64 = Json.string(Json.object(Json.array(cells.get("values")).get(0)).get("item"));
        Items.Stack s = Items.decode(b64);
        assertNotNull(s, b64);
        return s;
    }

    private static String snbt(String item) {
        Items.Stack s = stack(item);
        return s.components() == null ? "" : Nbt.print(s.components());
    }

    private static String back(String item) {
        String text = "when world_start {\n    give_items(" + item + ")\n}\n";
        Compiler.Result r = Compiler.compile(DATA, NAMES, text);
        assertTrue(r.ok(), () -> r.diagnostics().toString());
        String printed = Decompiler.decompile(DATA, NAMES, r.module()).text();
        int from = printed.indexOf("give_items(") + "give_items(".length();
        return (printed.substring(from, printed.lastIndexOf(')'))).replaceAll("\\(\\s+", "(").replaceAll(",\\s+\\)", ")").replaceAll("\\s+", " ").strip().replaceAll(",$", "");
    }

    @Test
    void nameLoreEnchantmentsTags() {
        assertEquals("{'minecraft:custom_name':{text:'Меч героя',color:'aqua',italic:0b},"
                        + "'minecraft:lore':[{text:'Первая',color:'gray',italic:0b},{text:'Вторая',color:'gold',italic:0b}],"
                        + "'minecraft:enchantments':{'minecraft:sharpness':5,'minecraft:unbreaking':3},"
                        + "'minecraft:unbreakable':{},"
                        + "'minecraft:custom_data':{PublicBukkitValues:{'justcreativeplus:kind':'hero'}}}",
                snbt("item(diamond_sword, name: \"&bМеч героя\", lore: [\"Первая\", \"&6Вторая\"], "
                        + "enchantments: {sharpness: 5, unbreaking: 3}, unbreakable: true, tags: {kind: \"hero\"})"));
    }

    @Test
    void sameBytesAsTheServerProbe() {
        Items.Stack s = stack("item(diamond_sword, name: \"&bМеч героя\")");
        assertEquals("minecraft:diamond_sword", s.id());
        assertEquals(1, s.count());
    }

    @Test
    void componentOrderFollowsTheCall() {
        assertEquals("{'minecraft:enchantment_glint_override':1b,'minecraft:custom_name':{text:'A',italic:0b}}",
                snbt("item(paper, glint: true, name: \"A\")"));
    }

    @Test
    void legacyRuns() {
        assertEquals("{'minecraft:custom_name':{text:'',italic:0b,extra:[{text:'a',color:'red'},"
                        + "{text:'b',color:'red',bold:1b},{text:'c'},{text:'d',color:'#12AB34'}]}}",
                snbt("item(paper, name: \"&ca&lb&rc&#12ab34d\")"));
    }

    @Test
    void miniMessage() {
        assertEquals("{'minecraft:custom_name':{text:'',italic:0b,extra:[{text:'a',color:'red'},"
                        + "{text:'b',color:'red',bold:1b},{text:'c',color:'red'},{text:'d'}]}}",
                snbt("item(paper, name: mm\"<red>a<b>b</b>c</red>d\")"));
        assertEquals("{'minecraft:custom_name':{text:'',italic:0b,extra:[{text:'a',color:'#FF0000'},"
                        + "{text:'b',color:'#0000FF'}]}}",
                snbt("item(paper, name: mm\"<gradient:#ff0000:#0000ff>ab</gradient>\")"));
        assertEquals("{'minecraft:custom_name':{text:'<nope>x',italic:0b}}",
                snbt("item(paper, name: mm\"<nope>x\")"));
    }

    @Test
    void plainAndJson() {
        assertEquals("{'minecraft:custom_name':{text:'&cне цвет',italic:0b}}", snbt("item(paper, name: plain\"&cне цвет\")"));
        assertEquals("{'minecraft:custom_name':{text:'J',color:'red',bold:1b}}",
                snbt("item(paper, name: json\"{\\\"text\\\":\\\"J\\\",\\\"color\\\":\\\"red\\\",\\\"bold\\\":true}\")"));
    }

    @Test
    void rawComponentsAndSugar() {
        assertEquals("{'minecraft:damage':3,'minecraft:custom_model_data':{floats:[7.0f]},"
                        + "'minecraft:max_stack_size':1,'minecraft:rarity':'epic','minecraft:tooltip_display':{hide_tooltip:1b}}",
                snbt("item(stick, damage: 3, model: 7, components: \"{max_stack_size:1,rarity:epic}\", hide_tooltip: true)"));
    }

    @Test
    void decompilerPrintsReadableItems() {
        String item = "item(diamond_sword, name: \"&bМеч героя\", lore: [\"Первая\", \"&6Вторая &6&lжирная\"], "
                + "enchantments: {sharpness: 5, unbreaking: 3}, unbreakable: true, tags: {kind: \"hero\"})";
        assertEquals(item, back(item));
        assertEquals("item(stone, 5)", back("item(stone, 5)"));
        assertEquals("item(paper, glint: false, hide_tooltip: true, damage: 2, model: 1.5)",
                back("item(paper, glint: false, hide_tooltip: true, damage: 2, model: 1.5)"));
        assertEquals("item(stick, name: \"A\", components: \"{'minecraft:max_stack_size':1}\")",
                back("item(stick, name: \"A\", components: \"{max_stack_size:1}\")"));
        assertEquals("item(stick, components: \"{'minecraft:max_stack_size':1,'minecraft:custom_name':{text:'A',italic:0b},"
                        + "'minecraft:rarity':'epic'}\")",
                back("item(stick, components: \"{max_stack_size:1,custom_name:{text:'A',italic:0b},rarity:epic}\")"));
    }

    @Test
    void errorsAndWarnings() {
        assertEquals(List.of("в предмете текст постоянный — ${…} здесь не подставится"),
                CompilerTest.errors("when world_start {\n x = 1\n give_items(item(paper, name: \"${x}\"))\n}"));
        assertEquals(List.of("unbreakable: false — просто не пиши unbreakable"),
                CompilerTest.errors("when world_start {\n give_items(item(paper, unbreakable: false))\n}"));
        assertEquals(List.of("SNBT: ожидалась «,» или «}»"),
                CompilerTest.errors("when world_start {\n give_items(item(paper, components: \"{a:1 b:2}\"))\n}"));
        assertEquals(List.of("компонент «minecraft:custom_name» задан дважды"),
                CompilerTest.errors("when world_start {\n give_items(item(paper, name: \"a\", components: \"{custom_name:'b'}\"))\n}"));
        Compiler.Result r = Compiler.compile(DATA, NAMES, "when player_join {\n give_items(item(paper, enchantments: {sharpnes: 5}))\n}");
        assertTrue(r.ok());
        Diagnostic w = r.diagnostics().get(0);
        assertEquals("нет чар «sharpnes» в Minecraft 26.2 — уйдут как есть", w.message());
        assertEquals("возможно, вы имели в виду «sharpness»", w.hint());
    }

    @Test
    void snbtRoundTrip() throws Nbt.Error {
        for (String s : List.of("{a:1b,b:2s,c:3,d:4L,e:1.5f,f:2.5d,g:'x',h:[1,2],i:[B;1b,2b],j:[I;1,2],k:[L;1L],l:[],m:{}}",
                "{extra:['',{text:'a'}],x:'it\\'s',y:'a\\\\b'}", "{'minecraft:x':-1.0E-5d,'':'empty key'}")) {
            Nbt.Tag t = Nbt.parse(s);
            assertTrue(Nbt.same(t, Nbt.parse(Nbt.print(t))), s);
        }
        assertEquals("{a:1b,b:0b,c:'word',d:1.0d}", Nbt.print(Nbt.parse("{a:true,b:false,c:word,d:1.0}")));
        assertThrows(Nbt.Error.class, () -> Nbt.parse("{a:300b}"));
        assertThrows(Nbt.Error.class, () -> Nbt.parse("{a:'x}"));
        assertThrows(Nbt.Error.class, () -> Nbt.parse("[I;1b]"));
    }

    @Test
    void worldItemsAreReadableAndExact() throws IOException {
        Path world = Path.of("..", "..", "dump", "world-5c2d3902-2026-10-16.json");
        Assumptions.assumeTrue(Files.isRegularFile(world), "нет кода плота");
        String json = Files.readString(world, StandardCharsets.UTF_8);
        Set<String> items = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"item\"\\s*:\\s*\"(H4sI[A-Za-z0-9+/=]+)\"").matcher(json);
        while (m.find()) items.add(m.group(1));
        assertTrue(items.size() >= 20, "предметов " + items.size());
        List<String> failed = new ArrayList<>();
        for (String b64 : items) {
            Map<String, Object> module = Json.object(Json.parse("{\"handlers\":[{\"type\":\"event\",\"event\":\"world_start\","
                    + "\"position\":0,\"operations\":[{\"action\":\"player_give_items\",\"values\":[{\"name\":\"items\","
                    + "\"value\":{\"type\":\"array\",\"values\":[{\"type\":\"item\",\"item\":\"" + b64 + "\"}]}}]}]}]}"));
            String text = Decompiler.decompile(DATA, NAMES, module).text();
            if (text.contains("H4sI")) failed.add("base64: " + text);
            Compiler.Result r = Compiler.compile(DATA, NAMES, text);
            if (!Json.write(r.module()).contains(b64)) failed.add("не тот предмет: " + text);
        }
        assertEquals(List.of(), failed);
    }
}
