package xero.compile;

import xero.data.Data;
import xero.lang.Ast;
import xero.lang.Ast.Arg;
import xero.lang.Ast.Expr;
import xero.lang.Ast.Span;
import xero.lang.Diagnostic;
import xero.lang.Keywords;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static xero.compile.Context.num;
import static xero.compile.Context.obj;

final class Values {
    private static final String PARTICLE_FIELDS = "значения в particle(…) постоянные, переменное ставят set_particle_amount, set_particle_size и другие set_particle_*";

    private String literalHint;

    private final Context c;

    Values(Context c) {
        this.c = c;
    }

    private static final String AMOUNT = "set_variable_set_item_amount", LORE = "set_variable_set_item_lore";
    private static final java.util.regex.Pattern ONE_STYLE = java.util.regex.Pattern.compile("^(?:[&§][0-9a-fk-or])*[^&§%]*$");

    Ast.Call foldedItem(Data.Action action, Ast.Call call) {
        boolean amount = AMOUNT.equals(action.canonical()), lore = LORE.equals(action.canonical());
        if (!amount && !lore || !c.methodCalls.contains(call)) return null;
        Map<String, Expr> bound = new LinkedHashMap<>();
        List<Data.Arg> order = new ArrayList<>(action.args());
        order.remove(action.result());
        int positional = 0;
        for (Arg a : call.args()) {
            String key = a.name() != null ? a.name().toLowerCase(Locale.ROOT) : positional < order.size() ? order.get(positional++).id() : null;
            if (key == null || bound.put(key, a.value()) != null) return null;
        }
        String what = amount ? "amount" : "lore";
        if (bound.size() != 2 || !(bound.get("item") instanceof Ast.Var held) || c.constant(held) == null
                || !(c.resolved(held) instanceof Ast.Call item) || item.qualifier() != null || item.quoted()
                || !item.name().equals("item") || !constant(item) || !bound.containsKey(what)) return null;
        Expr value = c.resolved(bound.get(what));
        List<Arg> args = new ArrayList<>();
        int at = 0;
        for (Arg a : item.args()) {
            if (a.name() == null) {
                if (at++ == 0 || !amount) args.add(a);
            } else if (!a.name().equals(amount ? "count" : "lore")) {
                args.add(a);
            }
        }
        if (amount) {
            if (!(value instanceof Ast.Num n) || n.millis() != null) return null;
            double count = Double.parseDouble(n.text());
            if (count != Math.rint(count) || count < 1 || count > 99) return null;
            args.add(Math.min(1, args.size()), new Arg(null, null, new Ast.Num(String.valueOf((int) count), n.span())));
        } else {
            List<Expr> lines = value instanceof Ast.ListLit l ? l.items() : List.of(value);
            for (Expr line : lines)
                if (!(line instanceof Ast.Str s) || s.interpolated() || s.prefix() != null && !s.prefix().equals("legacy")
                        || !ONE_STYLE.matcher(s.text()).matches()) return null;
            args.add(new Arg("lore", null, new Ast.ListLit(lines, value.span())));
        }
        return new Ast.Call(null, "item", false, item.nameSpan(), args, call.span());
    }

    private boolean constant(Expr e) {
        return switch (c.resolved(e)) {
            case Ast.Num n -> true;
            case Ast.Str s -> !s.interpolated() && s.text().indexOf('%') < 0;
            case Ast.Var v -> v.scope() == null && !c.known(v.name());
            case Ast.Unary u -> constant(u.operand());
            case Ast.ListLit l -> l.items().stream().allMatch(this::constant);
            case Ast.MapLit m -> m.keys().stream().allMatch(this::constant) && m.values().stream().allMatch(this::constant);
            case Ast.Call call -> call.qualifier() == null && !call.quoted() && call.name().equals("item")
                    && call.args().stream().allMatch(a -> constant(a.value()));
            default -> false;
        };
    }

