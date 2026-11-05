package xero.lang;

import xero.lang.Ast.Arg;
import xero.lang.Ast.Block;
import xero.lang.Ast.Branch;
import xero.lang.Ast.Expr;
import xero.lang.Ast.Item;
import xero.lang.Ast.Param;
import xero.lang.Ast.Span;
import xero.lang.Ast.Stmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Parser {
    private static final Set<String> COMPARE = Set.of("==", "!=", ">", ">=", "<", "<=");
    private static final Set<String> ASSIGN = Set.of("=", "+=", "-=", "*=", "/=", "%=");
    private static final Map<String, String> SHAPES = Map.ofEntries(
            Map.entry("map", "map"), Map.entry("словарь", "map"),
            Map.entry("list", "list"), Map.entry("array", "list"), Map.entry("список", "list"),
            Map.entry("location", "location"), Map.entry("loc", "location"), Map.entry("место", "location"),
            Map.entry("местоположение", "location"), Map.entry("vector", "vector"), Map.entry("vec", "vector"),
            Map.entry("вектор", "vector"), Map.entry("number", "number"), Map.entry("число", "number"),
            Map.entry("text", "text"), Map.entry("текст", "text"), Map.entry("item", "item"), Map.entry("предмет", "item"),
            Map.entry("block", "block"), Map.entry("блок", "block"), Map.entry("sound", "sound"), Map.entry("звук", "sound"),
            Map.entry("particle", "particle"), Map.entry("частица", "particle"), Map.entry("potion", "potion"),
            Map.entry("зелье", "potion"));

    private static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Failure() { super(null, null, false, false); }
    }

    private final String source;
    private final List<Token> tokens;
    private final List<Diagnostic> diagnostics;
    private int pos;
    private int nest;

    private Parser(String source, List<Token> tokens, List<Diagnostic> diagnostics) {
        this.source = source;
        this.tokens = tokens;
        this.diagnostics = diagnostics;
    }

    public static Ast.File parse(String text, List<Diagnostic> diagnostics) {
        Parser parser = new Parser(text, Lexer.tokens(text, diagnostics), diagnostics);
        return parser.file();
    }

    private Ast.File file() {
        List<Item> items = new ArrayList<>();
        while (!peek().kind().equals(Token.Kind.EOF)) {
            try {
                items.add(item());
            } catch (Failure f) {
                skipToItem();
            }
        }
        return new Ast.File(items);
    }

    private Item item() {
        Token t = peek();
        List<Ast.Decorator> decorators = new ArrayList<>();
        while (peek().is("@")) {
            Token at = next();
            Token name = expectIdent("после @ нужно имя пометки, например @hidden");
            List<Arg> args = peek().is("(") && !peek().newline() ? args() : List.of();
            decorators.add(new Ast.Decorator(name.text(), args, span(at).to(previousSpan())));
        }
        t = peek();
        if (!decorators.isEmpty() && !t.keyword("func", "process", "when"))
            throw fail("пометки @… ставят только перед when, func и process", t);
        if (t.keyword("const") && (peek(1).kind() == Token.Kind.IDENT || peek(1).kind() == Token.Kind.NAME)) {
            next();
            Token name = next();
            expect("=", "постоянной нужно значение: const " + name.text() + " = 5");
            Expr value = expr();
            return new Ast.Const(name.text(), span(name), value, span(t).to(value.span()));
        }
        if (t.keyword("import")) {
            next();
            Token path = next();
            if (path.kind() != Token.Kind.STRING || path.parts() != null)
                throw fail("после import нужен путь в кавычках: import \"kits.xero\"", path);
            boolean connect = !peek().newline() && peek().word("connect", "подключить");
            if (connect) next();
            return new Ast.Import(path.text(), connect, span(t).to(previousSpan()));
        }
        if (t.kind() == Token.Kind.IDENT && Keywords.SCOPES.contains(t.text())
                && (peek(1).kind() == Token.Kind.IDENT || peek(1).kind() == Token.Kind.NAME)) {
            next();
            Token name = next();
            String shape = shape();
            return new Ast.Global(t.text(), name.text(), shape, span(t).to(previousSpan()));
        }
        if (t.keyword("when")) {
            next();
            Token name = expectIdent("после when нужно имя события, например when player_join");
            Block body = block();
            return new Ast.Event(name.text(), span(name), body, decorators, span(t).to(body.span()));
        }
        if (t.keyword("func", "process")) {
            next();
            boolean process = t.keyword("process");
            Token name = next();
            if (name.kind() != Token.Kind.IDENT && name.kind() != Token.Kind.NAME)
                throw fail("нужно имя " + (process ? "процесса" : "функции"), name);
            Dotted full = dotted(name);
            List<Param> params = new ArrayList<>();
            expect("(", "после имени нужны скобки: " + full.text() + "()");
            nest++;
            while (!peek().is(")")) {
                params.add(param());
                if (!peek().is(",")) break;
                next();
            }
            nest--;
            expect(")", "ожидалась «)»");
            Block body = block();
            return new Ast.Func(process, full.text(), name.kind() == Token.Kind.NAME, full.span(), params, body,
                    decorators, span(t).to(body.span()));
        }
        throw fail("ожидалось when, func или process — код вне них не выполняется", t);
    }

    private record Dotted(String text, Span span) {}

    private Dotted dotted(Token first) {
        String text = first.text();
        Span at = span(first);
        while (first.kind() == Token.Kind.IDENT && peek().is(".") && peek(1).kind() == Token.Kind.IDENT) {
            next();
            Token part = next();
            text += "." + part.text();
            at = at.to(span(part));
        }
        return new Dotted(text, at);
    }

    private Param param() {
        Token name = peek().kind() == Token.Kind.NAME ? next() : expectIdent("ожидалось имя параметра");
        String type = null;
        List<String> options = null;
        int cells = 0;
        if (peek().is(":")) {
            next();
            type = expectIdent("после «:» нужен вид значения, например number").text();
            if (peek().is("(") && !peek().newline()) {
                options = new ArrayList<>();
                for (Arg a : args()) {
                    if (a.name() != null || !(a.value() instanceof Ast.Str s) || s.interpolated())
                        throw fail("варианты пишутся текстом: enum(\"первый\", \"второй\")", peek());
                    options.add(s.text());
                }
            }
            if (peek().is("[") && !peek().newline()) cells = cells();
        }
        Ast.Str about = peek().kind() == Token.Kind.STRING ? about() : null;
        Expr fallback = null;
        if (peek().is("=")) {
            next();
            fallback = expr();
        }
        return new Param(name.text(), type, options, fallback, span(name), about, cells);
    }

    private int cells() {
        next();
        Token size = next();
        if (size.kind() != Token.Kind.NUMBER || !size.text().matches("[1-9][0-9]{0,1}"))
            throw fail("в скобках — сколько значений принимает параметр: text[12]", size);
        expect("]", "ожидалась «]»");
        return Integer.parseInt(size.text());
    }

    private Ast.Str about() {
        Token at = peek();
        if (atom() instanceof Ast.Str s && !s.interpolated()) return s;
        throw fail("описание параметра — обычная строка, без ${…}", at);
    }

    private Block block() {
        Token open = expect("{", "ожидалась «{»");
        List<Stmt> stmts = new ArrayList<>();
        int saved = nest;
        nest = 0;
        while (!peek().is("}") && peek().kind() != Token.Kind.EOF) {
            try {
                stmts.add(statement());
                endOfStatement();
            } catch (Failure f) {
                skipToStatement();
            }
        }
        nest = saved;
        Token close = expect("}", "блок не закрыт: ожидалась «}»");
        return new Block(stmts, span(open).to(span(close)));
    }

    private Stmt statement() {
        Token t = peek();
        if (t.keyword("const") && peek(1).kind() == Token.Kind.IDENT && peek(2).is("="))
            throw fail("const пишут вне when, func и process — в начале файла", t);
        if (assignsWord()) return assignment(t);
        if (t.keyword("if")) return ifStatement();
        if (t.keyword("repeat") && !peek(1).is(".")) {
            next();
            Expr count = expr();
            Block body = block();
            return new Ast.Repeat(count, body, span(t).to(body.span()));
        }
        if (t.keyword("for")) return forStatement();
        if (t.keyword("while") && !peek(1).is(".")) {
            next();
            Expr condition = expr();
            Block body = block();
            return new Ast.While(condition, body, span(t).to(body.span()));
        }
        if (t.keyword("forever") && !peek(1).is(".")) {
            next();
            Block body = block();
            return new Ast.Forever(body, span(t).to(body.span()));
        }
        if (t.keyword("return", "break", "continue")) {
            next();
            String kind = Keywords.meaning(t.text());
            Expr value = null;
            if (kind.equals("return") && !peek().newline() && !peek().is("}") && !peek().is(";")
                    && peek().kind() != Token.Kind.EOF) value = expr();
            return new Ast.Jump(kind, value, value == null ? span(t) : span(t).to(value.span()));
        }
        if (t.keyword("start")) {
            next();
            Token name = next();
            if (name.kind() != Token.Kind.IDENT && name.kind() != Token.Kind.NAME)
                throw fail("после start нужно имя процесса", name);
            Dotted full = dotted(name);
            List<Arg> args = args();
            return new Ast.Start(full.text(), name.kind() == Token.Kind.NAME, full.span(), args,
                    span(t).to(previousSpan()));
        }
        return assignment(t);
    }

    private boolean assignsWord() {
        Token t = peek();
        if (t.kind() != Token.Kind.IDENT || !Keywords.reserved(t.text())) return false;
        return peek(1).kind() == Token.Kind.SYMBOL && ASSIGN.contains(peek(1).text()) || multiAssignAt() > 0;
    }

    private void word(Token name) {
        if (name.kind() != Token.Kind.IDENT || !Keywords.reserved(name.text()) || name.text().equals("_")) return;
        String quoted = "`" + name.text() + "`";
        diagnostics.add(new Diagnostic(false, "«" + name.text() + "» — слово языка: имя переменной лучше брать другое", name.start(),
                name.end(), "или в обратных кавычках: " + quoted, List.of(Diagnostic.Fix.replace(name.start(), name.end(), quoted))));
    }

    private Stmt assignment(Token t) {
        int multi = multiAssignAt();
        if (multi > 0) {
            String scope = null;
            Token first = t;
            if (multi == 2) {
                scope = next().text();
                first = peek();
            }
            List<String> names = new ArrayList<>();
            List<Span> spans = new ArrayList<>();
            while (true) {
                Token name = next();
                word(name);
                names.add(name.text());
                spans.add(span(name));
                if (!peek().is(",")) break;
                next();
            }
            next();
            List<Expr> values = new ArrayList<>();
            values.add(expr());
            while (peek().is(",") && !peek().newline()) {
                next();
                values.add(expr());
            }
            return new Ast.MultiAssign(scope, names, spans, values, span(first).to(values.get(values.size() - 1).span()));
        }
        if (scoped(t) && peek(3).kind() == Token.Kind.SYMBOL && ASSIGN.contains(peek(3).text())) {
            next();
            next();
            Token name = next();
            Token op = next();
            Expr value = expr();
            return new Ast.Assign(t.text(), name.text(), span(name), op.text(), value, span(t).to(value.span()), true);
        }
        if (t.kind() == Token.Kind.IDENT && Keywords.SCOPES.contains(t.text())
                && (peek(1).kind() == Token.Kind.IDENT || peek(1).kind() == Token.Kind.NAME)
                && !peek(1).newline()) {
            next();
            Token name = next();
            if (ASSIGN.contains(peek().text()) && peek().kind() == Token.Kind.SYMBOL) {
                Token op = next();
                Expr value = expr();
                return new Ast.Assign(t.text(), name.text(), span(name), op.text(), value,
                        span(t).to(value.span()));
            }
            String shape = shape();
            return new Ast.Declare(t.text(), name.text(), shape, span(t).to(previousSpan()));
        }
        if ((t.kind() == Token.Kind.IDENT || t.kind() == Token.Kind.NAME)
                && peek(1).kind() == Token.Kind.SYMBOL && ASSIGN.contains(peek(1).text())) {
            word(t);
            next();
            Token op = next();
            Expr value = expr();
            return new Ast.Assign(null, t.text(), span(t), op.text(), value, span(t).to(value.span()));
        }
        Expr call = expr();
        if (call instanceof Ast.Index index && peek().kind() == Token.Kind.SYMBOL && ASSIGN.contains(peek().text())) {
            Token op = next();
            Expr value = expr();
            return new Ast.IndexAssign(index, op.text(), value, call.span().to(value.span()));
        }
        Block body = null;
        if (peek().is("{")) body = block();
        return new Ast.Run(call, body, body == null ? call.span() : call.span().to(body.span()));
    }

    private Stmt forStatement() {
        Token start = next();
        Token first = loopName();
        Token second = null;
        boolean map = false;
        if (peek().is(",")) {
            next();
            second = loopName();
        } else if (peek().is(":")) {
            next();
            second = loopName();
            map = true;
        }
        if (!peek().keyword("in")) throw fail("ожидалось in: for x in список {}", peek());
        next();
        Expr source = expr();
        if (peek().is("..")) {
            if (second != null) throw fail("у диапазона одна переменная: for i in 1..10 {}", peek());
            next();
            Expr to = expr();
            Expr step = null;
            if (peek().keyword("step")) {
                next();
                step = expr();
            }
            Block body = block();
            return new Ast.ForRange(first.text(), span(first), source, to, step, body, span(start).to(body.span()));
        }
        Block body = block();
        if (second == null)
            return new Ast.ForEach(null, null, first.text(), span(first), false, source, body, span(start).to(body.span()));
        return new Ast.ForEach(first.text(), span(first), second.text(), span(second), map, source, body,
                span(start).to(body.span()));
    }

    private Token loopName() {
        Token t = next();
        if (t.kind() != Token.Kind.IDENT && t.kind() != Token.Kind.NAME)
            throw fail("нужно имя переменной цикла", t);
        word(t);
        return t;
    }

    private int multiAssignAt() {
        int i = pos;
        int kind = 1;
        Token t = tokens.get(i);
        if (t.kind() == Token.Kind.IDENT && Keywords.SCOPES.contains(t.text())) {
            i++;
            kind = 2;
        }
        int names = 0;
        while (i < tokens.size()) {
            Token n = tokens.get(i);
            if (n.kind() != Token.Kind.IDENT && n.kind() != Token.Kind.NAME) return 0;
            names++;
            i++;
            if (i < tokens.size() && tokens.get(i).is(",")) {
                i++;
                continue;
            }
            break;
        }
        return names > 1 && i < tokens.size() && tokens.get(i).is("=") ? kind : 0;
    }

    private Stmt ifStatement() {
        Token start = next();
        List<Branch> branches = new ArrayList<>();
        Expr condition = expr();
        Block body = block();
        branches.add(new Branch(condition, body));
        Block otherwise = null;
        Span end = body.span();
        while (peek().keyword("else")) {
            next();
            if (peek().keyword("if")) {
                next();
                Expr c = expr();
                Block b = block();
                branches.add(new Branch(c, b));
                end = b.span();
                continue;
            }
            otherwise = block();
            end = otherwise.span();
            break;
        }
        return new Ast.If(branches, otherwise, span(start).to(end));
    }

    private void endOfStatement() {
        Token t = peek();
        if (t.is(";")) { next(); return; }
        if (t.is("}") || t.newline() || t.kind() == Token.Kind.EOF) return;
        throw fail("после команды нужен перевод строки", t);
    }

    private String shape() {
        if (!peek().is(":") || peek().newline()) return null;
        next();
        Token word = next();
        String shape = SHAPES.get(word.text());
        if (shape == null)
            throw fail("после : идёт вид: list, map, location, vector, number, text, item, block, sound, particle, potion", word);
        return shape;
    }

    private List<Arg> args() {
        expect("(", "ожидалась «(»");
        nest++;
        List<Arg> args = new ArrayList<>();
        while (!peek().is(")")) {
            if (peek().kind() == Token.Kind.EOF) break;
            if ((peek().kind() == Token.Kind.IDENT || peek().kind() == Token.Kind.NAME) && peek(1).is(":")) {
                Token name = next();
                next();
                args.add(new Arg(name.text(), span(name), expr()));
            } else {
                args.add(new Arg(null, null, expr()));
            }
            if (!peek().is(",")) break;
            next();
        }
        nest--;
        expect(")", "ожидалась «)» или «,»");
        return args;
    }

    private Expr expr() {
        Expr condition = or();
        if (!continues() || !peek().is("?")) return condition;
        next();
        Expr then = expr();
        expect(":", "в «условие ? да : нет» не хватает «:»");
        Expr otherwise = expr();
        return new Ast.Ternary(condition, then, otherwise, condition.span().to(otherwise.span()));
    }

    private Expr or() {
        Expr left = and();
        while (continues() && peek().keyword("or")) {
            next();
            Expr right = and();
            left = new Ast.Binary("or", left, right, left.span().to(right.span()));
        }
        return left;
    }

    private Expr and() {
        Expr left = not();
        while (continues() && peek().keyword("and")) {
            next();
            Expr right = not();
            left = new Ast.Binary("and", left, right, left.span().to(right.span()));
        }
        return left;
    }

    private Expr not() {
        if (peek().keyword("not")) {
            Token t = next();
            Expr operand = not();
            return new Ast.Unary("not", operand, span(t).to(operand.span()));
        }
        return compare();
    }

    private Expr membership(Expr left) {
        Expr right = additive();
        if (continues() && peek().is("..")) {
            next();
            Expr to = additive();
            right = new Ast.Binary("..", right, to, right.span().to(to.span()));
        }
        return new Ast.Binary("in", left, right, left.span().to(right.span()));
    }

    private Expr compare() {
        Expr left = additive();
        if (continues() && peek().keyword("in")) {
            next();
            return membership(left);
        }
        if (continues() && peek().keyword("not") && peek(1).keyword("in")) {
            next();
            next();
            Expr in = membership(left);
            return new Ast.Unary("not", in, in.span());
        }
        if (continues() && peek().kind() == Token.Kind.SYMBOL && COMPARE.contains(peek().text())) {
            Token op = next();
            Expr right = additive();
            return new Ast.Binary(op.text(), left, right, left.span().to(right.span()));
        }
        return left;
    }

    private Expr additive() {
        Expr left = multiplicative();
        while (continues() && (peek().is("+") || peek().is("-"))) {
            Token op = next();
            Expr right = multiplicative();
            left = new Ast.Binary(op.text(), left, right, left.span().to(right.span()));
        }
        return left;
    }

    private Expr multiplicative() {
        Expr left = unary();
        while (continues() && (peek().is("*") || peek().is("/") || peek().is("%"))) {
            Token op = next();
            Expr right = unary();
            left = new Ast.Binary(op.text(), left, right, left.span().to(right.span()));
        }
        return left;
    }

    private Expr unary() {
        if (peek().is("-")) {
            Token t = next();
            Expr operand = unary();
            return new Ast.Unary("-", operand, span(t).to(operand.span()));
        }
        return primary();
    }

    private Expr primary() {
        Expr e = atom();
        while (peek().is("[") && !peek().newline() && indexable(e)) {
            next();
            nest++;
            Expr index = expr();
            nest--;
            Token close = expect("]", "ожидалась «]»");
            e = new Ast.Index(e, index, e.span().to(span(close)));
        }
        return e;
    }

    private static boolean indexable(Expr e) {
        return e instanceof Ast.Var || e instanceof Ast.Call || e instanceof Ast.Index || e instanceof Ast.Path;
    }

    private Expr atom() {
        Token t = next();
        switch (t.kind()) {
            case NUMBER -> {
                if (!Lexer.TIME.equals(t.prefix())) return new Ast.Num(t.text(), span(t));
                double millis = Lexer.millis(t.text());
                return new Ast.Num(Double.toString(millis / 50), span(t), millis);
            }
            case STRING -> {
                if (t.parts() == null) return new Ast.Str(t.prefix(), t.text(), span(t));
                List<Object> parts = new ArrayList<>();
                for (Object part : t.parts()) {
                    if (part instanceof Token.Code code) {
                        Parser inner = new Parser(source, Lexer.tokens(source, code.start(), code.end(), diagnostics),
                                diagnostics);
                        try {
                            Expr e = inner.expr();
                            if (inner.peek().kind() != Token.Kind.EOF)
                                inner.fail("в ${…} одно выражение", inner.peek());
                            parts.add(e);
                        } catch (Failure f) {
                            parts.add(new Ast.Blank(new Span(code.start(), code.end())));
                        }
                    } else {
                        parts.add(part);
                    }
                }
                return new Ast.Str(t.prefix(), t.text(), parts, span(t));
            }
            case NAME -> {
                if (peek().is("(") && !peek().newline()) {
                    List<Arg> args = args();
                    return new Ast.Call(null, t.text(), true, span(t), args, span(t).to(previousSpan()));
                }
                return new Ast.Var(t.text(), true, span(t));
            }
            case IDENT -> { return identifier(t); }
            case SYMBOL -> {
                if (t.is("@")) {
                    Token target = expectIdent("после @ нужна цель, например @all");
                    Expr inner = primary();
                    return new Ast.Targeted(target.text(), span(target), inner, span(t).to(inner.span()));
                }
                if (t.is("(")) {
                    nest++;
                    Expr inner = expr();
                    nest--;
                    expect(")", "ожидалась «)»");
                    return inner;
                }
                if (t.is("[")) return list(t);
                if (t.is("{")) return map(t);
            }
            default -> { }
        }
        throw fail(t.kind() == Token.Kind.EOF ? "файл оборвался" : "ожидалось значение, а не «" + t.text() + "»", t);
    }

    private boolean scoped(Token t) {
        return t.kind() == Token.Kind.IDENT && Keywords.SCOPES.contains(t.text()) && peek(1).is(".")
                && (peek(2).kind() == Token.Kind.IDENT || peek(2).kind() == Token.Kind.NAME)
                && !peek(3).is("(");
    }

    private Expr identifier(Token t) {
        if (t.text().equals("_")) return new Ast.Blank(span(t));
        if (Keywords.SCOPES.contains(t.text()) && peek().is(".") && peek(1).kind() == Token.Kind.NAME && !peek(2).is("(")) {
            next();
            Token name = next();
            return new Ast.Var(name.text(), true, span(t).to(span(name)), t.text());
        }
        List<String> parts = new ArrayList<>();
        parts.add(t.text());
        Span last = span(t);
        while (peek().is(".") && !peek().newline() && peek(1).kind() == Token.Kind.IDENT) {
            next();
            Token part = next();
            parts.add(part.text());
            last = span(part);
        }
        if (peek().is("(") && !peek().newline()) {
            boolean category = Keywords.qualifier(parts.get(0));
            List<Arg> args = args();
            if (parts.size() > 1 && (!category || parts.size() > 2))
                return new Ast.Call(null, String.join(".", parts), false, span(t).to(last), args, span(t).to(previousSpan()));
            String qualifier = parts.size() == 2 ? parts.get(0) : null;
            String name = parts.get(parts.size() - 1);
            return new Ast.Call(qualifier, name, false, last, args, span(t).to(previousSpan()));
        }
        if (parts.size() == 1) return new Ast.Var(t.text(), false, span(t));
        if (parts.size() == 2 && Keywords.SCOPES.contains(parts.get(0)))
            return new Ast.Var(parts.get(1), true, span(t).to(last), parts.get(0));
        return new Ast.Path(parts, span(t).to(last));
    }

    private Expr list(Token open) {
        nest++;
        List<Expr> items = new ArrayList<>();
        while (!peek().is("]") && peek().kind() != Token.Kind.EOF) {
            items.add(expr());
            if (!peek().is(",")) break;
            next();
        }
        nest--;
        Token close = expect("]", "ожидалась «]» или «,»");
        return new Ast.ListLit(items, span(open).to(span(close)));
    }

    private Expr map(Token open) {
        nest++;
        List<Expr> keys = new ArrayList<>();
        List<Expr> values = new ArrayList<>();
        while (!peek().is("}") && peek().kind() != Token.Kind.EOF) {
            keys.add(additive());
            expect(":", "в словаре нужно «ключ: значение»");
            values.add(expr());
            if (!peek().is(",")) break;
            next();
        }
        nest--;
        Token close = expect("}", "ожидалась «}» или «,»");
        return new Ast.MapLit(keys, values, span(open).to(span(close)));
    }

    private boolean continues() { return nest > 0 || !peek().newline(); }

    private Token peek() { return tokens.get(pos); }

    private Token peek(int ahead) { return tokens.get(Math.min(pos + ahead, tokens.size() - 1)); }

    private Token next() {
        Token t = tokens.get(pos);
        if (t.kind() != Token.Kind.EOF) pos++;
        return t;
    }

    private Span previousSpan() { return span(tokens.get(Math.max(0, pos - 1))); }

    private Token expect(String symbol, String message) {
        Token t = peek();
        if (!t.is(symbol)) throw fail(message, t);
        return next();
    }

    private Token expectIdent(String message) {
        Token t = peek();
        if (t.kind() != Token.Kind.IDENT) throw fail(message, t);
        return next();
    }

    private Failure fail(String message, Token at) {
        diagnostics.add(Diagnostic.error(message, at.start(), Math.max(at.end(), at.start() + 1)));
        return new Failure();
    }

    private void skipToStatement() {
        int depth = 0;
        boolean first = true;
        while (peek().kind() != Token.Kind.EOF) {
            Token t = peek();
            if (!first && depth == 0 && (t.newline() || t.is("}"))) return;
            if (t.is("{")) depth++;
            if (t.is("}")) {
                if (depth == 0) return;
                depth--;
            }
            first = false;
            next();
        }
    }

    private void skipToItem() {
        next();
        while (peek().kind() != Token.Kind.EOF) {
            Token t = peek();
            if (t.newline() && (t.is("@") || t.keyword("when", "func", "process", "import", "const")
                    || (t.kind() == Token.Kind.IDENT && Keywords.SCOPES.contains(t.text())))) return;
            next();
        }
    }

    private static Span span(Token t) { return new Span(t.start(), t.end()); }
}
