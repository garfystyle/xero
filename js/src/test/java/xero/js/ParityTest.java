package xero.js;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xero.compile.Compiler;
import xero.compile.Decompiler;
import xero.data.Data;
import xero.data.Names;
import xero.format.Formatter;
import xero.ide.Service;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParityTest {
    private static final Path BUNDLE = Path.of("build", "dist", "xero.js");
    private static final Path DUMP = Path.of("..", "..", "dump");
    private static final Path SAVED = Path.of(System.getProperty("user.home"),
            "AppData", "Roaming", "PrismLauncher", "instances", "26.2(1)", ".minecraft", "xerocode", "saved");

    private static final String SCRIPT = """
            const fs = require('fs');
            const x = require(process.argv[2]);
            const input = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
            const out = [];
            for (const c of input) {
              if (c.kind === 'source') {
                const b = JSON.parse(x.build('main.xero', c.text, null));
                const f = JSON.parse(x.format(c.text));
                const d = (b.diagnostics || []).map(d => d.severity + ':' + d.message + ':' + d.start + ':' + d.end);
                const h = JSON.parse(x.hover(c.text, c.probe));
                const k = JSON.parse(x.complete(c.text, c.probe));
                const a = JSON.parse(x.actions(c.text, c.item, c.item));
                out.push({module: b.module || null, diagnostics: d, format: f.ok ? f.text : null,
                          hover: h.markdown || null, complete: (k.items || []).map(i => i.label).join(','),
                          actions: a.map(e => e.start + ':' + e.end + ':' + e.text).join('|')});
              } else {
                out.push({text: JSON.parse(x.decompile(c.text)).text || null});
              }
            }
            fs.writeFileSync(process.argv[4], JSON.stringify(out));
            """;

    @TempDir
    Path dir;

    @Test
    void javascriptGivesTheSameAnswers() throws IOException, InterruptedException {
        Assumptions.assumeTrue(Files.isRegularFile(BUNDLE), "нет build/dist/xero.js");
        Assumptions.assumeTrue(node(), "нет node");
        Data data = Data.load();
        Names names = Names.build(data);
        Service service = new Service(data, names);
        List<Object> cases = new ArrayList<>();
        List<Object> expected = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Path f : sources()) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            add(service, data, names, text, f.getFileName().toString(), cases, expected, labels);
        }
        List<String> ids = new ArrayList<>(data.minecraft().items());
        int[] counts = {1, 5, 16, 64, 99};
        for (int from = 0; from < ids.size(); from += 200) {
            StringBuilder program = new StringBuilder("when player_join {\n");
            for (int i = from; i < Math.min(ids.size(), from + 200); i++) {
                String id = ids.get(i).startsWith("minecraft:") ? ids.get(i).substring(10) : ids.get(i);
                program.append("    give_items(item(").append(id).append(", ").append(counts[i % counts.length]).append("))\n");
            }
            add(service, data, names, program.append("}\n").toString(), "предметы с " + from, cases, expected, labels);
        }
        java.util.Set<String> copied = new java.util.LinkedHashSet<>();
        for (Path f : worlds()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"item\":\"(H4sI[^\"]+)\"")
                    .matcher(Files.readString(f, StandardCharsets.UTF_8));
            while (m.find()) copied.add(m.group(1));
        }
        for (String b64 : copied)
            add(service, data, names, "when player_join {\n    give_items(item(\"" + b64 + "\"))\n}\n",
                    "предмет " + b64.substring(0, 24), cases, expected, labels);
        for (Path f : worlds()) {
            String world = Files.readString(f, StandardCharsets.UTF_8);
            Object parsed = Json.parse(world);
            if (!(parsed instanceof Map<?, ?> m) || !(m.get("handlers") instanceof List<?>)) continue;
            String text = Decompiler.decompile(data, names, Json.object(parsed)).text();
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("kind", "world");
            c.put("text", world);
            cases.add(c);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("text", text);
            expected.add(e);
            labels.add("мир " + f.getFileName());
            add(service, data, names, text, "мир " + f.getFileName() + " текстом", cases, expected, labels);
        }
        Path in = dir.resolve("in.json"), out = dir.resolve("out.json"), script = dir.resolve("parity.js");
        Files.writeString(in, Json.write(cases), StandardCharsets.UTF_8);
        Files.writeString(script, SCRIPT, StandardCharsets.UTF_8);
        Process p = new ProcessBuilder("node", script.toString(), BUNDLE.toAbsolutePath().toString(), in.toString(), out.toString())
                .redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(10, TimeUnit.MINUTES);
        assertEquals(0, p.exitValue(), log);
        List<Object> got = Json.array(Json.parse(Files.readString(out, StandardCharsets.UTF_8)));
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < expected.size(); i++) {
            Map<String, Object> e = Json.object(expected.get(i)), g = Json.object(got.get(i));
            for (String key : e.keySet())
                if (!String.valueOf(e.get(key)).equals(String.valueOf(g.get(key)))) failed.add(labels.get(i) + ": " + key);
        }
        assertEquals(List.of(), failed, "сверено случаев: " + expected.size());
    }

    private static void add(Service service, Data data, Names names, String text, String label,
                            List<Object> cases, List<Object> expected, List<String> labels) {
        int probe = Math.max(0, text.indexOf('(') - 2);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("kind", "source");
        c.put("text", text);
        c.put("probe", probe);
        int item = Math.max(0, text.indexOf("item(\"H4sI") + 6);
        c.put("item", item);
        cases.add(c);
        Compiler.Result r = Compiler.compile(data, names, "main.xero", text, null);
        List<Object> diagnostics = new ArrayList<>();
        for (Diagnostic d : r.diagnostics())
            diagnostics.add((d.error() ? "error" : "warning") + ":" + d.message() + ":" + d.start() + ":" + d.end());
        Formatter.Result f = Formatter.format(text);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("module", r.ok() ? Json.write(r.module()) : null);
        e.put("diagnostics", diagnostics);
        e.put("format", f.ok() ? f.text() : null);
        e.put("hover", service.hover(text, probe));
        List<String> items = new ArrayList<>();
        for (Service.Item i : service.complete(text, probe).items()) items.add(i.label());
        e.put("complete", String.join(",", items));
        List<String> actions = new ArrayList<>();
        for (Diagnostic.Fix a : service.actions(text, item, item)) actions.add(a.start() + ":" + a.end() + ":" + a.text());
        e.put("actions", String.join("|", actions));
        expected.add(e);
        labels.add(label);
    }

    private static List<Path> sources() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String d : List.of("../bench/xero", "../examples")) {
            Path p = Path.of(d);
            if (!Files.isDirectory(p)) continue;
            try (DirectoryStream<Path> s = Files.newDirectoryStream(p, "*.xero")) {
                for (Path f : s) out.add(f);
            }
        }
        out.sort(null);
        return out;
    }

    private static List<Path> worlds() throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(Path.of("..", "core", "src", "test", "resources", "xero", "corpus"), "*.json")) {
            for (Path f : s) out.add(f);
        }
        Path world = DUMP.resolve("world-5c2d3902-2026-10-16.json");
        if (Files.isRegularFile(world)) out.add(world);
        if (Files.isDirectory(SAVED)) {
            try (DirectoryStream<Path> s = Files.newDirectoryStream(SAVED, "*.json")) {
                for (Path f : s) out.add(f);
            }
        }
        out.sort(null);
        return out;
    }

    private static boolean node() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
