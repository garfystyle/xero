package xero.dialect.jmscript;

import xero.data.Data;
import xero.data.Names;
import xero.lang.Ast;
import xero.lang.Ast.Arg;
import xero.lang.Ast.Block;
import xero.lang.Ast.Expr;
import xero.lang.Ast.Item;
import xero.lang.Ast.Param;
import xero.lang.Ast.Span;
import xero.lang.Ast.Stmt;
import xero.lang.Diagnostic;
import xero.lang.Keywords;
import xero.lang.Token;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Parser {
    record Result(Ast.File file, Ast.Event entry) {}

    private record Binding(String kind, Expr constant) {}

    private record Dotted(String text, Span span) {}

    private static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Failure() { super(null, null, false, false); }
    }

    private static final Set<String> COMPARE = Set.of("==", "!=", ">", ">=", "<", "<=");
    private static final Set<String> ASSIGN = Set.of("=", "+=", "-=", "*=", "/=", "%=");
    private static final Set<String> NO_EFFECT = Set.of("pure", "export");
    private static final Set<String> MARKS = Set.of("name", "desc", "icon", "hidden", "line", "display_name",
            "display_description", "description", "values", "twin");
    private static final Set<String> START_SETTINGS = Set.of("local_variables_mode", "target_mode");
    private static final Set<String> BUILTINS = Set.of("location", "loc", "vec3", "vec", "item", "potion", "sound",
            "particle", "game_value", "array", "dict", "number", "num", "text");
    private static final Map<String, String> ALIASES = Map.of(
            "avg", "set_variable_average", "lerp", "set_variable_lerp_number", "warp", "set_variable_warp");
    private static final Map<String, String> PARAM_TYPES = Map.of("vec3", "vector", "dict", "map", "bool", "number",
            "game_value", "any");
    private static final String TEXT = "Текст";
    private static final int DEEPEST = 200;
    private static final int LONGEST = 1000;
    private static final int RANGE_LIMIT = 128;

    private final String source;
    private final Names names;
    private final List<Diagnostic> diagnostics;
    private final List<Item> items = new ArrayList<>();
    private final List<Stmt> entry = new ArrayList<>();
    private final List<Map<String, Binding>> scopes = new ArrayList<>();
    private final Map<String, String> functions = new HashMap<>();
    private final Set<String> globals = new LinkedHashSet<>();
    private List<Token> tokens;
    private List<Stmt> post;
    private Span entrySpan;
    private int pos;
    private int depth;
    private boolean rawRange;
    private boolean atStatement;

    private Parser(String source, Names names, List<Diagnostic> diagnostics) {
        this.source = source;
        this.names = names;
        this.diagnostics = diagnostics;
        this.tokens = Lexer.tokens(source, diagnostics);
        scopes.add(new HashMap<>());
    }

    static Result parse(String text, Names names, List<Diagnostic> diagnostics) {
        return new Parser(text, names, diagnostics).run();
    }

    private Result run() {
        scan();
        packageLine();
        while (peek().kind() != Token.Kind.EOF) {
            int before = pos;
            try {
                top();
            } catch (Failure f) {
                depth = 0;
                while (scopes.size() > 1) scopes.remove(scopes.size() - 1);
                skipTop(before);
            }
        }
        Ast.Event start = null;
        if (!entry.isEmpty()) {
            start = new Ast.Event("world_start", entrySpan, new Block(entry, entrySpan), List.of(), entrySpan);
            items.add(start);
        }
        return new Result(new Ast.File(items), start);
    }

    private void scan() {
        int level = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.is("{") || t.is("(") || t.is("[")) level++;
            else if (t.is("}") || t.is(")") || t.is("]")) level = Math.max(0, level - 1);
            if (level != 0) continue;
            if (t.word("func", "process") && named(token(i + 1))) {
                int at = i + 1;
                StringBuilder full = new StringBuilder(token(at).text());
                while (token(at + 1).is(".") && token(at + 2).kind() == Token.Kind.IDENT) {
                    full.append('.').append(token(at + 2).text());
                    at += 2;
                }
                functions.put(full.toString(), returned(at + 1));
            } else if (named(t) && token(i + 1).is(":=") && token(i + 2).kind() == Token.Kind.STRING) {
                scopes.get(0).put(t.text(), new Binding("text", null));
            }
        }
    }

    private String returned(int open) {
        int level = 0;
        for (int i = open; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.is("(")) level++;
            else if (t.is(")") && --level <= 0) return token(i + 1).is("->") ? token(i + 2).text() : null;
            else if (t.is("{") && level == 0) return null;
        }
        return null;
    }

    private void packageLine() {
        Token t = peek();
        if (t.word("package")) {
            next();
            if (peek().kind() == Token.Kind.IDENT) next();
            else report("после package нужно имя: package main", peek());
            if (peek().is(";")) next();
        } else if (t.kind() != Token.Kind.EOF) {
            diagnostics.add(Diagnostic.warning("файл начинают со строки package: package main", t.start(),
                    Math.max(t.end(), t.start() + 1)));
        }
    }

    private void top() {
        List<Ast.Decorator> marks = annotations();
        Token t = peek();
        if (t.word("func", "process") && named(peek(1))) {
            items.add(function(marks));
            return;
        }
        if (t.word("event") && named(peek(1))) {
            items.add(event(marks));
            return;
        }
        if (t.word("import") && peek(1).kind() == Token.Kind.STRING) {
            next();
            Token path = next();
            if (path.parts() != null) throw fail("после import нужен путь в кавычках: import \"kits.jms\"", path);
            boolean connect = !peek().newline() && peek().word("connect", "подключить");
            if (connect) next();
            items.add(new Ast.Import(path.text(), connect, span(t).to(previousSpan())));
            return;
        }
        ignored(marks);
        if (t.kind() == Token.Kind.IDENT && Keywords.SCOPES.contains(t.text()) && named(peek(1)) && !peek(1).newline()
                && !assigning(peek(2))) {
            next();
            Token name = next();
            items.add(new Ast.Global(t.text(), name.text(), span(t).to(span(name))));
            return;
        }
        if (named(t) && peek(1).is("::")) {
            next();
            next();
            constant(t, null);
            return;
        }
        if (named(t) && peek(1).is(":") && peek(2).kind() == Token.Kind.IDENT && peek(3).is(":")) {
            next();
            next();
            String type = next().text();
            next();
            constant(t, type);
            return;
        }
        List<Stmt> stmts = new ArrayList<>();
        statement(stmts, null);
        for (Stmt s : stmts) {
            entrySpan = entrySpan == null ? s.span() : entrySpan.to(s.span());
            entry.add(s);
        }
    }

    private void constant(Token name, String type) {
        Expr value = expr();
        String kind = type != null ? type : kindOf(value);
        if (scopes.size() == 1) {
            items.add(new Ast.Const(name.text(), span(name), value, span(name).to(value.span())));
            scopes.get(0).put(name.text(), new Binding(kind, null));
        } else {
            scope().put(name.text(), new Binding(kind, value));
        }
    }

    private void ignored(List<Ast.Decorator> marks) {
        for (Ast.Decorator d : marks)
            diagnostics.add(Diagnostic.warning("пометка «" + d.name() + "» здесь ни на что не влияет", d.span().start(), d.span().end()));
    }

    private List<Ast.Decorator> annotations() {
        List<Ast.Decorator> out = new ArrayList<>();
        while (peek().is("@")) {
            Token at = next();
            if (peek().is("(")) {
                next();
                while (!peek().is(")") && peek().kind() != Token.Kind.EOF) {
                    Token name = expectName("ожидалось имя пометки: @(name=\"…\")");
                    List<Arg> args = new ArrayList<>();
                    if (peek().is("=")) {
                        next();
                        args.add(new Arg(null, null, expr()));
                    } else if (peek().is("(")) {
                        args = args();
                    }
                    mark(out, name, args, span(at).to(previousSpan()));
                    if (!peek().is(",")) break;
                    next();
                }
                expect(")", "ожидалась «)» после пометок");
            } else {
                Token name = expectName("после @ нужно имя пометки, например @hidden");
                List<Arg> args = peek().is("(") && !peek().newline() ? args() : List.of();
                mark(out, name, args, span(at).to(previousSpan()));
            }
            if (peek().is(";")) next();
        }
        return out;
    }

    private void mark(List<Ast.Decorator> out, Token name, List<Arg> args, Span at) {
        if (NO_EFFECT.contains(name.text())) return;
        if (!MARKS.contains(name.text())) {
            diagnostics.add(Diagnostic.warning("пометка «" + name.text() + "» ни на что не влияет", at.start(), at.end()));
            return;
        }
        switch (name.text()) {
            case "name" -> out.add(new Ast.Decorator("display_name", args, at));
            case "desc" -> out.add(new Ast.Decorator("display_description", args, at));
            case "icon" -> {
                if (args.size() == 1 && args.get(0).value() instanceof Ast.Str s) {
                    Expr item = new Ast.Call(null, "item", false, s.span(), List.of(new Arg(null, null, s)), s.span());
                    out.add(new Ast.Decorator("icon", List.of(new Arg(null, null, item)), at));
                } else {
                    out.add(new Ast.Decorator("icon", args, at));
                }
            }
            case "hidden" -> {
                boolean given = args.size() == 1 && args.get(0).value() instanceof Ast.Var;
                String word = given ? ((Ast.Var) args.get(0).value()).name() : "";
                if (args.isEmpty() || word.equals("true")) out.add(new Ast.Decorator("hidden", List.of(), at));
                else if (!word.equals("false")) out.add(new Ast.Decorator("hidden", args, at));
            }
            default -> out.add(new Ast.Decorator(name.text(), args, at));
        }
    }

    private Ast.Event event(List<Ast.Decorator> marks) {
        Token start = next();
        Token name = next();
        scopes.add(new HashMap<>());
        try {
            if (peek().is("(")) {
                Token open = next();
                List<Param> params = params();
                expect(")", "ожидалась «)»");
                if (!params.isEmpty())
                    diagnostics.add(Diagnostic.warning("у события параметров не бывает — они никуда не попадут", open.start(), open.end()));
            }
            Block body = block();
            List<Ast.Decorator> kept = new ArrayList<>();
            for (Ast.Decorator d : marks) {
                if (d.name().equals("line")) kept.add(d);
                else ignored(List.of(d));
            }
            return new Ast.Event(name.text(), span(name), body, kept, span(start).to(body.span()));
        } finally {
            scopes.remove(scopes.size() - 1);
        }
    }

    private Ast.Func function(List<Ast.Decorator> marks) {
        Token start = next();
        boolean process = start.text().equals("process");
        Token name = next();
        Dotted full = dotted(name);
        expect("(", "после имени нужны скобки: " + full.text() + "()");
        scopes.add(new HashMap<>());
        try {
            List<Param> params = params();
            expect(")", "ожидалась «)»");
            if (peek().is("->")) {
                next();
                expectName("после -> нужен вид результата, например number");
            }
            Block body = block();
            return new Ast.Func(process, full.text(), name.kind() == Token.Kind.NAME, full.span(), params, body, marks,
                    span(start).to(body.span()));
        } finally {
            scopes.remove(scopes.size() - 1);
        }
    }

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

    private List<Param> params() {
        List<Param> out = new ArrayList<>();
        while (!peek().is(")") && peek().kind() != Token.Kind.EOF) {
            out.add(param());
            if (!peek().is(",")) break;
            next();
        }
        return out;
    }

    private Param param() {
        Token name = expectName("ожидалось имя параметра");
        String type = null;
        List<String> options = null;
        if (peek().is(":")) {
            next();
            type = expectName("после «:» нужен вид значения, например number").text();
            if (peek().is("(") && !peek().newline()) {
                options = new ArrayList<>();
                for (Arg a : args()) {
                    if (a.name() != null || !(a.value() instanceof Ast.Str s) || s.interpolated())
                        throw fail("варианты пишутся текстом: enum(\"первый\", \"второй\")", peek());
                    options.add(s.text());
                }
            }
        }
        Expr fallback = null;
        if (peek().is("=")) {
            next();
            fallback = expr();
        }
        scope().put(name.text(), new Binding(type, null));
        return new Param(name.text(), type == null ? null : PARAM_TYPES.getOrDefault(type, type), options, fallback, span(name));
    }

    private Block block() {
        Token open = expect("{", "ожидалась «{»");
        scopes.add(new HashMap<>());
        List<Stmt> stmts = new ArrayList<>();
        try {
            body(stmts);
        } finally {
            scopes.remove(scopes.size() - 1);
        }
        Token close = peek();
        if (close.is("}")) {
            next();
            return new Block(stmts, span(open).to(span(close)));
        }
        report("блок не закрыт: ожидалась «}»", close);
        return new Block(stmts, span(open).to(previousSpan()));
    }

    private void body(List<Stmt> stmts) {
        List<List<Stmt>> deferred = new ArrayList<>();
        while (!peek().is("}") && peek().kind() != Token.Kind.EOF) {
            int before = pos;
            int saved = depth;
            int open = scopes.size();
            try {
                statement(stmts, deferred);
            } catch (Failure f) {
                depth = saved;
                while (scopes.size() > open) scopes.remove(scopes.size() - 1);
                skipStatement(before);
            }
        }
        for (int i = deferred.size() - 1; i >= 0; i--) stmts.addAll(deferred.get(i));
    }

    private void statement(List<Stmt> out, List<List<Stmt>> deferred) {
        enter();
        try {
            ignored(annotations());
            Token t = peek();
            if (t.is(";")) {
                next();
            } else if (t.is("{")) {
                nested(out);
            } else if (t.word("if")) {
                out.add(ifStatement());
            } else if (t.word("for")) {
                forStatement(out);
            } else if (t.word("defer")) {
                defer(t, deferred);
            } else if (t.word("return")) {
                returnStatement(out);
            } else if (t.word("break") && !assigning(peek(1))) {
                next();
                out.add(new Ast.Jump("break", null, span(t)));
            } else if (t.word("continue") && !assigning(peek(1))) {
                next();
                if (post != null) out.addAll(post);
                out.add(new Ast.Jump("continue", null, span(t)));
            } else if (t.word("func", "process", "event") && named(peek(1))) {
                throw fail("функции и события объявляют вне блоков", t);
            } else if (t.word("start") && named(peek(1)) && !peek(1).newline()) {
                out.add(start());
            } else if (!simple(out)) {
                call(out);
            }
        } finally {
            leave();
        }
    }

    private void nested(List<Stmt> out) {
        next();
        scopes.add(new HashMap<>());
        try {
            body(out);
        } finally {
            scopes.remove(scopes.size() - 1);
        }
        if (peek().is("}")) next();
        else report("блок не закрыт: ожидалась «}»", peek());
    }

    private void defer(Token t, List<List<Stmt>> deferred) {
        next();
        if (deferred == null) throw fail("defer бывает только внутри блока", t);
        List<Stmt> group = new ArrayList<>();
        statement(group, null);
        deferred.add(group);
    }

    private void returnStatement(List<Stmt> out) {
        Token t = next();
        Expr value = null;
        if (!peek().newline() && !peek().is("}") && !peek().is(";") && peek().kind() != Token.Kind.EOF) value = expr();
        out.add(new Ast.Jump("return", value, value == null ? span(t) : span(t).to(value.span())));
    }

    private Stmt start() {
        Token t = next();
        Token name = next();
        Dotted full = dotted(name);
        List<Arg> args = new ArrayList<>();
        for (Arg a : args()) {
            if (a.name() != null && START_SETTINGS.contains(a.name()) && a.value() instanceof Ast.Str s)
                args.add(new Arg(a.name(), a.nameSpan(), new Ast.Var(s.text().toLowerCase(Locale.ROOT), false, s.span())));
            else args.add(a);
        }
        return new Ast.Start(full.text(), name.kind() == Token.Kind.NAME, full.span(), args, span(t).to(previousSpan()));
    }

    private boolean simple(List<Stmt> out) {
        Token t = peek();
        int multi = multiAssignAt();
        if (multi > 0) {
            multiAssign(out, multi == 2 ? next().text() : null);
            return true;
        }
        boolean scoped = t.kind() == Token.Kind.IDENT && Keywords.SCOPES.contains(t.text());
        if (scoped && peek(1).is(".") && named(peek(2)) && assigning(peek(3))) {
            next();
            next();
            Token name = next();
            Token op = next();
            Expr value = expr();
            out.add(new Ast.Assign(t.text(), name.text(), span(name), written(op), value, span(t).to(value.span()), true));
            return true;
        }
        if (scoped && named(peek(1)) && !peek(1).newline()) {
            next();
            Token name = next();
            if (assigning(peek())) {
                Token op = next();
                Expr value = expr();
                out.add(new Ast.Assign(t.text(), name.text(), span(name), written(op), value, span(t).to(value.span())));
            } else {
                out.add(new Ast.Declare(t.text(), name.text(), span(t).to(span(name))));
            }
            return true;
        }
        if (!named(t)) return false;
        Token after = peek(1);
        if (after.is(":=")) {
            next();
            next();
            declared(t, null, out);
            return true;
        }
        if (after.is("::")) {
            next();
            next();
            constant(t, null);
            return true;
        }
        if (after.is(":")) {
            typed(t, out);
            return true;
        }
        if (assigning(after)) {
            next();
            Token op = next();
            assignment(t, op, out);
            return true;
        }
        return false;
    }

    private void typed(Token t, List<Stmt> out) {
        next();
        next();
        String type = null;
        if (peek().kind() == Token.Kind.IDENT) type = next().text();
        if (peek().is(":")) {
            next();
            constant(t, type);
        } else {
            expect("=", "ожидалось «=»: " + t.text() + ": " + (type == null ? "number" : type) + " = значение");
            declared(t, type, out);
        }
    }

    private void declared(Token name, String type, List<Stmt> out) {
        Expr value = expr();
        scope().put(name.text(), new Binding(type != null ? type : kindOf(value), null));
        Span whole = span(name).to(value.span());
        if (name.text().equals("_")) {
            out.add(new Ast.Assign("line", "_", span(name), "=", value, whole, true));
            return;
        }
        if (scopes.size() == 1 && globals.add(name.text()))
            items.add(new Ast.Global("game", name.text(), span(name)));
        out.add(new Ast.Assign(null, name.text(), span(name), "=", value, whole));
    }

    private void assignment(Token name, Token op, List<Stmt> out) {
        Expr value = expr();
        Binding known = lookup(name.text());
        Span whole = span(name).to(value.span());
        if (known != null && known.constant() != null) {
            diagnostics.add(Diagnostic.error("«" + name.text() + "» — постоянная, её не меняют", name.start(), name.end()));
            return;
        }
        if (op.is("+=") && known != null && "text".equals(known.kind()) && !(value instanceof Ast.Num)) {
            Expr target = new Ast.Var(name.text(), name.kind() == Token.Kind.NAME, span(name));
            out.add(new Ast.Assign(null, name.text(), span(name), "=", joined(target, value), whole));
            return;
        }
        out.add(new Ast.Assign(null, name.text(), span(name), op.text(), value, whole));
    }

    private void multiAssign(List<Stmt> out, String scope) {
        Token first = peek();
        List<String> targets = new ArrayList<>();
        List<Span> spans = new ArrayList<>();
        while (true) {
            Token name = next();
            targets.add(name.text());
            spans.add(span(name));
            if (!peek().is(",")) break;
            next();
        }
        next();
        List<Expr> values = new ArrayList<>();
        values.add(expr());
        while (peek().is(",")) {
            next();
            values.add(expr());
        }
        for (int i = 0; i < targets.size(); i++)
            scope().put(targets.get(i), new Binding(i < values.size() ? kindOf(values.get(i)) : null, null));
        out.add(new Ast.MultiAssign(scope, targets, spans, values, span(first).to(values.get(values.size() - 1).span())));
    }

    private int multiAssignAt() {
        int i = pos;
        int kind = 1;
        Token t = token(i);
        if (t.kind() == Token.Kind.IDENT && Keywords.SCOPES.contains(t.text()) && named(token(i + 1)) && token(i + 2).is(",")) {
            i++;
            kind = 2;
        }
        int count = 0;
        while (named(token(i))) {
            count++;
            i++;
            if (!token(i).is(",")) break;
            i++;
        }
        return count > 1 && (token(i).is("=") || token(i).is(":=")) ? kind : 0;
    }

    private void call(List<Stmt> out) {
        Expr call;
        atStatement = true;
        try {
            call = expr();
        } finally {
            atStatement = false;
        }
        if (call instanceof Ast.Index index && assigning(peek())) {
            Token op = next();
            Expr value = expr();
            out.add(new Ast.IndexAssign(index, written(op), value, call.span().to(value.span())));
            return;
        }
        Block body = peek().is("{") && !peek().newline() ? block() : null;
        out.add(new Ast.Run(call, body, body == null ? call.span() : call.span().to(body.span())));
    }

    private Stmt ifStatement() {
        Token start = next();
        List<Ast.Branch> branches = new ArrayList<>();
        Expr condition = expr();
        Block body = block();
        branches.add(new Ast.Branch(condition, body));
        Block otherwise = null;
        Span end = body.span();
        while (peek().word("else")) {
            next();
            if (peek().word("if")) {
                next();
                Expr c = expr();
                Block b = block();
                branches.add(new Ast.Branch(c, b));
                end = b.span();
                continue;
            }
            otherwise = block();
            end = otherwise.span();
            break;
        }
        return new Ast.If(branches, otherwise, span(start).to(end));
    }

    private void forStatement(List<Stmt> out) {
        Token start = next();
        if (peek().is("{")) {
            List<Stmt> saved = post;
            post = null;
            Block body = block();
            post = saved;
            out.add(new Ast.Forever(body, span(start).to(body.span())));
            return;
        }
        if (named(peek())) {
            if (peek(1).word("in")) {
                Token first = next();
                next();
                loop(start, first, null, false, out);
                return;
            }
            if ((peek(1).is(",") || peek(1).is(":")) && named(peek(2)) && peek(3).word("in")) {
                Token first = next();
                boolean map = next().is(":");
                Token second = next();
                next();
                loop(start, first, second, map, out);
                return;
            }
        }
        classic(start, out);
    }

    private void loop(Token start, Token first, Token second, boolean map, List<Stmt> out) {
        Expr source = rangeOperand();
        Expr step = null;
        if (peek().word("step")) {
            next();
            step = expr();
        }
        List<Stmt> saved = post;
        post = null;
        Block body = block();
        post = saved;
        Span whole = span(start).to(body.span());
        boolean range = source instanceof Ast.Binary b && (b.op().equals("..=") || b.op().equals("..<"));
        if (range && second == null) {
            Ast.Binary b = (Ast.Binary) source;
            Expr end = b.op().equals("..=") ? b.right() : before(b.right());
            out.add(new Ast.ForRange(first.text(), span(first), b.left(), end, step, body, whole));
            return;
        }
        if (step != null) diagnostics.add(Diagnostic.error("step бывает только у диапазона с одной переменной", start.start(), start.end()));
        if (range) source = expanded((Ast.Binary) source);
        else if (source instanceof Ast.Str s && !s.interpolated()) source = letters(s);
        if (map) out.add(new Ast.ForEach(first.text(), span(first), second.text(), span(second), true, source, body, whole));
        else if (second == null) out.add(new Ast.ForEach(null, null, first.text(), span(first), false, source, body, whole));
        else out.add(new Ast.ForEach(second.text(), span(second), first.text(), span(first), false, source, body, whole));
    }

    private void classic(Token start, List<Stmt> out) {
        scopes.add(new HashMap<>());
        try {
            List<Stmt> init = new ArrayList<>();
            List<Stmt> after = new ArrayList<>();
            Expr condition = null;
            if (!peek().is(";") && !simple(init)) condition = expr();
            if (peek().is(";")) {
                next();
                if (condition != null) throw fail("в начале for нужно объявление: for i := 0; i < n; i += 1 { }", start);
                if (!peek().is(";") && !peek().is("{")) condition = expr();
                expect(";", "после условия for нужна «;»");
                if (!peek().is("{") && !simple(after)) throw fail("в конце for нужно присваивание: i += 1", peek());
            } else if (!peek().is("{")) {
                throw fail("в заголовке for ожидалась «;» или «{»", peek());
            }
            List<Stmt> saved = post;
            post = after.isEmpty() ? null : after;
            Block body = block();
            post = saved;
            List<Stmt> stmts = new ArrayList<>(body.stmts());
            stmts.addAll(after);
            Block whole = new Block(stmts, body.span());
            Span at = span(start).to(body.span());
            out.addAll(init);
            out.add(condition == null ? new Ast.Forever(whole, at) : new Ast.While(condition, whole, at));
        } finally {
            scopes.remove(scopes.size() - 1);
        }
    }

    private static Expr before(Expr end) {
        if (end instanceof Ast.Num n && n.text().matches("\\d{1,15}"))
            return new Ast.Num(Long.toString(Long.parseLong(n.text()) - 1), n.span());
        return new Ast.Binary("-", end, new Ast.Num("1", end.span()), end.span());
    }

    private Expr expanded(Ast.Binary range) {
        Span at = range.span();
        if (!(range.left() instanceof Ast.Num from) || !(range.right() instanceof Ast.Num to)) {
            diagnostics.add(Diagnostic.error("как список диапазон бывает только из чисел: 0..<5", at.start(), at.end()));
            return new Ast.ListLit(List.of(), at);
        }
        double start = Double.parseDouble(from.text());
        double end = Double.parseDouble(to.text()) + (range.op().equals("..=") ? 1 : 0);
        if (end - start > RANGE_LIMIT) {
            diagnostics.add(Diagnostic.error("как список диапазон бывает не длиннее " + RANGE_LIMIT, at.start(), at.end()));
            return new Ast.ListLit(List.of(), at);
        }
        List<Expr> out = new ArrayList<>();
        for (int i = 0; start + i < end; i++) {
            double v = start + i;
            out.add(new Ast.Num(v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : Double.toString(v), at));
            if (i >= RANGE_LIMIT) break;
        }
        return new Ast.ListLit(out, at);
    }

    private static Expr letters(Ast.Str s) {
        List<Expr> out = new ArrayList<>();
        String t = s.text();
        int i = 0;
        while (i < t.length()) {
            int end = Character.isHighSurrogate(t.charAt(i)) && i + 1 < t.length() ? i + 2 : i + 1;
            out.add(new Ast.Str("plain", t.substring(i, end), s.span()));
            i = end;
        }
        return new Ast.ListLit(out, s.span());
    }

    private List<Arg> args() {
        expect("(", "ожидалась «(»");
        List<Arg> out = new ArrayList<>();
        while (!peek().is(")") && peek().kind() != Token.Kind.EOF) {
            if (peek().kind() == Token.Kind.IDENT && peek(1).is("=")) {
                Token name = next();
                next();
                out.add(new Arg(name.text(), span(name), expr()));
            } else {
                out.add(new Arg(null, null, expr()));
            }
            if (!peek().is(",")) break;
            next();
        }
        expect(")", "ожидалась «)» или «,»");
        return out;
    }

    private Expr expr() {
        enter();
        try {
            Expr condition = or();
            if (!peek().is("?")) return condition;
            next();
            Expr then = expr();
            expect(":", "в «условие ? да : нет» не хватает «:»");
            Expr otherwise = expr();
            return new Ast.Ternary(condition, then, otherwise, condition.span().to(otherwise.span()));
        } finally {
            leave();
        }
    }

    private Expr or() {
        Expr left = and();
        int count = 0;
        while (peek().is("||")) {
            chain(++count);
            next();
            Expr right = and();
            left = new Ast.Binary("or", left, right, left.span().to(right.span()));
        }
        return left;
    }

    private Expr and() {
        Expr left = compare();
        int count = 0;
        while (peek().is("&&")) {
            chain(++count);
            next();
            Expr right = compare();
            left = new Ast.Binary("and", left, right, left.span().to(right.span()));
        }
        return left;
    }

    private Expr compare() {
        Expr left = range();
        int count = 0;
        while (true) {
            chain(++count);
            if (peek().word("in")) {
                next();
                left = membership(left);
            } else if (peek().is("!") && peek(1).word("in")) {
                next();
                next();
                Expr in = membership(left);
                left = new Ast.Unary("not", in, in.span());
            } else if (peek().kind() == Token.Kind.SYMBOL && COMPARE.contains(peek().text())) {
                Token op = next();
                Expr right = additive();
                left = new Ast.Binary(op.text(), left, right, left.span().to(right.span()));
            } else {
                return left;
            }
        }
    }

    private Expr membership(Expr left) {
        Expr right = rangeOperand();
        if (right instanceof Ast.Binary b && (b.op().equals("..=") || b.op().equals("..<")))
            right = new Ast.Binary("..", b.left(), b.op().equals("..=") ? b.right() : before(b.right()), b.span());
        return new Ast.Binary("in", left, right, left.span().to(right.span()));
    }

    private Expr rangeOperand() {
        boolean saved = rawRange;
        rawRange = true;
        try {
            return range();
        } finally {
            rawRange = saved;
        }
    }

    private Expr range() {
        boolean raw = rawRange;
        rawRange = false;
        Expr left = additive();
        if (!peek().is("..<") && !peek().is("..=")) return left;
        Token op = next();
        Expr right = additive();
        Ast.Binary range = new Ast.Binary(op.text(), left, right, left.span().to(right.span()));
        return raw ? range : expanded(range);
    }

    private Expr additive() {
        Expr left = multiplicative();
        int count = 0;
        while (peek().is("+") || peek().is("-")) {
            chain(++count);
            Token op = next();
            Expr right = multiplicative();
            if (op.is("+") && text(left, right)) left = joined(left, right);
            else left = new Ast.Binary(op.text(), left, right, left.span().to(right.span()));
        }
        return left;
    }

    private boolean text(Expr left, Expr right) {
        if (left instanceof Ast.Str || right instanceof Ast.Str || textCall(left) || textCall(right)) return true;
        return "text".equals(kindOf(left)) && "text".equals(kindOf(right));
    }

    private Expr multiplicative() {
        Expr left = unary();
        int count = 0;
        while (peek().is("*") || peek().is("/") || peek().is("%")) {
            chain(++count);
            Token op = next();
            Expr right = unary();
            left = new Ast.Binary(op.text(), left, right, left.span().to(right.span()));
        }
        return left;
    }

    private Expr unary() {
        enter();
        try {
            Token t = peek();
            if (t.is("-") || t.is("!")) {
                next();
                Expr operand = unary();
                return new Ast.Unary(t.is("-") ? "-" : "not", operand, span(t).to(operand.span()));
            }
            if (t.is("+")) {
                next();
                return unary();
            }
            return postfix();
        } finally {
            leave();
        }
    }

    private Expr postfix() {
        Expr e = atom();
        while (peek().is("[") && !peek().newline() && indexable(e)) {
            next();
            Expr index = expr();
            Token close = expect("]", "ожидалась «]»");
            e = new Ast.Index(e, index, e.span().to(span(close)));
        }
        if (peek().is(".") && !peek().newline())
            throw fail("полей у значений нет: через точку пишут только game.x, value.x и имя функции", peek());
        return e;
    }

    private static boolean indexable(Expr e) {
        return e instanceof Ast.Var || e instanceof Ast.Call || e instanceof Ast.Index || e instanceof Ast.Path;
    }

    private Expr atom() {
        Token t = next();
        switch (t.kind()) {
            case NUMBER -> {
                return new Ast.Num(t.text(), span(t));
            }
            case STRING -> {
                return string(t);
            }
            case NAME -> {
                if (peek().is("(") && !peek().newline()) {
                    atStatement = false;
                    List<Arg> args = args();
                    return new Ast.Call(null, t.text(), true, span(t), args, span(t).to(previousSpan()));
                }
                return new Ast.Var(t.text(), true, span(t));
            }
            case IDENT -> {
                return identifier(t);
            }
            case SYMBOL -> {
                if (t.is("(")) {
                    Expr inner = expr();
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

    private Expr string(Token t) {
        String prefix = t.prefix() != null ? t.prefix() : Markup.detect(t.text());
        if (t.parts() == null) return new Ast.Str(prefix, t.text(), span(t));
        List<Object> parts = new ArrayList<>();
        for (Object part : t.parts()) parts.add(part instanceof Token.Code code ? code(code) : part);
        return new Ast.Str(prefix, t.text(), parts, span(t));
    }

    private Expr code(Token.Code code) {
        List<Token> savedTokens = tokens;
        int savedPos = pos;
        tokens = Lexer.tokens(source, code.start(), code.end(), diagnostics);
        pos = 0;
        try {
            Expr e = expr();
            if (peek().kind() != Token.Kind.EOF) report("в ${…} одно выражение", peek());
            return e;
        } catch (Failure f) {
            return new Ast.Blank(new Span(code.start(), code.end()));
        } finally {
            tokens = savedTokens;
            pos = savedPos;
        }
    }

    private Expr identifier(Token t) {
        if (t.text().equals("_")) return new Ast.Blank(span(t));
        if (t.word("true", "false")) return new Ast.Var(t.text(), false, span(t));
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
        if (peek().is("(") && !peek().newline()) return invocation(parts, t, last);
        if (parts.size() == 1) {
            Binding known = lookup(t.text());
            return known != null && known.constant() != null ? known.constant() : new Ast.Var(t.text(), false, span(t));
        }
        if (parts.size() == 2 && Keywords.SCOPES.contains(parts.get(0)))
            return new Ast.Var(parts.get(1), true, span(t).to(last), parts.get(0));
        return new Ast.Path(parts, span(t).to(last));
    }

    private Expr invocation(List<String> parts, Token first, Span last) {
        boolean top = atStatement;
        atStatement = false;
        List<Arg> args = args();
        Span whole = span(first).to(previousSpan());
        String name = parts.get(parts.size() - 1);
        if (parts.size() > 1) {
            if (!Keywords.qualifier(parts.get(0)))
                return new Ast.Call(null, String.join(".", parts), false, span(first).to(last), args, whole);
            if (parts.size() > 2) throw fail("слишком длинное имя: " + String.join(".", parts), first);
            return action(parts.get(0), name, null, last, args, whole, top);
        }
        Span at = span(first);
        if (functions.containsKey(name)) return new Ast.Call(null, name, false, at, args, whole);
        if (BUILTINS.contains(name)) return builtin(name, at, args, whole);
        Names.Entry known = names.byId(ALIASES.getOrDefault(name, name));
        if (known == null) return action(null, name, null, at, args, whole, top);
        String qualified = known.qualified();
        int dot = qualified.indexOf('.');
        return action(qualified.substring(0, dot), qualified.substring(dot + 1), known, at, args, whole, top);
    }

    private Expr action(String qualifier, String name, Names.Entry given, Span at, List<Arg> args, Span whole, boolean top) {
        String shown = qualifier == null ? name : qualifier + "." + name;
        Names.Entry entry = given != null ? given : names.resolve(Names.Space.ACTION, shown);
        if (entry == null) entry = names.resolve(Names.Space.CONDITION, shown);
        Data.Action known = entry == null ? null : entry.action();
        boolean condition = entry != null && entry.space() == Names.Space.CONDITION;
        List<Arg> rest = new ArrayList<>();
        Arg selection = null;
        for (Arg a : args) {
            if ("selection".equals(a.name()) && selection == null) selection = a;
            else rest.add(a);
        }
        List<Arg> bound = known == null ? rest : markers(known, rest, top || condition);
        Expr call = new Ast.Call(qualifier, name, false, at, bound, whole);
        if (selection == null) return call;
        if (!(selection.value() instanceof Ast.Str s) || s.interpolated()) {
            Span v = selection.value().span();
            diagnostics.add(Diagnostic.error("цель пишется текстом: selection=\"all\"", v.start(), v.end()));
            return call;
        }
        return new Ast.Targeted(target(known == null ? null : known.family(), s.text()), s.span(), call, whole);
    }

    private String target(String family, String text) {
        if (family == null) return text;
        Map<String, String> known = names.selectors(family);
        if (known.containsKey(text)) return text;
        for (Map.Entry<String, String> e : known.entrySet()) if (e.getValue().equals(text)) return e.getKey();
        return text;
    }

    private static List<Arg> markers(Data.Action action, List<Arg> args, boolean keepResult) {
        if (action.settings().isEmpty()) return args;
        List<Data.Arg> slots = new ArrayList<>(action.args());
        if (!keepResult && action.result() != null) slots.remove(action.result());
        Set<String> bound = new LinkedHashSet<>();
        for (Arg a : args) if (a.name() != null) bound.add(a.name().toLowerCase(Locale.ROOT));
        List<Arg> out = new ArrayList<>();
        int slot = 0;
        for (Arg a : args) {
            if (a.name() != null) {
                out.add(a);
                continue;
            }
            boolean textual = slot < slots.size() && (TEXT.equals(slots.get(slot).kind()) || Data.ANY.equals(slots.get(slot).kind()));
            Data.Setting owner = textual ? null : owner(action, a.value(), bound);
            if (owner != null) {
                bound.add(owner.id());
                out.add(new Arg(owner.id(), a.value().span(), a.value()));
            } else {
                out.add(a);
                slot++;
            }
        }
        return out;
    }

    private static Data.Setting owner(Data.Action action, Expr value, Set<String> bound) {
        if (!(value instanceof Ast.Str s) || s.interpolated()) return null;
        String option = s.text().toLowerCase(Locale.ROOT);
        for (Data.Setting setting : action.settings())
            if (!bound.contains(setting.id()) && setting.options().containsKey(option)) return setting;
        return null;
    }

    private Expr builtin(String name, Span at, List<Arg> args, Span whole) {
        return switch (name) {
            case "location", "loc" -> new Ast.Call(null, "loc", false, at, args, whole);
            case "vec3", "vec" -> new Ast.Call(null, "vec", false, at, args, whole);
            case "item" -> item(at, args, whole);
            case "potion" -> spread("potion", at, args, whole, List.of("amplifier", "duration"));
            case "sound" -> spread("sound", at, args, whole, List.of("pitch", "volume", "variation", "source"));
            case "particle" -> spread("particle", at, args, whole, List.of("count", "first_spread", "second_spread",
                    "x_motion", "y_motion", "z_motion", "color", "size"));
            case "game_value" -> gameValue(args, whole);
            case "array" -> {
                List<Expr> values = new ArrayList<>();
                for (Arg a : args) values.add(a.value());
                yield new Ast.ListLit(values, whole);
            }
            case "dict" -> dict(args, whole);
            case "text" -> {
                if (args.size() != 1 || args.get(0).name() != null) {
                    diagnostics.add(Diagnostic.error("text(значение): одно значение", whole.start(), whole.end()));
                    yield new Ast.Str(null, "", whole);
                }
                Expr value = args.get(0).value();
                yield value instanceof Ast.Str ? value : new Ast.Str(null, "", List.of(value), whole);
            }
            default -> {
                if (args.size() == 1 && args.get(0).name() == null && !(args.get(0).value() instanceof Ast.Str))
                    yield args.get(0).value();
                yield new Ast.Call(null, "num", false, at, args, whole);
            }
        };
    }

    private static Expr item(Span at, List<Arg> args, Span whole) {
        List<Arg> out = new ArrayList<>();
        for (Arg a : args) {
            if ("components".equals(a.name()) && a.value() instanceof Ast.Str s)
                out.add(new Arg(a.name(), a.nameSpan(), new Ast.Str(null, s.text(), s.parts(), s.span())));
            else out.add(a);
        }
        return new Ast.Call(null, "item", false, at, out, whole);
    }

    private static Expr spread(String name, Span at, List<Arg> args, Span whole, List<String> fields) {
        List<Arg> out = new ArrayList<>();
        int position = -1;
        for (Arg a : args) {
            if (a.name() != null) {
                out.add(a);
                continue;
            }
            if (position >= 0 && position < fields.size()) out.add(new Arg(fields.get(position), a.value().span(), a.value()));
            else out.add(a);
            position++;
        }
        return new Ast.Call(null, name, false, at, out, whole);
    }

    private Expr gameValue(List<Arg> args, Span whole) {
        String selection = null;
        Span selectionAt = null;
        Expr id = null;
        for (Arg a : args) {
            boolean second = "selection".equals(a.name()) || a.name() == null && id != null;
            if (!second) {
                id = a.value();
            } else if (a.value() instanceof Ast.Str s) {
                selection = s.text();
                selectionAt = s.span();
            }
        }
        if (!(id instanceof Ast.Str s) || s.interpolated()) {
            diagnostics.add(Diagnostic.error("игровое значение пишется так: game_value(\"current_health\", \"default\")", whole.start(), whole.end()));
            return new Ast.Blank(whole);
        }
        Expr path = new Ast.Path(List.of("value", s.text()), whole);
        return selection == null ? path : new Ast.Targeted(target("value", selection), selectionAt, path, whole);
    }

    private Expr dict(List<Arg> args, Span whole) {
        List<Expr> keys = new ArrayList<>();
        List<Expr> values = new ArrayList<>();
        if (args.size() % 2 != 0) diagnostics.add(Diagnostic.error("dict(ключ, значение, …): у ключа нет значения", whole.start(), whole.end()));
        for (int i = 0; i + 1 < args.size(); i += 2) {
            keys.add(args.get(i).value());
            values.add(args.get(i + 1).value());
        }
        return new Ast.MapLit(keys, values, whole);
    }

    private Expr list(Token open) {
        List<Expr> values = new ArrayList<>();
        while (!peek().is("]") && peek().kind() != Token.Kind.EOF) {
            values.add(expr());
            if (!peek().is(",")) break;
            next();
        }
        Token close = expect("]", "ожидалась «]» или «,»");
        return new Ast.ListLit(values, span(open).to(span(close)));
    }

    private Expr map(Token open) {
        List<Expr> keys = new ArrayList<>();
        List<Expr> values = new ArrayList<>();
        while (!peek().is("}") && peek().kind() != Token.Kind.EOF) {
            keys.add(additive());
            expect(":", "в словаре нужно «ключ: значение»");
            values.add(expr());
            if (!peek().is(",")) break;
            next();
        }
        Token close = expect("}", "ожидалась «}» или «,»");
        return new Ast.MapLit(keys, values, span(open).to(span(close)));
    }

    private static Expr joined(Expr left, Expr right) {
        Span at = left.span().to(right.span());
        List<Expr> parts = new ArrayList<>();
        if (textCall(left)) parts.addAll(((Ast.ListLit) ((Ast.Call) left).args().get(0).value()).items());
        else parts.add(left);
        parts.add(right);
        return new Ast.Call("var", "text", false, at, List.of(new Arg("text", at, new Ast.ListLit(parts, at)),
                new Arg("merging", at, new Ast.Str(null, "CONCATENATION", at))), at);
    }

    private static boolean textCall(Expr e) {
        return e instanceof Ast.Call c && "var".equals(c.qualifier()) && c.name().equals("text") && !c.args().isEmpty()
                && c.args().get(0).value() instanceof Ast.ListLit;
    }

    private String kindOf(Expr e) {
        return switch (e) {
            case Ast.Str s -> "text";
            case Ast.Var v -> {
                Binding known = v.scope() == null ? lookup(v.name()) : null;
                yield known == null ? null : known.kind();
            }
            case Ast.Call c when textCall(c) -> "text";
            case Ast.Call c when c.qualifier() == null -> functions.get(c.name());
            case Ast.Ternary t -> {
                String a = kindOf(t.then());
                yield a != null && a.equals(kindOf(t.otherwise())) ? a : null;
            }
            default -> null;
        };
    }

    private Binding lookup(String name) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            Binding b = scopes.get(i).get(name);
            if (b != null) return b;
        }
        return null;
    }

    private Map<String, Binding> scope() {
        return scopes.get(scopes.size() - 1);
    }

    private static String written(Token op) {
        return op.is(":=") ? "=" : op.text();
    }

    private static boolean assigning(Token t) {
        return t.kind() == Token.Kind.SYMBOL && (ASSIGN.contains(t.text()) || t.text().equals(":="));
    }

    private static boolean named(Token t) {
        return t.kind() == Token.Kind.IDENT || t.kind() == Token.Kind.NAME;
    }

    private void chain(int count) {
        if (count > LONGEST) throw fail("слишком длинное выражение: разбей его на части", peek());
    }

    private void enter() {
        if (++depth > DEEPEST) {
            depth--;
            throw fail("слишком глубокая вложенность", peek());
        }
    }

    private void leave() {
        depth--;
    }

    private Token token(int at) {
        return tokens.get(Math.min(at, tokens.size() - 1));
    }

    private Token peek() {
        return token(pos);
    }

    private Token peek(int ahead) {
        return token(pos + ahead);
    }

    private Token next() {
        Token t = peek();
        if (t.kind() != Token.Kind.EOF) pos++;
        return t;
    }

    private Span previousSpan() {
        return span(tokens.get(Math.max(0, pos - 1)));
    }

    private Token expect(String symbol, String message) {
        Token t = peek();
        if (!t.is(symbol)) throw fail(message, t);
        return next();
    }

    private Token expectName(String message) {
        Token t = peek();
        if (!named(t)) throw fail(message, t);
        return next();
    }

    private void report(String message, Token at) {
        int start = at.kind() == Token.Kind.EOF ? Math.max(0, at.start() - 1) : at.start();
        diagnostics.add(Diagnostic.error(message, start, Math.max(at.end(), start + 1)));
    }

    private Failure fail(String message, Token at) {
        report(message, at);
        return new Failure();
    }

    private void skipStatement(int before) {
        if (pos == before) next();
        int level = 0;
        while (peek().kind() != Token.Kind.EOF) {
            Token t = peek();
            if (level == 0 && (t.newline() || t.is("}"))) return;
            if (t.is("{")) level++;
            if (t.is("}")) level--;
            next();
        }
    }

    private void skipTop(int before) {
        if (pos == before) next();
        while (peek().kind() != Token.Kind.EOF) {
            Token t = peek();
            if (t.newline() && (t.is("@") || t.word("func", "process", "event", "import"))) return;
            next();
        }
    }

    private static Span span(Token t) {
        return new Span(t.start(), t.end());
    }
}
