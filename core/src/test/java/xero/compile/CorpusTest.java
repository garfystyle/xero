package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private static final Path OWN = Path.of("src", "test", "resources", "xero", "corpus");
    private static final Path DUMP = Path.of("..", "..", "dump");
    private static final Path SAVED = Path.of(System.getProperty("user.home"),
            "AppData", "Roaming", "PrismLauncher", "instances", "26.2(1)", ".minecraft", "xerocode", "saved");

    static List<Path> corpus() throws IOException {
        List<Path> files = new ArrayList<>(own());
        Path world = DUMP.resolve("world-5c2d3902-2026-10-16.json");
        if (Files.isRegularFile(world)) files.add(world);
        if (Files.isDirectory(SAVED)) {
            try (DirectoryStream<Path> dir = Files.newDirectoryStream(SAVED, "*.json")) {
                for (Path p : dir) files.add(p);
            }
        }
        files.sort(null);
        return files;
    }

    static List<Path> own() throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(OWN, "*.json")) {
            for (Path p : dir) files.add(p);
        }
        files.sort(null);
        return files;
    }

    @Test
    void ownCorpusIsThere() throws IOException {
        assertTrue(own().size() >= 3, "мало файлов в " + OWN.toAbsolutePath());
    }

    @Test
    void everyWorldSurvivesTheCircle() throws IOException {
        List<Path> files = corpus();
        List<String> failed = new ArrayList<>();
        int handlers = 0;
        for (Path f : files) {
            Object parsed = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> m) || !(m.get("handlers") instanceof List<?> hs)) continue;
            handlers += hs.size();
            Map<String, Object> module = Json.object(parsed);
            Decompiler.Result text = Decompiler.decompile(DATA, NAMES, module);
            Compiler.Result back = Compiler.compile(DATA, NAMES, text.text());
            List<String> errors = back.diagnostics().stream().filter(Diagnostic::error)
                    .map(Diagnostic::message).limit(3).toList();
            if (!errors.isEmpty()) {
                failed.add(f.getFileName() + ": не собралось: " + errors);
                continue;
            }
            String d = RoundTrip.firstDifference(RoundTrip.normalize(DATA, RoundTrip.healed(module)),
                    RoundTrip.normalize(DATA, Json.object(Json.parse(Json.write(back.module())))), "");
            if (d != null) failed.add(f.getFileName() + ": " + d);
        }
        assertEquals(List.of(), failed, "строк в корпусе: " + handlers);
    }

    @Test
    void moduleCodeFromTheServerSurvives() throws IOException {
        Path file = DUMP.resolve("world-5c2d3902-2026-10-22.json");
        if (!Files.isRegularFile(file)) return;
        Map<String, Object> world = Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
        Map<String, Object> module = world;
        Decompiler.Result text = Decompiler.decompile(DATA, NAMES, module);
        assertTrue(!text.text().contains("@values"), "остался @values");
        assertTrue(text.text().split("raw[(]", -1).length < 12, "много raw");
        Compiler.Result back = Compiler.compile(DATA, NAMES, text.text());
        assertEquals(List.of(), back.diagnostics().stream().filter(Diagnostic::error).map(Diagnostic::message).limit(3).toList());
        assertEquals(null, RoundTrip.firstDifference(RoundTrip.normalize(DATA, module),
                RoundTrip.normalize(DATA, Json.object(Json.parse(Json.write(back.module())))), ""));
    }

    @Test
    void printingIsStable() throws IOException {
        List<Path> files = corpus();
        List<String> failed = new ArrayList<>();
        for (Path f : files) {
            Object parsed = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> m) || !(m.get("handlers") instanceof List<?>)) continue;
            String once = Decompiler.decompile(DATA, NAMES, Json.object(parsed)).text();
            Compiler.Result back = Compiler.compile(DATA, NAMES, once);
            String twice = Decompiler.decompile(DATA, NAMES, back.module()).text();
            if (!once.equals(twice)) {
                String[] a = once.split("\n", -1), b = twice.split("\n", -1);
                int i = 0;
                while (i < Math.min(a.length, b.length) && a[i].equals(b[i])) i++;
                failed.add(f.getFileName() + ":" + (i + 1) + ": " + (i < a.length ? a[i] : "") + " | " + (i < b.length ? b[i] : ""));
            }
        }
        assertEquals(List.of(), failed);
    }
}
