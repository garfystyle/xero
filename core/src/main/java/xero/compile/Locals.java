package xero.compile;

import xero.lang.Ast;
import xero.lang.Ast.Span;
import xero.lang.Diagnostic;
import xero.lang.Keywords;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Locals {
    private record Read(String name, Span at) {}

    private record Start(String process, String mode) {}

    private static final class Line {
        final Ast.Item item;
        final String file;
        final Set<String> writes = new LinkedHashSet<>();
        final Map<String, Span> writtenAt = new LinkedHashMap<>();
        final Map<String, Read> reads = new LinkedHashMap<>();
        final Set<String> calls = new LinkedHashSet<>();
        final List<Start> starts = new ArrayList<>();
        boolean opaque;

        Line(Ast.Item item, String file) {
            this.item = item;
            this.file = file;
        }

        String title() {
            return item instanceof Ast.Event e ? "when " + e.name()
                    : (((Ast.Func) item).process() ? "процессе «" : "функции «") + ((Ast.Func) item).name() + "»";
        }

        boolean root() {
            return item instanceof Ast.Event || item instanceof Ast.Func f && f.process();
        }
    }

    private static final String SHARE = "share";
    private static final String COPY = "copy";

    private final Context c;
    private final List<Line> lines = new ArrayList<>();
    private Line current;

    Locals(Context c) {
        this.c = c;
    }

    void enter(Ast.Item item) {
        current = new Line(item, c.diagnostics.file);
        lines.add(current);
    }

    void leave() {
        current = null;
    }

    void use(String name, Span at, boolean write) {
        if (current == null || name.startsWith(Wire.TEMP) || c.declared.contains(name)) return;
        if (write) {
            current.writes.add(name);
            if (at != null) current.writtenAt.putIfAbsent(name, at);
        } else if (at != null) {
            current.reads.putIfAbsent(name, new Read(name, at));
        }
    }

    void call(String function, boolean known) {
        if (!known) c.lints.opaque();
        if (current == null) return;
        if (known) current.calls.add(function);
        else current.opaque = true;
    }

    void opaque() {
        c.lints.opaque();
        if (current != null) current.opaque = true;
    }

    void start(String process, String mode) {
        if (current != null) current.starts.add(new Start(process, mode));
    }

    void raw(Object json) {
        if (current == null) return;
        if (json instanceof Map<?, ?> m) {
            if ("variable".equals(m.get("type")) && m.get("variable") instanceof String name) c.lints.read(name);
            if ("variable".equals(m.get("type")) && m.get("variable") instanceof String name && "local".equals(m.get("scope")))
                use(name, null, true);
            for (Object v : m.values()) raw(v);
        } else if (json instanceof List<?> l) {
            for (Object v : l) raw(v);
        }
    }

    void report(List<Compiler.Unit> units) {
        Map<String, Line> functions = new LinkedHashMap<>();
        Map<String, Line> processes = new LinkedHashMap<>();
        List<Line> roots = new ArrayList<>();
        for (Line l : lines) {
            if (l.item instanceof Ast.Func f) (f.process() ? processes : functions).putIfAbsent(f.name(), l);
            if (l.root()) roots.add(l);
        }
        shared(functions);
        Map<Line, List<Line>> reach = new LinkedHashMap<>();
        Set<Line> reached = new HashSet<>();
        for (Line r : roots) {
            List<Line> all = reach(r, functions);
            reach.put(r, all);
            reached.addAll(all);
        }
        Set<String> elsewhere = new HashSet<>();
        for (Line l : lines) if (!reached.contains(l)) elsewhere.addAll(l.writes);
        Map<Line, Set<String>> own = new LinkedHashMap<>();
        Map<Line, Set<String>> writes = new LinkedHashMap<>();
        Set<Line> opaque = new HashSet<>();
        Set<Line> started = new HashSet<>();
        for (Line r : roots) {
            Set<String> w = new LinkedHashSet<>();
            for (Line l : reach.get(r)) {
                w.addAll(l.writes);
                if (l.opaque) opaque.add(r);
            }
            own.put(r, w);
            writes.put(r, new LinkedHashSet<>(w));
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Line q : roots) {
                for (Line l : reach.get(q)) {
                    for (Start s : l.starts) {
                        Line p = processes.get(s.process());
                        if (p == null) continue;
                        started.add(p);
                        if (!s.mode().equals(COPY) && !s.mode().equals(SHARE)) continue;
                        changed |= writes.get(p).addAll(writes.get(q));
                        if (opaque.contains(q)) changed |= opaque.add(p);
                        if (s.mode().equals(SHARE)) {
                            changed |= writes.get(q).addAll(writes.get(p));
                            if (opaque.contains(p)) changed |= opaque.add(q);
                        }
                    }
                }
            }
        }
        Set<String> warned = new HashSet<>();
        for (Line r : roots) {
            Set<String> told = new HashSet<>();
            if (opaque.contains(r)) continue;
            if (r.item instanceof Ast.Func && !started.contains(r)) continue;
            for (Line l : reach.get(r)) {
                for (Read read : l.reads.values()) {
                    String name = read.name();
                    if (writes.get(r).contains(name) || elsewhere.contains(name)) continue;
                    List<String> writers = new ArrayList<>();
                    for (Line w : roots) if (own.get(w).contains(name)) writers.add(w.title());
                    if (writers.isEmpty() || !told.add(name) || !warned.add(l.file + ":" + read.at().start())) continue;
                    warn(read, l, r, writers, units);
                }
            }
        }
    }

    private void shared(Map<String, Line> functions) {
        Set<String> told = new HashSet<>();
        for (Line caller : lines) {
            for (String name : caller.calls) {
                Line callee = functions.get(name);
                if (callee == null || callee == caller) continue;
                for (Map.Entry<String, Span> w : callee.writtenAt.entrySet()) {
                    String variable = w.getKey();
                    boolean used = caller.writes.contains(variable) || caller.reads.containsKey(variable);
                    if (!used || !told.add(name + ":" + variable)) continue;
                    c.diagnostics.add(new Diagnostic(false, "«" + variable + "» в " + callee.title() + " — та же локальная, что в "
                            + caller.title() + ": вызов её перепишет", w.getValue().start(), w.getValue().end(),
                            "локальные общие с вызывающим кодом; своя переменная — line " + Keywords.name(variable)).in(callee.file));
                }
            }
        }
    }

    private static List<Line> reach(Line root, Map<String, Line> functions) {
        List<Line> out = new ArrayList<>();
        Set<Line> seen = new HashSet<>();
        List<Line> queue = new ArrayList<>(List.of(root));
        while (!queue.isEmpty()) {
            Line l = queue.remove(0);
            if (!seen.add(l)) continue;
            out.add(l);
            for (String f : l.calls) {
                Line next = functions.get(f);
                if (next != null) queue.add(next);
            }
        }
        return out;
    }

    private void warn(Read read, Line at, Line root, List<String> writers, List<Compiler.Unit> units) {
        String printed = Keywords.name(read.name());
        String where = writers.size() > 3 ? String.join(", ", writers.subList(0, 3)) + " и ещё " + (writers.size() - 3)
                : String.join(", ", writers);
        String message = "«" + read.name() + "» — локальная: её задают только в " + where + ", а в " + root.title()
                + " она пустая" + (at == root ? "" : " (читают в " + at.title() + ")");
        String hint = "локальная живёт одно срабатывание события или процесса; общая для всех — игровая: game " + printed;
        List<Diagnostic.Fix> fixes = new ArrayList<>();
        Diagnostic.Fix fix = declaration(printed, at.file, units);
        if (fix != null) fixes.add(fix);
        c.diagnostics.add(new Diagnostic(false, message, read.at().start(), read.at().end(), hint, fixes).in(at.file));
    }

    private static Diagnostic.Fix declaration(String printed, String file, List<Compiler.Unit> units) {
        for (Compiler.Unit u : units) {
            if (u.file() == null ? file != null : !u.file().equals(file)) continue;
            String title = "Объявить игровой: game " + printed;
            Ast.Global last = null;
            for (Ast.Item item : u.ast().items()) if (item instanceof Ast.Global g) last = g;
            if (last != null) return new Diagnostic.Fix(title, last.span().end(), last.span().end(), "\ngame " + printed);
            for (Ast.Item item : u.ast().items()) {
                int start = switch (item) {
                    case Ast.Event e -> first(e.span(), e.decorators());
                    case Ast.Func f -> first(f.span(), f.decorators());
                    default -> -1;
                };
                if (start >= 0) return new Diagnostic.Fix(title, start, start, "game " + printed + "\n\n");
            }
        }
        return null;
    }

    private static int first(Span span, List<Ast.Decorator> decorators) {
        int at = span.start();
        for (Ast.Decorator d : decorators) at = Math.min(at, d.span().start());
        return at;
    }
}
