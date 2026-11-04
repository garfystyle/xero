package xero.ide;

import xero.compile.Compiler;
import xero.compile.Decompiler;
import xero.compile.Wire;
import xero.data.Data;
import xero.data.Names;
import xero.format.Formatter;
import xero.lang.Ast;
import xero.lang.Diagnostic;
import xero.lang.Keywords;
import xero.lang.Lexer;
import xero.lang.Parser;
import xero.lang.Token;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Service {
    public record Item(String label, String kind, String detail, String doc, boolean call, int rank,
                       String snippet, boolean retrigger) {
        public Item(String label, String kind, String detail, String doc, boolean call, int rank) {
            this(label, kind, detail, doc, call, rank, null, false);
        }

        Item ranked(int r) {
            return new Item(label, kind, detail, doc, call, r, snippet, retrigger);
        }

        Item snippet(String body, boolean again) {
            return new Item(label, kind, detail, doc, call, rank, body, again);
        }
    }

    public record Span(int start, int end) {}

    public record Completion(int from, List<Item> items) {}

    public record Edit(int start, int end, String text) {}

    public record Renamed(List<Edit> edits, String error, Map<String, List<Edit>> others) {
        public Renamed(List<Edit> edits, String error) { this(edits, error, Map.of()); }
    }

    public record Symbol(String name, String kind, int start, int end, int nameStart, int nameEnd) {}

    public record Hint(int at, String label) {}

    public record Mark(int start, int end, String scope) {}

    private enum Role { FUNC_DECL, FUNC_CALL, VAR, EVENT, VALUE, ACTION, ARG, TARGET }

    private record Occ(Role role, String name, int start, int end, Data.Action action, Ast.Func func) {}

    private static final int LIMIT = 150;

    private static final String BODY = " {\n\t$0\n}";
    private static final String AGAIN = "↻";
    private static final String ITEM_FIELDS_TITLE = "Расписать предмет полями";

    private static final String[][] STATEMENT_WORDS = {
            {"if", "условие: if условие { … }", "if ${1:условие}" + BODY},
            {"if … else", "условие с иначе", "if ${1:условие} {\n\t$2\n} else {\n\t$0\n}"},
            {"если", "условие: если условие { … }", "если ${1:условие}" + BODY},
            {"если … иначе", "условие с иначе", "если ${1:условие} {\n\t$2\n} иначе {\n\t$0\n}"},
            {"for", "повторение по диапазону: for i in 1..10 { … }", "for ${1:i} in ${2:1}..${3:10}" + BODY},
            {"for … in список", "по списку: for x in список { … }", "for ${1:x} in ${2:список}" + BODY},
            {"for i, x in список", "по списку с номером", "for ${1:i}, ${2:x} in ${3:список}" + BODY},
            {"for k: v in словарь", "по словарю", "for ${1:k}: ${2:v} in ${3:словарь}" + BODY},
            {"для", "повторение по диапазону: для i в 1..10 { … }", "для ${1:i} в ${2:1}..${3:10}" + BODY},
            {"while", "повторять, пока условие верно", "while ${1:условие}" + BODY},
            {"пока", "повторять, пока условие верно", "пока ${1:условие}" + BODY},
            {"repeat", "повторить N раз", "repeat ${1:3}" + BODY},
            {"повторить", "повторить N раз", "повторить ${1:3}" + BODY},
            {"forever", "повторять всегда", "forever" + BODY},
            {"всегда", "повторять всегда", "всегда" + BODY},
            {"return", "выйти из функции", null},
            {"break", "прервать повторение", null},
            {"continue", "к следующему шагу повторения", null},
            {"start", "запустить процесс: start имя(…)", "start " + AGAIN},
            {"запустить", "запустить процесс", "запустить " + AGAIN},
            {"game", "игровая переменная: game x = 1", "game ${1:имя} = ${0:0}"},
            {"save", "сохранённая переменная: save x = 1", "save ${1:имя} = ${0:0}"},
            {"local", "локальная переменная: local x = 1", "local ${1:имя} = ${0:0}"},
            {"line", "строчная переменная: line x = 1", "line ${1:имя} = ${0:0}"}};
    private static final String[][] ELSE_WORDS = {
            {"else", "иначе: } else { … }", "else" + BODY},
            {"else if", "иначе если: } else if условие { … }", "else if ${1:условие}" + BODY},
            {"иначе", "иначе: } иначе { … }", "иначе" + BODY},
            {"иначе если", "иначе если", "иначе если ${1:условие}" + BODY}};
    private static final String[][] TOP_WORDS = {
            {"when", "событие: when player_join { … }", "when " + AGAIN},
            {"когда", "событие: когда player_join { … }", "когда " + AGAIN},
            {"func", "функция: func имя(параметры) { … }", "func ${1:имя}($2)" + BODY},
            {"функция", "функция: функция имя(параметры) { … }", "функция ${1:имя}($2)" + BODY},
            {"process", "процесс: process имя(параметры) { … }", "process ${1:имя}($2)" + BODY},
            {"процесс", "процесс: процесс имя(параметры) { … }", "процесс ${1:имя}($2)" + BODY},
            {"import", "подключить файл или модуль: import \"kits.xero\", import \"mapi\"", "import \"$1\""},
            {"const", "постоянная: const МАКС = 5", "const ${1:ИМЯ} = ${0:0}"},
            {"константа", "постоянная: константа МАКС = 5", "константа ${1:ИМЯ} = ${0:0}"},
            {"game", "объявить игровую переменную", "game ${1:имя}"},
            {"save", "объявить сохранённую переменную", "save ${1:имя}"},
            {"local", "объявить локальную переменную", "local ${1:имя}"},
            {"line", "объявить строчную переменную", "line ${1:имя}"}};
    private static final Map<String, String> RECIPES = Map.of(
            "game_add_custom_crafting_recipe",
            "(\"${1:id}\", item(${2:diamond_sword}), [\n\t${3:_}, ${4:_}, ${5:_},\n\t${6:_}, ${7:_}, ${8:_},\n"
                    + "\t${9:_}, ${10:_}, ${11:_},\n], recipe_type: ${12|shaped,shapeless|})$0",
            "game_add_custom_cooking_recipe",
            "(\"${1:id}\", item(${2:raw_iron}), item(${3:iron_ingot}), ${4:0.7}, ${5:200}, "
                    + "recipe_type: ${6|furnace,blasting,smoking,campfire|})$0",
            "game_add_custom_smithing_transform_recipe",
            "(\"${1:id}\", item(${2:netherite_upgrade_smithing_template}), item(${3:diamond_sword}), "
                    + "item(${4:netherite_ingot}), item(${5:netherite_sword}))$0");
    private static final String[][] DECORATORS = {
            {"line", "номер строки в /dev: @line(10)"},
            {"hidden", "скрыть функцию в меню вызова"},
            {"icon", "значок функции: @icon(item(iron_ingot))"},
            {"display_name", "имя функции в меню: @display_name(\"Имя\")"},
            {"display_description", "описание функции: @display_description(\"строка\", \"строка\")"},
            {"description", "описание старым видом, строками: @description(\"строка\", \"строка\")"},
            {"values", "все значения строки как есть: @values(\"[…JSON…]\")"},
            {"twin", "функция задвоена в мире: сервер хранит обе"}};

    private final Data data;
    private final Names names;
    private final List<Item> actions = new ArrayList<>();
    private final List<Item> conditions = new ArrayList<>();
    private final List<Item> events = new ArrayList<>();
    private final List<Item> values = new ArrayList<>();
    private final List<Item> targets = new ArrayList<>();
    private final Map<String, String> targetLabels = new LinkedHashMap<>();
    private List<Compiler.Declared> known = List.of();
    private Map<String, String> sources = Map.of();
    private Map<String, String> shared = Map.of();

    public Service(Data data, Names names) {
        this.data = data;
        this.names = names;
        for (Names.Entry e : names.entries()) {
            if (e.action().alias() || e.action().blank()) continue;
            String label = e.bare() ? e.name() : e.qualified();
            Item item = withSnippet(new Item(label, e.space() == Names.Space.CONDITION ? "condition" : "action",
                    e.action().label() + " · " + e.action().category().russian, actionDoc(e), true, 0), e.action());
            (e.space() == Names.Space.CONDITION ? conditions : actions).add(item);
        }
        for (Data.Event ev : data.events().values())
            events.add(new Item(ev.id(), "event", ev.label() + " · " + ev.group(), eventDoc(ev), false, 0));
        for (Data.GameValue v : data.gameValues().values())
            values.add(new Item(v.id(), "value", v.label() + " → " + v.returns(), valueDoc(v), false, 0));
        for (String family : names.selectorFamilies()) {
            if (family.equals("value")) continue;
            Map<String, String> labels = new LinkedHashMap<>();
            for (Data.Selector s : data.selectors(family)) labels.put(s.id(), s.label());
            names.selectors(family).forEach((name, id) -> {
                if (targetLabels.containsKey(name)) return;
                targetLabels.put(name, labels.getOrDefault(id, id));
                targets.add(new Item(name, "target", labels.getOrDefault(id, id), "Цель: **" + labels.getOrDefault(id, id) + "**", false, 0));
            });
        }
    }

    public List<Item> vocabulary() {
        List<Item> out = new ArrayList<>(actions);
        out.addAll(conditions);
        out.addAll(events);
        out.addAll(values);
        out.addAll(targets);
        return out;
    }

    public static Service load() {
        Data data = Data.load();
        return new Service(data, Names.build(data));
    }

    public Data data() { return data; }

    public Names names() { return names; }

    public Compiler.Result build(String name, String text, Compiler.Loader loader) {
        Map<String, String> seen = new LinkedHashMap<>();
        Compiler.Loader watching = loader == null ? null : path -> {
            String t = loader.load(path);
            if (t != null && !path.startsWith(Compiler.MODULE)) seen.put(path, t);
            return t;
        };
        Compiler.Result r = Compiler.compile(data, names, name, text, watching);
        List<Compiler.Declared> others = new ArrayList<>();
        for (Compiler.Declared d : r.declared())
            if (d.module() != null || d.file() != null && !d.file().equals(name)) others.add(d);
        if (!others.equals(known) || !seen.equals(sources)) {
            known = others;
            sources = seen;
            shared = sharedVariables(seen);
            last = null;
        }
        return r;
    }

    private Map<String, String> sharedVariables(Map<String, String> files) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String t : files.values()) {
            Walk w = walk(t, Parser.parse(t, new ArrayList<>()), false);
            for (Occ o : w.occs) if (o.role() == Role.VAR) out.putIfAbsent(o.name(), w.scopes.getOrDefault(o.name(), "local"));
        }
        return out;
    }

    private Compiler.Declared declared(Ast.Func f) {
        for (Compiler.Declared d : known) if (d.func() == f) return d;
        return null;
    }

    private boolean module(Ast.Func f) {
        Compiler.Declared d = declared(f);
        return d != null && d.module() != null;
    }

    private String doc(String text, Ast.Func f) {
        Compiler.Declared d = declared(f);
        if (d == null) return functionDoc(text, f);
        return d.module() != null ? importedDoc(f) : functionDoc(sources.getOrDefault(d.file(), ""), f);
    }

    private static String shortName(String file) {
        return file == null ? "" : file.substring(file.lastIndexOf('/') + 1);
    }

    public Formatter.Result format(String text) {
        return Formatter.format(text);
    }

    public List<Symbol> symbols(String text) {
        List<Symbol> out = new ArrayList<>();
        for (Ast.Item item : analysis(text).file().items()) {
            switch (item) {
                case Ast.Event e -> out.add(new Symbol(e.name(), "event", e.span().start(), e.span().end(),
                        e.nameSpan().start(), e.nameSpan().end()));
                case Ast.Func f -> out.add(new Symbol(f.name(), f.process() ? "process" : "function", f.span().start(),
                        f.span().end(), f.nameSpan().start(), f.nameSpan().end()));
                default -> { }
            }
        }
        return out;
    }

    public List<Hint> hints(String text) {
        Walk w = analysis(text).walk();
        List<Hint> out = new ArrayList<>();
        for (Ast.Call c : w.calls) {
            List<String> names = positional(w, c);
            if (names.size() < 2) continue;
            int i = 0;
            for (Ast.Arg a : c.args()) {
                if (a.name() != null) continue;
                if (i >= names.size()) break;
                String name = names.get(i++);
                if (a.value() instanceof Ast.Var v && v.name().equals(name)) continue;
                out.add(new Hint(a.value().span().start(), name + ":"));
            }
        }
        out.sort(Comparator.comparingInt(Hint::at));
        return out;
    }

    private List<String> positional(Walk w, Ast.Call c) {
        List<String> out = new ArrayList<>();
        if (c.qualifier() == null && w.functions.containsKey(c.name())) {
            for (Ast.Param p : w.functions.get(c.name()).params()) out.add(p.name());
            return out;
        }
        if (c.quoted() || c.qualifier() == null && Wire.CALL_WORDS.contains(c.name())) return out;
        Names.Entry e = names.resolve(Names.Space.ACTION, c.shown());
        if (e == null) e = names.resolve(Names.Space.CONDITION, c.shown());
        if (e == null) return out;
        Data.Arg result = w.assigned.contains(c) ? e.action().result() : null;
        for (Data.Arg arg : e.action().args()) if (arg != result) out.add(arg.id());
        for (Data.Setting s : e.action().settings()) out.add(s.id());
        return out;
    }

    public List<Mark> variables(String text) {
        Walk w = analysis(text).walk();
        List<Mark> out = new ArrayList<>();
        for (Occ o : w.occs) {
            if (o.role() != Role.VAR) continue;
            String scope = w.pinned.get(o.start());
            if (scope == null) scope = w.scopes.getOrDefault(o.name(), "local");
            out.add(new Mark(o.start(), o.end(), scope));
        }
        return out;
    }

    public String hover(String text, int offset) {
        Walk w = analysis(text).walk();
        Occ o = at(w, offset);
        if (o == null) return null;
        return switch (o.role()) {
            case ACTION -> actionDoc(names.byId(o.action().id()));
            case FUNC_CALL, FUNC_DECL -> o.func() == null ? null : doc(text, o.func());
            case VAR -> variableDoc(w, o.name());
            case EVENT -> data.events().containsKey(o.name()) ? eventDoc(data.events().get(o.name())) : null;
            case VALUE -> data.gameValues().containsKey(o.name()) ? valueDoc(data.gameValues().get(o.name())) : null;
            case ARG -> argDoc(o.action(), o.name());
            case TARGET -> targetLabels.containsKey(o.name()) ? "Цель `@" + o.name() + "`: **" + targetLabels.get(o.name()) + "**" : null;
        };
    }

    public Span definition(String text, int offset) {
        Walk w = analysis(text).walk();
        Occ o = at(w, offset);
        if (o == null) return null;
        if ((o.role() == Role.FUNC_CALL || o.role() == Role.FUNC_DECL) && o.func() != null)
            return new Span(o.func().nameSpan().start(), o.func().nameSpan().end());
        if (o.role() == Role.VAR) {
            for (Occ x : w.occs) if (x.role() == Role.VAR && x.name().equals(o.name())) return new Span(x.start(), x.end());
        }
        return null;
    }

    public String definitionFile(String text, int offset) {
        Occ o = at(analysis(text).walk(), offset);
        if (o == null || o.func() == null) return null;
        Compiler.Declared d = declared(o.func());
        return d == null ? null : d.file();
    }

    public Map<String, List<Span>> referencesElsewhere(String text, int offset) {
        Occ o = at(analysis(text).walk(), offset);
        Map<String, List<Span>> out = new LinkedHashMap<>();
        if (o == null || !(o.role() == Role.VAR || o.role() == Role.FUNC_CALL || o.role() == Role.FUNC_DECL)) return out;
        boolean function = o.role() != Role.VAR;
        sources.forEach((file, t) -> {
            List<Span> found = new ArrayList<>();
            for (Occ x : walk(t, Parser.parse(t, new ArrayList<>()), true).occs)
                if (same(x, o, function)) found.add(new Span(x.start(), x.end()));
            if (!found.isEmpty()) out.put(file, found);
        });
        return out;
    }

    private static boolean same(Occ x, Occ o, boolean function) {
        boolean kind = function ? x.role() == Role.FUNC_DECL || x.role() == Role.FUNC_CALL : x.role() == Role.VAR;
        return kind && x.name().equals(o.name());
    }

    public Renamed rename(String text, int offset, String wanted) {
        Walk w = analysis(text).walk();
        Occ o = at(w, offset);
        if (o == null || !(o.role() == Role.VAR || o.role() == Role.FUNC_CALL || o.role() == Role.FUNC_DECL))
            return new Renamed(List.of(), "переименовать можно функцию, процесс или переменную");
        boolean function = o.role() != Role.VAR;
        if (function && (o.func() == null || module(o.func())))
            return new Renamed(List.of(), "функция «" + o.name() + "» объявлена не в этом файле");
        String name = wanted == null ? "" : wanted.strip();
        if (name.startsWith("`") && name.endsWith("`") && name.length() > 1) name = name.substring(1, name.length() - 1);
        if (name.isEmpty() || name.indexOf('`') >= 0 || name.indexOf('\n') >= 0)
            return new Renamed(List.of(), "такое имя не записать");
        if (name.equals(o.name())) return new Renamed(List.of(), null);
        for (Occ x : w.occs) {
            boolean sameKind = function ? x.role() == Role.FUNC_DECL : x.role() == Role.VAR;
            if (sameKind && x.name().equals(name))
                return new Renamed(List.of(), (function ? "функция «" : "переменная «") + name + "» уже есть");
        }
        boolean plain = (Keywords.plainName(name) || function && Keywords.dotted(name))
                && !Names.RESERVED.contains(name.toLowerCase(Locale.ROOT));
        if (function && plain && (names.resolve(Names.Space.ACTION, name) != null || names.resolve(Names.Space.CONDITION, name) != null))
            plain = false;
        String written = plain ? name : "`" + name + "`";
        List<Edit> edits = new ArrayList<>();
        for (Occ x : w.occs) {
            boolean match = function
                    ? (x.role() == Role.FUNC_DECL || x.role() == Role.FUNC_CALL) && x.func() == o.func()
                    : x.role() == Role.VAR && x.name().equals(o.name());
            if (match) edits.add(new Edit(x.start(), x.end(), written));
        }
        Map<String, List<Edit>> others = new LinkedHashMap<>();
        referencesElsewhere(text, offset).forEach((file, spans) -> {
            List<Edit> list = new ArrayList<>();
            for (Span x : spans) list.add(new Edit(x.start(), x.end(), written));
            others.put(file, list);
        });
        return new Renamed(edits, null, others);
    }

    public Completion complete(String text, int offset) {
        Analysis a = analysis(text);
        int from = offset;
        while (from > 0 && part(text.charAt(from - 1))) from--;
        List<Token> before = before(a, from);
        Open call = innermost(before);
        if (call != null && call.callee() != null && IDS.contains(call.callee())) {
            int start = call.bracket().end();
            String typed = text.substring(start, offset).strip();
            if (typed.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_' || ch == '.' || ch == ':')) {
                int at = offset - typed.length();
                List<Item> ids = argumentItems(call, call.bracket(), null);
                if (ids != null) return new Completion(at, finish(typed.toLowerCase(Locale.ROOT), ids));
            }
        }
        return new Completion(from, items(a, offset, from, before, call));
    }

    private static List<Token> before(Analysis a, int offset) {
        List<Token> out = new ArrayList<>();
        for (Token t : a.tokens()) if (t.kind() != Token.Kind.EOF && t.end() <= offset) out.add(t);
        return out;
    }

    private static final Set<String> IDS = Set.of("item", "block", "sound", "particle", "potion");

    private List<Item> items(Analysis a, int offset, int from, List<Token> before, Open open) {
        String text = a.text();
        String prefix = text.substring(from, offset).toLowerCase(Locale.ROOT);
        for (Lexer.Comment c : a.comments()) if (offset > c.start() && offset <= c.end()) return List.of();
        for (Token t : a.tokens()) {
            if (t.kind() != Token.Kind.STRING || offset <= t.start() || offset >= t.end()) continue;
            if (t.parts() == null) return List.of();
            boolean inside = false;
            for (Object p : t.parts()) if (p instanceof Token.Code c && offset >= c.start() && offset <= c.end()) inside = true;
            if (!inside) return List.of();
            return finish(prefix, expression(a, from));
        }
        Token last = before.isEmpty() ? null : before.get(before.size() - 1);
        Token second = before.size() < 2 ? null : before.get(before.size() - 2);
        if (last != null && last.is(".") && second != null && second.kind() == Token.Kind.IDENT) {
            if (second.text().equals("value")) return finish(prefix, values);
            List<Item> out = new ArrayList<>();
            String head = qualified(before) + ".";
            a.walk().functions.forEach((name, f) -> {
                if (name.startsWith(head) && name.length() > head.length())
                    out.add(new Item(name.substring(head.length()), "function", (f.process() ? "процесс" : "функция")
                            + (module(f) ? " из модуля" : " в этом файле"), null, !f.process(), 0));
            });
            for (Names.Entry e : names.entries()) {
                if (e.action().alias() || e.action().blank() || !e.action().category().qualifier.equals(second.text())) continue;
                out.add(withSnippet(new Item(Names.shortName(e.action().id()), e.space() == Names.Space.CONDITION ? "condition" : "action",
                        e.action().label() + " · " + e.action().category().russian, actionDoc(e), true, 0), e.action()));
            }
            return finish(prefix, out);
        }
        int depth = 0;
        for (Token t : before) {
            if (t.is("{")) depth++;
            else if (t.is("}")) depth--;
        }
        boolean lineStart = last == null || last.is("{") || last.is("}") || last.is(";") || newlineBetween(text, last.end(), from);
        if (last != null && last.is("@")) {
            boolean top = depth == 0 && (second == null || newlineBetween(text, second.end(), last.start()));
            return finish(prefix, top ? table(DECORATORS, "decorator") : targets);
        }
        if (last != null && last.keyword("when")) {
            int eol = text.indexOf('\n', offset);
            boolean alone = text.substring(offset, eol < 0 ? text.length() : eol).isBlank();
            if (!alone) return finish(prefix, events);
            List<Item> out = new ArrayList<>();
            for (Item e : events) out.add(e.snippet(e.label() + BODY, false));
            return finish(prefix, out);
        }
        if (enchantmentsAt(before)) {
            List<Item> out = new ArrayList<>();
            for (String id : data.minecraft().enchantments())
                out.add(new Item(id.substring("minecraft:".length()), "enum", "чары", null, false, 0));
            return finish(prefix, out);
        }
        if (open != null && open.callee() != null) {
            List<Item> special = argumentItems(open, last, second);
            if (special != null) return finish(prefix, special);
        }
        if (open == null && lineStart) {
            if (depth <= 0) return finish(prefix, table(TOP_WORDS, "keyword"));
            List<Item> out = new ArrayList<>(table(STATEMENT_WORDS, "keyword"));
            if (last != null && last.is("}")) out.addAll(0, table(ELSE_WORDS, "keyword"));
            out.addAll(actions);
            out.addAll(functionItems(a));
            out.addAll(variableItems(a, from));
            return finish(prefix, out);
        }
        if (last != null && last.keyword("if", "while", "and", "or", "not")
                || last != null && last.is("(") && open != null && open.callee() == null) {
            List<Item> out = new ArrayList<>(conditions);
            out.addAll(expression(a, from));
            return finish(prefix, out);
        }
        List<Item> out = expression(a, from);
        if (open != null && "item".equals(open.callee()) && open.args() > 0)
            ITEM_FIELDS.forEach((k, v) -> out.add(0, new Item(k, "field", v, null, false, 0)));
        if (open != null && open.callee() != null && data.actions().get(open.action()) != null) {
            Data.Action action = data.actions().get(open.action());
            for (Data.Arg arg : action.args())
                out.add(new Item(arg.id(), "field", label(arg), argDoc(action, arg.id()), false, 0));
            for (Data.Setting s : action.settings())
                out.add(new Item(s.id(), "field", s.label(), argDoc(action, s.id()), false, 0));
        }
        if (last != null && (last.is("=") || last.is("+=") || last.is("-=") || last.is("*=") || last.is("/="))) out.addAll(actions);
        return finish(prefix, out);
    }

    private static String qualified(List<Token> before) {
        int at = before.size() - 2;
        String out = before.get(at).text();
        while (at >= 2 && before.get(at - 1).is(".") && before.get(at - 2).kind() == Token.Kind.IDENT) {
            at -= 2;
            out = before.get(at).text() + "." + out;
        }
        return out;
    }

    private record Open(Token bracket, String callee, String action, int args, boolean assigned, String named) {}

    private static List<Integer> unclosed(List<Token> before) {
        List<Integer> stack = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) {
            Token t = before.get(i);
            if (t.is("(") || t.is("[") || t.is("{")) stack.add(i);
            else if ((t.is(")") || t.is("]") || t.is("}")) && !stack.isEmpty()) stack.remove(stack.size() - 1);
        }
        return stack;
    }

    private static boolean block(List<Token> before, int at) {
        return before.get(at).is("{") && !map(at == 0 ? null : before.get(at - 1));
    }

    private Open innermost(List<Token> before) {
        List<Integer> stack = unclosed(before);
        if (stack.isEmpty()) return null;
        int at = stack.get(stack.size() - 1);
        return block(before, at) ? null : open(before, at);
    }

    private Open callAt(List<Token> before) {
        List<Integer> stack = unclosed(before);
        for (int s = stack.size() - 1; s >= 0; s--) {
            int at = stack.get(s);
            if (block(before, at)) return null;
            Open o = open(before, at);
            if (o.callee() != null) return o;
        }
        return null;
    }

    private Open open(List<Token> before, int at) {
        Token bracket = before.get(at);
        Open none = new Open(bracket, null, null, 0, false, null);
        if (!bracket.is("(") || at == 0) return none;
        Token name = before.get(at - 1);
        if (name.kind() != Token.Kind.IDENT && name.kind() != Token.Kind.NAME) return none;
        if (name.kind() == Token.Kind.IDENT && Keywords.word(name.text()) && !(at >= 2 && before.get(at - 2).is(".")))
            return none;
        String callee = name.text();
        int head = at - 1;
        if (at >= 3 && before.get(at - 2).is(".") && before.get(at - 3).kind() == Token.Kind.IDENT) {
            callee = before.get(at - 3).text() + "." + callee;
            head = at - 3;
        }
        if (head >= 2 && before.get(head - 1).kind() == Token.Kind.IDENT && before.get(head - 2).is("@")) head -= 2;
        boolean assigned = head >= 1 && before.get(head - 1).is("=");
        int index = 0, nested = 0, segment = at + 1;
        for (int i = at + 1; i < before.size(); i++) {
            Token t = before.get(i);
            if (t.is("(") || t.is("[") || t.is("{")) nested++;
            else if (t.is(")") || t.is("]") || t.is("}")) nested--;
            else if (t.is(",") && nested == 0) {
                index++;
                segment = i + 1;
            }
        }
        String named = segment + 1 < before.size() && before.get(segment).kind() == Token.Kind.IDENT
                && before.get(segment + 1).is(":") ? before.get(segment).text() : null;
        Names.Entry e = names.resolve(Names.Space.ACTION, callee);
        if (e == null) e = names.resolve(Names.Space.CONDITION, callee);
        return new Open(bracket, callee, e == null ? null : e.action().id(), index, assigned, named);
    }

    private static boolean enchantmentsAt(List<Token> before) {
        int depth = 0;
        for (int i = before.size() - 1; i >= 0; i--) {
            Token t = before.get(i);
            if (t.is("}") || t.is(")") || t.is("]")) depth++;
            else if (t.is("{") || t.is("(") || t.is("[")) {
                if (depth > 0) {
                    depth--;
                    continue;
                }
                if (!t.is("{") || i < 2 || !before.get(i - 1).is(":") || !before.get(i - 2).word("enchantments")) return false;
                Token last = before.get(before.size() - 1);
                return last == t || last.is(",");
            }
        }
        return false;
    }

    private static boolean map(Token before) {
        if (before == null) return false;
        if (before.kind() == Token.Kind.SYMBOL) return !before.is(")") && !before.is("]") && !before.is("}");
        return before.keyword("in", "and", "or", "not", "return");
    }

    private List<Item> argumentItems(Open open, Token last, Token second) {
        boolean first = last != null && last == open.bracket();
        String callee = open.callee();
        if (first) {
            switch (callee) {
                case "item" -> { return strings(data.minecraft().items(), "item", "предмет"); }
                case "block" -> { return strings(data.minecraft().blocks(), "item", "блок"); }
                case "sound" -> { return strings(data.pickers().sounds(), "item", "звук"); }
                case "particle" -> { return strings(lower(data.pickers().particles().keySet()), "item", "частица"); }
                case "potion" -> { return strings(data.pickers().potions(), "item", "зелье"); }
                default -> { }
            }
        }
        if (last != null && last.is(":") && second != null && second.kind() == Token.Kind.IDENT && open.action() != null) {
            Data.Setting s = data.actions().get(open.action()).setting(second.text());
            if (s != null) {
                List<Item> out = new ArrayList<>();
                s.options().forEach((id, label) -> out.add(new Item(id, "enum", label, s.label() + ": **" + label + "**", false, 0)));
                return out;
            }
        }
        return null;
    }

    private static Set<String> lower(Set<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : ids) out.add(id.toLowerCase(Locale.ROOT));
        return out;
    }

    private static List<Item> strings(Set<String> ids, String kind, String what) {
        List<Item> out = new ArrayList<>();
        for (String id : ids) {
            String bare = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
            out.add(new Item(bare, kind, what, null, false, 0));
        }
        return out;
    }

    private List<Item> expression(Analysis a, int typing) {
        List<Item> out = new ArrayList<>();
        out.add(new Item("value", "keyword", "игровое значение: value.current_health", null, false, 0));
        for (Wire.Builtin b : Wire.BUILTINS) out.add(new Item(b.name(), "function", b.doc(), null, false, 0));
        out.addAll(functionItems(a));
        out.addAll(variableItems(a, typing));
        return out;
    }

    private List<Item> functionItems(Analysis a) {
        List<Item> out = new ArrayList<>();
        for (Ast.Item item : a.file().items())
            if (item instanceof Ast.Func f)
                out.add(new Item(f.quoted() ? "`" + f.name() + "`" : f.name(), "function",
                        (f.process() ? "процесс" : "функция") + " в этом файле", functionDoc(a.text(), f), !f.process(), 0));
        for (Compiler.Declared d : known) {
            Ast.Func f = d.func();
            String from = d.module() != null ? " из модуля" : " из " + shortName(d.file());
            out.add(new Item(Keywords.function(f.name()), "function", (f.process() ? "процесс" : "функция") + from,
                    doc(a.text(), f), !f.process(), 0));
        }
        return out;
    }

    private static String importedDoc(Ast.Func f) {
        List<String> params = new ArrayList<>();
        for (Ast.Param p : f.params()) params.add(p.name() + (p.type() == null ? "" : ": " + p.type()));
        return "```xero\n" + (f.process() ? "process " : "func ") + Keywords.function(f.name()) + "(" + String.join(", ", params)
                + ")\n```\nИз подключённого модуля.";
    }

    private List<Item> variableItems(Analysis a, int typing) {
        Walk w = a.walk();
        Set<String> seen = new LinkedHashSet<>();
        List<Item> out = new ArrayList<>();
        for (Occ o : w.occs) {
            if (o.role() != Role.VAR || o.start() == typing || !seen.add(o.name())) continue;
            String shown = Keywords.name(o.name());
            out.add(new Item(shown, "variable", "переменная" + scopeOf(w, o.name()), null, false, 0));
        }
        for (String name : shared.keySet())
            if (seen.add(name)) out.add(new Item(Keywords.name(name), "variable", "переменная" + scopeOf(w, name), null, false, 0));
        return out;
    }

    private static boolean newlineBetween(String text, int from, int to) {
        for (int i = from; i < to && i < text.length(); i++) if (text.charAt(i) == '\n') return true;
        return false;
    }

    private static List<Item> table(String[][] rows, String kind) {
        List<Item> out = new ArrayList<>();
        for (String[] r : rows) {
            Item item = new Item(r[0], kind, r[1], null, false, 0);
            if (r.length > 2 && r[2] != null) {
                boolean again = r[2].endsWith(AGAIN);
                item = item.snippet(again ? r[2].substring(0, r[2].length() - AGAIN.length()) : r[2], again);
            }
            out.add(item);
        }
        return out;
    }

    private static Item withSnippet(Item item, Data.Action action) {
        String recipe = RECIPES.get(action.id());
        if (recipe != null) return item.snippet(item.label() + recipe, false);
        if (action.container() && !action.category().condition) return item.snippet(item.label() + "($1)" + BODY, false);
        return item;
    }

    private static List<Item> finish(String prefix, List<Item> all) {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Item i : all) {
            int rank = rank(prefix, i);
            if (rank < 0 || !seen.add(i.label() + "\u0000" + i.kind())) continue;
            out.add(i.ranked(rank));
        }
        Map<Item, Integer> given = new java.util.IdentityHashMap<>();
        for (int n = 0; n < out.size(); n++) given.put(out.get(n), n);
        out.sort(Comparator.comparingInt(Item::rank).thenComparingInt(Service::order)
                .thenComparingInt(i -> order(i) == 0 ? given.get(i) : 0).thenComparing(Item::label));
        return out.size() > LIMIT ? new ArrayList<>(out.subList(0, LIMIT)) : out;
    }

    private static int order(Item item) {
        return switch (item.kind()) {
            case "keyword", "decorator" -> 0;
            case "variable" -> 1;
            case "function" -> 2;
            default -> 3;
        };
    }

    private static int rank(String prefix, Item item) {
        if (prefix.isEmpty()) return 0;
        String label = item.label().toLowerCase(Locale.ROOT).replace("`", "");
        if (label.startsWith(prefix)) return 0;
        if (label.contains("_" + prefix) || label.contains("." + prefix)) return 1;
        String detail = item.detail() == null ? "" : item.detail().toLowerCase(Locale.ROOT);
        for (int i = 0; i < detail.length(); i++) {
            boolean wordStart = Character.isLetterOrDigit(detail.charAt(i))
                    && (i == 0 || !Character.isLetterOrDigit(detail.charAt(i - 1)));
            if (wordStart && detail.startsWith(prefix, i)) return 2;
        }
        if (prefix.length() >= 3 && detail.contains(prefix)) return 3;
        return -1;
    }

    private static boolean part(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private record Analysis(String text, List<Token> tokens, List<Lexer.Comment> comments, Ast.File file, Walk walk) {}

    private Analysis last;

    private Analysis analysis(String text) {
        Analysis a = last;
        if (a != null && a.text().equals(text)) return a;
        List<Lexer.Comment> comments = new ArrayList<>();
        List<Token> tokens = Lexer.tokens(text, new ArrayList<>(), comments);
        Ast.File file = Parser.parse(text, new ArrayList<>());
        a = new Analysis(text, tokens, comments, file, walk(text, file, true));
        last = a;
        return a;
    }

    private static final class Walk {
        final List<Occ> occs = new ArrayList<>();
        final Map<String, Ast.Func> functions = new LinkedHashMap<>();
        final Map<String, String> scopes = new LinkedHashMap<>();
        final List<Ast.Call> items = new ArrayList<>();
        final List<Ast.Call> calls = new ArrayList<>();
        final Set<Ast.Call> assigned = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final Map<Integer, String> pinned = new LinkedHashMap<>();
        String text;
    }

    private Walk walk(String text, Ast.File file, boolean project) {
        Walk w = new Walk();
        w.text = text;
        for (Ast.Item item : file.items()) if (item instanceof Ast.Func f) w.functions.putIfAbsent(f.name(), f);
        if (project) for (Compiler.Declared d : known) w.functions.putIfAbsent(d.func().name(), d.func());
        for (Ast.Item item : file.items()) {
            switch (item) {
                case Ast.Event e -> {
                    w.occs.add(new Occ(Role.EVENT, e.name(), e.nameSpan().start(), e.nameSpan().end(), null, null));
                    block(w, e.body());
                }
                case Ast.Func f -> {
                    w.occs.add(new Occ(Role.FUNC_DECL, f.name(), f.nameSpan().start(), f.nameSpan().end(), null, f));
                    for (Ast.Decorator d : f.decorators()) for (Ast.Arg a : d.args()) expr(w, a.value(), false);
                    for (Ast.Param p : f.params()) {
                        w.occs.add(new Occ(Role.VAR, p.name(), p.span().start(), p.span().end(), null, null));
                        w.scopes.putIfAbsent(p.name(), "line");
                        if (p.fallback() != null) expr(w, p.fallback(), false);
                    }
                    block(w, f.body());
                }
                case Ast.Const k -> {
                    w.occs.add(new Occ(Role.VAR, k.name(), k.nameSpan().start(), k.nameSpan().end(), null, null));
                    w.scopes.putIfAbsent(k.name(), "const");
                    expr(w, k.value(), false);
                }
                case Ast.Global g -> {
                    Token name = lastToken(text, g.span());
                    if (name != null) w.occs.add(new Occ(Role.VAR, g.name(), name.start(), name.end(), null, null));
                    w.scopes.putIfAbsent(g.name(), g.scope());
                }
                case Ast.Import i -> { }
            }
        }
        w.occs.sort(Comparator.comparingInt(Occ::start));
        if (project) shared.forEach(w.scopes::putIfAbsent);
        return w;
    }

    private static Token lastToken(String text, Ast.Span span) {
        Token last = null;
        for (Token t : Lexer.tokens(text, span.start(), span.end(), new ArrayList<>()))
            if (t.kind() != Token.Kind.EOF) last = t;
        return last;
    }

    private void block(Walk w, Ast.Block b) {
        if (b == null) return;
        for (Ast.Stmt s : b.stmts()) stmt(w, s);
    }

    private void stmt(Walk w, Ast.Stmt s) {
        switch (s) {
            case Ast.Run r -> {
                expr(w, r.call(), false);
                block(w, r.body());
            }
            case Ast.Assign a -> {
                w.occs.add(new Occ(Role.VAR, a.name(), a.nameSpan().start(), a.nameSpan().end(), null, null));
                if (a.scope() != null && !a.pinned()) w.scopes.putIfAbsent(a.name(), a.scope());
                if (a.pinned()) w.pinned.put(a.nameSpan().start(), a.scope());
                Ast.Expr value = a.value() instanceof Ast.Targeted t ? t.inner() : a.value();
                if (value instanceof Ast.Call call) w.assigned.add(call);
                expr(w, a.value(), false);
            }
            case Ast.MultiAssign m -> {
                for (int i = 0; i < m.names().size(); i++) {
                    w.occs.add(new Occ(Role.VAR, m.names().get(i), m.nameSpans().get(i).start(), m.nameSpans().get(i).end(), null, null));
                    if (m.scope() != null) w.scopes.putIfAbsent(m.names().get(i), m.scope());
                }
                for (Ast.Expr e : m.values()) expr(w, e, false);
            }
            case Ast.Declare d -> {
                Token name = lastToken(w.text, d.span());
                if (name != null) w.occs.add(new Occ(Role.VAR, d.name(), name.start(), name.end(), null, null));
                w.scopes.putIfAbsent(d.name(), d.scope());
            }
            case Ast.If i -> {
                for (Ast.Branch br : i.branches()) {
                    expr(w, br.condition(), true);
                    block(w, br.body());
                }
                block(w, i.otherwise());
            }
            case Ast.Start st -> {
                Ast.Func f = w.functions.get(st.name());
                w.occs.add(new Occ(Role.FUNC_CALL, st.name(), st.nameSpan().start(), st.nameSpan().end(), null, f));
                for (Ast.Arg a : st.args()) expr(w, a.value(), false);
            }
            case Ast.Repeat r -> {
                expr(w, r.count(), false);
                block(w, r.body());
            }
            case Ast.ForRange r -> {
                w.occs.add(new Occ(Role.VAR, r.name(), r.nameSpan().start(), r.nameSpan().end(), null, null));
                expr(w, r.from(), false);
                expr(w, r.to(), false);
                if (r.step() != null) expr(w, r.step(), false);
                block(w, r.body());
            }
            case Ast.ForEach r -> {
                if (r.first() != null)
                    w.occs.add(new Occ(Role.VAR, r.first(), r.firstSpan().start(), r.firstSpan().end(), null, null));
                w.occs.add(new Occ(Role.VAR, r.second(), r.secondSpan().start(), r.secondSpan().end(), null, null));
                expr(w, r.source(), false);
                block(w, r.body());
            }
            case Ast.While r -> {
                expr(w, r.condition(), true);
                block(w, r.body());
            }
            case Ast.Forever r -> block(w, r.body());
            case Ast.Jump j -> expr(w, j.value(), false);
            case Ast.IndexAssign a -> {
                expr(w, a.target(), false);
                expr(w, a.value(), false);
            }
        }
    }

    private void expr(Walk w, Ast.Expr e, boolean condition) {
        if (e == null) return;
        switch (e) {
            case Ast.Num n -> { }
            case Ast.Blank b -> { }
            case Ast.Str s -> {
                if (s.parts() != null) for (Object p : s.parts()) if (p instanceof Ast.Expr x) expr(w, x, false);
            }
            case Ast.Var v -> {
                int start = v.span().start() + (v.scope() == null ? 0 : v.scope().length() + 1);
                w.occs.add(new Occ(Role.VAR, v.name(), start, v.span().end(), null, null));
                if (v.scope() != null) w.pinned.put(start, v.scope());
            }
            case Ast.ListLit l -> { for (Ast.Expr x : l.items()) expr(w, x, false); }
            case Ast.MapLit m -> {
                for (Ast.Expr x : m.keys()) expr(w, x, false);
                for (Ast.Expr x : m.values()) expr(w, x, false);
            }
            case Ast.Call c -> {
                w.calls.add(c);
                if (c.qualifier() == null && !c.quoted() && c.name().equals("item")) w.items.add(c);
                Data.Action action = null;
                if (c.qualifier() == null && w.functions.containsKey(c.name())) {
                    w.occs.add(new Occ(Role.FUNC_CALL, c.name(), c.nameSpan().start(), c.nameSpan().end(), null, w.functions.get(c.name())));
                } else {
                    Names.Entry entry = names.resolve(condition ? Names.Space.CONDITION : Names.Space.ACTION, c.shown());
                    if (entry == null) entry = names.resolve(condition ? Names.Space.ACTION : Names.Space.CONDITION, c.shown());
                    if (entry != null) {
                        action = entry.action();
                        w.occs.add(new Occ(Role.ACTION, c.shown(), c.nameSpan().start(), c.nameSpan().end(), action, null));
                    }
                }
                boolean item = c.qualifier() == null && !c.quoted() && c.name().equals("item");
                for (Ast.Arg a : c.args()) {
                    if (a.name() != null && action != null && a.nameSpan() != null)
                        w.occs.add(new Occ(Role.ARG, a.name(), a.nameSpan().start(), a.nameSpan().end(), action, null));
                    if (item && a.value() instanceof Ast.MapLit m) {
                        for (Ast.Expr x : m.values()) expr(w, x, false);
                        continue;
                    }
                    if (item && a.name() == null && a.value() instanceof Ast.Var) continue;
                    expr(w, a.value(), false);
                }
            }
            case Ast.Path p -> {
                if (p.parts().size() == 2 && p.parts().get(0).equals("value"))
                    w.occs.add(new Occ(Role.VALUE, p.parts().get(1), p.span().start(), p.span().end(), null, null));
            }
            case Ast.Targeted t -> {
                w.occs.add(new Occ(Role.TARGET, t.target(), t.targetSpan().start(), t.targetSpan().end(), null, null));
                expr(w, t.inner(), condition);
            }
            case Ast.Unary u -> expr(w, u.operand(), condition);
            case Ast.Binary b -> {
                boolean logic = b.op().equals("and") || b.op().equals("or");
                expr(w, b.left(), logic && condition);
                expr(w, b.right(), logic && condition);
            }
            case Ast.Index i -> {
                expr(w, i.target(), false);
                expr(w, i.index(), false);
            }
            case Ast.Ternary t -> {
                expr(w, t.condition(), true);
                expr(w, t.then(), false);
                expr(w, t.otherwise(), false);
            }
        }
    }

    private static Occ at(Walk w, int offset) {
        Occ best = null;
        for (Occ o : w.occs) {
            if (offset < o.start() || offset > o.end()) continue;
            if (best == null || o.end() - o.start() < best.end() - best.start()) best = o;
        }
        return best;
    }

    private String scopeOf(Walk w, String name) {
        String scope = w.scopes.get(name);
        return switch (scope == null ? "" : scope) {
            case "game" -> " · игровая";
            case "save" -> " · сохранённая";
            case "line" -> " · строчная";
            case "const" -> " · постоянная";
            default -> " · локальная";
        };
    }

    private String variableDoc(Walk w, String name) {
        return "Переменная `" + name + "`" + scopeOf(w, name).replace(" · ", " — ");
    }

    private static String functionDoc(String text, Ast.Func f) {
        int end = f.body() == null ? f.span().end() : f.body().span().start();
        String head = text.substring(f.span().start(), Math.max(f.span().start(), end)).strip();
        return "```xero\n" + head + "\n```";
    }

    private String actionDoc(Names.Entry e) {
        if (e == null) return null;
        Data.Action a = e.action();
        StringBuilder out = new StringBuilder();
        out.append("**").append(a.label()).append("** — ").append(a.category().russian).append("\n\n");
        if (!a.description().isEmpty()) out.append(a.description()).append("\n\n");
        List<String> parts = new ArrayList<>();
        for (Data.Arg arg : a.args()) parts.add(arg.id());
        for (Data.Setting s : a.settings()) parts.add(s.id() + ": …");
        String name = e.bare() ? e.name() : e.qualified();
        out.append("```xero\n").append(name).append("(").append(String.join(", ", parts)).append(")\n```\n");
        for (Data.Arg arg : a.args()) out.append("- `").append(arg.id()).append("` — ").append(label(arg)).append('\n');
        for (Data.Setting s : a.settings()) out.append("- `").append(s.id()).append("` — ").append(settingDoc(s)).append('\n');
        if (e.action().alias()) out.append("\nЗапасной id: сервер из палитры ставит `").append(e.live()).append("`.\n");
        return out.toString();
    }

    private static String label(Data.Arg arg) {
        String kind = arg.kind() == null || arg.kind().isEmpty() ? "" : arg.kind();
        String what = arg.label() == null || arg.label().isEmpty() ? kind : arg.label() + (kind.isEmpty() ? "" : " (" + kind.toLowerCase(Locale.ROOT) + ")");
        return what + (arg.list() ? ", можно несколько" : "");
    }

    private static String settingDoc(Data.Setting s) {
        List<String> options = new ArrayList<>();
        s.options().forEach((id, label) -> options.add("`" + id + "` " + label));
        return (s.label().isEmpty() ? "настройка" : s.label()) + ": " + String.join(", ", options);
    }

    private String argDoc(Data.Action a, String name) {
        if (a == null) return null;
        Data.Arg arg = a.arg(name.toLowerCase(Locale.ROOT));
        if (arg != null) return "`" + arg.id() + "` — " + label(arg) + "\n\n" + a.label();
        Data.Setting s = a.setting(name.toLowerCase(Locale.ROOT));
        if (s != null) return "`" + s.id() + "` — " + settingDoc(s) + "\n\n" + a.label();
        return null;
    }

    private static String eventDoc(Data.Event ev) {
        return "**" + ev.label() + "** — " + ev.group() + (ev.description().isEmpty() ? "" : "\n\n" + ev.description());
    }

    private static String valueDoc(Data.GameValue v) {
        return "**" + v.label() + "** — " + v.group() + ", возвращает " + v.returns().toLowerCase(Locale.ROOT)
                + (v.description().isEmpty() ? "" : "\n\n" + v.description());
    }

    public record Signature(String label, List<Span> params, int active, String doc) {}

    private static final Map<String, String> ITEM_FIELDS = Map.ofEntries(
            Map.entry("count", "количество, 1–99"),
            Map.entry("name", "имя предмета: текст с разметкой — \"&bМеч\", mm\"<red>Меч\""),
            Map.entry("lore", "описание: список строк — [\"строка\", \"&6вторая\"]"),
            Map.entry("enchantments", "чары: {sharpness: 5, unbreaking: 3}"),
            Map.entry("unbreakable", "неразрушимый: true"),
            Map.entry("damage", "сколько прочности уже потрачено"),
            Map.entry("glint", "блеск чар: true — всегда, false — никогда"),
            Map.entry("model", "custom_model_data — число для ресурс-пака"),
            Map.entry("hide_tooltip", "скрыть подсказку целиком: true"),
            Map.entry("tags", "кастомные теги JustMC: {kind: \"hero\"}"),
            Map.entry("components", "остальные компоненты SNBT, как в /give: \"{max_stack_size:1,rarity:'epic'}\""));

    public Signature signature(String text, int offset) {
        Analysis analysis = analysis(text);
        Open c = callAt(before(analysis, offset));
        if (c == null) return null;
        List<String> params = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        String doc;
        String shown = c.callee();
        Wire.Builtin factory = null;
        for (Wire.Builtin b : Wire.BUILTINS) if (b.name().equals(c.callee())) factory = b;
        Ast.Func func = null;
        for (Ast.Item item : analysis.file().items()) if (item instanceof Ast.Func f && f.name().equals(c.callee())) func = f;
        for (Compiler.Declared d : known) if (func == null && d.func().name().equals(c.callee())) func = d.func();
        if (factory != null && func == null) {
            for (String p : factory.args()) {
                params.add(p);
                keys.add(p.contains(":") ? p.substring(0, p.indexOf(':')) : p);
            }
            doc = factory.doc();
        } else if (func != null) {
            for (Ast.Param p : func.params()) {
                params.add(p.name() + (p.type() == null ? "" : ": " + p.type())
                        + (p.fallback() == null || declared(func) != null ? "" : " = " + text.substring(p.fallback().span().start(), p.fallback().span().end())));
                keys.add(p.name());
            }
            doc = (func.process() ? "процесс" : "функция") + (module(func) ? " из модуля" : declared(func) != null ? " из другого файла" : " в этом файле");
        } else {
            Names.Entry e = names.resolve(Names.Space.ACTION, c.callee());
            if (e == null) e = names.resolve(Names.Space.CONDITION, c.callee());
            if (e == null) return null;
            Data.Action a = e.action();
            Data.Arg result = c.assigned() ? a.result() : null;
            for (Data.Arg arg : a.args()) {
                if (arg == result) continue;
                params.add(arg.id() + ": " + (arg.label().isEmpty() ? arg.kind() : arg.label()) + (arg.list() ? "…" : ""));
                keys.add(arg.id());
            }
            for (Data.Setting s : a.settings()) {
                List<String> options = new ArrayList<>(s.options().keySet());
                params.add(s.id() + ": " + String.join("|", options.size() > 6 ? options.subList(0, 6) : options)
                        + (options.size() > 6 ? "|…" : ""));
                keys.add(s.id());
            }
            doc = "**" + a.label() + "** — " + a.category().russian + (a.description().isEmpty() ? "" : "\n\n" + a.description());
        }
        StringBuilder label = new StringBuilder(shown).append('(');
        List<Span> spans = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) label.append(", ");
            spans.add(new Span(label.length(), label.length() + params.get(i).length()));
            label.append(params.get(i));
        }
        label.append(')');
        int active = c.named() != null ? keys.indexOf(c.named().toLowerCase(Locale.ROOT)) : c.args();
        if (c.named() != null && active < 0) active = keys.indexOf(c.named());
        return new Signature(label.toString(), spans, active, doc);
    }

    public List<Span> references(String text, int offset) {
        Walk w = analysis(text).walk();
        Occ o = at(w, offset);
        List<Span> out = new ArrayList<>();
        if (o == null) return out;
        boolean function = o.role() == Role.FUNC_CALL || o.role() == Role.FUNC_DECL;
        if (!function && o.role() != Role.VAR) return out;
        for (Occ x : w.occs) {
            boolean match = function
                    ? (x.role() == Role.FUNC_DECL || x.role() == Role.FUNC_CALL) && x.name().equals(o.name())
                    : x.role() == Role.VAR && x.name().equals(o.name());
            if (match) out.add(new Span(x.start(), x.end()));
        }
        return out;
    }

    public List<Diagnostic.Fix> actions(String text, int start, int end) {
        List<Diagnostic.Fix> out = new ArrayList<>();
        for (Ast.Call c : analysis(text).walk().items) {
            if (start < c.span().start() || end > c.span().end() || c.args().size() != 1) continue;
            Ast.Arg only = c.args().get(0);
            if (only.name() != null || !(only.value() instanceof Ast.Str s) || s.interpolated() || s.prefix() != null
                    || !s.text().startsWith("H4sI")) continue;
            String fields = Decompiler.fields(data, names, s.text());
            if (fields != null) out.add(new Diagnostic.Fix(ITEM_FIELDS_TITLE, c.span().start(), c.span().end(), fields));
        }
        return out;
    }

    public List<Diagnostic> check(String name, String text, Compiler.Loader loader) {
        return build(name, text, loader).diagnostics();
    }
}
