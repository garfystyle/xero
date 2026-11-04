package xero.compile;

import xero.lang.Ast;
import xero.lang.Ast.Span;
import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Lints {
    private record Site(String file, Span at) {}

    private final Context c;
    private final Map<String, Site> assigned = new LinkedHashMap<>();
    private final Set<String> read = new HashSet<>();
    private final List<String> texts = new ArrayList<>();
    private Set<String> inside;
    private int textsFrom;
    private boolean opaque;

    Lints(Context c) {
        this.c = c;
    }

    void assigned(String name, Span at) {
        assigned.putIfAbsent(name, new Site(c.diagnostics.file, at));
    }

    void read(String name) {
        read.add(name);
        if (inside != null) inside.add(name);
    }

    void text(String text) {
        if (text != null && !text.isEmpty()) texts.add(text);
    }

    void opaque() {
        opaque = true;
    }

    void enter() {
        inside = new HashSet<>();
        textsFrom = texts.size();
    }

    void leave(Ast.Func f) {
        for (Ast.Param p : f.params()) {
            if (inside.contains(p.name()) || mentioned(p.name(), textsFrom)) continue;
            c.warning("параметр «" + p.name() + "» не используется", p.span());
        }
        inside = null;
    }

    void report() {
        if (opaque) return;
        for (Diagnostic d : c.diagnostics) if (d.error()) return;
        for (Map.Entry<String, Site> e : assigned.entrySet()) {
            String name = e.getKey();
            String scope = c.variables.get(name);
            if (read.contains(name) || c.declared.contains(name) || name.startsWith(Wire.TEMP) || name.indexOf('%') >= 0
                    || !("local".equals(scope) || "line".equals(scope)) || mentioned(name, 0)) continue;
            Span at = e.getValue().at();
            c.diagnostics.add(new Diagnostic(false, "«" + name + "» задана, но нигде не читается", at.start(), at.end(), null)
                    .in(e.getValue().file()));
        }
    }

    private boolean mentioned(String name, int from) {
        for (int i = from; i < texts.size(); i++) if (word(texts.get(i), name)) return true;
        return false;
    }

    private static boolean word(String text, String name) {
        int at = text.indexOf(name);
        while (at >= 0) {
            int end = at + name.length();
            boolean before = at == 0 || !part(text.charAt(at - 1));
            boolean after = end == text.length() || !part(text.charAt(end));
            if (before && after) return true;
            at = text.indexOf(name, at + 1);
        }
        return false;
    }

    private static boolean part(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }
}
