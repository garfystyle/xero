package xero.cli;

import xero.compile.Compiler;
import xero.compile.Wrap;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;
import xero.lang.Diagnostic;
import xero.lang.Source;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

final class Project {
    record Built(Map<String, Object> module, List<Diagnostic> diagnostics, Map<String, String> texts,
                 String name, Set<Path> files, int errors, int warnings, List<Object> connected) {
        boolean ok() { return errors == 0; }

        int lines() { return Json.array(module.get("handlers")).size(); }

        void report(PrintStream err) {
            Map<String, Source> sources = new HashMap<>();
            for (Diagnostic d : diagnostics) err.print(d.render(source(sources, d)));
        }

        List<Object> reportJson() {
            Map<String, Source> sources = new HashMap<>();
            List<Object> out = new ArrayList<>();
            for (Diagnostic d : diagnostics) out.add(json(d, source(sources, d)));
            return out;
        }

        private Source source(Map<String, Source> sources, Diagnostic d) {
            String file = d.file() == null ? name : d.file();
            return sources.computeIfAbsent(file, f -> new Source(display(f), texts.getOrDefault(f, "")));
        }
    }

    static final class Missing extends IOException {
        private static final long serialVersionUID = 1L;

        Missing(String message) { super(message); }
    }

    static boolean isModule(Path in) {
        return Files.isRegularFile(in) && in.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json");
    }

    static Built compile(Path in) throws IOException {
        String name;
        String text;
        Set<Path> files = new LinkedHashSet<>();
        if (Files.isDirectory(in)) {
            List<Path> found = sources(in);
            if (found.isEmpty()) throw new Missing("В папке " + in + " нет файлов .xero");
            name = in.toString().replace('\\', '/') + "/";
            StringBuilder imports = new StringBuilder();
            for (Path f : found)
                imports.append("import \"").append(in.relativize(f).toString().replace('\\', '/')).append("\"\n");
            text = imports.toString();
            files.add(in);
        } else if (Files.isRegularFile(in)) {
            name = in.toString().replace('\\', '/');
            text = Files.readString(in, StandardCharsets.UTF_8);
            files.add(in);
        } else {
            throw new Missing("Нет файла " + in);
        }
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put(name, text);
        Path home = Snapshot.locate(Snapshot.home(in)).getParent();
        Compiler.Loader loader = path -> {
            if (Library.module(path)) return Library.text(path.substring(Compiler.MODULE.length()), home);
            Path p = Path.of(path);
            files.add(p);
            if (!Files.isRegularFile(p)) return null;
            try {
                String t = Files.readString(p, StandardCharsets.UTF_8);
                texts.put(path, t);
                return t;
            } catch (IOException e) {
                return null;
            }
        };
        Data data = Data.load();
        Compiler.Result result = Compiler.compile(data, Names.build(data), name, text, loader);
        int errors = 0, warnings = 0;
        for (Diagnostic d : result.diagnostics()) {
            if (d.error()) errors++;
            else warnings++;
        }
        return new Built(Wrap.fitted(result.module(), data), result.diagnostics(), texts, name, files, errors, warnings, result.connected());
    }

    static List<Path> sources(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".xero")).sorted().toList();
        }
    }

    static Path output(Path in, String output) {
        if (output != null) return Path.of(output);
        if (Files.isDirectory(in)) return in.resolve(in.toAbsolutePath().getFileName() + ".json");
        return sibling(in, ".json");
    }

    static Path sibling(Path in, String extension) {
        String name = in.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        Path parent = in.toAbsolutePath().getParent();
        return parent == null ? Path.of(stem + extension) : parent.resolve(stem + extension);
    }

    static int blocks(List<Object> list) {
        int n = 0;
        for (Object o : list) {
            Map<String, Object> m = Json.object(o);
            if (m.containsKey("action") || m.containsKey("type")) n++;
            if (m.get("operations") != null) n += blocks(Json.array(m.get("operations")));
        }
        return n;
    }

    private static String display(String file) {
        int slash = file.lastIndexOf('/', file.length() - 2);
        return slash < 0 ? file : file.substring(slash + 1);
    }

    private static Map<String, Object> json(Diagnostic d, Source source) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("file", source.name);
        o.put("severity", d.error() ? "error" : "warning");
        o.put("message", d.message());
        if (d.hint() != null) o.put("hint", d.hint());
        o.put("line", source.line(d.start()));
        o.put("column", source.column(d.start()));
        int end = Math.max(d.start(), d.end() - 1);
        o.put("endLine", source.line(end));
        o.put("endColumn", source.column(end) + 1);
        if (!d.fixes().isEmpty()) {
            List<Object> fixes = new ArrayList<>();
            for (Diagnostic.Fix f : d.fixes()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("title", f.title());
                x.put("text", f.text());
                x.put("line", source.line(f.start()));
                x.put("column", source.column(f.start()));
                x.put("endLine", source.line(f.end()));
                x.put("endColumn", source.column(f.end()));
                fixes.add(x);
            }
            o.put("fixes", fixes);
        }
        return o;
    }

    private Project() {}
}
