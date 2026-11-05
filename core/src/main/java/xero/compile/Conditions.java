package xero.compile;

import xero.data.Data;
import xero.data.Names;
import xero.json.Json;
import xero.lang.Ast;
import xero.lang.Ast.Arg;
import xero.lang.Ast.Expr;
import xero.lang.Ast.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static xero.compile.Context.array;
import static xero.compile.Context.entry;
import static xero.compile.Context.num;
import static xero.compile.Context.obj;

final class Conditions {
    record Cond(String id, List<Object> values, Map<String, Object> selection, boolean inverted) {
        Cond negated() {
            return new Cond(id, values, selection, !inverted);
        }
    }

    sealed interface Tree permits Leaf, All, Any {}

    record Leaf(Expr expr, boolean negated) implements Tree {}

    record All(List<Tree> items) implements Tree {}

    record Any(List<Tree> items) implements Tree {}

    private final Context c;

    Conditions(Context c) {
        this.c = c;
    }

    Boolean truth(Expr e) {
        return switch (e) {
            case Ast.Unary u when u.op().equals("not") -> {
                Boolean v = truth(u.operand());
                yield v == null ? null : !v;
            }
            case Ast.Binary b when b.op().equals("and") || b.op().equals("or") -> {
                boolean all = b.op().equals("and");
                Boolean l = truth(b.left()), r = truth(b.right());
                if (l != null) yield l == all ? r : l;
                yield r != null && r != all && c.calls.pure(b.left()) ? r : null;
            }
            case Ast.Var v -> {
                Expr k = c.constant(v);
                if (k != null) yield truth(k);
                yield v.quoted() || c.known(v.name()) ? null : Context.truth(v.name());
            }
            case Ast.Num n -> n.text().equals("1") ? Boolean.TRUE : n.text().equals("0") ? Boolean.FALSE : null;
            default -> null;
        };
    }

    Map<String, Object> whileLoop(Expr condition, Ast.Block body) {
        Boolean fixed = truth(condition);
        if (Boolean.TRUE.equals(fixed)) return c.compiler.forever(body);
        if (fixed != null) throw c.stop("условие всегда ложно — повторение не выполнится ни разу", condition.span());
        Tree tree = tree(condition, false);
        if (tree instanceof Leaf leaf) {
            Context.Captured<Cond> captured = c.capture(() -> leafCond(leaf));
            if (captured.pre().isEmpty()) {
                c.loops++;
                List<Object> ops = c.compiler.block(body);
                c.loops--;
                Map<String, Object> loop = obj("action", "repeat_while", "values", captured.value().values(),
                        "conditional", obj("action", captured.value().id(), "is_inverted", captured.value().inverted()));
                if (captured.value().selection() != null) loop.put("selection", captured.value().selection());
                loop.put("operations", ops);
                return loop;
            }
            List<Object> inner = new ArrayList<>(captured.pre());
            inner.add(condOp(captured.value().negated(),
                    new ArrayList<>(List.of(obj("action", "control_stop_repeat", "values", new ArrayList<>())))));
            c.loops++;
            inner.addAll(c.compiler.block(body));
            c.loops--;
            return obj("action", "repeat_forever", "values", new ArrayList<>(), "operations", inner);
        }
        Map<String, Object> flag = flagVariable(c.flag());
        List<Object> inner = new ArrayList<>();
        inner.add(setVar(flag, 0));
        inner.addAll(compute(tree, flag));
        Map<String, Object> stop = obj("action", "if_variable_equals",
                "values", isOne(flag), "is_inverted", true,
                "operations", new ArrayList<>(List.of(obj("action", "control_stop_repeat", "values", new ArrayList<>()))));
        inner.add(stop);
        c.loops++;
        inner.addAll(c.compiler.block(body));
        c.loops--;
        return obj("action", "repeat_forever", "values", new ArrayList<>(), "operations", inner);
    }

