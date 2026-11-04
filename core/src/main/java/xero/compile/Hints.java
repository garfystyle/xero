package xero.compile;

import xero.data.Data;
import xero.data.Names;
import xero.lang.Ast;
import xero.lang.Ast.Span;
import xero.lang.Diagnostic;
import xero.lang.Keywords;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

final class Hints {
    record Found(String hint, List<String> names) {
        static final Found NONE = new Found(null, List.of());
    }

    private final Context c;

    Hints(Context c) {
        this.c = c;
    }

    Found russianActions(String query, Names.Space space) {
        List<Names.Entry> entries = new ArrayList<>();
        for (Names.Entry e : c.names.entries())
            if (e.space() == space && !e.action().alias() && !e.action().blank()) entries.add(e);
        return russian(query, entries, e -> e.action().label(), e -> e.bare() ? e.name() : e.qualified());
    }

    Found russianEvents(String query) {
        return russian(query, c.data.events().values(), Data.Event::label, Data.Event::id);
    }

    private static <T> Found russian(String query, Iterable<T> items, Function<T, String> label, Function<T, String> written) {
        if (query.chars().noneMatch(ch -> ch >= 0x0400 && ch <= 0x04FF)) return Found.NONE;
        String[] words = query.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[_\\s.]+");
        List<String> shown = new ArrayList<>();
        List<String> found = new ArrayList<>();
        for (T item : items) {
            String l = label.apply(item).toLowerCase(Locale.ROOT).replace('ё', 'е');
            boolean all = true;
            for (String w : words) if (!w.isEmpty() && !l.contains(w.length() > 4 ? w.substring(0, w.length() - 1) : w)) all = false;
            if (all) {
                found.add(written.apply(item));
                shown.add(written.apply(item) + " («" + label.apply(item) + "»)");
            }
            if (found.size() >= 4) break;
        }
        return found.isEmpty() ? Found.NONE : new Found("по-русски похоже: " + String.join(", ", shown), found);
    }

    String unknown(Ast.Call call, Names.Space space) {
        Names.Space other = space == Names.Space.ACTION ? Names.Space.CONDITION : Names.Space.ACTION;
        if (c.names.resolve(other, call.shown()) != null)
            return space == Names.Space.ACTION
                    ? "«" + call.shown() + "» — это условие, его пишут после if"
                    : "«" + call.shown() + "» — это действие, а не условие";
        List<String> full = new ArrayList<>();
        for (Names.Entry e : c.names.entries())
            if (e.space() == space && e.name().equals(call.name()) && !e.action().alias())
                full.add(e.qualified());
        if (call.qualifier() == null && !full.isEmpty())
            return "«" + call.name() + "» есть в нескольких категориях — напиши " + String.join(" или ", full);
        return "нет " + (space == Names.Space.ACTION ? "действия" : "условия") + " «" + call.shown() + "»";
    }

    Context.Stop unknownCall(Ast.Call call, Names.Space space) {
        String message = unknown(call, space);
        if (!message.startsWith("нет ")) return c.stop(message, call.nameSpan());
        Set<String> candidates = new LinkedHashSet<>();
        for (Names.Entry e : c.names.entries()) {
            if (e.space() != space) continue;
            candidates.add(e.bare() ? e.name() : e.qualified());
        }
        if (space == Names.Space.ACTION) candidates.addAll(c.functions.keySet());
        Span whole = new Span(call.span().start(), call.nameSpan().end());
        String best = Suggest.best(call.shown(), candidates);
        if (best != null) {
            String written = c.functions.containsKey(best) ? Keywords.function(best) : best;
            return c.stop(message, call.nameSpan(), Suggest.meant(best), replace(whole, written));
        }
        Found found = russianActions(call.name(), space);
        String hint = found.hint() == null && call.name().indexOf('.') > 0
                ? "функцию из мира, которой нет в файле, пишут в обратных кавычках: `" + call.name() + "`(…)" : found.hint();
        return c.stop(message, call.nameSpan(), hint, replace(whole, found.names()));
    }

    void spare(Names.Entry e, Span at) {
        if (!e.action().alias()) return;
        Names.Entry live = c.names.byId(e.live());
        c.warning("«" + e.name() + "» — запасной id " + e.action().id()
                + "; из палитры сервер ставит " + e.live() + " («" + (live.bare() ? live.name() : live.qualified())
                + "»)", at);
    }

    static List<Diagnostic.Fix> replace(Span at, String text) {
        return text == null || at == null ? List.of() : List.of(Diagnostic.Fix.replace(at.start(), at.end(), text));
    }

    static List<Diagnostic.Fix> replace(Span at, List<String> texts) {
        List<Diagnostic.Fix> out = new ArrayList<>();
        if (at != null) for (String t : texts) out.add(Diagnostic.Fix.replace(at.start(), at.end(), t));
        return out;
    }
}