    Object factory(Ast.Call call) {
        Map<String, Expr> named = new LinkedHashMap<>();
        List<Expr> positional = new ArrayList<>();
        for (Arg a : call.args()) {
            if (a.name() == null) positional.add(a.value());
            else named.put(a.name().toLowerCase(Locale.ROOT), a.value());
        }
        return switch (call.name()) {
            case "raw" -> {
                Object raw = c.rawJson(call.args(), call.span());
                c.locals.raw(raw);
                yield raw;
            }
            case "enum" -> {
                if (positional.isEmpty() || positional.size() > 2 || !named.isEmpty() || !(positional.get(0) instanceof Ast.Str str))
                    throw c.stop("enum(\"ВАРИАНТ\") или enum(\"ВАРИАНТ\", переменная)", call.span());
                Map<String, Object> o = obj("type", "enum", "enum", str.text());
                if (positional.size() == 2) {
                    if (!(positional.get(1) instanceof Ast.Var v)) throw c.stop("вторым идёт переменная", positional.get(1).span());
                    Map<String, Object> bound = xero.json.Json.object(c.compiler.variable(v, null));
                    o.put("variable", bound.get("variable"));
                    o.put("scope", bound.get("scope"));
                }
                yield o;
            }
            case "len" -> {
                Integer fixed = c.formula.constantLength(call);
                yield obj("type", "number", "number", fixed != null ? num(fixed) : c.formula.length(call));
            }
            case "literal" -> {
                if (positional.size() != 1 || !named.isEmpty()) throw c.stop("literal(значение)", call.span());
                yield c.compiler.stored(positional.get(0));
            }
            case "num" -> {
                if (positional.size() != 1 || !named.isEmpty() || !(positional.get(0) instanceof Ast.Str str))
                    throw c.stop("num(\"…\") — число, записанное текстом, например num(\"%math(%var(x)+1)\")",
                            call.span());
                c.formula.texts(str.text(), str.span(), 1);
                if (!Texts.numeric(str.text()) && !str.text().contains("%"))
                    c.warning("«" + str.text() + "» — не число: сервер остановит строку или даст 0", str.span());
                yield obj("type", "number", "number", str.text());
            }
            case "loc", "vec" -> {
                if (built(call)) {
                    Ast.Var tmp = c.temp(call.span());
                    c.need(call.span()).add(build(call, tmp));
                    yield c.compiler.variable(tmp, null);
                }
                if (call.name().equals("loc")) yield location(call, positional, named);
                double[] xyz = numbers(call, positional, named, List.of("x", "y", "z"), 3, 3);
                yield obj("type", "vector", "x", num(xyz[0]), "y", num(xyz[1]), "z", num(xyz[2]));
            }
            case "item" -> item(call, positional, named);
            case "block" -> {
                only(call, named, Set.of());
                if (positional.size() != 1) throw c.stop("block(id): нужен один id блока, например block(stone)", call.span());
                String id = minecraft(id(positional.get(0)));
                if (id.indexOf('[') >= 0)
                    throw c.stop("в block(…) только id, без состояния", positional.get(0).span(), "состояние пишут текстом: \"" + bare(id) + "\"");
                known(id, c.data.minecraft().blocks(), "блока", positional.get(0).span());
                yield obj("type", "block", "block", id);
            }
            case "sound" -> sound(call, positional, named);
            case "particle" -> particle(call, positional, named);
            case "potion" -> potion(call, positional, named);
            case "cell" -> cell(call, positional, named);
            default -> throw c.stop("нет значения «" + call.name() + "»", call.span());
        };
    }

    boolean built(Ast.Call call) {
        if (call.qualifier() != null || call.quoted() || !(call.name().equals("loc") || call.name().equals("vec"))) return false;
        for (Arg a : call.args()) if (c.formula.fold(a.value()) == null) return true;
        return false;
    }

    Map<String, Object> build(Ast.Call call, Ast.Var into) {
        boolean location = call.name().equals("loc");
        Data.Action action = c.data.actions().get(location ? "set_variable_set_all_coordinates" : "set_variable_vector");
        List<String> keys = location ? List.of("x", "y", "z", "yaw", "pitch") : List.of("x", "y", "z");
        List<Arg> args = new ArrayList<>();
        int position = 0;
        for (Arg a : call.args()) {
            if (a.name() == null && position >= keys.size())
                throw c.stop(call.name() + "(" + String.join(", ", keys) + ")", call.span());
            args.add(Context.named(a.name() != null ? a.name() : keys.get(position++), a.value()));
        }
        return obj("action", action.id(), "values", c.compiler.bind(action, args, into, call.span()));
    }