    Tree tree(Expr e, boolean negated) {
        if (e instanceof Ast.Unary u && u.op().equals("not")) return tree(u.operand(), !negated);
        if (e instanceof Ast.Binary b && (b.op().equals("and") || b.op().equals("or"))) {
            boolean all = b.op().equals("and") != negated;
            List<Tree> items = new ArrayList<>();
            for (Expr side : List.of(b.left(), b.right())) {
                Boolean fixed = truth(side);
                if (fixed != null && fixed != negated == all) continue;
                Tree t = tree(side, negated);
                if (all && t instanceof All a) items.addAll(a.items());
                else if (!all && t instanceof Any a) items.addAll(a.items());
                else items.add(t);
            }
            if (items.isEmpty()) return new Leaf(new Ast.Num(all ? "1" : "0", b.span()), false);
            if (items.size() == 1) return items.get(0);
            Tree joined = joined(items, all);
            if (joined != null) return joined;
            return all ? new All(items) : new Any(items);
        }
        return new Leaf(e, negated);
    }

    private Tree joined(List<Tree> items, boolean all) {
        Ast.Var left = null;
        List<Expr> rights = new ArrayList<>();
        for (Tree t : items) {
            if (!(t instanceof Leaf leaf) || !(leaf.expr() instanceof Ast.Binary b) || !(b.left() instanceof Ast.Var v)) return null;
            if (left != null && (!left.name().equals(v.name()) || !Objects.equals(left.scope(), v.scope()))) return null;
            left = v;
            if (b.op().equals("in") && b.right() instanceof Ast.ListLit l && leaf.negated() == all) {
                rights.addAll(l.items());
                continue;
            }
            if (!b.op().equals("==") && !b.op().equals("!=")) return null;
            if (b.op().equals("==") != leaf.negated() == all || !plain(b.right())) return null;
            rights.add(b.right());
        }
        Data.Arg compare = c.data.actions().get("if_variable_equals").arg("compare");
        if (rights.size() < 2 || compare.capacity() > 0 && rights.size() > compare.capacity()) return null;
        return new Leaf(new Ast.Binary("in", left, new Ast.ListLit(rights, left.span()), left.span()), all);
    }

    private static boolean plain(Expr e) {
        return e instanceof Ast.Num || e instanceof Ast.Var || e instanceof Ast.Str s && !s.interpolated()
                || e instanceof Ast.Unary u && u.op().equals("-") && u.operand() instanceof Ast.Num;
    }

    Cond leafCond(Leaf leaf) {
        Cond cond = condition(leaf.expr());
        return leaf.negated() ? cond.negated() : cond;
    }

    static Map<String, Object> flagVariable(String flag) {
        return obj("type", "variable", "variable", flag, "scope", "line");
    }

    static Map<String, Object> setVar(Map<String, Object> variable, int value) {
        return obj("action", "set_variable_value", "values", new ArrayList<>(List.of(
                entry("variable", variable), entry("value", obj("type", "number", "number", num(value))))));
    }

    private static List<Object> isOne(Map<String, Object> variable) {
        return new ArrayList<>(List.of(entry("value", variable),
                entry("compare", array(new ArrayList<>(List.of(obj("type", "number", "number", num(1))))))));
    }

    private static Map<String, Object> condOp(Cond cond, List<Object> body) {
        Map<String, Object> op = obj("action", cond.id(), "values", cond.values());
        if (cond.selection() != null) op.put("selection", cond.selection());
        if (cond.inverted()) op.put("is_inverted", true);
        op.put("operations", body);
        return op;
    }

    private Cond branchCond(Tree t) {
        if (t instanceof Leaf leaf) return leafCond(leaf);
        Map<String, Object> flag = flagVariable(c.flag());
        List<Object> pre = c.need(null);
        pre.add(setVar(flag, 0));
        pre.addAll(compute(t, flag));
        return new Cond("if_variable_equals", isOne(flag), null, false);
    }

