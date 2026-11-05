package xero.compile;

import xero.data.Names;
import xero.lang.Ast;
import xero.lang.Ast.Arg;
import xero.lang.Ast.Expr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Order {
    private final Context c;
    private final Map<String, Set<String>> writes = new HashMap<>();
    private final Map<String, Set<String>> calls = new HashMap<>();

    Order(Context c) {
        this.c = c;
    }

    void collect(List<Compiler.Unit> units) {
        for (Compiler.Unit u : units) {
            for (Ast.Item item : u.ast().items()) {
                if (!(item instanceof Ast.Func f) || f.process()) continue;
                Set<String> written = new HashSet<>();
                Set<String> called = new HashSet<>();
                block(f.body(), written, called);
                written.removeAll(c.declarations.lineScoped(f).keySet());
                for (Ast.Param p : f.params()) written.remove(p.name());
                writes.put(f.name(), written);
                calls.put(f.name(), called);
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, Set<String>> e : calls.entrySet())
                for (String callee : e.getValue()) {
                    Set<String> more = writes.get(callee);
                    if (more != null && writes.get(e.getKey()).addAll(more)) grew = true;
                }
        }
    }

    private void block(Ast.Block block, Set<String> written, Set<String> called) {
        if (block == null) return;
        for (Ast.Stmt s : block.stmts()) statement(s, written, called);
    }

    private void statement(Ast.Stmt s, Set<String> written, Set<String> called) {
        switch (s) {
            case Ast.Run r -> {
                expression(r.call(), called);
                receiver(r.call(), written);
                block(r.body(), written, called);
            }
            case Ast.Assign a -> {
                written.add(a.name());
                expression(a.value(), called);
            }
            case Ast.MultiAssign m -> {
                written.addAll(m.names());
                m.values().forEach(v -> expression(v, called));
            }
            case Ast.IndexAssign a -> {
                Expr root = a.target();
                while (root instanceof Ast.Index i) {
                    expression(i.index(), called);
                    root = i.target();
                }
                if (root instanceof Ast.Var v) written.add(v.name());
                expression(a.value(), called);
            }
            case Ast.If i -> {
                for (Ast.Branch b : i.branches()) {
                    expression(b.condition(), called);
                    block(b.body(), written, called);
                }
                block(i.otherwise(), written, called);
            }
            case Ast.Jump j -> expression(j.value(), called);
            case Ast.Start st -> st.args().forEach(a -> expression(a.value(), called));
            case Ast.Repeat r -> {
                expression(r.count(), called);
                block(r.body(), written, called);
            }
            case Ast.ForRange f -> {
                written.add(f.name());
                expression(f.from(), called);
                expression(f.to(), called);
                expression(f.step(), called);
                block(f.body(), written, called);
            }
            case Ast.ForEach f -> {
                if (f.first() != null) written.add(f.first());
                written.add(f.second());
                expression(f.source(), called);
                block(f.body(), written, called);
            }
            case Ast.While w -> {
                expression(w.condition(), called);
                block(w.body(), written, called);
            }
            case Ast.Forever f -> block(f.body(), written, called);
            case Ast.Declare d -> { }
        }
    }

    private void receiver(Expr call, Set<String> written) {
        Expr inner = call instanceof Ast.Targeted t ? t.inner() : call;
        if (!(inner instanceof Ast.Call cl) || c.userCall(cl) || cl.args().isEmpty()) return;
        Names.Entry e = c.names.resolve(Names.Space.ACTION, cl.shown());
        if (e == null || e.action().result() == null) return;
        if (cl.args().get(0).value() instanceof Ast.Var v) written.add(v.name());
    }

    private void expression(Expr e, Set<String> called) {
        switch (e) {
            case null -> { }
            case Ast.Call call -> {
                if (c.userCall(call)) called.add(call.shown());
                call.args().forEach(a -> expression(a.value(), called));
            }
            case Ast.Str s -> {
                if (s.parts() != null) for (Object p : s.parts()) if (p instanceof Expr x) expression(x, called);
            }
            case Ast.ListLit l -> l.items().forEach(x -> expression(x, called));
            case Ast.MapLit m -> {
                m.keys().forEach(x -> expression(x, called));
                m.values().forEach(x -> expression(x, called));
            }
            case Ast.Targeted t -> expression(t.inner(), called);
            case Ast.Unary u -> expression(u.operand(), called);
            case Ast.Binary b -> {
                expression(b.left(), called);
                expression(b.right(), called);
            }
            case Ast.Index i -> {
                expression(i.target(), called);
                expression(i.index(), called);
            }
            case Ast.Ternary t -> {
                expression(t.condition(), called);
                expression(t.then(), called);
                expression(t.otherwise(), called);
            }
            default -> { }
        }
    }

    void plan(Ast.Stmt s) {
        List<Expr> roots = switch (s) {
            case Ast.Run r -> List.of(r.call());
            case Ast.Assign a -> List.of(a.value());
            case Ast.MultiAssign m -> m.values();
            case Ast.IndexAssign a -> List.of(a.target().index(), a.value());
            case Ast.Jump j when j.value() != null -> List.of(j.value());
            case Ast.If i -> {
                List<Expr> conditions = new ArrayList<>();
                for (Ast.Branch b : i.branches()) conditions.add(b.condition());
                yield conditions;
            }
            case Ast.Start st -> {
                List<Expr> args = new ArrayList<>();
                for (Arg a : st.args()) args.add(a.value());
                yield args;
            }
            default -> List.of();
        };
        List<Ast.Var> reads = new ArrayList<>();
        for (Expr root : roots) visit(root, reads);
    }

    private void visit(Expr e, List<Ast.Var> reads) {
        switch (e) {
            case Ast.Var v -> {
                String scope = c.scope(v);
                if (c.constant(v) == null && scope != null && !scope.equals("line")) reads.add(v);
            }
            case Ast.Call call -> {
                int earlier = reads.size();
                for (Arg a : call.args()) visit(a.value(), reads);
                if (!c.userCall(call)) return;
                reads.subList(earlier, reads.size()).clear();
                Set<String> written = writes.get(call.shown());
                if (written == null) return;
                for (Ast.Var v : reads) if (written.contains(v.name())) c.compiler.snapshot(v);
            }
            case Ast.Str s -> {
                if (s.parts() != null) for (Object p : s.parts()) if (p instanceof Expr x) visit(x, reads);
            }
            case Ast.ListLit l -> l.items().forEach(x -> visit(x, reads));
            case Ast.MapLit m -> {
                for (int i = 0; i < m.keys().size(); i++) {
                    visit(m.keys().get(i), reads);
                    visit(m.values().get(i), reads);
                }
            }
            case Ast.Targeted t -> visit(t.inner(), reads);
            case Ast.Unary u -> visit(u.operand(), reads);
            case Ast.Binary b -> {
                visit(b.left(), reads);
                visit(b.right(), reads);
            }
            case Ast.Index i -> visit(i.index(), reads);
            case Ast.Ternary t -> {
                visit(t.condition(), reads);
                visit(t.then(), reads);
                visit(t.otherwise(), reads);
            }
            default -> { }
        }
    }
}
