package xero.dialect.jmcc;

import xero.lang.Ast.Span;

import java.util.List;

final class Tree {
    record Unit(String file, List<Stmt> items, List<Named> declared, List<Func> functions, List<Enum> enums,
                List<Foreign> foreign, List<Alias> aliases) {}

    record Named(String name, String scope) {}

    record Mark(String name, List<Arg> args, Span span) {}

    record Param(String name, String type, List<String> options, boolean ref, int star, Expr fallback, Span span) {}

    record Target(Expr target, String type) {}

    record Block(List<Stmt> stmts, Span span) {}

    record Branch(Expr condition, Block body) {}

    record Arm(List<Expr> patterns, Expr guard, Block body, Stmt single, Span span) {}

    record Arg(String name, Span nameSpan, Expr value, int star) {}

    sealed interface Stmt permits Import, Event, Func, Enum, Alias, Foreign, Var, Assign, Run, If, While, For, Try,
            Throw, Return, Jump {
        Span span();
    }

    record Import(String path, boolean connect, Span span) implements Stmt {}

    record Event(String name, Span nameSpan, Block body, List<Mark> marks, Span span) implements Stmt {}

    record Func(boolean process, boolean inline, String name, boolean quoted, Span nameSpan, List<Param> params,
                String result, Block body, List<Mark> marks, Span span) implements Stmt {}

    record Enum(String name, Span nameSpan, List<String> members, List<Mark> marks, Span span) implements Stmt {}

    record Alias(String name, String target, Span span) implements Stmt {}

    record Foreign(String kind, String name, Span span) implements Stmt {}

    record Var(String scope, List<Target> targets, List<Expr> values, Span span) implements Stmt {}

    record Assign(List<Target> targets, String op, List<Expr> values, Span span) implements Stmt {}

    record Run(Expr expr, List<Name> lambda, Block body, Span span) implements Stmt {}

    record If(List<Branch> branches, Block otherwise, Span span) implements Stmt {}

    record While(String label, Expr condition, Block body, Span span) implements Stmt {}

    record For(String label, List<Name> names, Expr source, Block body, Span span) implements Stmt {}

    record Try(Block body, Name variable, Block handler, Span span) implements Stmt {}

    record Throw(String kind, Expr message, Span span) implements Stmt {}

    record Return(Expr value, Span span) implements Stmt {}

    record Jump(String kind, String label, Span span) implements Stmt {}

    sealed interface Expr permits Num, Str, Bool, Name, ListLit, MapLit, Nbt, Action, Value, Call, Method, Attr, Index,
            Unary, Binary, Ternary, Step, Range, Cast, Match, Lambda {
        Span span();
    }

    record Num(String text, Span span) implements Expr {}

    record Str(String prefix, String text, List<Object> parts, Span span) implements Expr {}

    record Bool(boolean value, Span span) implements Expr {}

    record Name(String name, String scope, boolean quoted, List<Object> parts, Span span) implements Expr {}

    record ListLit(List<Expr> items, Span span) implements Expr {}

    record MapLit(List<Expr> keys, List<Expr> values, Span span) implements Expr {}

    record Nbt(String text, Span span) implements Expr {}

    record Action(String object, Span objectSpan, String name, Span nameSpan, String selector, Span selectorSpan,
                  List<Arg> args, Span span) implements Expr {}

    record Value(String name, Span nameSpan, String selector, Span selectorSpan, Span span) implements Expr {}

    record Call(String name, boolean quoted, Span nameSpan, List<Arg> args, Span span) implements Expr {}

    record Method(Expr receiver, String name, Span nameSpan, List<Arg> args, Span span) implements Expr {}

    record Attr(Expr receiver, String name, Span nameSpan, Span span) implements Expr {}

    record Index(Expr target, Expr from, Expr to, Span span) implements Expr {}

    record Unary(String op, Expr operand, Span span) implements Expr {}

    record Binary(String op, Expr left, Expr right, Span span) implements Expr {}

    record Ternary(Expr condition, Expr then, Expr otherwise, Span span) implements Expr {}

    record Step(String op, boolean prefix, Expr target, Span span) implements Expr {}

    record Range(Expr from, Expr to, boolean inclusive, Span span) implements Expr {}

    record Cast(Expr value, String type, Span span) implements Expr {}

    record Match(Expr subject, List<Arm> arms, Span span) implements Expr {}

    record Lambda(Span span) implements Expr {}

    private Tree() {}
}
