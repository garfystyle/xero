package xero.lang;

import java.util.List;

public final class Ast {
    public record Span(int start, int end) {
        public Span to(Span other) { return new Span(start, other.end); }
    }

    public record File(List<Item> items) {}

    public sealed interface Item permits Event, Func, Global, Const, Import {
        Span span();
    }

    public record Event(String name, Span nameSpan, Block body, List<Decorator> decorators, Span span) implements Item {}

    public record Func(boolean process, String name, boolean quoted, Span nameSpan, List<Param> params,
                       Block body, List<Decorator> decorators, Span span) implements Item {}

    public record Global(String scope, String name, Span span) implements Item {}

    public record Const(String name, Span nameSpan, Expr value, Span span) implements Item {}

    public record Import(String path, boolean connect, Span span) implements Item {}

    public record Decorator(String name, List<Arg> args, Span span) {}

    public record Param(String name, String type, List<String> options, Expr fallback, Span span) {}

    public record Block(List<Stmt> stmts, Span span) {}

    public sealed interface Stmt permits Run, Assign, MultiAssign, Declare, If, Jump, Start, Repeat, ForRange, ForEach,
            While, Forever, IndexAssign {
        Span span();
    }

    public record Run(Expr call, Block body, Span span) implements Stmt {}

    public record Assign(String scope, String name, Span nameSpan, String op, Expr value, Span span, boolean pinned)
            implements Stmt {
        public Assign(String scope, String name, Span nameSpan, String op, Expr value, Span span) {
            this(scope, name, nameSpan, op, value, span, false);
        }
    }

    public record Declare(String scope, String name, Span span) implements Stmt {}

    public record MultiAssign(String scope, List<String> names, List<Span> nameSpans, List<Expr> values, Span span)
            implements Stmt {}

    public record Branch(Expr condition, Block body) {}

    public record If(List<Branch> branches, Block otherwise, Span span) implements Stmt {}

    public record Jump(String kind, Expr value, Span span) implements Stmt {}

    public record IndexAssign(Index target, String op, Expr value, Span span) implements Stmt {}

    public record Start(String name, boolean quoted, Span nameSpan, List<Arg> args, Span span) implements Stmt {}

    public record Repeat(Expr count, Block body, Span span) implements Stmt {}

    public record ForRange(String name, Span nameSpan, Expr from, Expr to, Expr step, Block body, Span span)
            implements Stmt {}

    public record ForEach(String first, Span firstSpan, String second, Span secondSpan, boolean map, Expr source,
                          Block body, Span span) implements Stmt {}

    public record While(Expr condition, Block body, Span span) implements Stmt {}

    public record Forever(Block body, Span span) implements Stmt {}

    public record Arg(String name, Span nameSpan, Expr value) {}

    public sealed interface Expr permits Num, Str, Var, Blank, ListLit, MapLit, Call, Path, Targeted,
            Unary, Binary, Index, Ternary {
        Span span();
    }

    public record Num(String text, Span span, Double millis) implements Expr {
        public Num(String text, Span span) { this(text, span, null); }
    }

    public record Str(String prefix, String text, List<Object> parts, Span span) implements Expr {
        public Str(String prefix, String text, Span span) { this(prefix, text, null, span); }
        public boolean interpolated() { return parts != null; }
    }

    public record Var(String name, boolean quoted, Span span, String scope) implements Expr {
        public Var(String name, boolean quoted, Span span) { this(name, quoted, span, null); }
    }

    public record Blank(Span span) implements Expr {}

    public record ListLit(List<Expr> items, Span span) implements Expr {}

    public record MapLit(List<Expr> keys, List<Expr> values, Span span) implements Expr {}

    public record Call(String qualifier, String name, boolean quoted, Span nameSpan, List<Arg> args,
                       Span span) implements Expr {
        public String shown() { return qualifier == null ? name : qualifier + "." + name; }
    }

    public record Path(List<String> parts, Span span) implements Expr {
        public String joined() { return String.join(".", parts); }
    }

    public record Targeted(String target, Span targetSpan, Expr inner, Span span) implements Expr {}

    public record Unary(String op, Expr operand, Span span) implements Expr {}

    public record Binary(String op, Expr left, Expr right, Span span) implements Expr {}

    public record Index(Expr target, Expr index, Span span) implements Expr {}

    public record Ternary(Expr condition, Expr then, Expr otherwise, Span span) implements Expr {}

    private Ast() {}
}
