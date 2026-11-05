package xero.dialect;

import xero.compile.Compiler;
import xero.compile.Decompiler;
import xero.format.Formatter;
import xero.ide.Service;
import xero.lang.Diagnostic;
import xero.lang.Keywords;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class XeroDialect extends Dialect {
    private static final String SAMPLE = """
            when player_join {
                send_message("Привет, %player%!")
            }
            """;

    private final Service service;
    private Syntax syntax;

    public XeroDialect(Service service) { this.service = service; }

    public Service service() { return service; }

    @Override public String id() { return "xero"; }

    @Override public String title() { return "Xero"; }

    @Override public String author() { return "garfystyle"; }

    @Override public String link() { return "https://github.com/garfystyle/xerocode"; }

    @Override public String extension() { return "xero"; }

    @Override public String sample() { return SAMPLE; }

    private static final Map<String, String> FACTORIES = Map.ofEntries(
            Map.entry("loc", "location"), Map.entry("vec", "vector"), Map.entry("item", "item"),
            Map.entry("block", "block"), Map.entry("sound", "sound"), Map.entry("particle", "particle"),
            Map.entry("potion", "potion"));

    @Override protected Map<String, String> factories() { return FACTORIES; }

    @Override
    protected String holder(String expression) { return "when player_join {\n    " + HELD + " = " + expression + "\n}\n"; }

    @Override
    protected String held(String text) {
        int at = text.indexOf(HELD + " = ");
        int end = text.lastIndexOf('}');
        return at < 0 || end < at ? null : text.substring(at + HELD.length() + 3, end).strip();
    }

    @Override
    public Syntax syntax() {
        if (syntax == null) {
            syntax = Syntax.of(Keywords.all()).literals(Set.of("true", "false", "истина", "ложь"))
                    .comments("//", null, "/*", "*/", false)
                    .strings("\"", "mm plain json legacy", true, true).backtick("g s l game save local line")
                    .joiner(".").decorator('@').build();
        }
        return syntax;
    }

    @Override
    public Built build(String file, String text, Compiler.Loader loader) {
        Compiler.Result r = service.build(file, text, loader);
        return new Built(r.module(), r.diagnostics());
    }

    @Override
    public Decompiled decompile(Map<String, Object> module) {
        Decompiler.Result r = Decompiler.decompile(service.data(), service.names(), module);
        return new Decompiled(r.text(), r.notes());
    }

    @Override public List<Service.Item> vocabulary() { return service.vocabulary(); }

    @Override
    public String format(String text) {
        Formatter.Result r = service.format(text);
        return r.ok() ? r.text() : null;
    }

    @Override public List<Service.Symbol> symbols(String text) { return service.symbols(text); }

    @Override public Service.Completion complete(String text, int offset) { return service.complete(text, offset); }

    @Override public Service.Signature signature(String text, int offset) { return service.signature(text, offset); }

    @Override public String hover(String text, int offset) { return service.hover(text, offset); }

    @Override public Service.Span definition(String text, int offset) { return service.definition(text, offset); }

    @Override public List<Service.Span> references(String text, int offset) { return service.references(text, offset); }

    @Override
    public Service.Renamed rename(String text, int offset, String wanted) { return service.rename(text, offset, wanted); }

    @Override
    public List<Diagnostic.Fix> actions(String text, int start, int end) { return service.actions(text, start, end); }

    @Override public List<Service.Mark> variables(String text) { return service.variables(text); }
}
