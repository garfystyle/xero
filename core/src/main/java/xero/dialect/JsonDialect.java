package xero.dialect;

import xero.compile.Compiler;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JsonDialect extends Dialect {
    private static final String SAMPLE = """
            {
                "handlers": []
            }
            """;
    private static final Syntax SYNTAX = Syntax.of(Set.of()).literals(Set.of("true", "false", "null"))
            .comments(null, null, null, null, false).strings("\"", "", false, false).build();

    @Override public String id() { return "json"; }

    @Override public String title() { return "JSON"; }

    @Override public String extension() { return "json"; }

    @Override public Syntax syntax() { return SYNTAX; }

    @Override public String sample() { return SAMPLE; }

    @Override
    public Built build(String file, String text, Compiler.Loader loader) {
        try {
            Object parsed = Json.parse(text);
            if (!(parsed instanceof Map<?, ?>)) return failed("в модуле нужен объект с полем handlers", text);
            Map<String, Object> module = Json.object(parsed);
            if (!(module.get("handlers") instanceof List<?>)) return failed("в модуле нет поля handlers", text);
            return new Built(module, List.of());
        } catch (Json.Error | IllegalArgumentException | ClassCastException e) {
            return failed(String.valueOf(e.getMessage()), text);
        }
    }

    private static Built failed(String why, String text) {
        return new Built(null, List.of(Diagnostic.error(why, 0, Math.min(1, text.length()))));
    }

    @Override
    public Decompiled decompile(Map<String, Object> module) { return new Decompiled(Json.pretty(module), List.of()); }

    @Override
    public String format(String text) {
        try {
            return Json.pretty(Json.parse(text));
        } catch (Json.Error | IllegalArgumentException e) {
            return null;
        }
    }
}