    List<Object> compute(Tree tree, Map<String, Object> variable) {
        List<Object> out = new ArrayList<>();
        switch (tree) {
            case Leaf leaf -> {
                Context.Captured<Cond> captured = c.capture(() -> leafCond(leaf));
                out.addAll(captured.pre());
                out.add(condOp(captured.value(), new ArrayList<>(List.of(setVar(variable, 1)))));
            }
            case Any any -> {
                boolean first = true;
                for (Tree t : any.items()) {
                    Context.Captured<List<Object>> part = c.capture(() -> compute(t, variable));
                    List<Object> ops = new ArrayList<>(part.pre());
                    ops.addAll(part.value());
                    if (!first && part.calls()) out.add(condOp(new Cond("if_variable_equals", isOne(variable), null, true), ops));
                    else out.addAll(ops);
                    first = false;
                }
            }
            case All all -> {
                List<List<Object>> pres = new ArrayList<>();
                List<Cond> guards = new ArrayList<>();
                for (Tree t : all.items()) {
                    if (t instanceof Leaf leaf) {
                        Context.Captured<Cond> captured = c.capture(() -> leafCond(leaf));
                        pres.add(captured.pre());
                        guards.add(captured.value());
                    } else {
                        Map<String, Object> tmp = flagVariable(c.flag());
                        Context.Captured<List<Object>> part = c.capture(() -> compute(t, tmp));
                        List<Object> setup = new ArrayList<>();
                        setup.add(setVar(tmp, 0));
                        setup.addAll(part.pre());
                        setup.addAll(part.value());
                        if (part.calls()) {
                            pres.add(setup);
                        } else {
                            out.addAll(setup);
                            pres.add(List.of());
                        }
                        guards.add(new Cond("if_variable_equals", isOne(tmp), null, false));
                    }
                }
                List<Object> inner = new ArrayList<>(List.of(setVar(variable, 1)));
                for (int i = guards.size() - 1; i >= 0; i--) {
                    List<Object> wrapped = new ArrayList<>(pres.get(i));
                    wrapped.add(condOp(guards.get(i), inner));
                    inner = wrapped;
                }
                out.addAll(inner);
            }
        }
        return out;
    }

    private Cond fixed(boolean value, Span at) {
        Data.Action action = c.data.actions().get("if_variable_equals");
        List<Arg> args = List.of(new Arg(null, null, new Ast.Num("1", at)), new Arg(null, null, new Ast.Num(value ? "1" : "0", at)));
        return new Cond(action.id(), c.compiler.bind(action, args, null, at), null, false);
    }

    Cond condition(Expr e) {
        Boolean known = truth(e);
        if (known != null) return fixed(known, e.span());
        return switch (e) {
            case Ast.Unary u when u.op().equals("not") -> condition(u.operand()).negated();
            case Ast.Binary b when Wire.COMPARE.containsKey(b.op()) -> {
                Data.Action action = c.data.actions().get(Wire.COMPARE.get(b.op()));
                Expr right = c.resolved(b.right()) instanceof Ast.ListLit l ? new Ast.ListLit(List.of(l), l.span()) : b.right();
                List<Arg> args = List.of(new Arg(null, null, b.left()), new Arg(null, null, right));
                yield new Cond(action.id(), c.compiler.bind(action, args, null, b.span()), null, b.op().equals("!="));
            }
            case Ast.Binary b when b.op().equals("and") || b.op().equals("or") ->
                    throw c.stop("здесь «" + b.op() + "» нельзя", b.span());
            case Ast.Binary b when b.op().equals("in") -> membership(b);
            case Ast.Var v -> flag(v);
            case Ast.Index i -> condition(new Ast.Binary("==", i, new Ast.Num("1", i.span()), i.span()));
            case Ast.Targeted t when t.inner() instanceof Ast.Call call -> conditionCall(call, t.target(), t.targetSpan());
            case Ast.Call call when c.userCall(call) -> condition(new Ast.Binary("==", call, new Ast.Num("1", call.span()), call.span()));
            case Ast.Call call -> conditionCall(call, null, null);
            default -> throw c.stop("условие — это сравнение (x == 1) или проверка (has_item(…))", e.span());
        };
    }

    private Cond flag(Ast.Var v) {
        String kind = c.kind(v);
        if (kind != null && !kind.equals(Kinds.BOOL))
            throw c.stop("«" + v.name() + "» — " + Kinds.lower(Kinds.slot(kind)) + ": условие — это сравнение", v.span(),
                    "напиши, с чем сравнить: " + v.name() + " == …");
        Data.Action action = c.data.actions().get("if_variable_equals");
        List<Arg> args = List.of(new Arg(null, null, v), new Arg(null, null, new Ast.Num("1", v.span())));
        return new Cond(action.id(), c.compiler.bind(action, args, null, v.span()), null, false);
    }