    private Object location(Ast.Call call, List<Expr> positional, Map<String, Expr> named) {
        double[] v = numbers(call, positional, named, List.of("x", "y", "z", "yaw", "pitch"), 3, 5);
        return obj("type", "location", "x", num(v[0]), "y", num(v[1]), "z", num(v[2]),
                "yaw", num(v[3]), "pitch", num(v[4]));
    }

    private Object item(Ast.Call call, List<Expr> positional, Map<String, Expr> named) {
        Set<String> allowed = new LinkedHashSet<>(List.of("count"));
        allowed.addAll(ItemSpec.KEYS);
        only(call, named, allowed);
        if (positional.isEmpty() || positional.size() > 2)
            throw c.stop("item(id) или item(id, количество), например item(diamond, 5)", call.span());
        if (positional.get(0) instanceof Ast.Str s && (s.text().startsWith("H4sI") || s.text().equals(Items.EMPTY))) {
            if (positional.size() > 1 || !named.isEmpty())
                c.error("у готового предмета всё уже внутри: количество, имя и остальное", call.span());
            return obj("type", "item", "item", s.text());
        }
        String id = minecraft(id(positional.get(0)));
        known(id, c.data.minecraft().items(), "предмета", positional.get(0).span());
        Expr countExpr = positional.size() > 1 ? positional.get(1) : named.get("count");
        if (positional.size() > 1 && named.containsKey("count")) c.error("количество указано дважды", named.get("count").span());
        int count = 1;
        if (countExpr != null) {
            double n = literal(countExpr);
            if (n != Math.rint(n) || n < 1 || n > 99) c.error("количество предметов — целое от 1 до 99", countExpr.span());
            count = (int) n;
        }
        List<ItemSpec.Part> parts = new ArrayList<>();
        List<Span> spans = new ArrayList<>();
        for (Map.Entry<String, Expr> e : named.entrySet()) {
            if (e.getKey().equals("count") || !allowed.contains(e.getKey())) continue;
            ItemSpec.Part part = c.guard(() -> itemPart(e.getKey(), e.getValue()));
            if (part == null) continue;
            parts.add(part);
            spans.add(e.getValue().span());
        }
        Nbt.Compound components;
        try {
            components = ItemSpec.build(parts);
        } catch (ItemSpec.Clash clash) {
            throw c.stop(clash.getMessage(), spans.get(clash.part));
        } catch (Styled.Problem problem) {
            throw c.stop(problem.getMessage(), call.span());
        }
        return obj("type", "item", "item", Items.encode(new Items.Stack(id, count, components)));
    }

    private ItemSpec.Part itemPart(String key, Expr e) {
        return switch (key) {
            case "name" -> new ItemSpec.Name(itemLine(e));
            case "lore" -> {
                List<ItemSpec.Line> lines = new ArrayList<>();
                if (c.resolved(e) instanceof Ast.ListLit l) for (Expr item : l.items()) lines.add(itemLine(item));
                else lines.add(itemLine(e));
                if (lines.isEmpty()) throw c.stop("пустое описание — просто не пиши lore", e.span());
                yield new ItemSpec.Lore(lines);
            }
            case "enchantments" -> enchantments(e);
            case "unbreakable", "hide_tooltip" -> {
                if (!bool(e)) throw c.stop(key + ": false — просто не пиши " + key, e.span());
                yield new ItemSpec.Flag(key, true);
            }
            case "glint" -> new ItemSpec.Flag(key, bool(e));
            case "damage" -> {
                double v = literal(e);
                if (v != Math.rint(v) || v < 0 || v > Integer.MAX_VALUE)
                    throw c.stop("damage — целое от 0: сколько прочности потрачено", e.span());
                yield new ItemSpec.Whole(key, (int) v);
            }
            case "model" -> {
                double v = literal(e);
                float model = (float) v;
                if (model != v) c.warning("model хранится с меньшей точностью — уйдёт " + model, e.span());
                yield new ItemSpec.Model(model);
            }
            case "tags" -> tags(e);
            default -> components(e);
        };
    }

