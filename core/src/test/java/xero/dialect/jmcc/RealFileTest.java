package xero.dialect.jmcc;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import xero.dialect.Dialect;
import xero.ide.Service;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealFileTest {
    private static final JmccDialect JMCC = new JmccDialect(Service.load());
    private static final Set<String> UNSUPPORTED = Set.of("case1.jc", "interface_test.jc", "lambda.jc", "modern_alias.jc",
            "range_test.jc", "russian_code.jc", "test_for_iter.jc", "universaltest.jc", "vector3d.jc");

    private static boolean foreign(Path p) {
        String path = p.toString().replace('\\', '/');
        return path.contains("/std/") || UNSUPPORTED.contains(p.getFileName().toString());
    }

    private static Dialect.Built build(Path p, String text) {
        return JMCC.build(p.toString().replace('\\', '/'), text, Corpus.loader());
    }

    @Test
    void everySupportedFileBuilds() {
        Assumptions.assumeTrue(Corpus.there());
        List<String> failed = new ArrayList<>();
        int built = 0;
        int all = 0;
        for (Path p : Corpus.files()) {
            all++;
            String text = Corpus.text(p);
            Dialect.Built b = build(p, text);
            if (b.ok()) built++;
            if (foreign(p)) continue;
            for (Diagnostic d : b.diagnostics()) if (d.error()) failed.add(p.getFileName() + ": " + d.message());
        }
        assertEquals(List.of(), failed);
        assertTrue(built >= 34, "собралось " + built + " из " + all);
    }

    @Test
    void foreignFilesFailWithWordsAndNotWithACrash() {
        Assumptions.assumeTrue(Corpus.there());
        for (Path p : Corpus.files()) {
            Dialect.Built b = build(p, Corpus.text(p));
            for (Diagnostic d : b.diagnostics()) {
                assertFalse(d.message().contains("код не собрать"), p + ": " + d.message());
                assertTrue(d.message().matches("(?s).*[а-яА-Я].*"), d.message());
            }
        }
    }

    @Test
    void cutFilesNeverCrash() {
        Assumptions.assumeTrue(Corpus.there());
        Random random = new Random(20261104);
        List<Path> files = Corpus.files();
        for (Path p : files) {
            String text = Corpus.text(p);
            int rounds = text.length() > 100_000 ? 2 : 30;
            for (int i = 0; i < rounds; i++) {
                int from = random.nextBoolean() ? 0 : random.nextInt(text.length());
                int to = from + random.nextInt(text.length() - from + 1);
                String cut = text.substring(from, to);
                Dialect.Built b = JMCC.build(null, cut, null);
                assertNotNull(b.diagnostics());
                for (Diagnostic d : b.diagnostics()) assertFalse(d.message().contains("код не собрать"), p + " " + from + ".." + to);
                JMCC.symbols(cut);
                JMCC.format(cut);
                int at = cut.isEmpty() ? 0 : random.nextInt(cut.length());
                JMCC.signature(cut, at);
                JMCC.hover(cut, at);
                JMCC.complete(cut, at);
            }
        }
    }

    @Test
    void noiseNeverCrashes() {
        Random random = new Random(7);
        String[] pieces = {"event<", ">", "{", "}", "(", ")", "[", "]", "\"", "'", "`", "$", "${", "::", ".", ",", ";", "=", "==", "=>", "->", "..",
                "..=", "if", "else", "elif", "while", "for", "in", "match", "try", "catch", "throw", "function", "process", "class", "enum", "inline",
                "var", "game", "save", "local", "const", "return", "break", "continue", "@", "@hidden", "player::message", "value::x", "x", "1", "1e",
                "%player%", "%", "not", "and", "or", "?", ":", "++", "--", "m{", "\n", " ", "/*", "*/", "//", "#", "\\", "item(", "location(", "'a:", "_"};
        for (int round = 0; round < 3000; round++) {
            StringBuilder b = new StringBuilder();
            int n = random.nextInt(40);
            for (int i = 0; i < n; i++) b.append(pieces[random.nextInt(pieces.length)]).append(random.nextBoolean() ? " " : "");
            String text = b.toString();
            Dialect.Built built = JMCC.build(null, text, null);
            for (Diagnostic d : built.diagnostics()) assertFalse(d.message().contains("код не собрать"), text);
            JMCC.symbols(text);
        }
    }

    @Test
    void deepNestingIsAnErrorAndNotACrash() {
        Dialect.Built b = JMCC.build(null, "x = " + "(".repeat(5000) + "1" + ")".repeat(5000), null);
        assertFalse(b.ok());
        Dialect.Built c = JMCC.build(null, "event<world_start> {" + "if (a) {".repeat(5000), null);
        assertFalse(c.ok());
    }

    private static Object cell(Object v) {
        if (!(v instanceof Map<?, ?> m)) return v;
        Map<String, Object> o = Json.object(m);
        Object type = o.get("type");
        if ("item".equals(type)) return "item";
        if ("game_value".equals(type)) return "value:" + o.get("game_value");
        if ("location".equals(type)) return "location:" + number(o.get("x")) + "," + number(o.get("y")) + "," + number(o.get("z"));
        if ("number".equals(type)) return "number:" + number(o.get("number"));
        if ("array".equals(type)) {
            List<Object> cells = new ArrayList<>();
            for (Object c : Json.array(o.get("values"))) cells.add(cell(c));
            while (!cells.isEmpty() && cells.get(cells.size() - 1) instanceof Map<?, ?> e && e.isEmpty()) cells.remove(cells.size() - 1);
            return cells.size() == 1 ? cells.get(0) : cells;
        }
        Map<String, Object> rest = new TreeMap<>(o);
        rest.remove("parsing");
        return rest;
    }

    private static double number(Object v) {
        return Double.parseDouble(String.valueOf(v instanceof Json.Num n ? n.text() : v));
    }

    private static List<Object> ops(Object list) {
        List<Object> out = new ArrayList<>();
        for (Object raw : Json.array(list)) {
            Map<String, Object> op = Json.object(raw);
            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("action", op.get("action"));
            Map<String, Object> values = new TreeMap<>();
            for (Object v : Json.array(op.get("values"))) values.put(Json.string(Json.object(v).get("name")), cell(Json.object(v).get("value")));
            values.remove("merging");
            shape.put("values", values);
            if (op.get("operations") != null) shape.put("operations", ops(op.get("operations")));
            out.add(shape);
        }
        return out;
    }

    @Test
    void plotsEventsMeanTheSameAsTheRealCompiler() throws IOException {
        Assumptions.assumeTrue(Corpus.there());
        Path source = Corpus.repos().resolve("jms2").resolve("plots_events.jc");
        Dialect.Built b = JMCC.build(null, Corpus.text(source), null);
        assertTrue(b.ok(), String.valueOf(b.diagnostics()));
        Map<String, Object> real = Json.object(Json.parse(Files.readString(source.resolveSibling("plots_events.json"), StandardCharsets.UTF_8)));
        Map<String, Object> mine = Json.object(Json.parse(Json.write(b.module())));
        List<Object> a = Json.array(real.get("handlers"));
        List<Object> c = Json.array(mine.get("handlers"));
        assertEquals(a.size(), c.size());
        for (int i = 0; i < a.size(); i++) {
            Map<String, Object> x = Json.object(a.get(i));
            Map<String, Object> y = Json.object(c.get(i));
            assertEquals(x.get("event") == null ? "world_start" : x.get("event"), y.get("event"));
            assertEquals(ops(x.get("operations")).toString(), ops(y.get("operations")).toString());
        }
    }
}
