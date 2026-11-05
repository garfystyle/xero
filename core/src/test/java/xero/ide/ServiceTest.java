package xero.ide;

import org.junit.jupiter.api.Test;
import xero.lang.Diagnostic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceTest {
    @Test
    void otherFilesOfTheProject() {
        Service s = Service.load();
        String kit = "game shared\n\nfunc greet(name) {\n    send_message(\"${name}\")\n}\n";
        String main = "import \"kit.xero\"\n\nwhen player_join {\n    greet(\"x\")\n    shared = 1\n    \n}\n";
        s.build("main.xero", main, path -> path.equals("kit.xero") ? kit : null);
        int blank = main.indexOf("    \n}") + 4;
        List<String> labels = s.complete(main, blank).items().stream().map(Service.Item::label).toList();
        assertTrue(labels.contains("greet"), labels.toString());
        int call = main.indexOf("greet") + 1;
        assertTrue(s.hover(main, call).contains("func greet(name)"));
        assertEquals("kit.xero", s.definitionFile(main, call));
        assertEquals(kit.indexOf("greet"), s.definition(main, call).start());
        assertTrue(s.signature(main, main.indexOf("\"x\"")).label().startsWith("greet(name"));
        assertEquals(1, s.referencesElsewhere(main, call).get("kit.xero").size());
        Service.Renamed r = s.rename(main, call, "hello");
        assertEquals(null, r.error());
        assertEquals(1, r.edits().size());
        assertEquals("hello", r.others().get("kit.xero").get(0).text());
        assertTrue(s.hover(main, main.indexOf("shared") + 1).contains("игровая"));
        assertEquals(1, s.referencesElsewhere(main, main.indexOf("shared") + 1).get("kit.xero").size());
    }

    @Test
    void dottedFunctionsFromAModule() {
        Service s = Service.load();
        String module = "{\"handlers\":[{\"type\":\"function\",\"name\":\"Lib.hello\",\"position\":0,\"operations\":[],\"values\":[]}]}";
        String text = "import \"lib\"\n\nwhen player_join {\n    Lib.\n}\n";
        s.build("main.xero", text, path -> module);
        int offset = text.indexOf("Lib.") + 4;
        Service.Completion c = s.complete(text, offset);
        assertEquals("hello", c.items().get(0).label());
        String whole = "import \"lib\"\n\nwhen player_join {\n    Li\n}\n";
        List<String> labels = s.complete(whole, whole.indexOf("Li\n") + 2).items().stream().map(Service.Item::label).toList();
        assertTrue(labels.contains("Lib.hello"), labels.toString());
        String call = "import \"lib\"\n\nwhen player_join {\n    Lib.hello()\n}\n";
        s.build("main.xero", call, path -> module);
        assertTrue(s.hover(call, call.indexOf("hello") + 1).contains("Из подключённого модуля"));
    }

    private static final Service S = Service.load();

    private record At(String text, int offset) {}

    private static At at(String marked) {
        int i = marked.indexOf('|');
        return new At(marked.substring(0, i) + marked.substring(i + 1), i);
    }

    private static List<String> labels(String marked) {
        At a = at(marked);
        return S.complete(a.text(), a.offset()).items().stream().map(Service.Item::label).toList();
    }

    private static String first(String marked) {
        List<String> l = labels(marked);
        return l.isEmpty() ? null : l.get(0);
    }

    private static String hover(String marked) {
        At a = at(marked);
        return S.hover(a.text(), a.offset());
    }

    @Test
    void eventsAfterWhenAlsoByRussianWords() {
        assertEquals("player_join", first("when player_jo|"));
        assertTrue(labels("when зашёл|").contains("player_join"));
        assertTrue(labels("when |").size() > 100);
    }

    @Test
    void actionsAtStatementStartByNameOrRussian() {
        assertEquals("send_message", first("when player_join {\n    send_mes|\n}"));
        assertTrue(labels("when player_join {\n    отправить|\n}").contains("send_message"));
        assertTrue(labels("when player_join {\n    |\n}").contains("if"));
    }

    @Test
    void topLevelOffersItems() {
        assertEquals(List.of("func"), labels("fu|"));
        assertTrue(labels("|").containsAll(List.of("when", "func", "process", "import", "game")));
    }

    @Test
    void afterDots() {
        assertEquals("current_health", first("when player_join {\n    x = value.current_h|\n}"));
        assertTrue(labels("when player_join {\n    x = value.здоровье|\n}").contains("current_health"));
        assertEquals("teleport", first("when player_join {\n    player.telep|\n}"));
    }

    @Test
    void targetsAndDecorators() {
        assertTrue(labels("when player_join {\n    @|\n}").containsAll(List.of("all", "killer")));
        assertTrue(labels("@|\nfunc a() {}").containsAll(List.of("line", "hidden", "icon")));
    }

    @Test
    void insideCalls() {
        assertEquals("diamond", first("when player_join {\n    give_items(item(diamon|))\n}"));
        assertTrue(labels("when player_join {\n    send_message(\"x\", merging: |)\n}").containsAll(List.of("spaces", "concatenation")));
        assertTrue(labels("when player_join {\n    send_message(\"x\", mer|)\n}").contains("merging"));
        At sound = at("when player_join {\n    play_sound(sound(entity.player.lev|))\n}");
        Service.Completion c = S.complete(sound.text(), sound.offset());
        assertEquals("entity.player.levelup", c.items().get(0).label());
        assertEquals(sound.text().indexOf("entity."), c.from());
    }

    @Test
    void conditionsAfterIf() {
        assertTrue(labels("when player_join {\n    if is_sneak|\n}").contains("is_sneaking"));
    }

    @Test
    void variablesEverywhereButNotInsideTextOrComments() {
        String base = "when player_join {\n    game score = 1\n    ";
        assertTrue(labels(base + "x = sco|\n}").contains("score"));
        assertTrue(labels(base + "send_message(\"${sco|}\")\n}").contains("score"));
        assertEquals(List.of(), labels(base + "send_message(\"sco|\")\n}"));
        assertEquals(List.of(), labels(base + "// sco|\n}"));
    }

    @Test
    void hovers() {
        String h = hover("when player_join {\n    send_mes|sage(\"x\")\n}");
        assertNotNull(h);
        assertTrue(h.contains("Отправить сообщение") && h.contains("Отправляет сообщение"), h);
        assertTrue(hover("when player_jo|in {}").contains("Игрок зашёл"));
        assertTrue(hover("when player_join {\n    x = value.current_hea|lth\n}").contains("Текущее здоровье"));
        assertTrue(hover("when player_join {\n    send_message(\"x\", mergi|ng: spaces)\n}").contains("Объединение текста"));
        assertTrue(hover("when player_join {\n    @al|l send_message(\"x\")\n}").contains("Цель"));
        assertTrue(hover("func greet(name, count = 1) {}\nwhen player_join {\n    gre|et(\"x\")\n}").contains("func greet(name, count = 1)"));
        assertTrue(hover("when player_join {\n    game sc|ore = 1\n}").contains("игровая"));
        assertTrue(hover("when player_join {\n    if is_sneak|ing() {\n    }\n}").contains("Крадется"));
        assertNull(hover("when player_join {\n  |  \n}"));
    }

    @Test
    void definitions() {
        At a = at("func greet() {}\nwhen player_join {\n    gr|eet()\n}");
        Service.Span s = S.definition(a.text(), a.offset());
        assertEquals(5, s.start());
        assertEquals(10, s.end());
        At b = at("when player_join {\n    x = 1\n    send_message(\"${x|}\")\n}");
        Service.Span v = S.definition(b.text(), b.offset());
        assertEquals("x", b.text().substring(v.start(), v.end()));
        assertEquals(b.text().indexOf("x = 1"), v.start());
    }

    @Test
    void renames() {
        At a = at("game score\nwhen player_join {\n    sco|re += 1\n    send_message(\"${score}!\")\n    for score2 in [1] {}\n}");
        Service.Renamed r = S.rename(a.text(), a.offset(), "очки");
        assertNull(r.error());
        assertEquals("game очки\nwhen player_join {\n    очки += 1\n    send_message(\"${очки}!\")\n    for score2 in [1] {}\n}", apply(a.text(), r));
        Service.Renamed spaced = S.rename(a.text(), a.offset(), "мои очки");
        assertTrue(apply(a.text(), spaced).startsWith("game `мои очки`\n"));
        assertEquals("переменная «score2» уже есть", S.rename(a.text(), a.offset(), "score2").error());

        At f = at("process loop() {}\nfunc greet() {}\nwhen player_join {\n    gr|eet()\n    start loop()\n}");
        assertEquals("process loop() {}\nfunc hello() {}\nwhen player_join {\n    hello()\n    start loop()\n}",
                apply(f.text(), S.rename(f.text(), f.offset(), "hello")));
        assertEquals("process cycle() {}\nfunc greet() {}\nwhen player_join {\n    greet()\n    start cycle()\n}",
                apply(f.text(), S.rename(f.text(), f.text().indexOf("loop()"), "cycle")));
        assertTrue(apply(f.text(), S.rename(f.text(), f.offset(), "send_message")).contains("func `send_message`()"));
        assertNotNull(S.rename(f.text(), f.text().indexOf("player_join"), "x").error());
    }

    private static String apply(String text, Service.Renamed r) {
        StringBuilder out = new StringBuilder(text);
        List<Service.Edit> edits = r.edits().stream().sorted((x, y) -> Integer.compare(y.start(), x.start())).toList();
        for (Service.Edit e : edits) out.replace(e.start(), e.end(), e.text());
        return out.toString();
    }

    private static Service.Item item(String marked, String label) {
        At a = at(marked);
        return S.complete(a.text(), a.offset()).items().stream().filter(i -> i.label().equals(label)).findFirst().orElse(null);
    }

    private static String expand(String snippet) {
        return snippet.replaceAll("\\$\\{\\d+\\|([^,|]*)[^}]*}", "$1").replaceAll("\\$\\{\\d+:([^}]*)}", "$1")
                .replaceAll("\\$\\d+", "").replace("\t", "    ");
    }

    private static List<String> errors(String text) {
        return S.build("main.xero", text, null).diagnostics().stream().filter(xero.lang.Diagnostic::error)
                .map(xero.lang.Diagnostic::message).toList();
    }

    @Test
    void whenOpensEventsAndEventsOpenABlock() {
        Service.Item when = item("wh|", "when");
        assertEquals("when ", when.snippet());
        assertTrue(when.retrigger());
        assertTrue(item("ког|", "когда").retrigger());
        Service.Item join = item("when player_jo|", "player_join");
        assertEquals("player_join {\n\t$0\n}", join.snippet());
        assertNull(item("when player_jo| {}", "player_join").snippet());
        String program = expand("when " + join.snippet());
        assertEquals(List.of(), errors(program), program);
    }

    @Test
    void statementSnippetsBuild() {
        String head = "when player_join {\n    game x = 1\n    ";
        for (String label : List.of("if", "if … else", "если", "если … иначе", "for", "для", "while", "пока", "repeat",
                "повторить", "forever", "всегда")) {
            Service.Item i = item(head + label.substring(0, 2) + "|\n}", label);
            assertNotNull(i, label);
            String body = expand(i.snippet()).replace("условие", "x == 1");
            String program = head + body.replace("\n", "\n    ") + "\n}\n";
            assertEquals(List.of(), errors(program), program);
        }
        Service.Item list = item(head + "fo|\n}", "for … in список");
        assertEquals("for ${1:x} in ${2:список} {\n\t$0\n}", list.snippet());
    }

    @Test
    void elseAfterABlock() {
        List<String> l = labels("when player_join {\n    if value.current_health < 5 {\n    } |\n}");
        assertEquals(List.of("else", "else if", "иначе", "иначе если"), l.subList(0, 4));
        assertNull(item("when player_join {\n    x = 1\n    |\n}", "else"));
    }

    @Test
    void topLevelSnippets() {
        assertEquals("func ${1:имя}($2) {\n\t$0\n}", item("fu|", "func").snippet());
        assertEquals("process ${1:имя}($2) {\n\t$0\n}", item("pro|", "process").snippet());
        assertEquals(List.of(), errors(expand(item("fu|", "func").snippet())));
    }

    @Test
    void craftingGridAndOtherRecipes() {
        String head = "when world_start {\n    ";
        Service.Item craft = item(head + "add_custom_craf|\n}", "add_custom_crafting_recipe");
        assertTrue(craft.snippet().contains("[\n\t${3:_}, ${4:_}, ${5:_},\n\t${6:_}, ${7:_}, ${8:_},\n\t${9:_}, ${10:_}, ${11:_},\n]"),
                craft.snippet());
        for (String label : List.of("add_custom_crafting_recipe", "add_custom_cooking_recipe", "add_custom_smithing_transform_recipe")) {
            String program = head + expand(item(head + label.substring(0, 15) + "|\n}", label).snippet()).replace("\n", "\n    ") + "\n}\n";
            assertEquals(List.of(), errors(program), program);
        }
    }

    @Test
    void blockActionsGetABody() {
        assertEquals("while($1) {\n\t$0\n}", item("when player_join {\n    repeat.whi|\n}", "while").snippet());
        Service.Item forever = item("when player_join {\n    repeat.fore|\n}", "forever");
        String program = "when player_join {\n    repeat." + expand(forever.snippet()).replace("\n", "\n    ") + "\n}\n";
        assertEquals(List.of(), errors(program), program);
        assertNull(item("when player_join {\n    send_mes|\n}", "send_message").snippet());
        assertNull(item("when player_join {\n    if is_sneak|\n}", "is_sneaking").snippet());
    }

    @Test
    void symbols() {
        List<Service.Symbol> s = S.symbols("game x\nwhen player_join {}\nfunc `WE Load`() {}\nprocess loop() {}");
        assertEquals(List.of("player_join", "WE Load", "loop"), s.stream().map(Service.Symbol::name).toList());
        assertEquals(List.of("event", "function", "process"), s.stream().map(Service.Symbol::kind).toList());
    }
    private static Service.Signature signature(String marked) {
        At a = at(marked);
        return S.signature(a.text(), a.offset());
    }

    @Test
    void signatureHelp() {
        Service.Signature s = signature("when player_join {\n    send_message(\"a\", |)\n}");
        assertNotNull(s);
        assertTrue(s.label().startsWith("send_message(messages: "), s.label());
        assertEquals(1, s.active());
        s = signature("when player_join {\n    x = get_list_value(l, |)\n}");
        assertTrue(s.label().startsWith("get_list_value(list: "), s.label());
        assertEquals(1, s.active());
        s = signature("when player_join {\n    give_items(item(stone, name: \"a\", lore: |))\n}");
        assertTrue(s.label().startsWith("item(id, count, name: "), s.label());
        assertEquals("lore: [текст, …]", s.label().substring(s.params().get(s.active()).start(), s.params().get(s.active()).end()));
        s = signature("func kit(n: number = 1) {}\nwhen player_join {\n    kit(|)\n}");
        assertEquals("kit(n: number = 1)", s.label());
        assertNull(signature("when player_join {\n    x = 1|\n}"));
    }

    @Test
    void referencesAndFixes() {
        String text = "when player_join {\n    score = 1\n    score += 1\n    send_message(\"${score}\")\n}";
        assertEquals(3, S.references(text, text.indexOf("score")).size());
        String typo = "when player_join {\n    player.teleprt(loc(0, 0, 0))\n}";
        List<Diagnostic.Fix> fixes = fixes(typo);
        assertEquals(1, fixes.size());
        assertEquals(typo.indexOf("player."), fixes.get(0).start());
        assertEquals(typo.indexOf("("), fixes.get(0).end());
        assertEquals("player.teleport", fixes.get(0).text());
        assertEquals("Заменить на «player.teleport»", fixes.get(0).title());
        assertEquals("send_message", fixes("when player_join {\n    отправить_сообщение(\"a\")\n}").get(0).text());
        String value = "when player_join {\n    x = value.current_helth\n}";
        Diagnostic.Fix v = fixes(value).get(0);
        assertEquals("value.current_health", v.text());
        assertEquals("value.current_helth", value.substring(v.start(), v.end()));
        String odd = "when player_join {\n    save `мои очки` = 1\n    send_message(`мои очкии`)\n}";
        assertEquals("`мои очки`", fixes(odd).get(0).text());
        assertEquals(List.of(), fixes("@hiden\nfunc f() {}"));
    }

    private static final String CLOCK = "H4sIAAAAAAAA/22NsQrCQBBEJwYhnoWIINgL+Qdrv8E2HHerWbzshrtV8O89qzQ207yZNw5osb168zfKhVWAQ99hxRG7iYVC9ne7hKTh2WId9CUGoHFwQadZhcRKg/NSJQmjF5sqGB6Ja+qbcuZIdXRaeqaajOchcpmT/2ywHzlGkmHxdr8nHP+6S2VfbmXYJbwAAAA=";
    private static final String PANE = "H4sIAAAAAAAA/0XLMQ6DMAxG4b+NKqEwIg7A1Dswc4aukRUMitTYKDZDb1821qfvRSCgX8jpw82KCjC8OzzLiqkW4dxo83lv9EvmdIU17V8ySwcJB7yynuIAHhExaz1UWNw6jPecT3OtSajyBfEHet7OanIAAAA=";
    private static final String COPPER = "H4sIAAAAAAAA/+NiYGBm4HZJLEkMSy0qzszPY2AQVudgYMpMYZDKzcxLTS5KTCuxKk9NLMlILUpNiU/OLyhILWJmYE3OL80rYWBgYGQAAOgAAFtDAAAA";
    private static final String PAPER = "H4sIAAAAAAAA/0WMMQ7CMBAEF6xEYAoKyjyAP1BGeQNtdLIvkqX4zrIvf+RZ4IqpZ8YDDreFjN5cW1IBHs8LzininpNwqLTZq1Dh6jAEPcQAnDx80FxUWKx5TH81HM00r1kj72v8fa8Yt13J2tDD+YPOFzdnOqx3AAAA";

    private static String itemOf(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"item\":\"(H4sI[^\"]+)\"")
                .matcher(xero.json.Json.write(S.build("main.xero", text, null).module()));
        return m.find() ? m.group(1) : null;
    }

    private static Diagnostic.Fix spelled(String base64) {
        String text = "when player_join {\n    give_items(item(\"" + base64 + "\"))\n}\n";
        int inside = text.indexOf("H4sI") + 5;
        List<Diagnostic.Fix> found = S.actions(text, inside, inside);
        if (found.isEmpty()) return null;
        assertEquals(1, found.size());
        Diagnostic.Fix f = found.get(0);
        assertEquals("Расписать предмет полями", f.title());
        assertEquals("item(\"" + base64 + "\")", text.substring(f.start(), f.end()));
        String applied = text.substring(0, f.start()) + f.text() + text.substring(f.end());
        assertEquals(base64, itemOf(applied), applied);
        return f;
    }

    @Test
    void copiedItemIsSpelledOutAsFields() {
        assertEquals("item(clock, glint: true, components: \"{'minecraft:tooltip_display':{hidden_components:"
                + "['minecraft:enchantments']}}\")", spelled(CLOCK).text());
        assertEquals("item(gray_stained_glass_pane, components: \"{'minecraft:custom_name':''}\")", spelled(PANE).text());
        assertEquals("item(weathered_copper)", spelled(COPPER).text());
        assertTrue(spelled(PAPER).text().startsWith("item(paper, "), spelled(PAPER).text());
        String fields = "item(diamond_sword, 2, name: \"&bМеч\", lore: [\"a\", \"&cb\"], enchantments: {sharpness: 5}, "
                + "unbreakable: true, tags: {kind: \"hero\"})";
        String made = itemOf("when player_join {\n    give_items(" + fields + ")\n}\n");
        assertEquals(fields, spelled(made).text());
        String icon = "@icon(item(\"" + COPPER + "\"))\nfunc f() {}\n";
        assertEquals("item(weathered_copper)", S.actions(icon, icon.indexOf("item"), icon.indexOf("item")).get(0).text());
    }

    @Test
    void itemThatFieldsCannotRepeatHasNoAction() {
        assertNull(spelled(xero.compile.Items.encode("minecraft:nope", 1)));
        assertNull(spelled(xero.compile.Items.encode("minecraft:stone", 100)));
        assertNull(spelled("H4sIbroken"));
        String text = "when player_join {\n    give_items(item(\"" + CLOCK + "\"), item(stone))\n}\n";
        assertEquals(List.of(), S.actions(text, 0, 0));
        int stone = text.indexOf("item(stone)") + 2;
        assertEquals(List.of(), S.actions(text, stone, stone));
        assertEquals(1, S.actions(text, text.indexOf("give_items") + 11, text.indexOf("\")") + 2).size());
    }

    private static List<Diagnostic.Fix> fixes(String text) {
        return S.check("main.xero", text, null).stream().flatMap(d -> d.fixes().stream()).toList();
    }

    @Test
    void itemFieldsAndEnchantments() {
        assertTrue(labels("when player_join {\n    give_items(item(stone, na|))\n}").contains("name"));
        assertEquals("sharpness", first("when player_join {\n    give_items(item(diamond_sword, enchantments: {sharp|}))\n}"));
        assertTrue(labels("when player_join {\n    give_items(item(diamond_sword, enchantments: {sharpness: 1, unbr|}))\n}")
                .contains("unbreaking"));
    }

    @Test
    void afterTarget() {
        List<String> statement = labels("when player_join {\n    @all |\n}");
        assertTrue(statement.contains("give_items"), statement.toString());
        assertTrue(labels("when player_join {\n    @all send|\n}").contains("send_message"));
        assertTrue(!statement.contains("sound") && !statement.contains("potion"), statement.toString());
        assertTrue(labels("when player_join {\n    if @all sne|\n}").stream().anyMatch(l -> l.contains("sneaking")));
        assertTrue(labels("when player_join {\n    x = @all va|\n}").contains("value"));
        assertEquals("all", labels("when player_join {\n    @al|\n}").get(0));
    }

    @Test
    void markerWordIsNotAVariable() {
        String text = "when player_join {\n    wait(1, seconds)\n    wait(2, time_unit: minutes)\n    x = 1\n    \n}\n";
        assertEquals(2, S.variables(text).stream().filter(m -> m.scope().equals("option")).count());
        assertEquals(1, S.variables(text).stream().filter(m -> !m.scope().equals("option")).count());
        int word = text.indexOf("seconds") + 2;
        assertTrue(S.hover(text, word).contains("**"), S.hover(text, word));
        assertEquals("переименовать можно функцию, процесс или переменную", S.rename(text, word, "abc").error());
        List<String> blank = S.complete(text, text.indexOf("    \n}") + 4).items().stream().map(Service.Item::label).toList();
        assertTrue(!blank.contains("seconds") && !blank.contains("minutes") && blank.contains("x"), blank.toString());
    }

    @Test
    void realVariablesStayVariables() {
        String read = "when player_join {\n    seconds = 5\n    send_message(\"${seconds}\")\n    wait(seconds)\n}\n";
        assertTrue(S.variables(read).stream().allMatch(m -> m.scope().equals("local")), S.variables(read).toString());
        String constant = "const unit = \"seconds\"\nwhen player_join {\n    wait(1, unit)\n}\n";
        assertTrue(S.variables(constant).stream().noneMatch(m -> m.scope().equals("option")));
    }

    @Test
    void markerWordsInTheCompletion() {
        List<String> next = labels("when player_join {\n    wait(1, |)\n}");
        assertTrue(next.contains("seconds") && next.contains("time_unit"), next.toString());
        assertTrue(!next.contains("value") && !next.contains("sound"), next.toString());
        assertTrue(labels("when player_join {\n    wait(|)\n}").contains("seconds"));
        assertEquals("seconds", first("when player_join {\n    wait(1, sec|)\n}"));
        assertTrue(!labels("when player_join {\n    wait(1, time_unit: seconds, |)\n}").contains("minutes"));
    }
}