    private Cond membership(Ast.Binary b) {
        Expr right = c.resolved(b.right());
        if (right instanceof Ast.Binary range && range.op().equals("..")) {
            Data.Action action = c.data.actions().get("if_variable_number_in_range");
            return new Cond(action.id(), c.compiler.bind(action, List.of(Context.named("value", b.left()),
                    Context.named("min", range.left()), Context.named("max", range.right()),
                    Context.named("including", new Ast.Var("include_all", false, range.span()))), null, b.span()), null, false);
        }
        if (right instanceof Ast.ListLit l) {
            if (l.items().isEmpty()) throw c.stop("в пустом списке ничего нет — условие всегда ложно", right.span());
            Data.Action action = c.data.actions().get("if_variable_equals");
            return new Cond(action.id(), c.compiler.bind(action, List.of(Context.named("value", b.left()),
                    Context.named("compare", l)), null, b.span()), null, false);
        }
        String shape = right instanceof Ast.MapLit ? "map" : right instanceof Ast.Var v ? c.shape(v.name()) : null;
        if ("list".equals(shape)) {
            Data.Action action = c.data.actions().get("if_variable_list_contains_value");
            return new Cond(action.id(), c.compiler.bind(action, List.of(Context.named("list", right),
                    Context.named("values", b.left())), null, b.span()), null, false);
        }
        if ("map".equals(shape)) {
            Data.Action action = c.data.actions().get("if_variable_map_has_key");
            return new Cond(action.id(), c.compiler.bind(action, List.of(Context.named("map", right),
                    Context.named("key", b.left())), null, b.span()), null, false);
        }
        String name = right instanceof Ast.Var v ? v.name() : "это значение";
        throw c.stop("не понятно, «" + name + "» — список или словарь", right.span(),
                "скажи это при объявлении: save " + name + ": map или save " + name + ": list");
    }

    private Cond conditionCall(Ast.Call call, String target, Span targetSpan) {
        Names.Entry e = c.names.resolve(Names.Space.CONDITION, call.shown());
        if (e == null) throw c.hints.unknownCall(call, Names.Space.CONDITION);
        c.hints.spare(e, call.nameSpan());
        Data.Action action = e.action();
        List<Object> values = c.compiler.bind(action, call.args(), null, call.span());
        Map<String, Object> selection = target == null ? null : c.compiler.selection(action, target, targetSpan);
        if (target == null) c.compiler.noTarget(action, call.nameSpan());
        return new Cond(action.id(), values, selection, false);
    }

    private void discard(Ast.Block body) {
        c.capture(() -> c.compiler.block(body));
    }

    void ifChain(List<Ast.Branch> given, Ast.Block orElse, List<Object> ops) {
        List<Ast.Branch> branches = new ArrayList<>();
        Ast.Block otherwise = orElse;
        boolean closed = false;
        for (Ast.Branch b : given) {
            Boolean fixed = closed ? null : truth(b.condition());
            if (closed || Boolean.FALSE.equals(fixed)) {
                discard(b.body());
            } else if (Boolean.TRUE.equals(fixed)) {
                if (orElse != null) discard(orElse);
                otherwise = b.body();
                closed = true;
            } else {
                branches.add(b);
            }
        }
        if (branches.isEmpty()) {
            if (otherwise != null) ops.addAll(c.compiler.block(otherwise));
            return;
        }
        List<Tree> trees = new ArrayList<>();
        for (Ast.Branch b : branches) trees.add(tree(b.condition(), false));
        if (branches.size() == 1 && otherwise == null && trees.get(0) instanceof All all
                && all.items().stream().allMatch(t -> t instanceof Leaf)) {
            List<Context.Captured<Cond>> guards = new ArrayList<>();
            try {
                for (Tree t : all.items()) guards.add(c.capture(() -> leafCond((Leaf) t)));
            } catch (Context.Stop s) {
                c.compiler.block(branches.get(0).body());
                return;
            }
            List<Object> inner = c.compiler.block(branches.get(0).body());
            for (int g = guards.size() - 1; g >= 0; g--) {
                List<Object> wrapped = new ArrayList<>(guards.get(g).pre());
                wrapped.add(condOp(guards.get(g).value(), inner));
                inner = wrapped;
            }
            ops.addAll(inner);
            return;
        }
        List<Context.Captured<Cond>> conds = new ArrayList<>();
        for (Tree t : trees) {
            try {
                conds.add(c.capture(() -> branchCond(t)));
            } catch (Context.Stop s) {
                conds.add(null);
            }
        }
        chain(branches, conds, 0, otherwise, ops);
    }

