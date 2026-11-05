package xero.dialect.jmscript;

import xero.compile.Compiler;
import xero.compile.Decompiler;
import xero.data.Data;
import xero.data.Names;
import xero.dialect.Dialect;
import xero.dialect.Syntax;
import xero.ide.Service;
import xero.lang.Ast;
import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class JmscriptDialect extends Dialect {
    private static final String SAMPLE = """
            package main

            event player_join() {
                player_send_message("Привет, %player%!", "CONCATENATION")
            }
            """;
    private static final Set<String> KEYWORDS = Set.of("package", "func", "process", "event", "for", "in", "if", "else",
            "defer", "return", "continue", "break", "start", "import");
    private static final Set<String> TYPES = Set.of("any", "number", "text", "location", "vec3", "item", "block", "sound",
            "particle", "potion", "array", "dict", "enum", "game_value", "bool", "void");
    private static final String BODY = " {\n\t$0\n}";
    private static final int SHOWN_OPTIONS = 6;

    private final Service service;
    private final Compiler.Front front;
    private Syntax syntax;
    private List<Service.Item> vocabulary;

    public JmscriptDialect(Service service) {
        this.service = service;
        Names names = service.names();
        this.front = new Compiler.Front() {
            @Override
            public Ast.File parse(String file, String text, List<Diagnostic> diagnostics) {
                return Parser.parse(text, names, diagnostics).file();
            }

            @Override
            public boolean source(String path) {
                String lower = path.toLowerCase(Locale.ROOT);
                return lower.endsWith(".jms") || lower.endsWith(".jmscript");
            }
        };
    }

    @Override public String id() { return "jmscript"; }

    @Override public String title() { return "JMScript"; }

    private static final Map<String, String> FACTORIES = Map.ofEntries(
            Map.entry("location", "location"), Map.entry("loc", "location"), Map.entry("vec3", "vector"),
            Map.entry("vec", "vector"), Map.entry("item", "item"), Map.entry("block", "block"),
            Map.entry("sound", "sound"), Map.entry("particle", "particle"), Map.entry("potion", "potion"));

    @Override protected Map<String, String> factories() { return FACTORIES; }

    @Override
    protected String holder(String expression) { return "event player_join() {\n    " + HELD + " := " + expression + "\n}\n"; }

    @Override
    protected String held(String text) {
        int at = text.indexOf(HELD);
        int eq = at < 0 ? -1 : text.indexOf('=', at);
        int end = text.lastIndexOf('}');
        return eq < 0 || end < eq ? null : text.substring(eq + 1, end).strip();
    }

    @Override public String author() { return "altwine, awreh"; }

    @Override public String link() { return "https://github.com/altwine/jmscript"; }

    @Override public String extension() { return "jms"; }

    @Override public String sample() { return SAMPLE; }

    @Override
    public Syntax syntax() {
        if (syntax == null) {
            syntax = Syntax.of(KEYWORDS).types(TYPES).comments("//", null, "/*", "*/", false)
                    .strings("\"'", "", false, true).backtick("").joiner(".").decorator('@').build();
        }
        return syntax;
    }

    @Override
    public Built build(String file, String text, Compiler.Loader loader) {
        try {
            Compiler.Result r = Compiler.compile(service.data(), service.names(), file, text, loader, front);
            return new Built(r.module(), r.diagnostics());
        } catch (RuntimeException | StackOverflowError e) {
            return new Built(null, List.of(Diagnostic.error("сборка не удалась: " + e, 0, Math.min(1, text.length()))));
        }
    }

    @Override
    public Decompiled decompile(Map<String, Object> module) {
        Decompiler.Result r = Decompiler.forDialect(service.data(), service.names(), module);
        List<Diagnostic> problems = new ArrayList<>();
        Ast.File file = xero.lang.Parser.parse(r.text(), problems);
        List<String> notes = new ArrayList<>(r.notes());
        for (Diagnostic d : problems) if (d.error()) notes.add("не удалось записать: " + d.message());
        return new Decompiled(Printer.print(service.names(), file), notes);
    }

    @Override
    public List<Service.Item> vocabulary() {
        if (vocabulary != null) return vocabulary;
        List<Service.Item> out = new ArrayList<>();
        for (Names.Entry e : service.names().entries()) {
            Data.Action a = e.action();
            if (a.alias() || a.blank()) continue;
            boolean condition = e.space() == Names.Space.CONDITION;
            String body = a.container() && !condition ? a.id() + "($1)" + BODY : null;
            out.add(new Service.Item(a.id(), condition ? "condition" : "action", a.label() + " · " + a.category().russian,
                    doc(a), true, 0, body, false));
        }
        for (Service.Item i : service.vocabulary()) {
            if (i.kind().equals("event"))
                out.add(new Service.Item(i.label(), "event", i.detail(), i.doc(), false, 0, i.label() + "()" + BODY, false));
            else if (i.kind().equals("value"))
                out.add(new Service.Item(i.label(), "value", i.detail(), i.doc(), false, 0, "game_value(\"" + i.label() + "\")", false));
        }
        vocabulary = out;
        return out;
    }

    private static String doc(Data.Action a) {
        StringBuilder out = new StringBuilder();
        out.append("**").append(a.label()).append("** — ").append(a.category().russian).append("\n\n");
        if (!a.description().isEmpty()) out.append(a.description()).append("\n\n");
        for (Data.Arg arg : a.args())
            out.append("- `").append(arg.id()).append("` — ").append(arg.label().isEmpty() ? arg.kind() : arg.label()).append('\n');
        for (Data.Setting s : a.settings())
            out.append("- `").append(s.id()).append("` — ").append(String.join(", ", options(s))).append('\n');
        return out.toString();
    }

    private static List<String> options(Data.Setting s) {
        List<String> out = new ArrayList<>();
        for (String o : s.options().keySet()) out.add("\"" + o.toUpperCase(Locale.ROOT) + "\"");
        return out;
    }

    @Override
    public List<Service.Symbol> symbols(String text) {
        List<Service.Symbol> out = new ArrayList<>();
        Parser.Result r = Parser.parse(text, service.names(), new ArrayList<>());
        for (Ast.Item item : r.file().items()) {
            if (item instanceof Ast.Event e && e != r.entry()) {
                out.add(new Service.Symbol(e.name(), "event", e.span().start(), e.span().end(), e.nameSpan().start(), e.nameSpan().end()));
            } else if (item instanceof Ast.Func f) {
                out.add(new Service.Symbol(f.name(), f.process() ? "process" : "function", f.span().start(), f.span().end(),
                        f.nameSpan().start(), f.nameSpan().end()));
            }
        }
        return out;
    }

    @Override
    protected Service.Signature signatureOf(Service.Item item, int active) {
        Data.Action a = service.data().actions().get(item.label());
        if (a == null) return null;
        List<String> params = new ArrayList<>();
        for (Data.Arg arg : a.args())
            params.add(arg.id() + ": " + (arg.label().isEmpty() ? arg.kind() : arg.label()) + (arg.list() ? "…" : ""));
        for (Data.Setting s : a.settings()) {
            List<String> options = options(s);
            boolean many = options.size() > SHOWN_OPTIONS;
            params.add(s.id() + ": " + String.join("|", many ? options.subList(0, SHOWN_OPTIONS) : options) + (many ? "|…" : ""));
        }
        StringBuilder label = new StringBuilder(a.id()).append('(');
        List<Service.Span> spans = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) label.append(", ");
            spans.add(new Service.Span(label.length(), label.length() + params.get(i).length()));
            label.append(params.get(i));
        }
        return new Service.Signature(label.append(')').toString(), spans, active, item.doc());
    }
}
