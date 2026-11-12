package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamedArgsTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private static Compiler.Result compile(String body) {
        return Compiler.compile(DATA, NAMES, "when player_join {\n" + body + "\n}\n");
    }

    @Test
    void twelveActionsAreListed() {
        int n = 0;
        for (Data.Action a : DATA.actions().values()) if (DATA.named(a.id()) != null) n++;
        assertEquals(12, n);
        assertEquals(List.of("text", "remove"), DATA.named("set_variable_remove_text"));
        assertEquals(null, DATA.named("set_variable_get_vector_between_locations"));
    }

    @Test
    void positionalCallIsQuiet() {
        Compiler.Result r = compile("    t = remove_text(\"abc\", \"b\")\n    send_message(t)");
        assertTrue(r.ok(), r.diagnostics()::toString);
        assertTrue(r.diagnostics().isEmpty(), r.diagnostics()::toString);
    }

    @Test
    void namedCallIsQuietAndTheSame() {
        Compiler.Result named = compile("    t = remove_text(text: \"b\", remove: \"abc\")\n    send_message(t)");
        Compiler.Result positional = compile("    t = remove_text(\"abc\", \"b\")\n    send_message(t)");
        assertTrue(named.diagnostics().isEmpty(), named.diagnostics()::toString);
        assertEquals(Json.write(positional.module()), Json.write(named.module()));
    }

    @Test
    void methodCallOnValueIsQuiet() {
        Compiler.Result r = compile("    s = \"abc\"\n    s.remove_text(\"b\")\n    send_message(s)");
        assertTrue(r.ok(), r.diagnostics()::toString);
        assertTrue(r.diagnostics().isEmpty(), r.diagnostics()::toString);
    }

    @Test
    void decompilerNamesThem() {
        Compiler.Result r = compile("    a = loc(0, 0, 0)\n    b = loc(1, 0, 0)\n    v = get_vector_between_locations(start_location: a, end_location: b)\n"
                + "    t = remove_text(text: \"abc\", remove: \"b\")\n    m = {\"k\": 1}\n    q = get_map_value(map: m, key: \"k\", default_value: 0)\n"
                + "    send_message(\"${v} ${t} ${q}\")");
        assertTrue(r.ok(), r.diagnostics()::toString);
        String text = Decompiler.decompile(DATA, NAMES, r.module()).text();
        assertTrue(text.contains("get_vector_between_locations(b, a)"), text);
        assertTrue(text.contains("remove_text(remove: \"b\", text: \"abc\")") || text.contains("remove_text(text: \"abc\", remove: \"b\")"), text);
        assertTrue(text.contains("get_map_value(default_value: 0, key: \"k\", map: m)") || text.contains("get_map_value(map: m, key: \"k\", default_value: 0)"), text);
        Compiler.Result again = Compiler.compile(DATA, NAMES, text);
        assertTrue(again.diagnostics().isEmpty(), again.diagnostics()::toString);
        assertEquals(Json.write(r.module()), Json.write(again.module()));
    }
}