    private ItemSpec.Part enchantments(Expr e) {
        if (!(c.resolved(e) instanceof Ast.MapLit m) || m.keys().isEmpty())
            throw c.stop("чары пишутся словарём: enchantments: {sharpness: 5, unbreaking: 3}", e.span());
        Map<String, Integer> levels = new LinkedHashMap<>();
        Set<String> known = c.data.minecraft().enchantments();
        for (int i = 0; i < m.keys().size(); i++) {
            Expr k = m.keys().get(i);
            String ench = minecraft(id(k));
            String name = ench.startsWith("minecraft:") ? ench.substring("minecraft:".length()) : ench;
            if (!known.isEmpty() && !known.contains(ench)) {
                List<String> bare = new ArrayList<>();
                for (String x : known) bare.add(x.substring("minecraft:".length()));
                String best = Suggest.best(name, bare);
                c.warning("нет чар «" + name + "» в Minecraft 26.2 — уйдут как есть", k.span(), Suggest.meant(best),
                        Hints.replace(k.span(), best));
            }
            double level = literal(m.values().get(i));
            if (level != Math.rint(level) || level < 1 || level > 255)
                c.error("уровень чар — целое от 1 до 255", m.values().get(i).span());
            if (levels.put(name, (int) level) != null) c.error("чары повторяются", k.span());
        }
        return new ItemSpec.Enchantments(levels);
    }

