package xero.compile;

import xero.data.Data;
import xero.data.Names;
import xero.lang.Ast;
import xero.lang.Ast.Arg;
import xero.lang.Ast.Expr;
import xero.lang.Ast.Span;
import xero.lang.Keywords;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class Methods {
    private final Names names;
    private final Predicate<String> function;
    private final java.util.Set<Ast.Call> made = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private record Method(Ast.Var receiver, Ast.Call call, Data.Action action) {}

    public Methods(Names names, Predicate<String> function) {
        this.names = names;
        this.function = function;
    }

    public java.util.Set<Ast.Call> made() {
        return made;
    }

    public static List<String> functions(Ast.File file) {
        List<String> out = new ArrayList<>();
        for (Ast.Item item : file.items()) if (item instanceof Ast.Func f) out.add(f.name());
        return out;
    }

    public Ast.File file(Ast.File f) {
        List<Ast.Item> items = new ArrayList<>(f.items().size());
        for (Ast.Item item : f.items()) items.add(item(item));
        return new Ast.File(items);
    }

    private Ast.Item item(Ast.Item item) {
        return switch (item) {
            case Ast.Event e -> new Ast.Event(e.name(), e.nameSpan(), block(e.body()), decorators(e.decorators()), e.span());
            case Ast.Func f -> new Ast.Func(f.process(), f.name(), f.quoted(), f.nameSpan(), params(f.params()), block(f.body()),
                    decorators(f.decorators()), f.span());
            case Ast.Const k -> new Ast.Const(k.name(), k.nameSpan(), expr(k.value()), k.span());
            case Ast.Global g -> g;
            case Ast.Import i -> i;
        };
    }

    private List<Ast.Decorator> decorators(List<Ast.Decorator> list) {
        List<Ast.Decorator> out = new ArrayList<>(list.size());
        for (Ast.Decorator d : list) out.add(new Ast.Decorator(d.name(), args(d.args()), d.span()));
        return out;
    }

    private List<Ast.Param> params(List<Ast.Param> list) {
        List<Ast.Param> out = new ArrayList<>(list.size());
        for (Ast.Param p : list)
            out.add(new Ast.Param(p.name(), p.type(), p.options(), p.fallback() == null ? null : expr(p.fallback()), p.span(),
                    p.about(), p.cells()));
        return out;
    }

    private Ast.Block block(Ast.Block b) {
        if (b == null) return null;
        List<Ast.Stmt> out = new ArrayList<>(b.stmts().size());
        for (Ast.Stmt s : b.stmts()) out.add(stmt(s));
        return new Ast.Block(out, b.span());
    }

    private Ast.Stmt stmt(Ast.Stmt s) {
        return switch (s) {
            case Ast.Run r -> run(r);
            case Ast.Assign a -> new Ast.Assign(a.scope(), a.name(), a.nameSpan(), a.op(), expr(a.value()), a.span(), a.pinned());
            case Ast.IndexAssign a -> new Ast.IndexAssign((Ast.Index) expr(a.target()), a.op(), expr(a.value()), a.span());
            case Ast.MultiAssign m -> new Ast.MultiAssign(m.scope(), m.names(), m.nameSpans(), exprs(m.values()), m.span());
            case Ast.Declare d -> d;
            case Ast.If i -> {
                List<Ast.Branch> branches = new ArrayList<>(i.branches().size());
                for (Ast.Branch b : i.branches()) branches.add(new Ast.Branch(expr(b.condition()), block(b.body())));
                yield new Ast.If(branches, block(i.otherwise()), i.span());
            }
            case Ast.Jump j -> new Ast.Jump(j.kind(), j.value() == null ? null : expr(j.value()), j.span());
            case Ast.Start st -> new Ast.Start(st.name(), st.quoted(), st.nameSpan(), args(st.args()), st.span());
            case Ast.Repeat r -> new Ast.Repeat(expr(r.count()), block(r.body()), r.span());
            case Ast.ForRange f -> new Ast.ForRange(f.name(), f.nameSpan(), expr(f.from()), expr(f.to()),
                    f.step() == null ? null : expr(f.step()), block(f.body()), f.span());
            case Ast.ForEach f -> new Ast.ForEach(f.first(), f.firstSpan(), f.second(), f.secondSpan(), f.map(), expr(f.source()),
                    block(f.body()), f.span());
            case Ast.While w -> new Ast.While(expr(w.condition()), block(w.body()), w.span());
            case Ast.Forever f -> new Ast.Forever(block(f.body()), f.span());
        };
    }

    private Ast.Stmt run(Ast.Run r) {
        if (r.body() == null && r.call() instanceof Ast.Call call) {
            Method m = method(call);
            if (m != null && m.action().result() != null && !Wire.IN_PLACE.contains(m.action().canonical()))
                return new Ast.Assign(m.receiver().scope(), m.receiver().name(), m.receiver().span(), "=", m.call(), r.span());
        }
        return new Ast.Run(expr(r.call()), block(r.body()), r.span());
    }

    private List<Arg> args(List<Arg> list) {
        List<Arg> out = new ArrayList<>(list.size());
        for (Arg a : list) out.add(new Arg(a.name(), a.nameSpan(), expr(a.value())));
        return out;
    }

    private List<Expr> exprs(List<Expr> list) {
        List<Expr> out = new ArrayList<>(list.size());
        for (Expr e : list) out.add(expr(e));
        return out;
    }

    private Expr expr(Expr e) {
        return switch (e) {
            case Ast.Num n -> n;
            case Ast.Var v -> v;
            case Ast.Blank b -> b;
            case Ast.Path p -> p;
            case Ast.Str s -> {
                if (!s.interpolated()) yield s;
                List<Object> parts = new ArrayList<>(s.parts().size());
                for (Object part : s.parts()) parts.add(part instanceof Expr inner ? expr(inner) : part);
                yield new Ast.Str(s.prefix(), s.text(), parts, s.span());
            }
            case Ast.ListLit l -> new Ast.ListLit(exprs(l.items()), l.span());
            case Ast.MapLit m -> new Ast.MapLit(exprs(m.keys()), exprs(m.values()), m.span());
            case Ast.Call c -> {
                Method m = method(c);
                yield m != null ? m.call() : new Ast.Call(c.qualifier(), c.name(), c.quoted(), c.nameSpan(), args(c.args()), c.span());
            }
            case Ast.Targeted t -> new Ast.Targeted(t.target(), t.targetSpan(), expr(t.inner()), t.span());
            case Ast.Unary u -> new Ast.Unary(u.op(), expr(u.operand()), u.span());
            case Ast.Binary b -> new Ast.Binary(b.op(), expr(b.left()), expr(b.right()), b.span());
            case Ast.Index i -> new Ast.Index(expr(i.target()), expr(i.index()), i.span());
            case Ast.Ternary t -> new Ast.Ternary(expr(t.condition()), expr(t.then()), expr(t.otherwise()), t.span());
        };
    }

    private Method method(Ast.Call c) {
        if (c.qualifier() != null || c.quoted()) return null;
        int dot = c.name().lastIndexOf('.');
        if (dot <= 0 || dot == c.name().length() - 1 || function.test(c.name())) return null;
        String head = c.name().substring(0, dot), name = c.name().substring(dot + 1);
        Names.Entry e = names.resolve(Names.Space.ACTION, name);
        if (e == null) e = names.resolve(Names.Space.CONDITION, name);
        if (e == null || e.action() == null) return null;
        Span at = c.nameSpan();
        Ast.Var receiver = receiver(head, new Span(at.start(), at.start() + head.length()));
        if (receiver == null) return null;
        List<Arg> args = new ArrayList<>(c.args().size() + 1);
        args.add(new Arg(null, null, receiver));
        args.addAll(args(c.args()));
        Ast.Call call = new Ast.Call(null, name, false, new Span(at.end() - name.length(), at.end()), args, c.span());
        made.add(call);
        return new Method(receiver, call, e.action());
    }

    private static Ast.Var receiver(String head, Span at) {
        String[] parts = head.split("\\.");
        if (parts.length == 1) return Keywords.SCOPES.contains(parts[0]) ? null : new Ast.Var(parts[0], false, at);
        if (parts.length == 2 && Keywords.SCOPES.contains(parts[0])) return new Ast.Var(parts[1], true, at, parts[0]);
        return null;
    }
}
