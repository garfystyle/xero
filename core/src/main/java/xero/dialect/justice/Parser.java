package xero.dialect.justice;

import xero.compile.Items;
import xero.compile.Nbt;
import xero.data.Data;
import xero.dialect.justice.Lexer.Kind;
import xero.dialect.justice.Lexer.Token;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Parser {
    record Handler(String kind, String name, int start, int end, int nameStart, int nameEnd) {}

    record Result(Map<String, Object> module, List<Handler> handlers) {}

    private static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Failure() { super(null, null, false, false); }
    }

    private static final Set<String> KINDS = Set.of("event", "function", "process");
    private static final Set<String> BUILT_IN = Set.of("call_function", "start_process", "else", "empty");
    private static final Map<String, String> SCOPES = Map.of("l", "local", "g", "game", "s", "save",
            "local", "local", "game", "game", "save", "save", "line", "line");
    private static final Map<String, String> PARSINGS = Map.of("p", "plain", "l", "legacy", "m", "minimessage", "j", "json");
    private static final int DEEPEST = 200;
    private static final int NEAR = 3;
    static final String ZERO = "0.0";
    static final String ONE = "1.0";
    static final int DEFAULT_COLOR = 0xFF0000;
    static final String DEFAULT_MATERIAL = "STONE";

    private final Data data;
    private final Lexer lexer;
    private final List<Diagnostic> diagnostics;
    private int depth;

    private Parser(String text, Data data, List<Diagnostic> diagnostics) {
        this.data = data;
        this.diagnostics = diagnostics;
        this.lexer = new Lexer(text, diagnostics);
    }

    static Result parse(String text, Data data, List<Diagnostic> diagnostics) {
        return new Parser(text, data, diagnostics).file();
    }

    static Object value(String text, Data data) {
        List<Diagnostic> problems = new ArrayList<>();
        Parser p = new Parser(text, data, problems);
        try {
            Object v = p.value();
            for (Diagnostic d : problems) if (d.error()) return null;
            return p.lexer.peek().kind() == Kind.EOF ? v : null;
        } catch (Failure f) {
            return null;
        }
    }

    private Result file() {
        List<Object> handlers = new ArrayList<>();
        List<Handler> found = new ArrayList<>();
        int position = 0;
        while (lexer.peek().kind() != Kind.EOF) {
            Token before = lexer.peek();
            try {
                Integer at = line();
                if (at != null) position = at;
                Map<String, Object> h = handler(position, found);
                handlers.add(h);
                position++;
            } catch (Failure f) {
                depth = 0;
                skipTop(before);
            }
        }
        return new Result(obj("handlers", handlers), found);
    }

    private Integer line() {
        Integer at = null;
        while (lexer.peek().is("@")) {
            lexer.next();
            Token name = lexer.peek();
            if (!name.word("line")) throw fail("здесь бывает только пометка @line(номер)", name);
            lexer.next();
            expect("(", "@line(номер): нужна «(»");
            Token n = lexer.next();
            if (n.kind() != Kind.NUMBER || !n.text().matches("-?\\d{1,9}")) throw fail("@line(номер): нужно целое число", n);
            at = Integer.parseInt(n.text());
            expect(")", "ожидалась «)»");
        }
        return at;
    }

    private Map<String, Object> handler(int position, List<Handler> found) {
        Token first = lexer.next();
        if (!first.named()) throw fail("ожидалось имя строки: event, function или process", first);
        if (first.word("raw") && lexer.peek().is("{") && lexer.after().kind() == Kind.STRING) {
            if (!(rawBody() instanceof Map<?, ?> raw)) throw fail("строка в raw{…} — это JSON-объект", first);
            Map<String, Object> line = new LinkedHashMap<>(Json.object(raw));
            line.put("position", number(Integer.toString(position)));
            return line;
        }
        String kind = "function";
        Token name = first;
        if (first.kind() == Kind.WORD && KINDS.contains(first.text()) && lexer.peek().named()) {
            kind = first.text();
            name = lexer.next();
        }
        List<Object> values = lexer.peek().is("(") ? args(null) : new ArrayList<>();
        if (kind.equals("event") && !values.isEmpty())
            diagnostics.add(Diagnostic.error("у события нет значений в скобках", name.start(), name.end()));
        if (kind.equals("event") && !data.events().containsKey(name.text()))
            unknown("события", name, data.events().keySet());
        expect("{", "ожидалась «{»");
        List<Object> ops = body();
        Token close = lexer.peek();
        int end = close.is("}") ? close.end() : close.start();
        if (close.is("}")) lexer.next();
        else report("блок не закрыт: ожидалась «}»", close);
        found.add(new Handler(kind, name.text(), first.start(), end, name.start(), name.end()));
        if (kind.equals("event")) return obj("type", kind, "event", name.text(), "position", number(Integer.toString(position)), "operations", ops);
        return obj("type", kind, "name", name.text(), "values", values, "position", number(Integer.toString(position)), "operations", ops);
    }

    private List<Object> body() {
        List<Object> ops = new ArrayList<>();
        while (!lexer.peek().is("}") && lexer.peek().kind() != Kind.EOF) {
            int saved = depth;
            Token before = lexer.peek();
            try {
                ops.add(operation());
            } catch (Failure f) {
                depth = saved;
                skipOperation(before);
            }
        }
        return ops;
    }

    private Map<String, Object> operation() {
        enter(lexer.peek());
        try {
            Token first = lexer.peek();
            if (first.word("raw") && lexer.after().is("{")) return rawOperation();
            boolean inverted = false, otherInverted = false;
            Token name = expectName("ожидалось действие");
            if (name.word("not")) {
                inverted = true;
                name = expectName("после not нужно действие");
            }
            Token delegate = null;
            if (lexer.peek().named()) {
                delegate = lexer.next();
                if (delegate.word("not")) {
                    otherInverted = true;
                    delegate = expectName("после not нужно условие");
                }
            }
            String selector = null;
            Token selectorToken = null;
            if (lexer.peek().is("<")) {
                lexer.next();
                selectorToken = expectName("ожидалась цель: <default>");
                selector = selectorToken.text();
                expect(">", "ожидалась «>»");
            }
            checkAction(name, delegate, selectorToken);
            Data.Action owner = data.actions().get(delegate != null ? delegate.text() : name.text());
            List<Object> values = lexer.peek().is("(") ? args(owner) : new ArrayList<>();
            Map<String, Object> op = obj("action", name.text());
            if (delegate != null) op.put("conditional", obj("action", delegate.text(), "is_inverted", otherInverted));
            if (inverted) op.put("is_inverted", true);
            if (selector != null) op.put("selection", obj("type", selector));
            op.put("values", values);
            finish(op);
            return op;
        } finally {
            depth--;
        }
    }

    private void finish(Map<String, Object> op) {
        if (lexer.peek().is("{")) {
            lexer.next();
            op.put("operations", body());
            if (lexer.peek().is("}")) lexer.next();
            else report("блок не закрыт: ожидалась «}»", lexer.peek());
        } else if (lexer.peek().is(";")) {
            lexer.next();
        } else {
            report("после действия нужна «;» или тело в { }", lexer.peek());
        }
    }

    private Map<String, Object> rawOperation() {
        Token first = lexer.next();
        Object raw = rawBody();
        if (!(raw instanceof Map<?, ?>)) throw fail("действие в raw{…} — это JSON-объект", first);
        Map<String, Object> op = new LinkedHashMap<>(Json.object(raw));
        finish(op);
        return op;
    }

    private void checkAction(Token name, Token delegate, Token selector) {
        if (!data.actions().containsKey(name.text()) && !BUILT_IN.contains(name.text()))
            unknown("действия", name, data.actions().keySet());
        if (delegate != null && !data.actions().containsKey(delegate.text()))
            unknown("условия", delegate, data.actions().keySet());
        Data.Action owner = data.actions().get(delegate != null ? delegate.text() : name.text());
        if (selector == null || owner == null || owner.family() == null) return;
        List<String> ids = new ArrayList<>();
        for (Data.Selector s : data.selectors(owner.family())) ids.add(s.id());
        if (!ids.contains(selector.text())) unknown("цели", selector, ids);
    }

    private void unknown(String what, Token at, Collection<String> known) {
        diagnostics.add(new Diagnostic(false, "нет " + what + " «" + at.text() + "» — уйдёт как есть", at.start(),
                Math.max(at.end(), at.start() + 1), near(at.text(), known)));
    }

    private static String near(String word, Collection<String> known) {
        String best = null;
        int score = NEAR + 1;
        for (String k : known) {
            if (Math.abs(k.length() - word.length()) >= score) continue;
            int d = distance(word, k);
            if (d < score) {
                score = d;
                best = k;
            }
        }
        return best == null ? null : "возможно, вы имели в виду «" + best + "»";
    }

    private static int distance(String a, String b) {
        int[] row = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) row[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int diagonal = row[0];
            row[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int up = row[j];
                row[j] = Math.min(Math.min(up, row[j - 1]) + 1, diagonal + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                diagonal = up;
            }
        }
        return row[b.length()];
    }

    private List<Object> args(Data.Action owner) {
        expect("(", "ожидалась «(»");
        List<Object> out = new ArrayList<>();
        while (!lexer.peek().is(")") && lexer.peek().kind() != Kind.EOF) {
            Token name = expectName("ожидалось имя аргумента: имя=значение");
            expect("=", "после имени аргумента нужно «=»: " + name.text() + "=значение");
            Object v = value();
            if (owner != null) checkArg(owner, name, v);
            out.add(obj("name", name.text(), "value", v));
            if (lexer.peek().is(")")) break;
            expect(",", "ожидалась «)» или «,»");
        }
        expect(")", "ожидалась «)» или «,»");
        return out;
    }

    private void checkArg(Data.Action owner, Token name, Object v) {
        Data.Setting setting = owner.setting(name.text());
        if (setting == null && owner.arg(name.text()) == null) {
            List<String> known = new ArrayList<>();
            for (Data.Arg a : owner.args()) known.add(a.id());
            for (Data.Setting s : owner.settings()) known.add(s.id());
            unknown("аргумента", name, known);
        } else if (setting != null && v instanceof Map<?, ?> m && "enum".equals(m.get("type")) && m.get("enum") instanceof String option
                && !setting.options().containsKey(option.toLowerCase(Locale.ROOT))) {
            diagnostics.add(new Diagnostic(false, "у «" + name.text() + "» нет варианта «" + option + "»", name.start(), name.end(),
                    "есть: " + String.join(", ", setting.options().keySet()).toUpperCase(Locale.ROOT)));
        }
    }

    private Object value() {
        enter(lexer.peek());
        try {
            Token t = lexer.next();
            return switch (t.kind()) {
                case NUMBER -> obj("type", "number", "number", number(t));
                case PLACEHOLDER -> obj("type", "number", "number", t.text());
                case STRING -> text(t.text(), "plain");
                case ENUM -> obj("type", "enum", "enum", t.text());
                case NAME -> variable(t.text(), "local");
                case WORD -> word(t);
                case SYMBOL -> symbol(t);
                default -> throw fail(t.kind() == Kind.EOF ? "файл оборвался" : "ожидалось значение", t);
            };
        } finally {
            depth--;
        }
    }

    private Object symbol(Token t) {
        if (t.is("{")) return array();
        if (t.is("<")) return gameValue();
        throw fail("ожидалось значение, а не «" + t.text() + "»", t);
    }

    private Object word(Token t) {
        Token after = lexer.peek();
        if (t.text().equals("_")) return obj();
        if (after.kind() == Kind.STRING) {
            String parsing = PARSINGS.get(t.text());
            lexer.next();
            if (parsing == null) throw fail("разметка текста пишется буквой p, l, m или j", t);
            return text(after.text(), parsing);
        }
        if (after.kind() == Kind.NAME) {
            String scope = SCOPES.get(t.text());
            lexer.next();
            if (scope == null) throw fail("область переменной пишется буквой l, g, s или словом line", t);
            return variable(after.text(), scope);
        }
        if (after.is("{")) return constructor(t);
        return variable(t.text(), "local");
    }

    private Object constructor(Token t) {
        return switch (t.text()) {
            case "location" -> location();
            case "vector" -> vector();
            case "item" -> item(t);
            case "block" -> block();
            case "sound" -> sound();
            case "potion" -> potion();
            case "particle" -> particle();
            case "number" -> number();
            case "enum" -> enumeration();
            case "map" -> map();
            case "raw" -> {
                Object raw = rawBody();
                if (raw == null) throw fail("значение в raw{…} не бывает null", t);
                yield raw;
            }
            default -> typed(t.text());
        };
    }

    private Object rawBody() {
        expect("{", "ожидалась «{»");
        Token json = expect(Kind.STRING, "raw{ \"…JSON…\" }: нужна строка с JSON");
        expect("}", "ожидалась «}»");
        try {
            return Json.parse(json.text());
        } catch (Json.Error | IllegalArgumentException | StackOverflowError e) {
            throw fail("в raw{…} не JSON: " + e.getMessage(), json);
        }
    }

    private List<Json.Num> numbers() {
        List<Json.Num> out = new ArrayList<>();
        while (!lexer.peek().is("}") && lexer.peek().kind() != Kind.EOF) {
            out.add(number(expect(Kind.NUMBER, "здесь нужно число")));
            if (lexer.peek().is("}")) break;
            expect(",", "ожидалась «,» или «}»");
        }
        return out;
    }

    private Object location() {
        Token open = expect("{", "ожидалась «{»");
        List<Json.Num> n = numbers();
        expect("}", "ожидалась «}»");
        if (n.size() != 3 && n.size() != 5) throw fail("location{ x, y, z } или location{ x, y, z, yaw, pitch }", open);
        Json.Num zero = new Json.Num(ZERO);
        return obj("type", "location", "x", n.get(0), "y", n.get(1), "z", n.get(2), "yaw", n.size() == 5 ? n.get(3) : zero,
                "pitch", n.size() == 5 ? n.get(4) : zero);
    }

    private Object vector() {
        Token open = expect("{", "ожидалась «{»");
        List<Json.Num> n = numbers();
        expect("}", "ожидалась «}»");
        if (n.size() != 3) throw fail("vector{ x, y, z }", open);
        return obj("type", "vector", "x", n.get(0), "y", n.get(1), "z", n.get(2));
    }

    private Object item(Token at) {
        Token open = lexer.peek();
        String body = lexer.compound();
        if (body == null) throw fail("item{ не закрыт", at);
        String inner = body.substring(1, body.length() - 1).strip();
        if (inner.isEmpty()) return obj("type", "item", "item", Items.EMPTY);
        if (inner.charAt(0) == '"') {
            String base64 = plainString(inner);
            if (base64 != null) return obj("type", "item", "item", base64);
        }
        try {
            if (!(Nbt.parse(body) instanceof Nbt.Compound given)) throw fail("предмет — это { id: …, components: … }", at);
            if (given.get("id") == null) throw fail("у предмета нужен id: item{ id: diamond }", at);
            return obj("type", "item", "item", Stacks.encode(Stacks.full(given)));
        } catch (Nbt.Error e) {
            int start = open.start() + Math.max(0, e.offset);
            diagnostics.add(Diagnostic.error("SNBT: " + e.getMessage(), start, start + 1));
            throw new Failure();
        } catch (IllegalArgumentException e) {
            throw fail("предмет не записать: " + e.getMessage(), at);
        }
    }

    private static String plainString(String quoted) {
        int close = quoted.indexOf('"', 1);
        return close == quoted.length() - 1 && quoted.indexOf('\\') < 0 ? quoted.substring(1, close) : null;
    }

    private Object block() {
        expect("{", "ожидалась «{»");
        if (lexer.peek().is("}")) {
            lexer.next();
            return obj("type", "block");
        }
        Token id = expect(Kind.STRING, "block{ \"stone\" }: нужен id блока");
        expect("}", "ожидалась «}»");
        return obj("type", "block", "block", namespaced(id.text()));
    }

    private static String namespaced(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    private Map<String, Token> fields(Set<String> allowed) {
        Map<String, Token> out = new LinkedHashMap<>();
        while (lexer.peek().is(",")) {
            lexer.next();
            if (lexer.peek().is("}")) break;
            Token key = expect(Kind.WORD, "ожидалось имя поля: имя=значение");
            if (!allowed.contains(key.text())) throw fail("нет поля «" + key.text() + "»: есть " + String.join(", ", allowed), key);
            if (out.containsKey(key.text())) throw fail("поле «" + key.text() + "» указано дважды", key);
            expect("=", "после имени поля нужно «=»");
            out.put(key.text(), fieldValue());
        }
        return out;
    }

    private Token fieldValue() {
        Token t = lexer.next();
        if (t.is("{")) {
            List<Json.Num> n = numbers();
            Token close = expect("}", "ожидалась «}»");
            StringBuilder joined = new StringBuilder();
            for (Json.Num x : n) joined.append(joined.isEmpty() ? "" : " ").append(x.text());
            return new Token(Kind.SYMBOL, joined.toString(), t.start(), close.end());
        }
        if (t.kind() != Kind.NUMBER && t.kind() != Kind.STRING && t.kind() != Kind.COLOR) throw fail("здесь нужно число или текст", t);
        return t;
    }

    private Json.Num numeric(Map<String, Token> fields, String key, String fallback) {
        Token t = fields.get(key);
        if (t == null) return new Json.Num(fallback);
        if (t.kind() != Kind.NUMBER) throw fail("«" + key + "» — число", t);
        return number(t);
    }

    private String textual(Map<String, Token> fields, String key, String fallback) {
        Token t = fields.get(key);
        if (t == null) return fallback;
        if (t.kind() != Kind.STRING) throw fail("«" + key + "» — текст в кавычках", t);
        return t.text();
    }

    private Object sound() {
        expect("{", "ожидалась «{»");
        Token id = expect(Kind.STRING, "sound{ \"entity.player.death\" }: нужен id звука");
        Map<String, Token> f = fields(Set.of("volume", "pitch", "source", "variant", "variation"));
        expect("}", "ожидалась «}»");
        String variation = textual(f, "variant", textual(f, "variation", ""));
        return obj("type", "sound", "sound", namespaced(id.text()), "pitch", numeric(f, "pitch", ONE), "volume", numeric(f, "volume", ONE),
                "variation", variation, "source", textual(f, "source", "MASTER"));
    }

    private Object potion() {
        expect("{", "ожидалась «{»");
        Token id = expect(Kind.STRING, "potion{ \"speed\" }: нужен id зелья");
        Map<String, Token> f = fields(Set.of("amplifier", "duration"));
        expect("}", "ожидалась «}»");
        return obj("type", "potion", "potion", namespaced(id.text()), "duration", numeric(f, "duration", "-1"),
                "amplifier", numeric(f, "amplifier", "0"));
    }

    private Object particle() {
        expect("{", "ожидалась «{»");
        Token id = expect(Kind.STRING, "particle{ \"FLAME\" }: нужен id частицы");
        Map<String, Token> f = fields(Set.of("material", "spread", "motion", "count", "color", "to_color", "size"));
        expect("}", "ожидалась «}»");
        List<String> extras = data.pickers().particles().getOrDefault(id.text(), List.of());
        String[] spread = triple(f.get("spread"), 2);
        Map<String, Object> o = obj("type", "particle", "particle_type", id.text(), "count", numeric(f, "count", "1"),
                "first_spread", new Json.Num(spread[0]), "second_spread", new Json.Num(spread[1]));
        if (extras.contains("Движение") || f.containsKey("motion")) {
            String[] motion = triple(f.get("motion"), 3);
            o.put("x_motion", new Json.Num(motion[0]));
            o.put("y_motion", new Json.Num(motion[1]));
            o.put("z_motion", new Json.Num(motion[2]));
        }
        if (extras.contains("Цвет") || f.containsKey("color")) o.put("color", color(f.get("color")));
        if (extras.contains("Цвет перехода") || f.containsKey("to_color")) o.put("to_color", color(f.get("to_color")));
        if (extras.contains("Размер") || f.containsKey("size")) o.put("size", numeric(f, "size", ONE));
        if (extras.contains("Материал") || f.containsKey("material")) o.put("material", textual(f, "material", DEFAULT_MATERIAL));
        return o;
    }

    private String[] triple(Token t, int size) {
        String[] out = new String[size];
        Arrays.fill(out, ZERO);
        if (t == null) return out;
        String[] given = t.kind() == Kind.SYMBOL ? t.text().split(" ") : new String[0];
        if (given.length != size) throw fail("здесь нужно " + size + " числа в { }", t);
        return given;
    }

    private Json.Num color(Token t) {
        if (t == null) return new Json.Num(Integer.toString(DEFAULT_COLOR));
        if (t.kind() == Kind.NUMBER) return number(t);
        if (t.kind() != Kind.COLOR || t.text().length() != 7) throw fail("цвет пишется #RRGGBB", t);
        return new Json.Num(Integer.toString(Integer.parseInt(t.text().substring(1), 16)));
    }

    private Object number() {
        expect("{", "ожидалась «{»");
        Token t = expect(Kind.STRING, "number{ \"…\" }: нужен текст");
        expect("}", "ожидалась «}»");
        return obj("type", "number", "number", t.text());
    }

    private Object enumeration() {
        expect("{", "ожидалась «{»");
        Token option = expect(Kind.ENUM, "enum{ 'ВАРИАНТ', переменная }: нужен вариант");
        expect(",", "enum{ 'ВАРИАНТ', переменная }: нужна переменная");
        Object bound = value();
        expect("}", "ожидалась «}»");
        if (!(bound instanceof Map<?, ?> m) || !"variable".equals(m.get("type"))) throw fail("вторым идёт переменная", option);
        return obj("type", "enum", "enum", option.text(), "variable", m.get("variable"), "scope", m.get("scope"));
    }

    private Object map() {
        expect("{", "ожидалась «{»");
        Map<String, Object> entries = new LinkedHashMap<>();
        while (!lexer.peek().is("}") && lexer.peek().kind() != Kind.EOF) {
            Token at = lexer.peek();
            String key = Json.write(value());
            expect("=", "в словаре нужно ключ=значение");
            if (entries.containsKey(key)) report("ключ повторяется", at);
            entries.put(key, value());
            if (lexer.peek().is("}")) break;
            expect(",", "ожидалась «,» или «}»");
        }
        expect("}", "ожидалась «}»");
        return obj("type", "map", "values", entries);
    }

    private Object typed(String type) {
        expect("{", "ожидалась «{»");
        Map<String, Object> o = obj("type", type);
        while (!lexer.peek().is("}") && lexer.peek().kind() != Kind.EOF) {
            Token key = expect(Kind.WORD, "ожидалось имя поля: имя=значение");
            expect("=", "после имени поля нужно «=»");
            Token v = lexer.next();
            Object field = switch (v.kind()) {
                case STRING -> v.text();
                case NUMBER -> number(v);
                case WORD -> switch (v.text()) {
                    case "true" -> Boolean.TRUE;
                    case "false" -> Boolean.FALSE;
                    case "null" -> null;
                    default -> throw fail("значение поля — текст, число, true, false или null", v);
                };
                default -> throw fail("значение поля — текст, число, true, false или null", v);
            };
            o.put(key.text(), field);
            if (lexer.peek().is("}")) break;
            expect(",", "ожидалась «,» или «}»");
        }
        expect("}", "ожидалась «}»");
        return o;
    }

    private Object array() {
        List<Object> cells = new ArrayList<>();
        while (!lexer.peek().is("}") && lexer.peek().kind() != Kind.EOF) {
            cells.add(value());
            if (lexer.peek().is("}")) break;
            expect(",", "ожидалась «,» или «}»");
        }
        expect("}", "ожидалась «}» или «,»");
        return obj("type", "array", "values", cells);
    }

    private Object gameValue() {
        String selection = "null";
        if (!lexer.peek().is(">")) selection = "{\"type\":\"" + expectName("ожидалась цель: <default>").text() + "\"}";
        expect(">", "ожидалась «>»");
        Token name = expectName("после <цель> нужно имя значения");
        if (!data.gameValues().containsKey(name.text())) unknown("игрового значения", name, data.gameValues().keySet());
        return obj("type", "game_value", "game_value", name.text(), "selection", selection);
    }

    private static Map<String, Object> text(String s, String parsing) {
        return obj("type", "text", "text", s, "parsing", parsing);
    }

    private static Map<String, Object> variable(String name, String scope) {
        return obj("type", "variable", "variable", name, "scope", scope);
    }

    private Json.Num number(Token t) {
        Json.Num n = number(t.text());
        if (n == null) {
            report("непонятное число «" + t.text() + "»", t);
            return new Json.Num("0");
        }
        return n;
    }

    private static Json.Num number(String raw) {
        String t = raw.startsWith("+") ? raw.substring(1) : raw;
        if (t.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][-+]?[0-9]+)?")) return new Json.Num(t);
        try {
            return new Json.Num(Double.toString(Double.parseDouble(t)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private void enter(Token at) {
        if (++depth > DEEPEST) {
            depth--;
            throw fail("слишком глубокая вложенность", at);
        }
    }

    private Token expectName(String message) {
        Token t = lexer.peek();
        if (!t.named()) throw fail(message, t);
        return lexer.next();
    }

    private Token expect(String symbol, String message) {
        Token t = lexer.peek();
        if (!t.is(symbol)) throw fail(message, t);
        return lexer.next();
    }

    private Token expect(Kind kind, String message) {
        Token t = lexer.peek();
        if (t.kind() != kind) throw fail(message, t);
        return lexer.next();
    }

    private void report(String message, Token at) {
        int start = at.kind() == Kind.EOF ? Math.max(0, at.start() - 1) : at.start();
        diagnostics.add(Diagnostic.error(message, start, Math.max(at.end(), start + 1)));
    }

    private Failure fail(String message, Token at) {
        report(message, at);
        return new Failure();
    }

    private void skipOperation(Token before) {
        if (lexer.peek() == before) lexer.next();
        int level = 0;
        while (lexer.peek().kind() != Kind.EOF) {
            Token t = lexer.peek();
            if (level == 0 && t.is("}")) return;
            if (level == 0 && t.is(";")) {
                lexer.next();
                return;
            }
            if (t.is("{") || t.is("(")) level++;
            if ((t.is("}") || t.is(")")) && level > 0) level--;
            lexer.next();
        }
    }

    private void skipTop(Token before) {
        if (lexer.peek() == before) lexer.next();
        Set<String> starts = new LinkedHashSet<>(KINDS);
        int level = 0;
        while (lexer.peek().kind() != Kind.EOF) {
            Token t = lexer.peek();
            if (level == 0 && (t.kind() == Kind.WORD && starts.contains(t.text()) || t.is("@"))) return;
            if (t.is("{")) level++;
            if (t.is("}")) level = Math.max(0, level - 1);
            lexer.next();
        }
    }
}
