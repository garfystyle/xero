package xero.format;

import xero.lang.Ast;
import xero.lang.Diagnostic;
import xero.lang.Keywords;
import xero.lang.Lexer;
import xero.lang.Parser;
import xero.lang.Token;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class Formatter {
    public record Result(String text, List<Diagnostic> errors) {
        public boolean ok() { return text != null; }
    }

    private static final String INDENT = "    ";
    private static final int WIDTH = 100;

    private static final Set<String> BINARY = Set.of(
            "==", "!=", ">=", "<=", "+=", "-=", "*=", "/=", "=", ">", "<", "+", "-", "*", "/", "%");

    private enum Kind { COMMENT, CODE, GLOBAL, IMPORT, DECORATOR, OTHER }

    private static final class Line {
        final int indent;
        final StringBuilder text = new StringBuilder();
        final Kind kind;
        Kind family;
        boolean blank;
        Token last;
        boolean lineComment;
        boolean closesItem;
        boolean decorator;
        boolean attached;

        Line(int indent, Kind kind) {
            this.indent = indent;
            this.kind = kind;
        }
    }

    private final String source;
    private final Set<Integer> blockOpen = new HashSet<>();
    private final Set<Integer> blockClose = new HashSet<>();
    private final Map<Integer, Kind> items = new HashMap<>();
    private final List<Integer> contextualAfter = new ArrayList<>();
    private final Set<Integer> contextual = new HashSet<>();
    private final List<Line> lines = new ArrayList<>();
    private final Deque<Integer> brackets = new ArrayDeque<>();
    private final Deque<Integer> questions = new ArrayDeque<>();
    private Line cur;
    private int depth;
    private boolean pending;
    private Token prev;
    private Token beforePrev;
    private boolean lastWasOpen;

    private Formatter(String source) {
        this.source = source;
    }

    public static Result format(String text) {
        try {
            return run(text);
        } catch (StackOverflowError e) {
            return new Result(null, List.of(Diagnostic.error("код вложен слишком глубоко — раздели его на части", 0, 1)));
        }
    }

    private static Result run(String text) {
        String source = text.replace("\r\n", "\n");
        List<Diagnostic> problems = new ArrayList<>();
        Ast.File file = Parser.parse(source, problems);
        List<Diagnostic> errors = problems.stream().filter(Diagnostic::error).toList();
        if (!errors.isEmpty()) return new Result(null, errors);
        List<Lexer.Comment> comments = new ArrayList<>();
        List<Token> tokens = Lexer.tokens(source, new ArrayList<>(), comments);
        Formatter f = new Formatter(source);
        f.collect(file);
        return new Result(f.run(tokens, comments), List.of());
    }

    private void collect(Ast.File file) {
        for (Ast.Item item : file.items()) {
            switch (item) {
                case Ast.Event e -> {
                    items.put(start(e.span(), e.decorators()), Kind.CODE);
                    block(e.body());
                }
                case Ast.Func f -> {
                    items.put(start(f.span(), f.decorators()), Kind.CODE);
                    block(f.body());
                }
                case Ast.Global g -> items.put(g.span().start(), Kind.GLOBAL);
                case Ast.Const k -> items.put(k.span().start(), Kind.GLOBAL);
                case Ast.Import i -> items.put(i.span().start(), Kind.IMPORT);
            }
        }
    }

    private static int start(Ast.Span span, List<Ast.Decorator> decorators) {
        int at = span.start();
        for (Ast.Decorator d : decorators) at = Math.min(at, d.span().start());
        return at;
    }

    private void block(Ast.Block b) {
        if (b == null) return;
        blockOpen.add(b.span().start());
        blockClose.add(b.span().end() - 1);
        for (Ast.Stmt s : b.stmts()) {
            switch (s) {
                case Ast.If i -> {
                    for (Ast.Branch br : i.branches()) block(br.body());
                    block(i.otherwise());
                }
                case Ast.Run r -> block(r.body());
                case Ast.Repeat r -> block(r.body());
                case Ast.ForRange r -> {
                    contextualAfter.add(r.nameSpan().end());
                    if (r.step() != null) contextualAfter.add(r.to().span().end());
                    block(r.body());
                }
                case Ast.ForEach r -> {
                    contextualAfter.add(r.secondSpan().end());
                    block(r.body());
                }
                case Ast.While w -> block(w.body());
                case Ast.Forever w -> block(w.body());
                default -> { }
            }
        }
    }

    private String run(List<Token> tokens, List<Lexer.Comment> comments) {
        TreeSet<Integer> starts = new TreeSet<>();
        for (Token t : tokens) if (t.kind() == Token.Kind.IDENT) starts.add(t.start());
        for (int after : contextualAfter) {
            Integer at = starts.ceiling(after);
            if (at != null) contextual.add(at);
        }
        int ci = 0;
        int end = 0;
        for (Token t : tokens) {
            if (t.kind() == Token.Kind.EOF) break;
            while (ci < comments.size() && comments.get(ci).start() < t.start()) {
                Lexer.Comment c = comments.get(ci++);
                comment(c, end, attached(comments, ci, c, t));
                end = c.end();
            }
            token(t, end);
            end = t.end();
        }
        while (ci < comments.size()) {
            Lexer.Comment c = comments.get(ci++);
            comment(c, end, false);
            end = c.end();
        }
        StringBuilder out = new StringBuilder();
        for (Line l : lines) {
            if (l.blank && !out.isEmpty()) out.append('\n');
            String body = l.text.toString().stripTrailing();
            if (body.isEmpty()) {
                out.append('\n');
                continue;
            }
            for (String line : l.lineComment || body.contains("/*") ? List.of(INDENT.repeat(l.indent) + body) : wrapped(body, l.indent))
                out.append(line).append('\n');
        }
        return out.toString();
    }

    private static List<String> wrapped(String body, int indent) {
        String pad = INDENT.repeat(indent);
        if (pad.length() + body.length() <= WIDTH) return List.of(pad + body);
        int open = widest(body);
        if (open < 0) return List.of(pad + body);
        int close = closing(body, open);
        while ("([{".indexOf(body.charAt(open + 1)) >= 0 && closing(body, open + 1) == close - 1) {
            open++;
            close--;
        }
        List<String> items = items(body.substring(open + 1, close));
        if (items.size() < 2 && !(items.size() == 1 && widest(items.get(0)) >= 0)) return List.of(pad + body);
        boolean grouping = grouping(body, open);
        List<String> out = new ArrayList<>();
        out.addAll(wrapped(body.substring(0, open + 1), indent));
        String inner = INDENT.repeat(indent + 1);
        StringBuilder row = new StringBuilder();
        for (String item : items) {
            String cell = grouping ? item : item + ",";
            if (!row.isEmpty() && inner.length() + row.length() + 1 + cell.length() > WIDTH) {
                out.add(inner + row);
                row.setLength(0);
            }
            if (inner.length() + cell.length() > WIDTH) {
                if (!row.isEmpty()) out.add(inner + row);
                row.setLength(0);
                out.addAll(wrapped(cell, indent + 1));
                continue;
            }
            if (!row.isEmpty()) row.append(' ');
            row.append(cell);
        }
        if (!row.isEmpty()) out.add(inner + row);
        out.add(pad + body.substring(close));
        return out;
    }

    private static boolean grouping(String body, int open) {
        if (body.charAt(open) != '(') return false;
        int end = open;
        while (end > 0 && body.charAt(end - 1) == ' ') end--;
        if (end < open) return true;
        int start = end;
        while (start > 0 && (Character.isLetterOrDigit(body.charAt(start - 1)) || body.charAt(start - 1) == '_')) start--;
        if (start == end) return end == 0 || body.charAt(end - 1) != '`';
        return Keywords.reserved(body.substring(start, end));
    }

    private static int widest(String s) {
        int best = -1, size = 0, depth = 0;
        for (int i = 0; i < s.length(); i++) {
            i = skip(s, i);
            if (i >= s.length()) break;
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                if (depth++ == 0) {
                    int close = closing(s, i);
                    if (close - i > size && close > 0) {
                        best = i;
                        size = close - i;
                    }
                }
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            }
        }
        return size > 2 ? best : -1;
    }

    private static int closing(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            i = skip(s, i);
            if (i >= s.length()) break;
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if ((c == ')' || c == ']' || c == '}') && --depth == 0) return i;
        }
        return -1;
    }

    private static int skip(String s, int i) {
        char q = s.charAt(i);
        if (q != '"' && q != '`') return i;
        for (int j = i + 1; j < s.length(); j++) {
            if (s.charAt(j) == '\\' && q == '"') j++;
            else if (s.charAt(j) == q) return j + 1 < s.length() ? j + 1 : j;
        }
        return s.length();
    }

    private static List<String> items(String inner) {
        List<String> out = new ArrayList<>();
        int depth = 0, from = 0;
        for (int i = 0; i < inner.length(); i++) {
            i = skip(inner, i);
            if (i >= inner.length()) break;
            char c = inner.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                out.add(inner.substring(from, i).strip());
                from = i + 1;
            }
        }
        String last = inner.substring(from).strip();
        if (!last.isEmpty()) out.add(last);
        return out;
    }

    private int newlines(int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) if (source.charAt(i) == '\n') n++;
        return n;
    }

    private boolean attached(List<Lexer.Comment> comments, int next, Lexer.Comment c, Token t) {
        if (items.get(t.start()) != Kind.CODE) return false;
        int from = c.end();
        for (int i = next; i < comments.size() && comments.get(i).start() < t.start(); i++) {
            if (newlines(from, comments.get(i).start()) > 1) return false;
            from = comments.get(i).end();
        }
        return newlines(from, t.start()) <= 1;
    }

    private void comment(Lexer.Comment c, int end, boolean attached) {
        String text = source.substring(c.start(), c.end()).stripTrailing();
        int gap = lines.isEmpty() && cur == null ? 1 : newlines(end, c.start());
        if (cur != null && gap == 0 && !cur.lineComment) {
            cur.text.append(' ').append(text);
        } else {
            open(attached ? Kind.CODE : Kind.COMMENT, gap > 1, continuation(null));
            cur.text.append(text);
            if (attached) cur.attached = true;
        }
        if (text.startsWith("//")) {
            cur.lineComment = true;
            pending = true;
        }
        lastWasOpen = false;
    }

    private void token(Token t, int end) {
        String raw = source.substring(t.start(), t.end());
        boolean open = t.is("{") && blockOpen.contains(t.start());
        boolean close = t.is("}") && blockClose.contains(t.start());
        if (t.is(";") && brackets.isEmpty()) {
            pending = true;
            return;
        }
        int gap = newlines(end, t.start());
        boolean empty = close && lastWasOpen && cur != null && !cur.lineComment;
        boolean elseJoin = t.keyword("else") && prev != null && prev.is("}") && blockClose.contains(prev.start())
                && cur != null && !cur.lineComment;
        if (close) depth--;
        boolean fresh = cur == null || cur.lineComment || (!empty && !elseJoin && (pending || close || (gap > 0 && !open)));
        if (fresh) {
            Kind kind = depth == 0 && brackets.isEmpty()
                    ? items.containsKey(t.start()) ? items.get(t.start()) : t.is("@") ? Kind.DECORATOR : Kind.OTHER
                    : Kind.OTHER;
            open(kind, gap > 1 && !close, continuation(t));
            cur.decorator = kind != Kind.OTHER && t.is("@") || kind == Kind.DECORATOR;
        } else if (!empty && space(t)) {
            String original = source.substring(end, t.start());
            boolean table = prev.is(",") && !brackets.isEmpty() && brackets.peek() != lines.size() - 1
                    && original.length() > 1 && original.isBlank();
            cur.text.append(table ? original : " ");
        }
        if (t.is(":") && ternary()) questions.pop();
        else if (t.is("?")) questions.push(brackets.size());
        cur.text.append(raw);
        cur.last = t;
        pending = false;
        lastWasOpen = false;
        if (open) {
            depth++;
            pending = true;
            lastWasOpen = true;
        } else if (close) {
            pending = true;
            cur.closesItem = depth == 0;
        } else if (t.is("(") || t.is("[") || t.is("{")) {
            brackets.push(lines.size() - 1);
        } else if ((t.is(")") || t.is("]") || t.is("}")) && !brackets.isEmpty()) {
            brackets.pop();
        }
        beforePrev = prev;
        prev = t;
    }

    private int continuation(Token first) {
        Set<Integer> distinct = new HashSet<>(brackets);
        boolean closer = first != null && (first.is(")") || first.is("]") || (first.is("}") && !blockClose.contains(first.start())));
        if (closer && !brackets.isEmpty()) distinct.remove(brackets.peek());
        int n = distinct.size();
        Token last = cur == null || cur.lineComment ? null : cur.last;
        if (last != null && (last.kind() == Token.Kind.SYMBOL && BINARY.contains(last.text()) || last.keyword("and", "or")))
            n++;
        return n;
    }

    private void open(Kind kind, boolean sourceBlank, int extra) {
        Line l = new Line(depth + extra, kind);
        Line before = lines.isEmpty() ? null : lines.get(lines.size() - 1);
        l.family = before != null && !brackets.isEmpty() ? before.family : kind;
        l.blank = before != null && blank(l, before, sourceBlank);
        lines.add(l);
        cur = l;
        pending = false;
    }

    private boolean blank(Line l, Line before, boolean sourceBlank) {
        boolean top = depth == 0 && brackets.isEmpty();
        if (!top) return sourceBlank && !prevOpenedBlock(before);
        if (before.closesItem) return true;
        if (before.decorator) return false;
        if (before.attached) return false;
        return switch (l.kind) {
            case CODE -> before.kind == Kind.COMMENT ? sourceBlank : true;
            case GLOBAL, IMPORT -> before.family == l.kind || before.kind == Kind.COMMENT ? sourceBlank : true;
            default -> sourceBlank;
        };
    }

    private boolean prevOpenedBlock(Line before) {
        return before.last != null && before.last.is("{") && blockOpen.contains(before.last.start());
    }

    private boolean space(Token t) {
        Token a = prev;
        if (a == null) return false;
        boolean aMapOpen = a.is("{") && !blockOpen.contains(a.start());
        boolean bMapClose = t.is("}") && !blockClose.contains(t.start());
        if (a.is("(") || a.is("[") || aMapOpen || a.is("@") || a.is(".") || a.is("..")) return false;
        if (t.is(":") && ternary()) return true;
        if (t.is(")") || t.is("]") || bMapClose || t.is(",") || t.is(":") || t.is(".") || t.is("..") || t.is(";"))
            return false;
        if (t.is("[") && operatorIn(a)) return true;
        if (t.is("[") && ((a.kind() == Token.Kind.IDENT && !keyword(a)) || a.kind() == Token.Kind.NAME
                || a.is(")") || a.is("]"))) return false;
        if (t.is("(")) {
            boolean name = beforePrev != null && (beforePrev.is("@") || beforePrev.is("."));
            if ((keyword(a) || operatorIn(a)) && !name) return true;
            return !(a.kind() == Token.Kind.IDENT || a.kind() == Token.Kind.NAME || a.is(")"));
        }
        if (a.is("-") && unary(beforePrev)) return false;
        return true;
    }

    private boolean operatorIn(Token t) {
        if (!t.keyword("in") || beforePrev == null) return false;
        return beforePrev.kind() == Token.Kind.IDENT && !keyword(beforePrev) || beforePrev.kind() == Token.Kind.NAME
                || beforePrev.kind() == Token.Kind.NUMBER || beforePrev.kind() == Token.Kind.STRING
                || beforePrev.is(")") || beforePrev.is("]");
    }

    private boolean keyword(Token t) {
        if (t.kind() != Token.Kind.IDENT || t.text().equals("_")) return false;
        return Keywords.reserved(t.text()) || Keywords.means(t.text(), "in", "step") && contextual.contains(t.start());
    }

    private boolean ternary() {
        return !questions.isEmpty() && questions.peek() == brackets.size();
    }

    private boolean unary(Token before) {
        if (before == null) return true;
        if (before.kind() == Token.Kind.NUMBER || before.kind() == Token.Kind.STRING || before.kind() == Token.Kind.NAME)
            return false;
        if (before.kind() == Token.Kind.IDENT) return keyword(before) && !before.keyword("else");
        if (before.is(")") || before.is("]")) return false;
        if (before.is("}")) return blockClose.contains(before.start());
        return true;
    }
}
