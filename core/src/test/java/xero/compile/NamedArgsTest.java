package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamedArgsTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private static Compiler.Result compile(String body) {
        return Compiler.compile(DATA, NAMES, "when player_join {\n" + body + "\n}\n");
    }

    private static List<String> warnings(Compiler.Result r) {
        return r.diagnostics().stream().filter(d -> !d.error()).map(Diagnostic::message)
                .filter(m -> m.contains("не как на сервере")).toList();
    }

    @Test
    void thirteenActionsAreListed() {
        int n = 0;
        for (Data.Action a : DATA.actions().values()) if (DATA.named(a.id()) != null) n++;
        assertEquals(12, n);
        assertEquals(List.of("text", "remove"), DATA.named("set_variable_remove_text"));
        assertEquals(null, DATA.named("set_variable_get_vector_between_locations"));
    }

    @Test
    void positionalCallWarns() {
        Compiler.Result r = compile("    t = remove_text(\"abc\", \"b\")\n    send_message(t)");
        assertTrue(r.ok(), r.diagnostics()::toString);
        List<String> w = warnings(r);
        assertEquals(1, w.size(), r.diagnostics()::toString);
        assertTrue(w.get(0).contains("remove_text") && w.get(0).contains("«remove», «text»"), w::toString);
        Diagnostic d = r.diagnostics().stream().filter(x -> x.message().contains("не как на сервере")).findFirst().orElseThrow();
        assertNotNull(d.hint());
        assertTrue(d.hint().contains("remove_text(text: …, remove: …)"), d.hint());
    }

    @Test
    void namedCallIsQuietAndTheSame() {
        Compiler.Result named = compile("    t = remove_text(text: \"b\", remove: \"abc\")\n    send_message(t)");
        Compiler.Result positional = compile("    t = remove_text(\"abc\", \"b\")\n    send_message(t)");
        assertTrue(warnings(named).isEmpty(), named.diagnostics()::toString);
        assertEquals(Json.write(positional.module()), Json.write(named.module()));
    }

    @Test
    void otherActionsStayQuiet() {
        assertTrue(warnings(compile("    l = [3, 1, 2]\n    x = get_list_value(l, 0, 0)\n    send_message(\"${x}\")")).isEmpty());
        assertTrue(warnings(compile("    set_block(loc(1, 2, 3), block(stone))")).isEmpty());
    }

    @Test
    void methodCallOnValueWarnsToo() {
        Compiler.Result r = compile("    s = \"abc\"\n    s.remove_text(\"b\")\n    send_message(s)");
        assertFalse(warnings(r).isEmpty(), r.diagnostics()::toString);
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
        assertTrue(warnings(again).isEmpty(), again.diagnostics()::toString);
        assertEquals(Json.write(r.module()), Json.write(again.module()));
    }
}
