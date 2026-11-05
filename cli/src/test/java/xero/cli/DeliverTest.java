package xero.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xero.json.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliverTest {
    private static final String LINK = "http://api.creative.justmc.io/public/creative/handlers/0123456789abcdef0123456789abcdef?token=abc";

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final List<String> uploaded = new ArrayList<>();
    private final List<String> copied = new ArrayList<>();
    private final List<URI> fetched = new ArrayList<>();
    private final List<Net.Store> stores = new ArrayList<>();
    private String world;
    private PrintStream savedOut, savedErr;
    private Net.Link savedLink;
    private Game.Link savedGame;
    private final List<String> sentToGame = new ArrayList<>();
    private boolean linesFail;

    @BeforeEach
    void fake() {
        savedOut = Main.OUT;
        savedErr = Main.ERR;
        savedLink = Net.link;
        Main.OUT = new PrintStream(out, true, StandardCharsets.UTF_8);
        Main.ERR = new PrintStream(err, true, StandardCharsets.UTF_8);
        Net.link = new Net.Link() {
            @Override
            public String upload(String module, Net.Store store) {
                uploaded.add(module);
                stores.add(store);
                return store.code() + "0123456789abcdef0123456789abcdef.json";
            }

            @Override
            public String fetch(URI link) {
                fetched.add(link);
                return world;
            }
        };
        Clipboard.sink = copied::add;
        savedGame = Game.link;
        Game.link = new Game.Link() {
            @Override
            public Game.Sent upload(String module, boolean replace) throws IOException {
                if (module.contains("offline")) throw new Game.Failed("игрок не в /dev своего мира");
                sentToGame.add((replace ? "replace " : "add ") + module);
                return new Game.Sent(List.of("Модуль установлен"), true);
            }

            @Override
            public List<Object> world() {
                return xero.json.Json.array(xero.json.Json.object(xero.json.Json.parse(world)).get("handlers"));
            }

            @Override
            public List<String> lines(String lines) throws IOException {
                sentToGame.add("lines " + lines);
                if (linesFail) throw new Game.Failed("строка 1: не дошёл до строки — она не тронута");
                return List.of("строка на месте");
            }

            @Override
            public String connect(String entry) {
                sentToGame.add("connect " + entry);
                return "подключён";
            }
        };
    }

    @AfterEach
    void restore() {
        Main.OUT = savedOut;
        Main.ERR = savedErr;
        Net.link = savedLink;
        Game.link = savedGame;
    }

    private int run(String... args) {
        out.reset();
        err.reset();
        return Main.run(args);
    }

    private String said() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private Path write(String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    private static String worldOf(String... handlers) {
        return "{\"handlers\":[" + String.join(",", handlers) + "]}";
    }

    private static final String START = "{\"type\":\"event\",\"position\":0,\"operations\":[],\"event\":\"world_start\"}";
    private static final String OLD = "{\"type\":\"function\",\"position\":1,\"operations\":[],\"values\":[{\"name\":\"is_hidden\",\"value\":{\"type\":\"enum\",\"enum\":\"FALSE\"}}],\"name\":\"old\"}";

    @Test
    void gameTakesTheCodeWithoutTheClipboard() throws IOException {
        Path src = write("main.xero", "func greet() { send_message(\"hi\") }\n");
        assertEquals(0, run("upload", src.toString(), "--game"), this::said);
        assertEquals(1, sentToGame.size());
        assertTrue(sentToGame.get(0).startsWith("replace "), sentToGame.get(0));
        assertTrue(uploaded.isEmpty());
        assertTrue(copied.isEmpty());
        assertTrue(said().contains("сервер: Модуль установлен"), this::said);
        assertEquals(0, run("upload", src.toString(), "--game"), this::said);
        assertEquals(0, run("upload", write("more.xero", "func more() { send_message(\"x\") }\n").toString(), "--game", "--add"), this::said);
        assertEquals(2, sentToGame.size());
        assertTrue(sentToGame.get(1).startsWith("add "), sentToGame.get(1));
    }

    @Test
    void diffShowsWithoutSending() throws IOException {
        Path src = write("main.xero", "func greet() { send_message(\"hi\") }\n");
        assertEquals(0, run("diff", src.toString()), this::said);
        assertTrue(said().contains("Сверять не с чем"), this::said);
        world = worldOf(START, OLD);
        assertEquals(0, run("diff", src.toString(), "--game"), this::said);
        assertTrue(said().contains("новых 1, сотрётся 2"), this::said);
        assertTrue(said().contains("«old»"), this::said);
        assertTrue(uploaded.isEmpty() && sentToGame.isEmpty());
        assertEquals(0, run("upload", src.toString(), "--game", "--drop"), this::said);
        assertEquals(0, run("diff", src.toString()), this::said);
        assertTrue(said().contains("Без изменений 1, изменено 0, новых 0, сотрётся 0"), this::said);
    }

    @Test
    void watchSendsOnlyWhatIsSafe() throws IOException {
        Path src = write("main.xero", "func greet() { send_message(\"hi\") }\n");
        Project.Built built = Project.compile(src);
        Watch.send(src, built, "");
        assertEquals(1, sentToGame.size());
        Watch.send(src, built, "");
        assertEquals(1, sentToGame.size());
        assertTrue(said().contains("В мире уже это"), this::said);
        Path smaller = write("main.xero", "func other() { send_message(\"hi\") }\n");
        Watch.send(smaller, Project.compile(smaller), "");
        assertEquals(1, sentToGame.size());
        assertTrue(said().contains("замена сотрёт"), this::said);
    }

    @Test
    void failedLinesFallBackToTheWholeUpload() throws IOException {
        Path src = write("main.xero", "func a() { send_message(\"1\") }\nfunc b() { send_message(\"2\") }\n");
        assertEquals(0, run("upload", src.toString(), "--game"), this::said);
        write("main.xero", "func a() { send_message(\"1\") }\nfunc b() { send_message(\"3\") }\n");
        linesFail = true;
        assertEquals(0, run("upload", src.toString(), "--game"), this::said);
        assertEquals(3, sentToGame.size(), sentToGame::toString);
        assertTrue(sentToGame.get(1).startsWith("lines "), sentToGame.get(1));
        assertTrue(sentToGame.get(2).startsWith("replace "), sentToGame.get(2));
        assertTrue(said().contains("Построчно не вышло"), this::said);
        assertTrue(sentToGame.get(2).contains("\"3\""), sentToGame.get(2));
    }

    @Test
    void tooWideLineGoesWhole() throws IOException {
        Path src = write("main.xero", "func a() { send_message(\"1\") }\n");
        assertEquals(0, run("upload", src.toString(), "--game"), this::said);
        write("main.xero", "func a() {\n" + "    send_message(\"1\")\n".repeat(44) + "}\n");
        assertEquals(0, run("upload", src.toString(), "--game"), this::said);
        assertEquals(2, sentToGame.size(), sentToGame::toString);
        assertTrue(sentToGame.get(1).startsWith("replace "), sentToGame.get(1));
    }

    @Test
    void smallChangeGoesLineByLine() throws IOException {
        Path src = write("main.xero", "func a() { send_message(\"1\") }\nfunc b() { send_message(\"2\") }\n");
        assertEquals(0, run("upload", src.toString(), "--game"), this::said);
        assertTrue(sentToGame.get(0).startsWith("replace "), sentToGame.get(0));
        assertEquals(0, run("upload", src.toString(), "--game"), this::said);
        assertTrue(said().contains("В мире уже это"), this::said);
        assertEquals(1, sentToGame.size());
        write("main.xero", "func a() { send_message(\"1\") }\nfunc b() { send_message(\"3\") }\n");
        assertEquals(0, run("upload", src.toString(), "--game"), this::said);
        assertEquals(2, sentToGame.size());
        assertTrue(sentToGame.get(1).startsWith("lines "), sentToGame.get(1));
        assertTrue(sentToGame.get(1).contains("\"position\":1") && sentToGame.get(1).contains("\"was\""), sentToGame.get(1));
        assertFalse(sentToGame.get(1).contains("\"name\":\"a\""), sentToGame.get(1));
        assertTrue(said().contains("Заменено строк: 1, мир не перезапускался"), this::said);
        assertEquals(0, run("diff", src.toString()), this::said);
        assertTrue(said().contains("Без изменений 2"), this::said);
    }

    @Test
    void gameRefusalIsAnError() throws IOException {
        Path src = write("main.xero", "func offline() { send_message(\"hi\") }\n");
        assertEquals(1, run("upload", src.toString(), "--game"), this::said);
        assertTrue(said().contains("игрок не в /dev своего мира"), this::said);
    }

    @Test
    void pullFromTheGame() throws IOException {
        world = worldOf(START, OLD);
        Path target = dir.resolve("world.xero");
        assertEquals(0, run("pull", "--game", "-o", target.toString()), this::said);
        assertTrue(Files.readString(target, StandardCharsets.UTF_8).contains("func old()"));
        assertTrue(fetched.isEmpty());
    }

    @Test
    void firstUploadAppendsAndCopiesTheCommand() throws IOException {
        Path src = write("main.xero", "func greet() { send_message(\"hi\") }\n");
        assertEquals(0, run("upload", src.toString(), "--add"), this::said);
        assertEquals(1, uploaded.size());
        if (System.getenv(Net.STORE_VARIABLE) == null) assertEquals(Net.DEFAULT, stores.get(0));
        assertEquals(List.of("/module loadUrl " + stores.get(0).code() + "0123456789abcdef0123456789abcdef.json"), copied);
        assertTrue(said().contains("Задвоение не проверено: копии мира нет"), this::said);
        assertTrue(said().contains("в течение 60 с"), this::said);
        assertTrue(Files.isRegularFile(dir.resolve(".xero/world.json")));
    }

    @Test
    void storeFlagChoosesTheStore() throws IOException {
        Path src = write("main.xero", "func greet() { send_message(\"hi\") }\n");
        assertEquals(0, run("upload", src.toString(), "--add", "--store", "http://10.0.0.5:8791/mine"), this::said);
        assertEquals(1, stores.get(0).uploads().size());
        assertTrue(stores.get(0).uploads().get(0).startsWith("http://10.0.0.5:8791/mine/upload.php?key="));
        assertEquals("http://10.0.0.5:8791/mine/code/", stores.get(0).code());
        assertEquals(List.of("/module loadUrl http://10.0.0.5:8791/mine/code/0123456789abcdef0123456789abcdef.json"), copied);
        assertEquals(2, run("upload", src.toString(), "--add", "--duplicate", "--store", "ftp://x"), this::said);
        assertTrue(said().contains("адрес хранилища"), this::said);
        assertEquals(1, uploaded.size());
        assertEquals(2, run("upload", src.toString(), "--store"), this::said);
    }

    @Test
    void secondUploadOfSameFunctionIsRefused() throws IOException {
        Path src = write("main.xero", "func greet() { send_message(\"hi\") }\n");
        assertEquals(0, run("upload", src.toString(), "--add"), this::said);
        assertEquals(1, run("upload", src.toString(), "--add"), this::said);
        assertTrue(said().contains("функция «greet» — уже отправлена"), this::said);
        assertEquals(1, uploaded.size());
        assertEquals(0, run("upload", src.toString(), "--add", "--duplicate"), this::said);
        assertEquals(2, uploaded.size());
    }

    @Test
    void pullWritesTextAndSnapshotButKeepsEditedFile() throws IOException {
        Path file = write("w.json", worldOf(START, OLD));
        Path target = dir.resolve("world.xero");
        assertEquals(0, run("pull", file.toString(), "-o", target.toString()), this::said);
        String text = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(text.contains("func old()"), text);
        Map<String, Object> snap = Json.object(Json.parse(Files.readString(dir.resolve(".xero/world.json"))));
        assertEquals(2, Json.array(snap.get("handlers")).size());
        Files.writeString(target, text + "\nfunc mine() {}\n", StandardCharsets.UTF_8);
        assertEquals(0, run("pull", file.toString(), "-o", target.toString()), this::said);
        assertTrue(said().contains("не тронут"), this::said);
        assertTrue(Files.readString(target, StandardCharsets.UTF_8).contains("func mine()"));
        assertEquals(0, run("pull", file.toString(), "-o", target.toString(), "--overwrite"), this::said);
        assertFalse(Files.readString(target, StandardCharsets.UTF_8).contains("func mine()"));
    }

    @Test
    void pullFetchesLinkFromEditorDownload() throws IOException {
        world = worldOf(START);
        assertEquals(0, run("pull", LINK, "-o", dir.resolve("world.xero").toString()), this::said);
        assertEquals(List.of(URI.create(LINK)), fetched);
        assertEquals(1, run("pull", "http://example.com/x", "-o", dir.resolve("x.xero").toString()));
        assertTrue(said().contains("Это не ссылка /editor download"), this::said);
    }

    @Test
    void appendSeesFunctionsAlreadyInTheWorld() throws IOException {
        Path file = write("w.json", worldOf(START, OLD));
        assertEquals(0, run("pull", file.toString(), "-o", dir.resolve("world.xero").toString()), this::said);
        Path src = write("new.xero", "when world_start { }\nfunc old() { }\n");
        assertEquals(1, run("upload", src.toString(), "--add"), this::said);
        assertTrue(said().contains("функция «old» — уже есть в мире, строка 1"), this::said);
        Path events = write("events.xero", "when world_start { }\nfunc fresh() { }\n");
        assertEquals(0, run("upload", events.toString(), "--add"), this::said);
        assertTrue(said().contains("событие world_start уже есть в мире, строка 0 — после заливки сработают обе строки"), this::said);
    }

    @Test
    void firstReplaceGoesWithoutAnyCopy() throws IOException {
        Path src = write("main.xero", "when world_start { }\n");
        assertEquals(0, run("upload", src.toString()), this::said);
        assertTrue(said().contains("Сверять не с чем"), this::said);
        assertTrue(copied.get(0).startsWith("/module loadUrl force http://"), copied::toString);
    }

    @Test
    void uploadingOneFileInsteadOfTheFolderIsCaughtWithoutDownload() throws IOException {
        write("game/main.xero", "when player_join { send_message(\"hi\") }\n");
        Path kit = write("game/kit.xero", "func greet() { send_message(\"hi\") }\n");
        assertEquals(0, run("upload", dir.resolve("game").toString()), this::said);
        assertEquals(1, run("upload", dir.resolve("game/main.xero").toString()), this::said);
        assertTrue(said().contains("функция «greet» (строка 0)"), this::said);
        assertTrue(said().contains("то, что Xero залил заменой"), this::said);
        assertEquals(1, uploaded.size());
        Files.writeString(kit, "func greet() { send_message(\"hello\") }\n", StandardCharsets.UTF_8);
        assertEquals(0, run("upload", dir.resolve("game").toString()), this::said);
        assertTrue(said().contains("без изменений 1, изменено 1"), this::said);
    }

    @Test
    void replaceKeepsEveryWorldLineFromTheCopy() throws IOException {
        Path src = write("main.xero", "when world_start { }\n");
        Path file = write("w.json", worldOf(START, OLD));
        assertEquals(0, run("pull", file.toString(), "-o", dir.resolve("world.xero").toString()), this::said);
        assertEquals(1, run("upload", src.toString()), this::said);
        assertTrue(said().contains("функция «old» (строка 1)"), this::said);
        assertTrue(uploaded.isEmpty());
        assertEquals(0, run("upload", src.toString(), "--drop"), this::said);
        assertTrue(said().contains("сотрётся 1"), this::said);
        assertTrue(copied.get(0).startsWith("/module loadUrl force http://"), copied::toString);
    }

    @Test
    void pulledWorldGoesBackWhole() throws IOException {
        Path file = write("w.json", worldOf(START, OLD));
        Path target = dir.resolve("world.xero");
        assertEquals(0, run("pull", file.toString(), "-o", target.toString()), this::said);
        assertEquals(0, run("upload", target.toString()), this::said);
        assertTrue(said().contains("без изменений 2, изменено 0, новых 0"), this::said);
        assertEquals(Json.parse(worldOf(START, OLD)), Json.parse(uploaded.get(0)));
        assertEquals(worldOf(START, OLD), uploaded.get(0));
        assertEquals(0, run("upload", target.toString()), this::said);
        assertTrue(said().contains("то, что Xero залил заменой"), this::said);
    }

    @Test
    void unchangedLinesKeepTheirWorldFormWhileEditedOnesAreCompiled() throws IOException {
        String plain = "{\"type\":\"event\",\"position\":1,\"operations\":[{\"action\":\"set_variable_value\",\"values\":["
                + "{\"name\":\"value\",\"value\":{\"type\":\"number\",\"number\":1.0}},"
                + "{\"name\":\"variable\",\"value\":{\"type\":\"variable\",\"variable\":\"x\",\"scope\":\"game\"}}]}],\"event\":\"player_join\"}";
        Path file = write("w.json", worldOf(START, plain));
        Path target = dir.resolve("world.xero");
        assertEquals(0, run("pull", file.toString(), "-o", target.toString()), this::said);
        String text = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(text.contains("    x = 1\n"), text);
        Files.writeString(target, text.replace("when world_start {}", "when world_start { x = 2 }"), StandardCharsets.UTF_8);
        assertEquals(0, run("upload", target.toString()), this::said);
        assertTrue(said().contains("без изменений 1, изменено 1"), this::said);
        List<Object> handlers = Json.array(Json.object(Json.parse(uploaded.get(0))).get("handlers"));
        assertEquals(Json.parse(plain), handlers.get(1));
        assertTrue(Json.write(handlers.get(0)).contains("\"number\":2"), uploaded::toString);
    }

    @Test
    void oldCopyStillGuardsAndShowsItsAge() throws IOException {
        Path file = write("w.json", worldOf(START));
        Path target = dir.resolve("world.xero");
        assertEquals(0, run("pull", file.toString(), "-o", target.toString()), this::said);
        Path snap = dir.resolve(".xero/world.json");
        Map<String, Object> o = Json.object(Json.parse(Files.readString(snap)));
        o.put("known", Instant.now().minusSeconds(20 * 60).toString());
        Files.writeString(snap, Json.write(o));
        assertEquals(0, run("upload", target.toString()), this::said);
        assertTrue(said().contains("20 мин назад"), this::said);
        assertTrue(said().contains("Правки в /dev после неё Xero не видит"), this::said);
        world = worldOf(START);
        assertEquals(0, run("upload", target.toString(), "--world", LINK), this::said);
        assertEquals(1, fetched.size());
    }

    @Test
    void snapshotIsFoundUpTheTree() throws IOException {
        Path file = write("w.json", worldOf(START, OLD));
        assertEquals(0, run("pull", file.toString(), "-o", dir.resolve("world.xero").toString()), this::said);
        Path src = write("src/more.xero", "func old() { }\n");
        assertEquals(1, run("upload", dir.resolve("src").toString(), "--add"), this::said);
        assertTrue(said().contains("уже есть в мире"), this::said);
        assertFalse(Files.exists(dir.resolve("src/.xero")));
        assertTrue(Files.isRegularFile(src));
    }

    @Test
    void builtModuleUploadsAsIs() throws IOException {
        Path json = write("ready.json", worldOf(START));
        assertEquals(0, run("upload", json.toString(), "--add"), this::said);
        assertEquals(Json.parse(worldOf(START)), Json.parse(uploaded.get(0)));
    }

    @Test
    void brokenCodeIsNotUploaded() throws IOException {
        Path src = write("bad.xero", "when player_jum { }\n");
        assertEquals(1, run("upload", src.toString()), this::said);
        assertTrue(said().contains("Не залито: ошибок 1"), this::said);
        assertTrue(uploaded.isEmpty());
    }

    @Test
    void flagsThatDoNotGoTogether() throws IOException {
        Path src = write("main.xero", "when world_start { }\n");
        assertEquals(2, run("upload", src.toString(), "--drop", "--add"));
        assertEquals(2, run("upload", src.toString(), "--duplicate"));
        assertEquals(2, run("upload", src.toString(), "--replace"));
        assertTrue(said().contains("--replace больше не нужен"), this::said);
        assertEquals(2, run("upload", src.toString(), "--force"));
        assertTrue(said().contains("Нет флага «--force»"), this::said);
        assertEquals(2, run("upload"));
        assertEquals(2, run("pull"));
    }

    @Test
    void gapsInTheWorldSurviveTheCircle() throws IOException {
        String gap = "{\"type\":\"event\",\"position\":7,\"operations\":[],\"event\":\"player_join\"}";
        Path file = write("w.json", worldOf(START, gap));
        Path target = dir.resolve("world.xero");
        assertEquals(0, run("pull", file.toString(), "-o", target.toString()), this::said);
        assertTrue(Files.readString(target).contains("@line(7)\nwhen player_join"));
        assertEquals(0, run("upload", target.toString()), this::said);
        assertTrue(said().contains("без изменений 2"), this::said);
    }
}
