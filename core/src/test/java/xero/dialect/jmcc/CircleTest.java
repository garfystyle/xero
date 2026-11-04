package xero.dialect.jmcc;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import xero.compile.Compiler;
import xero.compile.Decompiler;
import xero.compile.Delivery;
import xero.data.Data;
import xero.dialect.Dialect;
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
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircleTest {
    private static final Service SERVICE = Service.load();
    private static final Data DATA = SERVICE.data();
    private static final JmccDialect JMCC = new JmccDialect(SERVICE);

    static Object normal(Map<String, Object> module) {
        List<Map<String, Object>> handlers = new ArrayList<>();
        for (Object h : Json.array(module.get("handlers"))) handlers.add(Json.object(h));
        handlers.sort(Comparator.comparingInt(h -> h.get("position") instanceof Json.Num n ? n.intValue() : 0));
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> h : handlers) {
            Map<String, Object> copy = new TreeMap<>(Json.object(Delivery.normal(DATA, h)));
            if (h.get("position") instanceof Json.Num n) copy.put("position", n.intValue());
            out.add(copy);
        }
        return out;
    }

    static String difference(Object a, Object b, String path) {
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            for (Object k : x.keySet()) {
                if (!y.containsKey(k)) return path + "." + k + ": нет после круга";
                String d = difference(x.get(k), y.get(k), path + "." + k);
                if (d != null) return d;
            }
            for (Object k : y.keySet()) if (!x.containsKey(k)) return path + "." + k + ": лишнее после круга";
            return null;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return path + ": длина " + x.size() + " ≠ " + y.size();
            for (int i = 0; i < x.size(); i++) {
                String d = difference(x.get(i), y.get(i), path + "[" + i + "]");
                if (d != null) return d;
            }
            return null;
        }
        if (a == null ? b == null : a.equals(b)) return null;
        String sa = Json.write(a), sb = Json.write(b);
        return path + ": " + sa.substring(0, Math.min(200, sa.length())) + " ≠ " + sb.substring(0, Math.min(200, sb.length()));
    }

    private static List<String> circle(Map<String, Object> module, String name) throws IOException {
        Dialect.Decompiled printed = JMCC.decompile(module);
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", name + ".jc"), printed.text(), StandardCharsets.UTF_8);
        Dialect.Built back = JMCC.build(null, printed.text(), null);
        List<String> out = new ArrayList<>();
        xero.lang.Source source = new xero.lang.Source(name, printed.text());
        for (Diagnostic d : back.diagnostics()) {
            if (!d.error()) continue;
            String line = source.lineText(source.line(d.start())).strip();
            out.add(d.message() + " @" + source.line(d.start()) + ": " + line.substring(0, Math.min(160, line.length())));
        }
        if (!out.isEmpty()) return out.subList(0, Math.min(8, out.size()));
        String d = difference(normal(module), normal(Json.object(Json.parse(Json.write(back.module())))), "");
        return d == null ? List.of() : List.of(d);
    }

    @Test
    void realWorldSurvivesTheCircle() throws IOException {
        Path world = Corpus.world("world-5c2d3902-2026-10-22.json");
        Assumptions.assumeTrue(world != null);
        Map<String, Object> module = Json.object(Json.parse(Files.readString(world, StandardCharsets.UTF_8)));
        assertEquals(List.of(), circle(module, "world"));
    }

    @Test
    void ownCorpusSurvivesTheCircle() throws IOException {
        Path dir = Path.of("src", "test", "resources", "xero", "corpus");
        List<String> failed = new ArrayList<>();
        int files = 0;
        try (DirectoryStream<Path> list = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : list) {
                Object parsed = Json.parse(Files.readString(p, StandardCharsets.UTF_8));
                if (!(parsed instanceof Map<?, ?> m) || !(m.get("handlers") instanceof List<?>)) continue;
                files++;
                for (String f : circle(Json.object(parsed), p.getFileName().toString().replace(".json", ""))) failed.add(p.getFileName() + ": " + f);
            }
        }
        assertTrue(files >= 3);
        assertEquals(List.of(), failed);
    }

    @Test
    void bigRealFileSurvivesTheCircle() throws IOException {
        Assumptions.assumeTrue(Corpus.there());
        Path cubed = Corpus.repos().resolve("rust").resolve("jmcc").resolve("tests").resolve("cubed.jc");
        Dialect.Built built = JMCC.build(null, Corpus.text(cubed), null);
        assertTrue(built.ok(), String.valueOf(built.diagnostics().stream().filter(Diagnostic::error).limit(5).toList()));
        assertEquals(List.of(), circle(Json.object(Json.parse(Json.write(built.module()))), "cubed"));
    }

    @Test
    void jmccOutputSurvivesTheCircle() throws IOException {
        Assumptions.assumeTrue(Corpus.there());
        List<String> failed = new ArrayList<>();
        int done = 0;
        try (DirectoryStream<Path> list = Files.newDirectoryStream(Corpus.repos().resolve("jms2"), "*.json")) {
            for (Path p : list) {
                Map<String, Object> module = Json.object(Json.parse(Files.readString(p, StandardCharsets.UTF_8)));
                if (!(module.get("handlers") instanceof List<?>)) continue;
                Compiler.Result own = SERVICE.build(null, Decompiler.decompile(DATA, SERVICE.names(), module).text(), null);
                if (!own.ok() || difference(normal(module), normal(Json.object(Json.parse(Json.write(own.module())))), "") != null) continue;
                done++;
                for (String f : circle(module, "jms2")) failed.add(p.getFileName() + ": " + f);
            }
        }
        assertEquals(List.of(), failed, "файлов " + done);
    }

    @Test
    void printingIsStable() throws IOException {
        Path world = Corpus.world("world-5c2d3902-2026-10-22.json");
        Assumptions.assumeTrue(world != null);
        Map<String, Object> module = Json.object(Json.parse(Files.readString(world, StandardCharsets.UTF_8)));
        String once = JMCC.decompile(module).text();
        Dialect.Built back = JMCC.build(null, once, null);
        assertTrue(back.ok());
        String twice = JMCC.decompile(Json.object(Json.parse(Json.write(back.module())))).text();
        String[] x = once.split("\n", -1);
        String[] y = twice.split("\n", -1);
        int i = 0;
        while (i < Math.min(x.length, y.length) && x[i].equals(y[i])) i++;
        assertEquals(i < x.length ? x[i] : "", i < y.length ? y[i] : "", "строка " + (i + 1));
    }
}