    private void chain(List<Ast.Branch> branches, List<Context.Captured<Cond>> conds, int from, Ast.Block otherwise,
                       List<Object> ops) {
        List<Object> hoisted = new ArrayList<>();
        List<Object> chain = new ArrayList<>();
        for (int n = from; n < branches.size(); n++) {
            Context.Captured<Cond> captured = conds.get(n);
            if (n > from && captured != null && captured.calls()) {
                List<Object> inner = new ArrayList<>();
                chain(branches, conds, n, otherwise, inner);
                chain.add(obj("action", "else", "values", new ArrayList<>(), "operations", inner));
                ops.addAll(hoisted);
                ops.addAll(chain);
                return;
            }
            List<Object> body = c.compiler.block(branches.get(n).body());
            if (captured == null) continue;
            hoisted.addAll(captured.pre());
            Cond cond = captured.value();
            Map<String, Object> op;
            if (n == from) {
                op = obj("action", cond.id(), "values", cond.values());
                if (cond.selection() != null) op.put("selection", cond.selection());
                if (cond.inverted()) op.put("is_inverted", true);
            } else {
                op = obj("action", "else", "values", cond.values(),
                        "conditional", obj("action", cond.id(), "is_inverted", cond.inverted()));
                if (cond.selection() != null) op.put("selection", cond.selection());
            }
            op.put("operations", body);
            chain.add(op);
        }
        if (otherwise != null) chain.add(obj("action", "else", "values", new ArrayList<>(),
                "operations", c.compiler.block(otherwise)));
        ops.addAll(hoisted);
        ops.addAll(chain);
    }

    void ternary(Ast.Ternary t, Ast.Var into, List<Object> out) {
        Boolean fixed = truth(t.condition());
        if (fixed != null) {
            Context.Captured<List<Object>> one = c.capture(() -> assigned(into, fixed ? t.then() : t.otherwise()));
            out.addAll(one.pre());
            out.addAll(one.value());
            return;
        }
        Tree tree = tree(t.condition(), false);
        Context.Captured<Cond> cond = c.capture(() -> branchCond(tree));
        Context.Captured<List<Object>> yes = c.capture(() -> assigned(into, t.then()));
        Context.Captured<List<Object>> no = c.capture(() -> assigned(into, t.otherwise()));
        out.addAll(cond.pre());
        List<Object> thenOps = new ArrayList<>(yes.pre());
        thenOps.addAll(yes.value());
        List<Object> elseOps = new ArrayList<>(no.pre());
        elseOps.addAll(no.value());
        out.add(condOp(cond.value(), thenOps));
        out.add(obj("action", "else", "values", new ArrayList<>(), "operations", elseOps));
    }

    private List<Object> assigned(Ast.Var into, Expr value) {
        List<Object> ops = new ArrayList<>();
        c.compiler.assign(new Ast.Assign(into.scope(), into.name(), into.span(), "=", value, value.span(),
                into.scope() != null), ops);
        return ops;
    }

    Object conditionValue(Expr e) {
        List<Object> pre = c.need(e.span());
        Ast.Var tmp = c.temp(e.span());
        Map<String, Object> into = Json.object(c.compiler.variable(tmp, null));
        Context.Captured<List<Object>> ops = c.capture(() -> compute(tree(e, false), into));
        pre.add(setVar(into, 0));
        pre.addAll(ops.pre());
        pre.addAll(ops.value());
        return into;
    }

    Object ternaryValue(Ast.Ternary t) {
        List<Object> pre = c.need(t.span());
        Ast.Var tmp = c.temp(t.span());
        List<Object> ops = new ArrayList<>();
        ternary(t, tmp, ops);
        pre.addAll(ops);
        return c.compiler.variable(tmp, null);
    }

    boolean isCondition(Expr e) {
        return switch (e) {
            case Ast.Binary b -> Wire.COMPARE.containsKey(b.op()) || Wire.LOGIC.contains(b.op());
            case Ast.Unary u -> u.op().equals("not");
            case Ast.Targeted t -> t.inner() instanceof Ast.Call call && conditionName(call);
            case Ast.Call call -> conditionName(call);
            default -> false;
        };
    }

    boolean conditionName(Ast.Call call) {
        if (call.quoted()) return false;
        if (c.userCall(call)) return false;
        if (call.qualifier() == null && (Wire.CALL_WORDS.contains(call.name()) || c.formula.mathCall(call))) return false;
        return c.names.resolve(Names.Space.ACTION, call.shown()) == null
                && c.names.resolve(Names.Space.CONDITION, call.shown()) != null;
    }
}