    private ItemSpec.Part tags(Expr e) {
        if (!(c.resolved(e) instanceof Ast.MapLit m) || m.keys().isEmpty())
            throw c.stop("теги пишутся словарём: tags: {kind: \"hero\"}", e.span());
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < m.keys().size(); i++) {
            String k = id(m.keys().get(i));
            Expr v = c.resolved(m.values().get(i));
            String text = switch (v) {
                case Ast.Str str when c.formula.fixedText(str) != null -> c.formula.fixedText(str);
                case Ast.Num n -> n.text();
                default -> throw c.stop("значение тега — текст в кавычках", v.span());
            };
            c.lints.text(text);
            if (values.put(k, text) != null) c.error("тег «" + k + "» повторяется", m.keys().get(i).span());
        }
        return new ItemSpec.Tags(values);
    }

    private ItemSpec.Part components(Expr e) {
        if (!(c.resolved(e) instanceof Ast.Str str) || str.interpolated() || str.prefix() != null)
            throw c.stop("components — SNBT в кавычках, как в /give: components: \"{max_stack_size:1}\"", e.span());
        Nbt.Tag parsed;
        try {
            parsed = Nbt.parse(str.text());
        } catch (Nbt.Error err) {
            int at = Math.min(str.span().end() - 1, str.span().start() + 1 + err.offset);
            throw c.stop("SNBT: " + err.getMessage(), new Span(at, Math.min(str.span().end(), at + 1)));
        }
        if (!(parsed instanceof Nbt.Compound comps) || comps.entries().isEmpty())
            throw c.stop("components — это {компонент: значение, …}", e.span());
        Map<String, Data.Component> known = c.data.minecraft().components();
        for (String k : comps.entries().keySet()) {
            String full = ItemSpec.namespaced(k);
            if (known.isEmpty() || known.containsKey(full)) continue;
            List<String> bare = new ArrayList<>();
            for (String x : known.keySet()) bare.add(x.substring("minecraft:".length()));
            String name = full.startsWith("minecraft:") ? full.substring("minecraft:".length()) : full;
            c.diagnostics.add(new Diagnostic(false, "нет компонента «" + name + "» в Minecraft 26.2 — уйдёт как есть",
                    str.span().start(), str.span().end(), Suggest.hint(name, bare)));
        }
        return new ItemSpec.Raw(comps);
    }

    private ItemSpec.Line itemLine(Expr e) {
        if (!(c.resolved(e) instanceof Ast.Str s)) throw c.stop("здесь нужен текст в кавычках", e.span());
        String text = c.formula.fixedText(s);
        if (text == null) throw c.stop("в предмете текст постоянный — ${…} здесь не подставится", s.span(),
                "менять имя по ходу игры — действием set_item_name");
        c.lints.text(text);
        if ("mm".equals(s.prefix())) {
            for (String tag : Wire.NOT_IN_ITEMS)
                if (text.toLowerCase(Locale.ROOT).contains("<" + tag))
                    c.warning("в предмете <" + tag + "…> не сработает: имя собирается заранее и останется текстом", s.span(),
                            "такое имя задают действием set_item_name или полем components");
        }
        return new ItemSpec.Line(text, Context.parsing(s.prefix()));
    }

    private boolean bool(Expr e) {
        Expr r = e instanceof Ast.Var v && c.constant(v) != null ? c.constant(v) : e;
        if (r instanceof Ast.Var v && !v.quoted() && Context.truth(v.name()) != null) return Context.truth(v.name());
        throw c.stop("здесь true или false", e.span());
    }

    private Object sound(Ast.Call call, List<Expr> positional, Map<String, Expr> named) {
        only(call, named, Set.of("volume", "pitch", "variation", "source"));
        if (positional.size() != 1) throw c.stop("sound(id), например sound(entity.player.levelup, pitch: 2)", call.span());
        String id = id(positional.get(0));
        String bare = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        if (!c.data.pickers().sounds().isEmpty() && !c.data.pickers().sounds().contains(bare)) {
            String best = Suggest.best(bare, c.data.pickers().sounds());
            c.error("нет звука «" + bare + "»", positional.get(0).span(), Suggest.meant(best),
                    Hints.replace(positional.get(0).span(), best == null ? null : dotted(best)));
        }
        double volume = named.containsKey("volume") ? literal(named.get("volume")) : 1;
        double pitch = named.containsKey("pitch") ? literal(named.get("pitch")) : 1;
        String variation = "";
        if (named.containsKey("variation")) {
            if (c.resolved(named.get("variation")) instanceof Ast.Str s) variation = s.text();
            else c.error("variation — текст в кавычках", named.get("variation").span());
        }
        String source = "master";
        if (named.containsKey("source")) {
            source = id(named.get("source")).toLowerCase(Locale.ROOT);
            if (!Wire.SOUND_SOURCES.contains(source)) {
                c.error("нет источника «" + source + "»", named.get("source").span(),
                        "есть: " + String.join(", ", Wire.SOUND_SOURCES));
                source = "master";
            }
        }
        return obj("type", "sound", "sound", "minecraft:" + bare, "pitch", num(pitch), "volume", num(volume),
                "variation", variation, "source", source.toUpperCase(Locale.ROOT));
    }

    private Object cell(Ast.Call call, List<Expr> positional, Map<String, Expr> named) {
        only(call, named, Set.of("storage", "id"));
        if (positional.size() != 1 || !(c.resolved(positional.get(0)) instanceof Ast.Str name))
            throw c.stop("cell(\"имя\"): имя ячейки текстом, например cell(\"данные\")", call.span());
        if (name.text().isBlank()) throw c.stop("у ячейки нет имени", positional.get(0).span());
        if (name.text().length() > CELL_NAME_MAX)
            throw c.stop("имя ячейки длиннее " + CELL_NAME_MAX + " символов: сервер остановит строку", positional.get(0).span());
        if (named.containsKey("storage") != named.containsKey("id"))
            throw c.stop("общему хранилищу нужны оба: cell(\"имя\", storage: \"ник/хранилище\", id: \"uuid\")",
                    call.span(), "без storage и id ячейка лежит в файлах этого мира");
        if (!named.containsKey("storage")) return obj("type", "cell", "cell", name.text());
        return obj("type", "cell", "cell", name.text(), "global", "true",
                "storage", quoted(named.get("id"), "id"), "storage_path", quoted(named.get("storage"), "storage"));
    }

    private static final int CELL_NAME_MAX = 256;

    private String quoted(Expr e, String what) {
        if (c.resolved(e) instanceof Ast.Str s && !s.text().isBlank()) return s.text();
        throw c.stop(what + " — текст в кавычках", e.span());
    }

    private Object particle(Ast.Call call, List<Expr> positional, Map<String, Expr> named) {
        literalHint = PARTICLE_FIELDS;
        try {
            return particleValue(call, positional, named);
        } finally {
            literalHint = null;
        }
    }

    private Object particleValue(Ast.Call call, List<Expr> positional, Map<String, Expr> named) {
        for (String field : List.copyOf(named.keySet()))
            if (!Wire.PARTICLE_FIELDS.contains(field)) {
                c.warning("у particle() нет «" + field + "» — поле не уйдёт", named.get(field).span(),
                        "есть: " + String.join(", ", Wire.PARTICLE_FIELDS));
                named.remove(field);
            }
        if (positional.size() != 1) throw c.stop("particle(id), например particle(flame, count: 10)", call.span());
        String id = id(positional.get(0)).toUpperCase(Locale.ROOT);
        List<String> extras = c.data.pickers().particles().get(id);
        if (extras == null && !c.data.pickers().particles().isEmpty()) {
            String best = Suggest.best(id, c.data.pickers().particles().keySet());
            c.error("нет частицы «" + id.toLowerCase(Locale.ROOT) + "»", positional.get(0).span(), Suggest.meant(best),
                    Hints.replace(positional.get(0).span(), best == null ? null : dotted(best.toLowerCase(Locale.ROOT))));
            extras = List.of();
        }
        if (extras == null) extras = List.of();
        int count = 1;
        if (named.containsKey("count")) {
            double n = literal(named.get("count"));
            if (n != Math.rint(n) || n < 0 || n > Integer.MAX_VALUE)
                c.error("количество частиц — целое от 0", named.get("count").span());
            count = (int) n;
        }
        Map<String, Object> o = obj("type", "particle", "particle_type", id,
                "count", count,
                "first_spread", num(named.containsKey("first_spread") ? literal(named.get("first_spread")) : 0),
                "second_spread", num(named.containsKey("second_spread") ? literal(named.get("second_spread")) : 0));
        extra(o, named, extras.contains("Движение"), "движения", List.of("x_motion", "y_motion", "z_motion"));
        if (extras.contains("Цвет")) o.put("color", color(named.get("color")));
        else noField(named, "color", "цвета");
        if (extras.contains("Цвет перехода")) o.put("to_color", color(named.get("to_color")));
        else noField(named, "to_color", "цвета перехода");
        if (extras.contains("Размер")) o.put("size", num(named.containsKey("size") ? literal(named.get("size")) : 1));
        else noField(named, "size", "размера");
        if (extras.contains("Материал")) {
            Expr m = named.get("material");
            String material = m == null ? "minecraft:stone" : minecraft(id(m));
            if (m != null && !c.data.minecraft().blocks().contains(material))
                known(material, c.data.minecraft().items(), "блока или предмета", m.span());
            o.put("material", material(material));
        } else noField(named, "material", "материала");
        return o;
    }

    private void noField(Map<String, Expr> named, String key, String what) {
        if (named.containsKey(key)) c.warning("у этой частицы нет " + what + " — поле не уйдёт", named.get(key).span());
    }

    private void extra(Map<String, Object> o, Map<String, Expr> named, boolean allowed, String what, List<String> keys) {
        for (String k : keys) {
            if (allowed) o.put(k, num(named.containsKey(k) ? literal(named.get(k)) : 0));
            else noField(named, k, what);
        }
    }

    private int color(Expr e) {
        if (e == null) return 0xFFFF0000;
        Expr r = c.resolved(e);
        if (r instanceof Ast.Str s && s.text().matches("#?[0-9a-fA-F]{6}"))
            return 0xFF000000 | Integer.parseInt(s.text().replace("#", ""), 16);
        Double d = c.formula.fold(r);
        if (d != null && d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) return d.intValue();
        c.error("цвет пишется \"#RRGGBB\"", e.span());
        return 0xFFFF0000;
    }

    private Object potion(Ast.Call call, List<Expr> positional, Map<String, Expr> named) {
        only(call, named, Set.of("amplifier", "duration"));
        if (positional.size() != 1) throw c.stop("potion(id), например potion(speed, amplifier: 1, duration: 600)", call.span());
        String id = minecraft(id(positional.get(0)));
        if (!c.data.pickers().potions().isEmpty() && !c.data.pickers().potions().contains(id)) {
            String best = Suggest.best(id, c.data.pickers().potions());
            c.error("нет зелья «" + id.substring("minecraft:".length()) + "»", positional.get(0).span(), Suggest.meant(best),
                    Hints.replace(positional.get(0).span(), best == null ? null : dotted(bare(best))));
        }
        int duration = -1;
        if (named.containsKey("duration")) {
            Expr e = named.get("duration");
            double d = literal(e);
            if (d != Math.rint(d) || d > Integer.MAX_VALUE || d < Integer.MIN_VALUE)
                c.error("длительность зелья — целое число тиков", e.span(), "бесконечно — -1, так и по умолчанию");
            else if (d < -1)
                c.warning("длительность меньше -1 — эффект, скорее всего, сразу кончится", e.span(),
                        "бесконечно — -1, так и по умолчанию");
            duration = (int) d;
        }
        int amplifier = 0;
        if (named.containsKey("amplifier")) {
            Expr e = named.get("amplifier");
            double a = literal(e);
            if (a != Math.rint(a) || a > Integer.MAX_VALUE || a < Integer.MIN_VALUE)
                c.error("уровень зелья — целое число: 0 — первый уровень", e.span());
            else if (a < 0 || a > 255)
                c.warning("уровень зелья вне 0–255 — как его поймёт сервер, не проверено", e.span());
            amplifier = (int) a;
        }
        return obj("type", "potion", "potion", id, "duration", duration, "amplifier", amplifier);
    }

    private void only(Ast.Call call, Map<String, Expr> named, Set<String> allowed) {
        for (String k : named.keySet())
            if (!allowed.contains(k))
                c.error("у " + call.name() + "() нет «" + k + "»", call.span(),
                        allowed.isEmpty() ? null : "есть: " + String.join(", ", allowed));
    }

    private void known(String id, Set<String> ids, String what, Span at) {
        if (ids.isEmpty() || ids.contains(id)) return;
        List<String> bare = new ArrayList<>();
        for (String x : ids) bare.add(x.startsWith("minecraft:") ? x.substring("minecraft:".length()) : x);
        String name = bare(id);
        if (c.known(name)) {
            c.error("«" + name + "» — переменная, а здесь нужен постоянный id", at,
                    "передай её без обёртки: сервер примет id текстом, а у предмета тип меняет set_item_type");
            return;
        }
        String best = Suggest.best(name, bare);
        c.error("нет " + what + " «" + name + "» в Minecraft 26.2", at, Suggest.meant(best),
                Hints.replace(at, best == null ? null : dotted(best)));
    }

    private double[] numbers(Ast.Call call, List<Expr> positional, Map<String, Expr> named, List<String> keys,
                             int min, int max) {
        double[] out = new double[keys.size()];
        only(call, named, Set.copyOf(keys));
        if (positional.size() + named.size() < min || positional.size() > max)
            throw c.stop(call.name() + "(" + String.join(", ", keys.subList(0, min)) + ")", call.span());
        for (int i = 0; i < keys.size(); i++) {
            Expr e = i < positional.size() ? positional.get(i) : named.get(keys.get(i));
            if (e == null) {
                if (i < min) throw c.stop("не хватает «" + keys.get(i) + "»", call.span());
                continue;
            }
            out[i] = literal(e);
        }
        return out;
    }

    private double literal(Expr e) {
        Double folded = c.formula.fold(e);
        if (folded != null) return c.finite(folded, e.span());
        throw c.stop("здесь нужно число, а не выражение или переменная", e.span(), literalHint);
    }

    private String id(Expr e) {
        return switch (e) {
            case Ast.Var v when c.constant(v) instanceof Ast.Str s -> s.text();
            case Ast.Var v -> v.name();
            case Ast.Path p -> p.joined();
            case Ast.Str s -> s.text();
            default -> throw c.stop("здесь нужен id, например diamond или \"minecraft:diamond\"", e.span());
        };
    }

    static String material(String id) {
        return bare(id).toUpperCase(Locale.ROOT);
    }

    private static String bare(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static String dotted(String id) {
        for (String part : id.split("\\.", -1))
            if (!Keywords.plainName(part) || Wire.CALL_WORDS.contains(part)) return Decompiler.quote(id, null);
        return id;
    }

    private static String minecraft(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains(":") ? lower : "minecraft:" + lower;
    }
}
