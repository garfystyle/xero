package xero.compile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.format.Formatter;
import xero.json.Json;
import xero.lang.Diagnostic;
import xero.lang.Lexer;
import xero.lang.Token;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private static String format(String text) {
        Formatter.Result r = Formatter.format(text);
        assertTrue(r.ok(), () -> "не отформатировано: " + r.errors());
        return r.text();
    }

    private static String json(String text) {
        Compiler.Result r = Compiler.compile(DATA, NAMES, text);
        return Json.write(r.module()) + r.diagnostics().stream().filter(Diagnostic::error).map(Diagnostic::message).toList();
    }

    private static List<String> comments(String text) {
        List<Lexer.Comment> found = new ArrayList<>();
        Lexer.tokens(text, new ArrayList<>(), found);
        List<String> out = new ArrayList<>();
        for (Lexer.Comment c : found) out.add(text.substring(c.start(), c.end()).strip());
        return out;
    }

    private static void same(String name, String text, List<String> failed) {
        Formatter.Result once = Formatter.format(text);
        if (!once.ok()) {
            failed.add(name + ": не отформатировано " + once.errors());
            return;
        }
        String twice = format(once.text());
        if (!twice.equals(once.text())) failed.add(name + ": второй проход меняет текст: " + firstDiff(once.text(), twice));
        if (!json(text).equals(json(once.text()))) failed.add(name + ": после форматирования другой JSON");
        if (!comments(text).equals(comments(once.text()))) failed.add(name + ": комментарии изменились");
    }

    private static String firstDiff(String a, String b) {
        String[] x = a.split("\n", -1), y = b.split("\n", -1);
        int i = 0;
        while (i < Math.min(x.length, y.length) && x[i].equals(y[i])) i++;
        return (i + 1) + ": «" + (i < x.length ? x[i] : "") + "» | «" + (i < y.length ? y[i] : "") + "»";
    }

    @Test
    void messyCodeComesOutInOneStyle() {
        String messy = """
                game   kills
                save `монеты %player%`
                // главная функция
                @hidden
                func   greet( name ,count=1 ){send_message( "привет, ${name}" ) ; give_items(item(diamond,count))}



                when player_join{
                  // привет
                    greet("x")   // в конце строки
                  if kills==10 or(kills >100 and not kills== 5){
                  @all send_message("ура" ,
                     "второе")
                  }
                  else if kills<0 { x=-kills }
                  else{}
                    for i in 1..3 step 1 {x+=i}
                    m = {"a":1,"b":[1,2 ,3]}
                    hp,food=value.current_health,value.food_level
                    /* блочный
                       комментарий */
                    y = (x + 1) * -2
                }
                @line(10)
                when player_quit {}
                """;
        assertEquals("""
                game kills
                save `монеты %player%`

                // главная функция
                @hidden
                func greet(name, count = 1) {
                    send_message("привет, ${name}")
                    give_items(item(diamond, count))
                }

                when player_join {
                    // привет
                    greet("x") // в конце строки
                    if kills == 10 or (kills > 100 and not kills == 5) {
                        @all send_message("ура",
                            "второе")
                    } else if kills < 0 {
                        x = -kills
                    } else {}
                    for i in 1..3 step 1 {
                        x += i
                    }
                    m = {"a": 1, "b": [1, 2, 3]}
                    hp, food = value.current_health, value.food_level
                    /* блочный
                       комментарий */
                    y = (x + 1) * -2
                }

                @line(10)
                when player_quit {}
                """, format(messy));
    }

    @Test
    void russianKeywordsStayRussian() {
        assertEquals("""
                когда player_join {
                    если value.current_health < 5 и не value.food_level == 0 {
                        send_message("мало")
                    } иначе {
                        send_message("ок")
                    }
                }
                """, format("когда player_join{если value.current_health<5 и не value.food_level==0{send_message(\"мало\")}иначе{send_message(\"ок\")}}"));
    }

    @Test
    void contextualWordsAreKeywordsOnlyInForHeaders() {
        String text = """
                when player_join {
                    step = 3
                    in = 1
                    шаг = 2
                    x = step - 9
                    y = in - 1
                    z = шаг - 1
                    w = step > 4 ? step - 9 : step
                    for i in -5..5 step -1 {}
                    для j в -2..step шаг -1 {}
                    for in in [-1] {}
                }
                """;
        assertEquals(text, format(text));
        assertEquals(text, format(text.replace("step - 9", "step-9").replace("in - 1", "in -1")));
        assertTrue(json(text).endsWith("[]"), json(text));
    }

    @Test
    void commentsAtTheEdges() {
        assertEquals("// в начале\n\nwhen player_join {\n    // только комментарий\n}\n\n// в конце\n",
                format("// в начале\n\n\n\nwhen player_join {\n\n    // только комментарий\n\n}\n// в конце"));
        assertEquals("when world_start {} // рядом\n", format("when world_start {}   // рядом   "));
        assertEquals("when world_start {\n    x = 1 // a\n} // b\n", format("when world_start { x = 1 // a\n} // b\n"));
    }

    @Test
    void elseAfterCommentStaysParseable() {
        String text = "when world_start {\n    if a == 1 {\n        b = 2\n    } // c\n    else {\n        b = 3\n    }\n}\n";
        String out = format(text);
        assertEquals(json(text), json(out));
        assertEquals(out, format(out));
    }

    @Test
    void longCallsKeepTheirBreaks() {
        assertEquals("""
                when world_start {
                    send_message(
                        "a",
                        item(diamond,
                            5)
                    )
                    x = 1 +
                        2
                }
                """, format("when world_start {\nsend_message(\n\"a\",\nitem(diamond,\n5)\n)\nx = 1 +\n2\n}\n"));
    }

    @Test
    void gridsKeepTheirColumnsAndDottedKeywordsAreNames() {
        String grid = """
                when world_start {
                    add_custom_crafting_recipe("x", item(diamond_sword), [
                        _, item(emerald), _,
                        _, item(stick),   _,
                    ], recipe_type: shaped)
                    repeat.while(x < 5) {
                        x += 1
                    }
                    repeat.forever() {}
                }
                """;
        assertEquals(grid, format(grid));
        assertEquals("when world_start {\n    f(1, 2)\n}\n", format("when world_start {\n    f(1,     2)\n}\n"));
    }

    @Test
    void brokenCodeIsNotFormatted() {
        Formatter.Result r = Formatter.format("when player_join {\n    send_message(\"x\"\n}\n");
        assertFalse(r.ok());
        assertFalse(r.errors().isEmpty());
    }

    @Test
    void decompiledWorldsAreAlreadyFormatted() throws IOException {
        List<Path> files = CorpusTest.corpus();
        Assumptions.assumeFalse(files.isEmpty(), "нет корпуса кода из мира");
        List<String> failed = new ArrayList<>();
        for (Path f : files) {
            Object parsed = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> m) || !(m.get("handlers") instanceof List<?>)) continue;
            String text = Decompiler.decompile(DATA, NAMES, Json.object(parsed)).text();
            String out = format(text);
            if (!out.equals(text)) failed.add(f.getFileName() + ":" + firstDiff(text, out));
            same(f.getFileName().toString(), text, failed);
        }
        assertEquals(List.of(), failed);
    }

    @Test
    void projectSourcesSurvive() throws IOException {
        List<String> failed = new ArrayList<>();
        for (String dir : List.of("../bench/xero", "../examples")) {
            Path d = Path.of(dir);
            if (!Files.isDirectory(d)) continue;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(d, "*.xero")) {
                for (Path f : files) same(f.toString(), Files.readString(f, StandardCharsets.UTF_8), failed);
            }
        }
        assertEquals(List.of(), failed);
    }

    @Test
    void randomProgramsAndTheirMessyTwins() {
        List<String> failed = new ArrayList<>();
        for (int seed = 0; seed < 300; seed++) {
            Random r = new Random(seed);
            String text = FuzzTest.program(r);
            same("seed " + seed, text, failed);
            String messy = mess(text, new Random(seed * 31L + 7));
            Formatter.Result a = Formatter.format(text), b = Formatter.format(messy);
            if (!a.ok() || !b.ok()) {
                failed.add("seed " + seed + ": не отформатировано");
                continue;
            }
            if (!a.text().equals(b.text())) failed.add("seed " + seed + ": беспорядок даёт другой текст: " + firstDiff(a.text(), b.text()));
            if (failed.size() > 5) break;
        }
        assertEquals(List.of(), failed);
    }

    private static String mess(String text, Random r) {
        List<Token> tokens = Lexer.tokens(text, new ArrayList<>());
        StringBuilder out = new StringBuilder();
        int end = 0;
        Token before = null;
        for (Token t : tokens) {
            String gap = text.substring(end, t.start());
            if (gap.contains("\n")) {
                long n = gap.chars().filter(c -> c == '\n').count();
                out.append("\n".repeat((int) n + (n > 1 ? r.nextInt(3) : 0))).append(" ".repeat(r.nextInt(7)));
            } else if (before != null) {
                String a = text.substring(before.start(), before.end()), b = text.substring(t.start(), t.end());
                String glue = " ".repeat(r.nextInt(3));
                if (glue.isEmpty() && !splits(a, b)) glue = " ";
                out.append(glue);
            }
            out.append(text, t.start(), t.end());
            end = t.end();
            before = t;
        }
        return out.toString();
    }

    private static boolean splits(String a, String b) {
        List<Token> two = Lexer.tokens(a + b, new ArrayList<>());
        return two.size() == 3 && two.get(0).end() == a.length();
    }
}
