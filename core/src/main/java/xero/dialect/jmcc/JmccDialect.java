package xero.dialect.jmcc;

import xero.compile.Compiler;
import xero.compile.Decompiler;
import xero.dialect.Dialect;
import xero.dialect.Syntax;
import xero.ide.Service;
import xero.lang.Ast;
import xero.lang.Diagnostic;
import xero.lang.Parser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JmccDialect extends Dialect {
    private static final String SAMPLE = """
            event<player_join> {
                player::message("Привет, %player%!");
            }
            """;

    private final Service service;
    private Table table;
    private Syntax syntax;

    public JmccDialect(Service service) { this.service = service; }

    private Table table() {
        if (table == null) table = new Table(service.data(), service.names());
        return table;
    }

    @Override public String id() { return "jmcc"; }

    @Override public String title() { return "JMCC"; }

    private static final Map<String, String> FACTORIES = Map.ofEntries(
            Map.entry("location", "location"), Map.entry("местоположение", "location"), Map.entry("локация", "location"),
            Map.entry("vector", "vector"), Map.entry("вектор", "vector"), Map.entry("vec", "vector"),
            Map.entry("item", "item"), Map.entry("предмет", "item"), Map.entry("block", "block"), Map.entry("блок", "block"),
            Map.entry("sound", "sound"), Map.entry("звук", "sound"), Map.entry("particle", "particle"),
            Map.entry("частица", "particle"), Map.entry("potion", "potion"), Map.entry("зелье", "potion"));

    @Override protected Map<String, String> factories() { return FACTORIES; }

    @Override
    protected String holder(String expression) { return "event<player_join> {\n    var " + HELD + " = " + expression + ";\n}\n"; }

    @Override
    protected String held(String text) {
        int at = text.indexOf(HELD);
        int eq = at < 0 ? -1 : text.indexOf('=', at);
        int end = text.lastIndexOf(';');
        return eq < 0 || end < eq ? null : text.substring(eq + 1, end).strip();
    }

    @Override public String author() { return "rqbik, donzgold, Deolure, suprohub"; }

    @Override public String link() { return "https://github.com/jmcc-reborn/jmcc"; }

    @Override public String extension() { return "jc"; }

    @Override public String sample() { return SAMPLE; }

    @Override
    public Syntax syntax() {
        if (syntax == null) {
            Set<String> literals = new LinkedHashSet<>(Words.TRUE);
            literals.addAll(Words.FALSE);
            syntax = Syntax.of(Words.keywords()).literals(literals).types(Words.types())
                    .comments("//", "#", "/*", "*/", true)
                    .strings("\"'", "lmpj", true, true).backtick("g s l i j b game save local line inline jmcc bracket")
                    .joiner("::").decorator('@').build();
        }
        return syntax;
    }

    @Override
    public Built build(String file, String text, Compiler.Loader loader) {
        try {
            Project project = new Project(table(), file, text, loader);
            Compiler.Result r = Compiler.compile(service.data(), service.names(), file, text, project.loader(), project.front());
            return new Built(r.module(), r.diagnostics());
        } catch (RuntimeException | StackOverflowError e) {
            return new Built(null, List.of(Diagnostic.error("код не собрать: слишком сложное выражение", 0, Math.min(1, text.length()))));
        }
    }

    @Override
    public Decompiled decompile(Map<String, Object> module) {
        Decompiler.Result r = Decompiler.decompile(service.data(), service.names(), module);
        List<Diagnostic> diagnostics = new ArrayList<>();
        Ast.File file = Parser.parse(r.text(), diagnostics);
        List<String> notes = new ArrayList<>(r.notes());
        for (Diagnostic d : diagnostics) if (d.error()) notes.add(d.message());
        return new Decompiled(Print.file(table(), file), notes);
    }

    @Override public List<Service.Item> vocabulary() { return table().vocabulary(); }

    @Override
    public List<Service.Symbol> symbols(String text) {
        Tree.Unit unit;
        try {
            unit = Read.parse(null, text, new ArrayList<>());
        } catch (RuntimeException | StackOverflowError e) {
            return List.of();
        }
        List<Service.Symbol> out = new ArrayList<>();
        for (Tree.Stmt s : unit.items()) {
            if (s instanceof Tree.Event e)
                out.add(new Service.Symbol(e.name(), "event", e.span().start(), e.span().end(), e.nameSpan().start(), e.nameSpan().end()));
        }
        for (Tree.Func f : unit.functions())
            out.add(new Service.Symbol(f.name(), f.process() ? "process" : "function", f.span().start(), f.span().end(),
                    f.nameSpan().start(), f.nameSpan().end()));
        out.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return out;
    }

    @Override
    protected Service.Signature signatureOf(Service.Item item, int active) {
        int cut = item.label().indexOf("::");
        if (cut < 0) return null;
        Table.Entry e = table().action(item.label().substring(0, cut), item.label().substring(cut + 2));
        return e == null ? null : table().signature(e, active, false, false);
    }

    @Override
    public Service.Signature signature(String text, int offset) {
        int depth = 0, active = 0;
        for (int i = Math.min(offset, text.length()) - 1; i >= 0 && i >= offset - 4000; i--) {
            char c = text.charAt(i);
            if (c == ')' || c == ']' || c == '}') {
                depth++;
            } else if (c == '(' || c == '[' || c == '{') {
                if (depth > 0) {
                    depth--;
                    continue;
                }
                return c == '(' ? callee(text, i, active) : null;
            } else if (c == ',' && depth == 0) {
                active++;
            }
        }
        return null;
    }

    private Service.Signature callee(String text, int open, int active) {
        int end = open;
        while (end > 0 && text.charAt(end - 1) == ' ') end--;
        if (end > 0 && text.charAt(end - 1) == '>') {
            int close = text.lastIndexOf('<', end);
            if (close >= 0) end = close;
        }
        int from = wordStart(text, end);
        String word = text.substring(from, end);
        if (from > 0 && text.charAt(from - 1) == '.') {
            Table.Entry e = table().method(word);
            return e == null ? null : table().signature(e, active, true, true);
        }
        Service.Item item = words().get(word);
        return item == null ? null : signatureOf(item, active);
    }

    @Override
    public String hover(String text, int offset) {
        Occurrence at = wordAt(text, offset);
        if (at != null) {
            Service.Item item = words().get(text.substring(wordStart(text, at.end()), at.end()));
            if (item != null) return item.doc() != null ? item.doc() : item.detail();
        }
        return super.hover(text, offset);
    }
}
