package xero.js;

import org.teavm.jso.JSExport;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import xero.compile.Compiler;
import xero.compile.Decompiler;
import xero.compile.Delivery;
import xero.format.Formatter;
import xero.ide.Service;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Bridge {
    @JSFunctor
    public interface Reader extends JSObject {
        String read(String path);
    }

    private static Service service;

    private static Service service() {
        if (service == null) service = Service.load();
        return service;
    }

    public static void main(String[] args) {
    }

    @JSExport
    public static String check(String name, String text, Reader reader) {
        return guard(() -> diagnostics(service().build(name, text, loader(reader)).diagnostics()));
    }

    @JSExport
    public static String build(String name, String text, Reader reader) {
        return guard(() -> {
            Compiler.Result r = service().build(name, text, loader(reader));
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("ok", r.ok());
            o.put("diagnostics", diagnostics(r.diagnostics()));
            if (r.ok()) {
                o.put("module", Json.write(r.module()));
                List<Object> handlers = Json.array(r.module().get("handlers"));
                o.put("lines", handlers.size());
                o.put("blocks", blocks(handlers));
                o.put("connected", r.connected());
            }
            return o;
        });
    }

    @JSExport
    public static String format(String text) {
        return guard(() -> {
            Formatter.Result r = Formatter.format(text);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("ok", r.ok());
            if (r.ok()) o.put("text", r.text());
            o.put("diagnostics", diagnostics(r.errors()));
            return o;
        });
    }

    @JSExport
    public static String complete(String text, int offset) {
        return guard(() -> {
            Service.Completion c = service().complete(text, offset);
            List<Object> items = new ArrayList<>();
            for (Service.Item i : c.items()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("label", i.label());
                o.put("kind", i.kind());
                if (i.detail() != null) o.put("detail", i.detail());
                if (i.doc() != null) o.put("doc", i.doc());
                o.put("call", i.call());
                if (i.snippet() != null) o.put("snippet", i.snippet());
                if (i.retrigger()) o.put("retrigger", true);
                items.add(o);
            }
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("from", c.from());
            o.put("items", items);
            return o;
        });
    }

    @JSExport
    public static String hover(String text, int offset) {
        return guard(() -> {
            Map<String, Object> o = new LinkedHashMap<>();
            String h = service().hover(text, offset);
            if (h != null) o.put("markdown", h);
            return o;
        });
    }

    @JSExport
    public static String definition(String text, int offset) {
        return guard(() -> {
            Map<String, Object> o = new LinkedHashMap<>();
            Service.Span s = service().definition(text, offset);
            if (s != null) {
                o.put("start", s.start());
                o.put("end", s.end());
                String file = service().definitionFile(text, offset);
                if (file != null) o.put("file", file);
            }
            return o;
        });
    }

    @JSExport
    public static String rename(String text, int offset, String name) {
        return guard(() -> {
            Service.Renamed r = service().rename(text, offset, name);
            Map<String, Object> o = new LinkedHashMap<>();
            if (r.error() != null) o.put("error", r.error());
            List<Object> edits = new ArrayList<>();
            for (Service.Edit e : r.edits()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("start", e.start());
                x.put("end", e.end());
                x.put("text", e.text());
                edits.add(x);
            }
            o.put("edits", edits);
            Map<String, Object> others = new LinkedHashMap<>();
            r.others().forEach((file, list) -> {
                List<Object> rows = new ArrayList<>();
                for (Service.Edit e : list) {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("start", e.start());
                    x.put("end", e.end());
                    x.put("text", e.text());
                    rows.add(x);
                }
                others.put(file, rows);
            });
            o.put("others", others);
            return o;
        });
    }

    @JSExport
    public static String signature(String text, int offset) {
        return guard(() -> {
            Map<String, Object> o = new LinkedHashMap<>();
            Service.Signature s = service().signature(text, offset);
            if (s == null) return o;
            o.put("label", s.label());
            List<Object> params = new ArrayList<>();
            for (Service.Span p : s.params()) params.add(List.of(p.start(), p.end()));
            o.put("params", params);
            o.put("active", s.active());
            if (s.doc() != null) o.put("doc", s.doc());
            return o;
        });
    }

    @JSExport
    public static String references(String text, int offset) {
        return guard(() -> {
            List<Object> out = new ArrayList<>();
            for (Service.Span s : service().references(text, offset)) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("start", s.start());
                o.put("end", s.end());
                out.add(o);
            }
            service().referencesElsewhere(text, offset).forEach((file, spans) -> {
                for (Service.Span s : spans) {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("file", file);
                    o.put("start", s.start());
                    o.put("end", s.end());
                    out.add(o);
                }
            });
            return out;
        });
    }

    @JSExport
    public static String hints(String text) {
        return guard(() -> {
            List<Object> out = new ArrayList<>();
            for (Service.Hint h : service().hints(text)) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("at", h.at());
                o.put("label", h.label());
                out.add(o);
            }
            return out;
        });
    }

    @JSExport
    public static String variables(String text) {
        return guard(() -> {
            List<Object> out = new ArrayList<>();
            for (Service.Mark m : service().variables(text)) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("start", m.start());
                o.put("end", m.end());
                o.put("scope", m.scope());
                out.add(o);
            }
            return out;
        });
    }

    @JSExport
    public static String actions(String text, int start, int end) {
        return guard(() -> {
            List<Object> out = new ArrayList<>();
            for (Diagnostic.Fix f : service().actions(text, start, end)) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("title", f.title());
                x.put("start", f.start());
                x.put("end", f.end());
                x.put("text", f.text());
                out.add(x);
            }
            return out;
        });
    }

    @JSExport
    public static String symbols(String text) {
        return guard(() -> {
            List<Object> out = new ArrayList<>();
            for (Service.Symbol s : service().symbols(text)) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("name", s.name());
                o.put("kind", s.kind());
                o.put("start", s.start());
                o.put("end", s.end());
                o.put("nameStart", s.nameStart());
                o.put("nameEnd", s.nameEnd());
                out.add(o);
            }
            return out;
        });
    }

    @JSExport
    public static String decompile(String world) {
        return guard(() -> {
            Object parsed = Json.parse(world);
            Map<String, Object> o = new LinkedHashMap<>();
            if (!(parsed instanceof Map<?, ?> m) || !(m.get("handlers") instanceof List<?> l)) {
                o.put("error", "нет поля handlers — это не код мира");
                return o;
            }
            Decompiler.Result r = Decompiler.decompile(service().data(), service().names(), Json.object(parsed));
            o.put("text", r.text());
            o.put("notes", new ArrayList<Object>(r.notes()));
            o.put("lines", l.size());
            o.put("blocks", blocks(Json.array(l)));
            return o;
        });
    }

    @JSExport
    public static String plan(String copy, String module) {
        return guard(() -> {
            Map<String, Object> project = Json.object(Json.parse(module));
            List<Delivery.Line> mine = Delivery.lines(project);
            List<Delivery.Line> world = copy == null || copy.isEmpty() ? List.of() : Delivery.lines(Json.object(Json.parse(copy)));
            Delivery.Plan p = Delivery.plan(service().data(), world, mine);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("same", p.same().size());
            o.put("changed", titles(p.changed()));
            o.put("added", titles(p.added()));
            o.put("missing", titles(p.missing()));
            o.put("module", Json.write(p.module(mine)));
            o.put("bytes", Json.write(p.module(mine)).length());
            o.put("limit", Delivery.MAX_BYTES);
            if (!world.isEmpty() && p.lineByLine(world, 20)) o.put("rows", Json.write(p.lines()));
            return o;
        });
    }

    private static List<Object> titles(List<Delivery.Line> lines) {
        List<Object> out = new ArrayList<>();
        for (Delivery.Line l : lines) out.add(l.title());
        return out;
    }

    private interface Work {
        Object run();
    }

    private static String guard(Work work) {
        try {
            return Json.write(work.run());
        } catch (Throwable e) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return Json.write(o);
        }
    }

    private static Compiler.Loader loader(Reader reader) {
        if (reader == null) return null;
        return path -> {
            try {
                return reader.read(path);
            } catch (Throwable e) {
                return null;
            }
        };
    }

    private static List<Object> diagnostics(List<Diagnostic> list) {
        List<Object> out = new ArrayList<>();
        for (Diagnostic d : list) {
            Map<String, Object> o = new LinkedHashMap<>();
            if (d.file() != null) o.put("file", d.file());
            o.put("severity", d.error() ? "error" : "warning");
            o.put("message", d.message());
            if (d.hint() != null) o.put("hint", d.hint());
            o.put("start", d.start());
            o.put("end", d.end());
            if (!d.fixes().isEmpty()) {
                List<Object> fixes = new ArrayList<>();
                for (Diagnostic.Fix f : d.fixes()) {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("title", f.title());
                    x.put("start", f.start());
                    x.put("end", f.end());
                    x.put("text", f.text());
                    fixes.add(x);
                }
                o.put("fixes", fixes);
            }
            out.add(o);
        }
        return out;
    }

    private static int blocks(List<Object> list) {
        int n = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> m = Json.object(raw);
            if (m.containsKey("action") || m.containsKey("type")) n++;
            if (m.get("operations") instanceof List<?> ops) n += blocks(Json.array(ops));
        }
        return n;
    }

    private Bridge() {
    }
}
