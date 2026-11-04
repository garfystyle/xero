package xero.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xero.json.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryTest {
    private static final String A = "11111111-1111-1111-1111-111111111111";
    private static final String B = "22222222-2222-2222-2222-222222222222";
    private static final String CLOSED = "33333333-3333-3333-3333-333333333333";

    private static final String CODE = "{\"handlers\":[{\"type\":\"function\",\"name\":\"Lib.hello\",\"position\":0,\"operations\":[],"
            + "\"values\":[{\"name\":\"parameters\",\"value\":{\"type\":\"array\",\"values\":[{\"type\":\"parameter\",\"type_key\":\"singular\","
            + "\"name\":\"who\",\"value_type\":\"text\",\"is_required\":\"true\",\"default_value\":\"{}\"}]}},"
            + "{\"name\":\"is_hidden\",\"value\":{\"type\":\"enum\",\"enum\":\"FALSE\"}}]},"
            + "{\"type\":\"event\",\"event\":\"world_start\",\"position\":1,\"operations\":[]}]}";

    @TempDir
    Path dir;

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final List<String> asked = new ArrayList<>();
    private PrintStream savedErr;
    private Library.Catalog saved;

    private static String entry(String id, String name, String title, int installs, boolean open, int price) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"displayName\":\"" + title + "\",\"history\":" + installs
                + ",\"codeVisible\":" + open + ",\"price\":" + price + ",\"updatedAt\":\"2026-01-01\"}";
    }

    @BeforeEach
    void fake() {
        savedErr = Main.ERR;
        Main.ERR = new PrintStream(err, true, StandardCharsets.UTF_8);
        saved = Library.catalog;
        Library.catalog = url -> {
            asked.add(url);
            if (url.endsWith("/" + A + "/code") || url.endsWith("/" + B + "/code")) return CODE;
            if (url.endsWith("/" + CLOSED + "/code")) throw new Library.Absent();
            if (url.endsWith("/" + A)) return entry(A, "lib", "Lib", 5, true, 0);
            if (url.contains("search=lib"))
                return "[" + entry(B, "lib", "&#ff0000Малая &7[1.0]\\\\n&7описание", 3, true, 0) + "," + entry(A, "lib", "Lib", 50, true, 0) + "]";
            if (url.contains("search=closed")) return "[" + entry(CLOSED, "closed", "Closed", 1, false, 0) + "]";
            if (url.contains("search=paid")) return "[" + entry(A, "paid", "Paid", 1, true, 100) + "]";
            if (url.contains("search=%D0%9C%D0%B0%D0%BB%D0%B0%D1%8F"))
                return "[" + entry(B, "lib", "&#ff0000Малая &7[1.0]\\\\n&7описание", 3, true, 0) + "]";
            if (url.contains("search=li")) return "[" + entry(A, "lib", "Lib", 5, true, 0) + "]";
            return "[]";
        };
    }

    @AfterEach
    void restore() {
        Main.ERR = savedErr;
        Library.catalog = saved;
    }

    private Path write(String text) throws IOException {
        Path p = dir.resolve("main.xero");
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    private int build(String text) throws IOException {
        err.reset();
        return Main.run(new String[] {"build", write(text).toString()});
    }

    private String said() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void importByName() throws IOException {
        assertEquals(0, build("import \"lib\"\nwhen player_join {\n    `Lib.hello`(\"x\")\n}\n"), this::said);
        assertTrue(said().contains("модулей «lib» в каталоге 2 — взят самый установленный: «Lib», установок 50"), this::said);
        Map<String, Object> module = Json.object(Json.parse(Files.readString(dir.resolve("main.json"), StandardCharsets.UTF_8)));
        List<Object> handlers = Json.array(module.get("handlers"));
        assertEquals(3, handlers.size());
        assertEquals("Lib.hello", Json.object(handlers.get(1)).get("name"));
        assertEquals(1, ((Json.Num) Json.object(handlers.get(1)).get("position")).intValue());
        assertEquals(2, ((Json.Num) Json.object(handlers.get(2)).get("position")).intValue());
        assertTrue(Files.isRegularFile(dir.resolve(".xero/modules/lib.json")));
    }

    @Test
    void cachedModuleNeedsNoNetwork() throws IOException {
        assertEquals(0, build("import \"lib\"\nwhen player_join {}\n"), this::said);
        int before = asked.size();
        assertEquals(0, build("import \"lib\"\nwhen player_join {}\n"), this::said);
        assertEquals(before, asked.size());
    }

    @Test
    void importById() throws IOException {
        assertEquals(0, build("import \"" + A + "\"\nwhen player_join {}\n"), this::said);
        assertFalse(said().contains("взят самый установленный"), this::said);
    }

    @Test
    void importByTitle() throws IOException {
        assertEquals(0, build("import \"Малая\"\nwhen player_join {}\n"), this::said);
        assertTrue(Files.readString(dir.resolve(".xero/modules/малая.json"), StandardCharsets.UTF_8).contains("\"title\":\"Малая\""));
    }

    @Test
    void callsAreChecked() throws IOException {
        assertEquals(1, build("import \"lib\"\nwhen player_join {\n    `Lib.hello`()\n}\n"), this::said);
        assertTrue(said().contains("не передан параметр «who»"), this::said);
        assertEquals(1, build("import \"lib\"\nfunc `Lib.hello`() {}\n"), this::said);
        assertTrue(said().contains("«Lib.hello» уже есть в модуле «lib»"), this::said);
    }

    @Test
    void connectKeepsTheCodeOutOfTheProject() throws IOException {
        assertEquals(0, build("import \"lib\" connect\nwhen player_join {\n    Lib.hello(\"x\")\n}\n"), this::said);
        Map<String, Object> module = Json.object(Json.parse(Files.readString(dir.resolve("main.json"), StandardCharsets.UTF_8)));
        assertEquals(1, Json.array(module.get("handlers")).size());
        assertEquals(0, build("import \"closed\" connect\nwhen player_join {}\n"), this::said);
    }

    @Test
    void closedAndPaidAreRefused() throws IOException {
        assertEquals(1, build("import \"closed\"\n"), this::said);
        assertTrue(said().contains("автор закрыл код"), this::said);
        assertEquals(1, build("import \"paid\"\n"), this::said);
        assertTrue(said().contains("платный"), this::said);
        assertFalse(Files.exists(dir.resolve(".xero/modules/paid.json")));
    }

    @Test
    void unknownNameGetsAHint() throws IOException {
        assertEquals(1, build("import \"nothing\"\n"), this::said);
        assertTrue(said().contains("нет модуля «nothing»"), this::said);
        assertTrue(said().contains("import \"nothing.xero\""), this::said);
        assertEquals(1, build("import \"li\"\n"), this::said);
        assertTrue(said().contains("похожие: lib"), this::said);
    }

    @Test
    void networkFailureIsNotCached() throws IOException {
        Library.catalog = url -> {
            throw new IOException("сети нет");
        };
        assertEquals(1, build("import \"lib\"\n"), this::said);
        assertTrue(said().contains("не скачан: сети нет"), this::said);
        assertFalse(Files.exists(dir.resolve(".xero/modules/lib.json")));
    }
}
