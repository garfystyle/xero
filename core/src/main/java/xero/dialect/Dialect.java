package xero.dialect;

import xero.compile.Compiler;
import xero.compile.Items;
import xero.ide.Service;
import xero.json.Json;
import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public abstract class Dialect {
    public record Built(Map<String, Object> module, List<Diagnostic> diagnostics) {
        public boolean ok() {
            if (module == null) return false;
            for (Diagnostic d : diagnostics) if (d.error()) return false;
            return true;
        }
    }

    public record Decompiled(String text, List<String> notes) {}

    public record Occurrence(int start, int end) {}

    public record Spot(int start, int end, String kind) {}

    protected static final String HELD = "xero_value";

    private static final int LIMIT = 200;

    private Map<String, Byte> lexicon;
    private Map<String, Service.Item> words;

    public abstract String id();

    public abstract String title();

    public abstract String extension();

    public String author() { return ""; }

    public String link() { return ""; }

    public abstract Syntax syntax();

    public abstract String sample();

    public abstract Built build(String file, String text, Compiler.Loader loader);

    public abstract Decompiled decompile(Map<String, Object> module);

    public List<Service.Item> vocabulary() { return List.of(); }

    protected Map<String, String> factories() { return Map.of(); }

    protected String holder(String expression) { return null; }

    protected String held(String text) { return null; }

    public boolean editsValues() { return !factories().isEmpty(); }

    public List<Spot> spots(String line, byte[] ink) {
        List<Spot> out = new ArrayList<>();
        Map<String, String> known = factories();
        if (known.isEmpty()) return out;
        int n = line.length();
        for (int i = 0; i < n; ) {
            boolean code = i >= ink.length || ink[i] != Ink.STRING && ink[i] != Ink.COMMENT && ink[i] != Ink.ESCAPE;
            if (!code || !Ink.wordStart(line.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && Ink.wordPart(line.charAt(j))) j++;
            String kind = known.get(line.substring(i, j));
            int open = j;
            while (open < n && line.charAt(open) == ' ') open++;
            boolean call = open < n && (line.charAt(open) == '(' || line.charAt(open) == '{');
            if (kind != null && call) out.add(new Spot(i, close(line, ink, open), kind));
            i = j;
        }
        return out;
    }

    private static int close(String line, byte[] ink, int open) {
        int depth = 0;
        for (int i = open; i < line.length(); i++) {
            if (i < ink.length && (ink[i] == Ink.STRING || ink[i] == Ink.ESCAPE || ink[i] == Ink.COMMENT)) continue;
            char c = line.charAt(i);
            if (c == '(' || c == '{' || c == '[') depth++;
            else if ((c == ')' || c == '}' || c == ']') && --depth == 0) return i + 1;
        }
        return -1;
    }

    private static int codeEnd(String e, int open, int end) {
        int depth = 0;
        for (int i = open; i < end; i++) {
            char c = e.charAt(i);
            if (c == '"') {
                i++;
                while (i < end && e.charAt(i) != '"') i += e.charAt(i) == '\\' ? 2 : 1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String spelled(String e) {
        int quote = e.indexOf('"');
        if (quote < 0 || !e.contains("${") || !e.endsWith("\"") || e.length() - quote < 2) return e;
        for (int i = 0; i < quote; i++) if (!Character.isLetter(e.charAt(i))) return e;
        StringBuilder out = new StringBuilder(e.substring(0, quote + 1));
        int end = e.length() - 1;
        for (int i = quote + 1; i < end; i++) {
            char c = e.charAt(i);
            if (c == '\\' && i + 1 < end) {
                if (e.charAt(i + 1) == '$') return null;
                out.append(c).append(e.charAt(++i));
            } else if (c == '$' && e.charAt(i + 1) == '{') {
                int close = codeEnd(e, i + 1, end);
                if (close < 0) return e;
                out.append("\\$");
                for (int k = i + 1; k <= close; k++) {
                    char x = e.charAt(k);
                    if (x == '"' || x == '\\') out.append('\\');
                    out.append(x);
                }
                i = close;
            } else if (c == '"') {
                return e;
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String code(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (!text.startsWith("\\${", i)) {
                out.append(text.charAt(i));
                continue;
            }
            out.append("${");
            int depth = 1;
            for (i += 3; i < text.length() && depth > 0; i++) {
                char c = text.charAt(i);
                if (c == '\\' && i + 1 < text.length()) c = text.charAt(++i);
                else if (c == '{') depth++;
                else if (c == '}') depth--;
                out.append(c);
            }
            i--;
        }
        return out.toString();
    }

    public Map<String, Object> valueOf(String expression) {
        String spelled = syntax().dollar() ? spelled(expression) : expression;
        String program = spelled == null ? null : holder(spelled);
        if (program == null) return null;
        Built built = build("значение." + extension(), program, null);
        if (!built.ok()) return null;
        for (Object h : Json.array(built.module().get("handlers"))) {
            Object ops = Json.object(h).get("operations");
            if (!(ops instanceof List<?>)) continue;
            for (Object op : Json.array(ops)) {
                Object values = Json.object(op).get("values");
                if (!(values instanceof List<?>)) continue;
                for (Object v : Json.array(values)) {
                    Map<String, Object> entry = Json.object(v);
                    if ("value".equals(entry.get("name"))) return Json.object(entry.get("value"));
                }
            }
        }
        return null;
    }

    public String valueText(Map<String, Object> value) {
        Map<String, Object> variable = new LinkedHashMap<>();
        variable.put("type", "variable");
        variable.put("variable", HELD);
        variable.put("scope", "local");
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("name", "variable");
        first.put("value", variable);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("name", "value");
        second.put("value", plain(value));
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("action", "set_variable_value");
        op.put("values", new ArrayList<Object>(List.of(first, second)));
        Map<String, Object> handler = new LinkedHashMap<>();
        handler.put("type", "event");
        handler.put("event", "player_join");
        handler.put("position", new Json.Num("0"));
        handler.put("operations", new ArrayList<Object>(List.of(op)));
        Map<String, Object> module = new LinkedHashMap<>();
        module.put("handlers", new ArrayList<Object>(List.of(handler)));
        String text = oneLine(held(decompile(module).text()));
        if (text == null || !"text".equals(value.get("type"))) return text;
        if (syntax().dollar()) text = code(text);
        if ("plain".equals(value.get("parsing")) && text.startsWith("\"") && syntax().stringPrefix("plain")) text = "plain" + text;
        return text;
    }

    private static Map<String, Object> plain(Map<String, Object> value) {
        if (!"item".equals(value.get("type")) || !(value.get("item") instanceof String encoded)) return value;
        Map<String, Object> out = new LinkedHashMap<>(value);
        out.put("item", Items.canonical(encoded));
        return out;
    }

    private static String oneLine(String s) {
        if (s == null) return null;
        StringBuilder out = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < s.length()) out.append(s.charAt(++i));
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') quote = c;
            if (c != '\n') {
                out.append(c);
                continue;
            }
            while (i + 1 < s.length() && s.charAt(i + 1) == ' ') i++;
            char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
            char last = out.isEmpty() ? 0 : out.charAt(out.length() - 1);
            boolean closing = next == ')' || next == '}' || next == ']';
            if (closing && last == ',') out.setLength(out.length() - 1);
            else if (!closing && last != '(' && last != '{' && last != '[') out.append(' ');
        }
        String done = out.toString().strip();
        return done.endsWith(",") ? done.substring(0, done.length() - 1) : done;
    }

    public List<Service.Symbol> symbols(String text) { return List.of(); }

    public String format(String text) { return Reindent.apply(syntax(), text); }

    public Map<String, Byte> lexicon() {
        if (lexicon != null) return lexicon;
        Map<String, Byte> out = new LinkedHashMap<>();
        for (Service.Item item : vocabulary()) {
            byte kind = switch (item.kind()) {
                case "action" -> Ink.ACTION;
                case "condition" -> Ink.CONDITION;
                case "event" -> Ink.EVENT;
                case "value" -> Ink.VALUE;
                case "target" -> Ink.TARGET;
                case "type" -> Ink.TYPE;
                default -> Ink.PLAIN;
            };
            if (kind != Ink.PLAIN) out.putIfAbsent(item.label(), kind);
        }
        for (String w : syntax().keywords()) out.put(w, Ink.KEYWORD);
        for (String w : syntax().literals()) out.put(w, Ink.LITERAL);
        for (String w : syntax().types()) out.put(w, Ink.TYPE);
        lexicon = out;
        return out;
    }

    protected Map<String, Service.Item> words() {
        if (words != null) return words;
        Map<String, Service.Item> out = new LinkedHashMap<>();
        for (Service.Item item : vocabulary()) out.putIfAbsent(item.label(), item);
        words = out;
        return out;
    }

    public Service.Completion complete(String text, int offset) {
        int from = wordStart(text, offset);
        String typed = text.substring(from, offset).toLowerCase(Locale.ROOT);
        List<Service.Item> found = new ArrayList<>();
        for (String w : syntax().keywords())
            if (!typed.isEmpty() && w.startsWith(typed)) found.add(new Service.Item(w, "keyword", null, null, false, 0));
        for (Service.Item item : vocabulary()) if (matches(item, typed)) found.add(item);
        Set<String> seen = new LinkedHashSet<>();
        for (Occurrence o : identifiers(text)) {
            String w = text.substring(o.start(), o.end());
            if (o.end() == offset || !w.toLowerCase(Locale.ROOT).startsWith(typed) || !seen.add(w)) continue;
            if (syntax().keywords().contains(w) || words().containsKey(w)) continue;
            found.add(new Service.Item(w, "variable", null, null, false, 0));
        }
        found.sort(Comparator.comparingInt((Service.Item i) -> rank(i, typed)).thenComparing(Service.Item::label));
        return new Service.Completion(from, found.size() > LIMIT ? new ArrayList<>(found.subList(0, LIMIT)) : found);
    }

    private static boolean matches(Service.Item item, String typed) {
        if (typed.isEmpty()) return false;
        if (item.label().toLowerCase(Locale.ROOT).contains(typed)) return true;
        return item.detail() != null && item.detail().toLowerCase(Locale.ROOT).contains(typed);
    }

    private static int rank(Service.Item item, String typed) {
        String label = item.label().toLowerCase(Locale.ROOT);
        int base = label.startsWith(typed) ? 0 : label.contains(typed) ? 10 : 20;
        return base + ("keyword".equals(item.kind()) ? 0 : "variable".equals(item.kind()) ? 1 : 2);
    }

    public Service.Signature signature(String text, int offset) {
        int depth = 0, active = 0;
        for (int i = offset - 1; i >= 0 && i >= offset - 2000; i--) {
            char c = text.charAt(i);
            if (c == ')' || c == ']' || c == '}') depth++;
            else if (c == '(' || c == '[' || c == '{') {
                if (depth > 0) {
                    depth--;
                    continue;
                }
                if (c != '(') return null;
                int end = i;
                while (end > 0 && text.charAt(end - 1) == ' ') end--;
                Service.Item item = words().get(text.substring(wordStart(text, end), end));
                return item == null ? null : signatureOf(item, active);
            } else if (c == ',' && depth == 0) active++;
        }
        return null;
    }

    protected Service.Signature signatureOf(Service.Item item, int active) { return null; }

    public String hover(String text, int offset) {
        Occurrence at = wordAt(text, offset);
        if (at == null) return null;
        String word = text.substring(at.start(), at.end());
        Service.Item item = words().get(word);
        if (item != null) return item.doc() != null ? item.doc() : item.detail();
        for (Service.Symbol s : symbols(text))
            if (s.name().equals(word)) return "**" + s.name() + "** · " + symbolKind(s.kind());
        return null;
    }

    private static String symbolKind(String kind) {
        return switch (kind) {
            case "event" -> "событие";
            case "function" -> "функция";
            case "process" -> "процесс";
            default -> kind;
        };
    }

    public Service.Span definition(String text, int offset) {
        Occurrence at = wordAt(text, offset);
        if (at == null) return null;
        String word = text.substring(at.start(), at.end());
        for (Service.Symbol s : symbols(text))
            if (s.name().equals(word)) return new Service.Span(s.nameStart(), s.nameEnd());
        for (Occurrence o : identifiers(text))
            if (o.end() - o.start() == word.length() && text.startsWith(word, o.start()))
                return new Service.Span(o.start(), o.end());
        return null;
    }

    public List<Service.Span> references(String text, int offset) {
        Occurrence at = wordAt(text, offset);
        if (at == null) return List.of();
        String word = text.substring(at.start(), at.end());
        List<Service.Span> out = new ArrayList<>();
        for (Occurrence o : identifiers(text))
            if (o.end() - o.start() == word.length() && text.startsWith(word, o.start()))
                out.add(new Service.Span(o.start(), o.end()));
        return out;
    }

    public Service.Renamed rename(String text, int offset, String wanted) {
        List<Service.Span> found = references(text, offset);
        if (found.isEmpty()) return new Service.Renamed(List.of(), "здесь нечего переименовать");
        if (wanted.isEmpty() || !Ink.wordStart(wanted.charAt(0))) return new Service.Renamed(List.of(), "такое имя не годится");
        for (int i = 0; i < wanted.length(); i++)
            if (!Ink.wordPart(wanted.charAt(i))) return new Service.Renamed(List.of(), "такое имя не годится");
        List<Service.Edit> edits = new ArrayList<>();
        for (Service.Span s : found) edits.add(new Service.Edit(s.start(), s.end(), wanted));
        return new Service.Renamed(edits, null);
    }

    public List<Diagnostic.Fix> actions(String text, int start, int end) { return List.of(); }

    public List<Service.Mark> variables(String text) { return List.of(); }

    protected int wordStart(String text, int offset) {
        int i = Math.min(offset, text.length());
        String joiner = syntax().joiner();
        while (i > 0) {
            char c = text.charAt(i - 1);
            if (Ink.wordPart(c)) {
                i--;
                continue;
            }
            if (joiner != null && i >= joiner.length() && text.startsWith(joiner, i - joiner.length())) {
                i -= joiner.length();
                continue;
            }
            break;
        }
        return i;
    }

    protected Occurrence wordAt(String text, int offset) {
        int at = Math.min(offset, text.length());
        for (Occurrence o : identifiers(text)) {
            if (at >= o.start() && at <= o.end()) return o;
            if (o.start() > at) break;
        }
        return null;
    }

    public List<Occurrence> identifiers(String text) {
        List<Occurrence> out = new ArrayList<>();
        Map<String, Byte> empty = Map.of();
        int state = Ink.NORMAL, from = 0;
        Syntax s = syntax();
        while (from <= text.length()) {
            int nl = text.indexOf('\n', from);
            int end = nl < 0 ? text.length() : nl;
            String line = text.substring(from, end);
            byte[] ink = new byte[line.length()];
            state = Ink.scan(s, empty, line, state, ink);
            int i = 0;
            while (i < line.length()) {
                boolean code = ink[i] == Ink.PLAIN || ink[i] == Ink.FUNCTION;
                if (!code || !Ink.wordStart(line.charAt(i))) {
                    i++;
                    continue;
                }
                int j = i;
                while (j < line.length() && Ink.wordPart(line.charAt(j))) j++;
                out.add(new Occurrence(from + i, from + j));
                i = j;
            }
            if (nl < 0) break;
            from = nl + 1;
        }
        return out;
    }
}
