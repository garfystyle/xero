package xero.dialect.justice;

import xero.compile.Compiler;
import xero.data.Data;
import xero.data.Names;
import xero.dialect.Dialect;
import xero.dialect.Syntax;
import xero.ide.Service;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class JusticeDialect extends Dialect {
    private static final String SAMPLE = """
            event player_join {
                player_send_message(messages={ l"&bПривет, %player%!" });
            }
            """;
    private static final Set<String> KEYWORDS = Set.of("event", "function", "process", "not", "else");
    private static final Set<String> TYPES = Set.of("location", "vector", "item", "block", "sound", "potion", "particle", "map",
            "number", "enum", "raw");
    private static final Set<String> NO_TARGET = Set.of("Мир", "Событие");
    private static final String BODY = " {\n\t$0\n}";
    private static final int SHOWN_OPTIONS = 6;

    private final Service service;
    private Syntax syntax;
    private List<Service.Item> vocabulary;

    public JusticeDialect(Service service) { this.service = service; }

    @Override public String id() { return "justice"; }

    @Override public String title() { return "JustIce"; }

    private static final Map<String, String> FACTORIES = Map.ofEntries(
            Map.entry("location", "location"), Map.entry("vector", "vector"), Map.entry("item", "item"),
            Map.entry("block", "block"), Map.entry("sound", "sound"), Map.entry("particle", "particle"),
            Map.entry("potion", "potion"));

    @Override protected Map<String, String> factories() { return FACTORIES; }

    @Override
    protected String holder(String expression) {
        return "event player_join {\n    set_variable_value(variable=" + HELD + ", value=" + expression + ");\n}\n";
    }

    @Override
    protected String held(String text) {
        int at = text.indexOf("value=", text.indexOf(HELD));
        int end = text.lastIndexOf(");");
        return at < 0 || end < at ? null : text.substring(at + 6, end).strip();
    }

    @Override public String author() { return "White-145"; }

    @Override public String link() { return "https://github.com/White-145/justice"; }

    @Override public String extension() { return "ji"; }

    @Override public String sample() { return SAMPLE; }

    @Override
    public Syntax syntax() {
        if (syntax == null) {
            syntax = Syntax.of(KEYWORDS).literals(Set.of()).types(TYPES).comments("//", null, "/*", "*/", false)
                    .strings("\"'", "plmj", false, false).backtick("l g s local game save line").decorator('@').build();
        }
        return syntax;
    }

    @Override
    public Built build(String file, String text, Compiler.Loader loader) {
        List<Diagnostic> problems = new ArrayList<>();
        try {
            Parser.Result r = Parser.parse(text, service.data(), problems);
            List<Diagnostic> out = new ArrayList<>();
            for (Diagnostic d : problems) out.add(file == null ? d : d.in(file));
            return new Built(r.module(), out);
        } catch (RuntimeException | StackOverflowError e) {
            return new Built(null, List.of(Diagnostic.error("сборка не удалась: " + e, 0, Math.min(1, text.length()))));
        }
    }

    @Override
    public Decompiled decompile(Map<String, Object> module) {
        List<String> notes = new ArrayList<>();
        String text = Printer.print(service.data(), Json.object(Json.parse(Json.write(module))), notes);
        return new Decompiled(text, notes);
    }

    @Override
    public List<Service.Item> vocabulary() {
        if (vocabulary != null) return vocabulary;
        List<Service.Item> out = new ArrayList<>();
        for (Names.Entry e : service.names().entries()) {
            Data.Action a = e.action();
            if (a.alias() || a.blank()) continue;
            boolean condition = e.space() == Names.Space.CONDITION;
            String tail = a.args().isEmpty() && a.settings().isEmpty() ? "" : "($1)";
            String body = a.id() + tail + (a.container() ? BODY : ";$0");
            out.add(new Service.Item(a.id(), condition ? "condition" : "action", a.label() + " · " + a.category().russian,
                    doc(a), true, 0, body, false));
        }
        for (Service.Item i : service.vocabulary()) {
            if (i.kind().equals("event")) {
                out.add(new Service.Item(i.label(), "event", i.detail(), i.doc(), false, 0, i.label() + BODY, false));
            } else if (i.kind().equals("value")) {
                Data.GameValue v = service.data().gameValues().get(i.label());
                String target = v != null && NO_TARGET.contains(v.group()) ? "<>" : "<default>";
                out.add(new Service.Item(i.label(), "value", i.detail(), i.doc(), false, 0, target + i.label(), false));
            }
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
        for (String o : s.options().keySet()) out.add("'" + o.toUpperCase(Locale.ROOT) + "'");
        return out;
    }

    @Override
    public List<Service.Symbol> symbols(String text) {
        List<Service.Symbol> out = new ArrayList<>();
        for (Parser.Handler h : Parser.parse(text, service.data(), new ArrayList<>()).handlers())
            out.add(new Service.Symbol(h.name(), h.kind(), h.start(), h.end(), h.nameStart(), h.nameEnd()));
        return out;
    }

    @Override
    protected Service.Signature signatureOf(Service.Item item, int active) {
        Data.Action a = service.data().actions().get(item.label());
        if (a == null) return null;
        List<String> params = new ArrayList<>();
        for (Data.Arg arg : a.args())
            params.add(arg.id() + "=" + (arg.label().isEmpty() ? arg.kind() : arg.label()) + (arg.list() ? "…" : ""));
        for (Data.Setting s : a.settings()) {
            List<String> options = options(s);
            boolean many = options.size() > SHOWN_OPTIONS;
            params.add(s.id() + "=" + String.join("|", many ? options.subList(0, SHOWN_OPTIONS) : options) + (many ? "|…" : ""));
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
