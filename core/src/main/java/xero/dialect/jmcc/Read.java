package xero.dialect.jmcc;

import xero.dialect.jmcc.Lex.Kind;
import xero.dialect.jmcc.Lex.Tok;
import xero.lang.Ast.Span;
import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class Read {
    private static final int DEEPEST = 100;
    private static final Set<String> ASSIGN = Set.of("=", "+=", "-=", "*=", "/=", "%=", "^=");
    private static final Set<String> COMPARE = Set.of("==", "!=", ">", ">=", "<", "<=");

    private static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Failure() { super(null, null, false, false); }
    }

    private final String source;
    private final List<Tok> tokens;
    private final List<Diagnostic> diagnostics;
    private final List<Tree.Named> declared;
    private final List<Tree.Func> functions = new ArrayList<>();
    private final List<Tree.Enum> enums = new ArrayList<>();
    private final List<Tree.Foreign> foreign = new ArrayList<>();
    private final List<Tree.Alias> aliases = new ArrayList<>();
    private int pos;
    private int depth;
    private boolean noTernary;
    private boolean noLambda;

    private Read(String source, List<Tok> tokens, List<Diagnostic> diagnostics, List<Tree.Named> declared) {
        this.source = source;
        this.tokens = tokens;
        this.diagnostics = diagnostics;
        this.declared = declared;
    }

    static Tree.Unit parse(String file, String text, List<Diagnostic> diagnostics) {
        List<Tree.Named> declared = new ArrayList<>();
        Read r = new Read(text, Lex.tokens(text, 0, text.length(), diagnostics), diagnostics, declared);
        List<Tree.Stmt> items = r.statements(true);
        return new Tree.Unit(file, items, declared, r.functions, r.enums, r.foreign, r.aliases);
    }

    private List<Tree.Stmt> statements(boolean top) {
        List<Tree.Stmt> out = new ArrayList<>();
        while (peek().kind() != Kind.EOF && (top || !peek().is("}"))) {
            int before = pos;
            try {
                if (top && peek().is("}")) throw fail("лишняя «}»", peek());
                Tree.Stmt s = statement();
                if (s != null) {
                    out.add(s);
                    end(s);
                }
            } catch (Failure f) {
                recover(top);
            }
            if (pos == before) next();
        }
        return out;
    }

    private void end(Tree.Stmt s) {
        Tok t = peek();
        if (t.is(";")) {
            next();
            return;
        }
        if (t.is("}") || t.newline() || t.kind() == Kind.EOF || closed()) return;
        throw fail("после команды нужен перевод строки или «;»", t);
    }

    private boolean closed() { return pos > 0 && tokens.get(pos - 1).is("}"); }

    private void recover(boolean top) {
        int level = 0;
        boolean first = true;
        while (peek().kind() != Kind.EOF) {
            Tok t = peek();
            if (!first && level == 0 && t.newline()) return;
            if (t.is("{")) level++;
            if (t.is("}")) {
                if (level == 0) {
                    if (top) next();
                    return;
                }
                level--;
                if (level == 0) {
                    next();
                    return;
                }
            }
            first = false;
            next();
        }
    }

    private Tree.Stmt statement() {
        enter();
        depth++;
        try {
            return statementBody();
        } finally {
            depth--;
        }
    }

    private void enter() {
        if (depth >= DEEPEST) throw fail("слишком глубокая вложенность", peek());
    }

    private Tree.Stmt statementBody() {
        Tok t = peek();
        if (t.is(";")) {
            next();
            return null;
        }
        if (t.is("@")) return marked();
        if (t.kind() == Kind.LABEL) {
            next();
            return loopOrAction(t.text());
        }
        if (t.kind() == Kind.WORD) {
            Tree.Stmt s = keyword(t, List.of());
            if (s != null) return s;
        }
        return simple();
    }

    private Tree.Stmt loopOrAction(String label) {
        Tok t = peek();
        if (t.means("while")) return whileLoop(label);
        if (t.means("for")) return forLoop(label);
        return simple();
    }

    private Tree.Stmt keyword(Tok t, List<Tree.Mark> marks) {
        String word = Words.meaning(t.text());
        if (word == null) return null;
        switch (word) {
            case "export" -> {
                if (!starts(peek(1))) return null;
                next();
                Tree.Stmt inner = peek().kind() == Kind.WORD ? keyword(peek(), marks) : null;
                if (inner == null) throw fail("после export нужно объявление", peek());
                return inner;
            }
            case "import" -> {
                if (peek(1).kind() == Kind.STRING || peek(1).is("{") || peek(1).is("*")) return importStatement();
                return null;
            }
            case "from" -> {
                return peek(1).kind() == Kind.STRING ? fromImport() : null;
            }
            case "inline", "cached" -> {
                Tok after = peek(1);
                if (after.means("var")) {
                    if (word.equals("cached")) return null;
                    next();
                    return variable("inline");
                }
                if (after.means("function") || after.means("process")) {
                    next();
                    return function(marks, word.equals("inline"));
                }
                if (after.means("class") || after.means("inline") || after.means("cached")) {
                    next();
                    return keyword(peek(), marks);
                }
                if (after.means("import")) {
                    next();
                    return importStatement();
                }
                return null;
            }
            case "function", "process" -> {
                return peek(1).name() ? function(marks, false) : null;
            }
            case "event" -> {
                return peek(1).is("<") ? event(marks) : null;
            }
            case "class", "interface" -> {
                return peek(1).name() ? foreign(word) : null;
            }
            case "enum" -> {
                return peek(1).name() && peek(2).is("{") ? enumeration(marks) : null;
            }
            case "type" -> {
                return peek(1).name() && (peek(2).is("=") || peek(2).is("<")) ? alias() : null;
            }
            case "var" -> {
                return peek(1).name() ? variable(null) : null;
            }
            case "const" -> {
                return peek(1).name() ? variable("const") : null;
            }
            case "local", "game", "save", "line", "jmcc", "bracket" -> {
                if (!peek(1).means("var")) return null;
                next();
                return variable(word);
            }
            case "if" -> {
                return ifStatement();
            }
            case "while" -> {
                return whileLoop(null);
            }
            case "for" -> {
                return forLoop(null);
            }
            case "try" -> {
                return peek(1).is("{") ? tryStatement() : null;
            }
            case "throw" -> {
                return throwStatement();
            }
            case "return" -> {
                return usedAsName() ? null : returnStatement();
            }
            case "break", "continue" -> {
                if (usedAsName()) return null;
                next();
                String label = peek().kind() == Kind.LABEL ? next().text() : null;
                return new Tree.Jump(word, label, span(t).to(previousSpan()));
            }
            default -> {
                return null;
            }
        }
    }

    private boolean usedAsName() {
        Tok n = peek(1);
        return n.kind() == Kind.SYMBOL && (ASSIGN.contains(n.text()) || n.is(".") || n.is("++") || n.is("--"));
    }

    private static boolean starts(Tok t) {
        return t.kind() == Kind.WORD && Words.meaning(t.text()) != null;
    }

    private Tree.Stmt marked() {
        List<Tree.Mark> marks = new ArrayList<>();
        boolean dropped = false;
        while (peek().is("@")) {
            Tok at = next();
            Tok name = peek();
            if (!name.name()) throw fail("после @ нужно имя пометки", name);
            next();
            List<Tree.Arg> args = peek().is("(") && !peek().newline() ? args() : List.of();
            Span whole = span(at).to(previousSpan());
            if (name.text().equals("cfg") || name.text().equals("кфг")) {
                if (!feature(args)) dropped = true;
                continue;
            }
            marks.add(new Tree.Mark(name.text(), args, whole));
        }
        Tok t = peek();
        Tree.Stmt s = t.kind() == Kind.WORD ? keyword(t, marks) : null;
        if (dropped) return null;
        if (s != null) {
            if (marks.isEmpty() || s instanceof Tree.Func || s instanceof Tree.Event || s instanceof Tree.Enum
                    || s instanceof Tree.Foreign || s instanceof Tree.Alias) return s;
            diagnostics.add(Diagnostic.error("пометки @… ставят только перед функцией, процессом и событием",
                    marks.get(0).span().start(), marks.get(0).span().end()));
            return s;
        }
        if (!marks.isEmpty()) throw fail("после пометки нужна функция, процесс или событие", t);
        return statementBody();
    }

    private boolean feature(List<Tree.Arg> args) {
        if (args.size() != 1) return false;
        return featureOf(args.get(0).value());
    }

    private boolean featureOf(Tree.Expr e) {
        if (e instanceof Tree.Call c) {
            boolean any = c.name().equals("any") || c.name().equals("любой") || c.name().equals("любая");
            boolean all = c.name().equals("all") || c.name().equals("все");
            boolean not = c.name().equals("not") || c.name().equals("не");
            if (not && c.args().size() == 1) return !featureOf(c.args().get(0).value());
            if (any) {
                for (Tree.Arg a : c.args()) if (featureOf(a.value())) return true;
                return false;
            }
            if (all) {
                for (Tree.Arg a : c.args()) if (!featureOf(a.value())) return false;
                return true;
            }
        }
        if (e instanceof Tree.Unary u && u.op().equals("not")) return !featureOf(u.operand());
        return false;
    }

    private Tree.Stmt importStatement() {
        Tok t = next();
        if (peek().is("{")) {
            skipBraces();
            return importFrom(t);
        }
        if (peek().is("*")) {
            next();
            if (peek().means("as")) {
                next();
                next();
            }
            return importFrom(t);
        }
        Tok path = next();
        if (path.kind() != Kind.STRING || path.parts() != null) throw fail("после import нужен путь в кавычках", path);
        boolean connect = !peek().newline() && (peek().text().equals("connect") || peek().text().equals("подключить"));
        if (connect) next();
        if (peek().means("as")) {
            next();
            next();
        }
        return new Tree.Import(path.text(), connect, span(t).to(previousSpan()));
    }

    private Tree.Stmt importFrom(Tok t) {
        if (!peek().means("from")) throw fail("ожидалось from \"файл\"", peek());
        next();
        Tok path = next();
        if (path.kind() != Kind.STRING || path.parts() != null) throw fail("нужен путь в кавычках", path);
        return new Tree.Import(path.text(), false, span(t).to(previousSpan()));
    }

    private Tree.Stmt fromImport() {
        Tok t = next();
        Tok path = next();
        if (!peek().means("import")) throw fail("ожидалось import", peek());
        next();
        while (peek().name() || peek().is(",") || peek().is("*") || peek().means("as")) {
            if (peek().newline() && !tokens.get(pos - 1).is(",")) break;
            next();
        }
        return new Tree.Import(path.text(), false, span(t).to(previousSpan()));
    }

    private void skipBraces() {
        int level = 0;
        while (peek().kind() != Kind.EOF) {
            Tok t = next();
            if (t.is("{")) level++;
            if (t.is("}") && --level == 0) return;
        }
    }

    private Tree.Stmt foreign(String kind) {
        Tok t = next();
        Tok name = next();
        while (peek().kind() != Kind.EOF && !peek().is("{")) {
            if (peek().is(";")) break;
            next();
        }
        if (peek().is("{")) skipBraces();
        Tree.Foreign f = new Tree.Foreign(kind, name.text(), span(t).to(span(name)));
        foreign.add(f);
        return f;
    }

    private Tree.Stmt enumeration(List<Tree.Mark> marks) {
        Tok t = next();
        Tok name = next();
        expect("{", "ожидалась «{»");
        List<String> members = new ArrayList<>();
        while (peek().name()) {
            members.add(next().text());
            if (peek().is("=")) {
                next();
                expression();
            }
            if (!peek().is(",")) break;
            next();
        }
        Tok close = expect("}", "ожидалась «}» или «,»");
        Tree.Enum e = new Tree.Enum(name.text(), span(name), members, marks, span(t).to(span(close)));
        enums.add(e);
        return e;
    }

    private Tree.Stmt alias() {
        Tok t = next();
        Tok name = next();
        if (peek().is("<")) skipAngles();
        expect("=", "ожидалось «=»");
        String target = type();
        Tree.Alias a = new Tree.Alias(name.text(), target, span(t).to(previousSpan()));
        aliases.add(a);
        return a;
    }

    private void skipAngles() {
        int level = 0;
        while (peek().kind() != Kind.EOF) {
            Tok t = peek();
            if (t.is("<")) level++;
            else if (t.is(">") && --level == 0) {
                next();
                return;
            } else if (t.is("{") || t.is(";") || t.is("(")) {
                return;
            }
            next();
        }
    }

    private String type() {
        Tok t = next();
        if (!t.name()) throw fail("нужен вид значения, например number", t);
        if (peek().is("<")) skipAngles();
        if (peek().is("?")) next();
        return t.text();
    }

    private Tree.Stmt function(List<Tree.Mark> marks, boolean inline) {
        Tok t = next();
        boolean process = t.means("process");
        Tok name = next();
        if (!name.name()) throw fail("нужно имя " + (process ? "процесса" : "функции"), name);
        if (name.parts() != null) throw fail("имя функции пишется без подстановок", name);
        if (peek().is("<")) skipAngles();
        List<Tree.Param> params = new ArrayList<>();
        if (peek().is("(")) {
            next();
            while (!peek().is(")") && peek().kind() != Kind.EOF) {
                params.add(param());
                if (!peek().is(",")) break;
                next();
            }
            expect(")", "ожидалась «)» или «,»");
        }
        String result = null;
        if (peek().is("->")) {
            next();
            result = type();
        }
        if (!peek().is("{")) {
            if (peek().is(";") || peek().newline() || peek().kind() == Kind.EOF) {
                Tree.Func head = new Tree.Func(process, inline, name.text(), name.kind() == Kind.NAME, span(name), params, result,
                        null, marks, span(t).to(span(name)));
                functions.add(head);
                return head;
            }
            throw fail("ожидалось тело функции в «{ }»", peek());
        }
        Tree.Block body = block();
        Tree.Func f = new Tree.Func(process, inline, name.text(), name.kind() == Kind.NAME, span(name), params, result, body,
                marks, span(t).to(body.span()));
        functions.add(f);
        return f;
    }

    private Tree.Param param() {
        boolean ref = peek().means("ref") && peek(1).name();
        if (ref) next();
        int star = 0;
        if (peek().is("*")) {
            next();
            star = 1;
        } else if (peek().is("**")) {
            next();
            star = 2;
        }
        Tok name = next();
        if (!name.name()) throw fail("ожидалось имя параметра", name);
        String type = null;
        List<String> options = null;
        if (peek().is(":")) {
            next();
            if (peek().means("enum") && peek(1).is("(")) {
                next();
                options = new ArrayList<>();
                type = "enum";
                for (Tree.Arg a : args()) {
                    if (a.value() instanceof Tree.Str s && s.parts() == null) options.add(s.text());
                    else diagnostics.add(Diagnostic.error("варианты пишутся текстом", a.value().span().start(), a.value().span().end()));
                }
            } else {
                type = type();
            }
        }
        Tree.Expr fallback = null;
        if (peek().is("=")) {
            next();
            fallback = expression();
        }
        return new Tree.Param(name.text(), type, options, ref, star, fallback, span(name));
    }

    private Tree.Stmt event(List<Tree.Mark> marks) {
        Tok t = next();
        expect("<", "после event нужно <имя события>");
        Tok name = next();
        if (!name.name() && name.kind() != Kind.STRING) throw fail("нужно имя события", name);
        expect(">", "имя события закрывается «>»");
        Tree.Block body = block();
        return new Tree.Event(name.text(), span(name), body, marks, span(t).to(body.span()));
    }

    private Tree.Stmt variable(String scope) {
        Tok start = next();
        String kept = "const".equals(scope) || "jmcc".equals(scope) ? "inline" : "bracket".equals(scope) ? "line" : scope;
        List<Tree.Target> targets = new ArrayList<>();
        while (true) {
            Tok n = next();
            if (!n.name()) throw fail("нужно имя переменной", n);
            String own = n.kind() == Kind.NAME && n.prefix() != null ? n.prefix() : kept;
            Tree.Name name = name(n, own);
            String type = null;
            if (peek().is(":")) {
                next();
                type = type();
            }
            targets.add(new Tree.Target(name, type));
            if (!peek().is(",")) break;
            next();
        }
        List<Tree.Expr> values = new ArrayList<>();
        if (peek().is("=")) {
            next();
            values.add(expression());
            while (peek().is(",") && !peek().newline()) {
                next();
                values.add(expression());
            }
        }
        Span whole = span(start).to(values.isEmpty() ? previousSpan() : values.get(values.size() - 1).span());
        return new Tree.Var(kept, targets, values, whole);
    }

    private Tree.Stmt ifStatement() {
        Tok start = next();
        List<Tree.Branch> branches = new ArrayList<>();
        Tree.Expr condition = condition();
        Tree.Block body = bodyOrStatement();
        branches.add(new Tree.Branch(condition, body));
        Tree.Block otherwise = null;
        Span end = body.span();
        while (true) {
            if (peek().is(";") && peek(1).means("else")) next();
            if (peek().means("elif")) {
                next();
                Tree.Expr c = condition();
                Tree.Block b = bodyOrStatement();
                branches.add(new Tree.Branch(c, b));
                end = b.span();
                continue;
            }
            if (!peek().means("else")) break;
            next();
            if (peek().means("if")) {
                next();
                Tree.Expr c = condition();
                Tree.Block b = bodyOrStatement();
                branches.add(new Tree.Branch(c, b));
                end = b.span();
                continue;
            }
            otherwise = bodyOrStatement();
            end = otherwise.span();
            break;
        }
        return new Tree.If(branches, otherwise, span(start).to(end));
    }

    private Tree.Expr condition() {
        boolean saved = noTernary;
        noTernary = true;
        try {
            return expression();
        } finally {
            noTernary = saved;
        }
    }

    private Tree.Block bodyOrStatement() {
        if (peek().is("{")) return block();
        Tree.Stmt s = statement();
        if (s == null) return new Tree.Block(List.of(), previousSpan());
        return new Tree.Block(List.of(s), s.span());
    }

    private Tree.Block block() {
        Tok open = expect("{", "ожидалась «{»");
        enter();
        depth++;
        List<Tree.Stmt> stmts;
        try {
            stmts = statements(false);
        } finally {
            depth--;
        }
        Tok close = peek();
        if (!close.is("}")) {
            diagnostics.add(Diagnostic.error("блок не закрыт: ожидалась «}»", close.start(), Math.max(close.end(), close.start() + 1)));
            return new Tree.Block(stmts, span(open).to(span(close)));
        }
        next();
        return new Tree.Block(stmts, span(open).to(span(close)));
    }

    private Tree.Stmt whileLoop(String label) {
        Tok start = next();
        Tree.Expr condition = condition();
        Tree.Block body = bodyOrStatement();
        return new Tree.While(label, condition, body, span(start).to(body.span()));
    }

    private Tree.Stmt forLoop(String label) {
        Tok start = next();
        boolean wrapped = peek().is("(") && peek(1).name() && (peek(2).means("in") || peek(2).is(","));
        if (wrapped) next();
        List<Tree.Name> names = new ArrayList<>();
        while (true) {
            Tok n = next();
            if (!n.name()) throw fail("нужно имя переменной цикла", n);
            names.add(name(n, n.prefix()));
            if (!peek().is(",")) break;
            next();
        }
        if (!peek().means("in")) throw fail("ожидалось in: for x in список {}", peek());
        next();
        Tree.Expr source = condition();
        if (wrapped) expect(")", "ожидалась «)»");
        Tree.Block body = bodyOrStatement();
        return new Tree.For(label, names, source, body, span(start).to(body.span()));
    }

    private Tree.Stmt tryStatement() {
        Tok start = next();
        Tree.Block body = block();
        Tree.Name variable = null;
        Tree.Block handler = null;
        Span end = body.span();
        if (peek().means("catch")) {
            next();
            if (peek().is("(")) {
                next();
                Tok n = next();
                if (!n.name()) throw fail("нужно имя переменной", n);
                variable = name(n, n.prefix());
                expect(")", "ожидалась «)»");
            }
            handler = block();
            end = handler.span();
        }
        return new Tree.Try(body, variable, handler, span(start).to(end));
    }

    private Tree.Stmt throwStatement() {
        Tok start = next();
        String kind = "ERROR";
        if (peek().kind() == Kind.WORD && peek().text().equals(peek().text().toUpperCase(java.util.Locale.ROOT))
                && !peek().newline()) kind = next().text();
        Tree.Expr message = null;
        if (!peek().newline() && !peek().is(";") && !peek().is("}") && peek().kind() != Kind.EOF) message = expression();
        return new Tree.Throw(kind, message, span(start).to(previousSpan()));
    }

    private Tree.Stmt returnStatement() {
        Tok start = next();
        Tree.Expr value = null;
        if (!peek().newline() && !peek().is(";") && !peek().is("}") && peek().kind() != Kind.EOF) value = expression();
        return new Tree.Return(value, value == null ? span(start) : span(start).to(value.span()));
    }

    private Tree.Stmt simple() {
        Tree.Expr first = expression();
        String type = null;
        if (first instanceof Tree.Name && peek().is(":")) {
            next();
            type = type();
        }
        List<Tree.Target> targets = new ArrayList<>();
        targets.add(new Tree.Target(first, type));
        if (peek().is(",") && target(first)) {
            while (peek().is(",")) {
                next();
                Tree.Expr e = postfix();
                if (!target(e)) throw fail("слева от «=» нужны переменные", peek());
                targets.add(new Tree.Target(e, null));
            }
        }
        Tok op = peek();
        if (op.kind() == Kind.SYMBOL && ASSIGN.contains(op.text())) {
            if (!target(first)) throw fail("слева от «" + op.text() + "» нужна переменная", op);
            next();
            List<Tree.Expr> values = new ArrayList<>();
            values.add(expression());
            while (peek().is(",") && !peek().newline() && targets.size() > 1) {
                next();
                values.add(expression());
            }
            return new Tree.Assign(targets, op.text(), values, first.span().to(values.get(values.size() - 1).span()));
        }
        if (type != null || targets.size() > 1) return new Tree.Var("?", targets, List.of(), first.span());
        List<Tree.Name> lambda = new ArrayList<>();
        Tree.Block body = null;
        if (peek().is("{") && callable(first)) body = lambdaBlock(lambda);
        return new Tree.Run(first, lambda, body, body == null ? first.span() : first.span().to(body.span()));
    }

    private static boolean target(Tree.Expr e) {
        return e instanceof Tree.Name || e instanceof Tree.Index || e instanceof Tree.Attr
                || e instanceof Tree.Cast c && target(c.value());
    }

    private static boolean callable(Tree.Expr e) {
        return e instanceof Tree.Action || e instanceof Tree.Method || e instanceof Tree.Call;
    }

    private Tree.Block lambdaBlock(List<Tree.Name> lambda) {
        int arrow = lambdaArrow();
        if (arrow == 0) return block();
        Tok open = next();
        while (!peek().is("->")) {
            Tok n = next();
            if (n.name()) lambda.add(name(n, n.prefix()));
        }
        next();
        enter();
        depth++;
        List<Tree.Stmt> stmts;
        try {
            stmts = statements(false);
        } finally {
            depth--;
        }
        Tok close = peek();
        if (close.is("}")) next();
        else diagnostics.add(Diagnostic.error("блок не закрыт: ожидалась «}»", close.start(), Math.max(close.end(), close.start() + 1)));
        return new Tree.Block(stmts, span(open).to(span(close)));
    }

    private int lambdaArrow() {
        int i = 1;
        while (peek(i).name()) {
            i++;
            if (!peek(i).is(",")) break;
            i++;
        }
        return peek(i).is("->") ? i : 0;
    }

    private Tree.Name name(Tok t, String scope) {
        String own = scope == null ? null : scope.equals("jmcc") ? "inline" : scope.equals("bracket") ? "line" : scope;
        List<Object> parts = t.parts() == null ? null : parts(t);
        Tree.Name n = new Tree.Name(t.text(), own, t.kind() == Kind.NAME, parts, span(t));
        if (own != null && !own.equals("inline") && parts == null) declared.add(new Tree.Named(t.text(), own));
        return n;
    }

    private List<Object> parts(Tok t) {
        List<Object> out = new ArrayList<>();
        for (Object p : t.parts()) {
            if (p instanceof String s) {
                out.add(s);
            } else if (p instanceof Lex.Ref r) {
                out.add(new Tree.Name(r.name(), null, false, null, new Span(r.start(), r.end())));
            } else if (p instanceof Lex.Code c) {
                Read inner = new Read(source, Lex.tokens(source, c.start(), c.end(), diagnostics), diagnostics, declared);
                inner.depth = depth;
                try {
                    Tree.Expr e = inner.expression();
                    if (inner.peek().kind() != Kind.EOF) throw inner.fail("в ${…} одно выражение", inner.peek());
                    out.add(e);
                } catch (Failure f) {
                    out.add(new Tree.Str(null, "", null, new Span(c.start(), c.end())));
                }
            }
        }
        return out;
    }

    private List<Tree.Arg> args() {
        expect("(", "ожидалась «(»");
        boolean savedTernary = noTernary;
        boolean savedLambda = noLambda;
        noTernary = false;
        noLambda = false;
        List<Tree.Arg> out = new ArrayList<>();
        try {
            while (!peek().is(")") && peek().kind() != Kind.EOF) {
                if (peek().name() && (peek(1).is("=") || peek(1).is(":") && !peek(2).is(":"))) {
                    Tok name = next();
                    next();
                    out.add(new Tree.Arg(name.text(), span(name), expression(), 0));
                } else if (peek().is("*") || peek().is("**")) {
                    int star = next().text().length();
                    out.add(new Tree.Arg(null, null, expression(), star));
                } else {
                    out.add(new Tree.Arg(null, null, expression(), 0));
                }
                if (!peek().is(",")) break;
                next();
            }
        } finally {
            noTernary = savedTernary;
            noLambda = savedLambda;
        }
        expect(")", "ожидалась «)» или «,»");
        return out;
    }

    private Tree.Expr expression() {
        enter();
        depth++;
        try {
            return ternary();
        } finally {
            depth--;
        }
    }

    private Tree.Expr ternary() {
        Tree.Expr condition = or();
        if (peek().is("?")) {
            next();
            Tree.Expr then = expression();
            expect(":", "в «условие ? да : нет» не хватает «:»");
            Tree.Expr otherwise = expression();
            return new Tree.Ternary(condition, then, otherwise, condition.span().to(otherwise.span()));
        }
        if (!noTernary && peek().means("if") && !peek().newline()) {
            next();
            Tree.Expr test = or();
            if (!peek().means("else")) throw fail("в «a if условие else b» не хватает else", peek());
            next();
            Tree.Expr otherwise = expression();
            return new Tree.Ternary(test, condition, otherwise, condition.span().to(otherwise.span()));
        }
        return condition;
    }

    private Tree.Expr or() {
        Tree.Expr left = and();
        while (peek().means("or") || peek().is("||") || peek().is("|")) {
            next();
            Tree.Expr right = and();
            left = new Tree.Binary("or", left, right, left.span().to(right.span()));
        }
        return left;
    }

    private Tree.Expr and() {
        Tree.Expr left = not();
        while (peek().means("and") || peek().is("&&") || peek().is("&")) {
            next();
            Tree.Expr right = not();
            left = new Tree.Binary("and", left, right, left.span().to(right.span()));
        }
        return left;
    }

    private Tree.Expr not() {
        if (peek().means("not") || peek().is("!")) {
            Tok t = next();
            enter();
            depth++;
            try {
                Tree.Expr operand = not();
                return new Tree.Unary("not", operand, span(t).to(operand.span()));
            } finally {
                depth--;
            }
        }
        return compare();
    }

    private Tree.Expr compare() {
        Tree.Expr left = range();
        while (true) {
            Tok t = peek();
            if (t.means("in")) {
                next();
                Tree.Expr right = range();
                left = new Tree.Binary("in", left, right, left.span().to(right.span()));
            } else if (t.means("not") && peek(1).means("in")) {
                next();
                next();
                Tree.Expr right = range();
                Tree.Expr in = new Tree.Binary("in", left, right, left.span().to(right.span()));
                left = new Tree.Unary("not", in, in.span());
            } else if (t.kind() == Kind.SYMBOL && COMPARE.contains(t.text())) {
                next();
                Tree.Expr right = range();
                left = new Tree.Binary(t.text(), left, right, left.span().to(right.span()));
            } else {
                return left;
            }
        }
    }

    private Tree.Expr range() {
        Tree.Expr left = additive();
        if (peek().is("..") || peek().is("..=")) {
            boolean inclusive = next().is("..=");
            Tree.Expr right = additive();
            return new Tree.Range(left, right, inclusive, left.span().to(right.span()));
        }
        return left;
    }

    private Tree.Expr additive() {
        Tree.Expr left = multiplicative();
        while (peek().is("+") || peek().is("-")) {
            Tok op = next();
            Tree.Expr right = multiplicative();
            left = new Tree.Binary(op.text(), left, right, left.span().to(right.span()));
        }
        return left;
    }

    private Tree.Expr multiplicative() {
        Tree.Expr left = power();
        while (peek().is("*") || peek().is("/") || peek().is("%")) {
            Tok op = next();
            Tree.Expr right = power();
            left = new Tree.Binary(op.text(), left, right, left.span().to(right.span()));
        }
        return left;
    }

    private Tree.Expr power() {
        Tree.Expr left = unary();
        if (peek().is("^") || peek().is("**")) {
            next();
            enter();
            depth++;
            try {
                Tree.Expr right = power();
                return new Tree.Binary("^", left, right, left.span().to(right.span()));
            } finally {
                depth--;
            }
        }
        return left;
    }

    private Tree.Expr unary() {
        Tok t = peek();
        if (t.is("-") || t.is("+")) {
            next();
            enter();
            depth++;
            try {
                Tree.Expr operand = unary();
                return t.is("+") ? operand : new Tree.Unary("-", operand, span(t).to(operand.span()));
            } finally {
                depth--;
            }
        }
        if (t.is("++") || t.is("--")) {
            next();
            Tree.Expr target = postfix();
            return new Tree.Step(t.text(), true, target, span(t).to(target.span()));
        }
        return postfix();
    }

    private Tree.Expr postfix() {
        Tree.Expr e = primary();
        while (true) {
            Tok t = peek();
            if (t.is(".") && peek(1).name()) {
                next();
                Tok name = next();
                if (peek().is("(") && !peek().newline()) {
                    List<Tree.Arg> args = args();
                    e = new Tree.Method(e, name.text(), span(name), args, e.span().to(previousSpan()));
                } else {
                    e = new Tree.Attr(e, name.text(), span(name), e.span().to(span(name)));
                }
            } else if (t.is("[") && !t.newline()) {
                next();
                boolean savedTernary = noTernary;
                boolean savedLambda = noLambda;
                noTernary = false;
                noLambda = false;
                Tree.Expr from;
                Tree.Expr to = null;
                try {
                    from = expression();
                    if (peek().is(":")) {
                        next();
                        to = expression();
                    }
                } finally {
                    noTernary = savedTernary;
                    noLambda = savedLambda;
                }
                Tok close = expect("]", "ожидалась «]»");
                e = new Tree.Index(e, from, to, e.span().to(span(close)));
            } else if ((t.is("++") || t.is("--")) && !t.newline()) {
                next();
                e = new Tree.Step(t.text(), false, e, e.span().to(span(t)));
            } else if (t.means("as") && peek(1).name()) {
                next();
                String type = type();
                e = new Tree.Cast(e, type, e.span().to(previousSpan()));
            } else {
                return e;
            }
        }
    }

    private Tree.Expr primary() {
        Tok t = next();
        switch (t.kind()) {
            case NUMBER -> {
                return new Tree.Num(t.text(), span(t));
            }
            case STRING -> {
                return new Tree.Str(t.prefix(), t.text(), t.parts() == null ? null : parts(t), span(t));
            }
            case SNBT -> {
                return new Tree.Nbt(t.text(), span(t));
            }
            case NAME -> {
                if (peek().is("(") && !peek().newline() && t.parts() == null && t.prefix() == null) {
                    List<Tree.Arg> args = args();
                    return new Tree.Call(t.text(), true, span(t), args, span(t).to(previousSpan()));
                }
                return name(t, t.prefix());
            }
            case WORD -> {
                return word(t);
            }
            case SYMBOL -> {
                if (t.is("(")) return parenthesized(t);
                if (t.is("[")) return list(t);
                if (t.is("{")) return map(t);
            }
            default -> { }
        }
        pos = Math.max(0, t.kind() == Kind.EOF ? pos : pos - 1);
        throw fail(t.kind() == Kind.EOF ? "файл оборвался" : "ожидалось значение, а не «" + t.text() + "»", t);
    }

    private Tree.Expr parenthesized(Tok open) {
        if (lambdaAhead()) return lambda(open);
        boolean savedTernary = noTernary;
        boolean savedLambda = noLambda;
        noTernary = false;
        noLambda = false;
        try {
            Tree.Expr inner = expression();
            expect(")", "ожидалась «)»");
            return inner;
        } finally {
            noTernary = savedTernary;
            noLambda = savedLambda;
        }
    }

    private boolean lambdaAhead() {
        int level = 1;
        int i = 0;
        while (level > 0) {
            Tok t = peek(i);
            if (t.kind() == Kind.EOF) return false;
            if (t.is("(")) level++;
            if (t.is(")")) level--;
            i++;
        }
        return peek(i).is("=>");
    }

    private Tree.Expr lambda(Tok open) {
        int level = 1;
        while (level > 0 && peek().kind() != Kind.EOF) {
            Tok t = next();
            if (t.is("(")) level++;
            if (t.is(")")) level--;
        }
        return lambdaBody(open);
    }

    private Tree.Expr lambdaBody(Tok open) {
        expect("=>", "ожидалось «=>»");
        if (peek().is("{")) {
            Tree.Block b = block();
            return new Tree.Lambda(span(open).to(b.span()));
        }
        Tree.Expr e = expression();
        return new Tree.Lambda(span(open).to(e.span()));
    }

    private Tree.Expr word(Tok t) {
        String text = t.text();
        if (Words.TRUE.contains(text)) return new Tree.Bool(true, span(t));
        if (Words.FALSE.contains(text)) return new Tree.Bool(false, span(t));
        if (t.means("if")) return prefixIf(t);
        if (t.means("match") && !peek().is("=") && !peek().is(".") && !peek().is(")") && !peek().is(",")) return match(t);
        if (peek().is("::")) return scoped(t);
        if (!noLambda && peek().is("=>")) return lambdaBody(t);
        if (peek().is("(") && !peek().newline()) {
            List<Tree.Arg> args = args();
            return new Tree.Call(text, false, span(t), args, span(t).to(previousSpan()));
        }
        return new Tree.Name(text, null, false, null, span(t));
    }

    private Tree.Expr prefixIf(Tok t) {
        Tree.Expr test = condition();
        Tree.Expr then = expression();
        if (!peek().means("else")) throw fail("в «if условие a else b» не хватает else", peek());
        next();
        Tree.Expr otherwise = expression();
        return new Tree.Ternary(test, then, otherwise, span(t).to(otherwise.span()));
    }

    private Tree.Expr scoped(Tok object) {
        next();
        Tok name = next();
        if (!name.name()) throw fail("после «::» нужно имя", name);
        String selector = null;
        Span selectorSpan = null;
        if (peek().is("<") && selectorAhead()) {
            next();
            if (peek().is("$")) next();
            Tok s = next();
            selector = (tokens.get(pos - 2).is("$") ? "$" : "") + s.text();
            selectorSpan = span(s);
            next();
        }
        if (Words.factory(object.text()) != null && Words.factory(object.text()).equals("value"))
            return new Tree.Value(name.text(), span(name), selector, selectorSpan, span(object).to(previousSpan()));
        if (!peek().is("(")) throw fail("после «" + object.text() + "::" + name.text() + "» нужны скобки", peek());
        List<Tree.Arg> args = args();
        return new Tree.Action(object.text(), span(object), name.text(), span(name), selector, selectorSpan, args,
                span(object).to(previousSpan()));
    }

    private boolean selectorAhead() {
        int i = 1;
        if (peek(i).is("$")) i++;
        return (peek(i).kind() == Kind.WORD || peek(i).kind() == Kind.STRING) && peek(i + 1).is(">");
    }

    private Tree.Expr match(Tok start) {
        Tree.Expr subject = condition();
        expect("{", "ожидалась «{»");
        List<Tree.Arm> arms = new ArrayList<>();
        while (!peek().is("}") && peek().kind() != Kind.EOF) {
            int before = pos;
            arms.add(arm());
            while (peek().is(",") || peek().is(";")) next();
            if (pos == before) next();
        }
        Tok close = expect("}", "ожидалась «}»");
        return new Tree.Match(subject, arms, span(start).to(span(close)));
    }

    private Tree.Arm arm() {
        Tok first = peek();
        if (first.means("case")) next();
        List<Tree.Expr> patterns = new ArrayList<>();
        boolean saved = noLambda;
        boolean savedTernary = noTernary;
        noLambda = true;
        noTernary = true;
        try {
            if (peek().means("default")) {
                Tok d = next();
                patterns.add(new Tree.Name("_", null, false, null, span(d)));
            } else {
                patterns.add(range());
                while (peek().is("|")) {
                    next();
                    patterns.add(range());
                }
            }
            Tree.Expr guard = null;
            if (peek().means("if")) {
                next();
                guard = or();
            }
            noLambda = saved;
            noTernary = savedTernary;
            expect("=>", "в ветке match нужно «=>»");
            if (peek().is("{")) {
                Tree.Block b = block();
                return new Tree.Arm(patterns, guard, b, null, span(first).to(b.span()));
            }
            Tree.Stmt s = statement();
            return new Tree.Arm(patterns, guard, null, s, s == null ? span(first) : span(first).to(s.span()));
        } finally {
            noLambda = saved;
            noTernary = savedTernary;
        }
    }

    private Tree.Expr list(Tok open) {
        List<Tree.Expr> items = new ArrayList<>();
        boolean savedTernary = noTernary;
        boolean savedLambda = noLambda;
        noTernary = false;
        noLambda = false;
        try {
            while (!peek().is("]") && peek().kind() != Kind.EOF) {
                items.add(expression());
                if (!peek().is(",")) break;
                next();
            }
        } finally {
            noTernary = savedTernary;
            noLambda = savedLambda;
        }
        Tok close = expect("]", "ожидалась «]» или «,»");
        return new Tree.ListLit(items, span(open).to(span(close)));
    }

    private Tree.Expr map(Tok open) {
        List<Tree.Expr> keys = new ArrayList<>();
        List<Tree.Expr> values = new ArrayList<>();
        boolean savedTernary = noTernary;
        boolean savedLambda = noLambda;
        noTernary = false;
        noLambda = false;
        try {
            while (!peek().is("}") && peek().kind() != Kind.EOF) {
                keys.add(additive());
                expect(":", "в словаре нужно «ключ: значение»");
                values.add(expression());
                if (!peek().is(",")) break;
                next();
            }
        } finally {
            noTernary = savedTernary;
            noLambda = savedLambda;
        }
        Tok close = expect("}", "ожидалась «}» или «,»");
        return new Tree.MapLit(keys, values, span(open).to(span(close)));
    }

    private Tok peek() { return tokens.get(Math.min(pos, tokens.size() - 1)); }

    private Tok peek(int ahead) { return tokens.get(Math.min(pos + ahead, tokens.size() - 1)); }

    private Tok next() {
        Tok t = peek();
        if (t.kind() != Kind.EOF) pos++;
        return t;
    }

    private Span previousSpan() { return span(tokens.get(Math.max(0, pos - 1))); }

    private Tok expect(String symbol, String message) {
        Tok t = peek();
        if (!t.is(symbol)) throw fail(message, t);
        return next();
    }

    private Failure fail(String message, Tok at) {
        diagnostics.add(Diagnostic.error(message, at.start(), Math.max(at.end(), at.start() + 1)));
        return new Failure();
    }

    private static Span span(Tok t) { return new Span(t.start(), t.end()); }
}
