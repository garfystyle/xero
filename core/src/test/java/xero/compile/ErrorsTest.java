package xero.compile;

import org.junit.jupiter.api.Test;
import xero.data.Data;
import xero.data.Names;
import xero.lang.Diagnostic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorsTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    private record Case(String code, boolean error, String message, String hint) {}

    private static Case e(String body, String message, String hint) {
        return new Case("when player_join {\n" + body + "\n}", true, message, hint);
    }

    private static Case w(String body, String message, String hint) {
        return new Case("when player_join {\n" + body + "\n}", false, message, hint);
    }

    private static Case file(String code, boolean error, String message, String hint) {
        return new Case(code, error, message, hint);
    }

    private static final List<Case> CASES = List.of(
            file("when player_jion {}", true, "нет события «player_jion»", "player_join"),
            file("when зашёл {}", true, "нет события «зашёл»", "player_join"),
            e("send_mesage(\"a\")", "нет действия «send_mesage»", "send_message"),
            e("отправить_сообщение(\"a\")", "нет действия «отправить_сообщение»", "send_message"),
            e("teleport(loc(0, 64, 0))", "player.teleport или entity.teleport", null),
            e("has_item(item(stone))", "это условие, его пишут после if", null),
            e("if send_message(\"a\") {}", "это действие, а не условие", null),
            file("send_message(\"a\")", true, "код вне них не выполняется", null),
            e("save score = 0\nsend_message(scroe)", "переменная «scroe» нигде не задана", "score"),
            e("send_message(points)", "переменная «points» нигде не задана", "задай её"),
            e("give_items(item(stone), amout: 2)", "нет аргумента «amout»", "amount"),
            e("wait(1, time_unit: second)", "нет варианта «second»", "seconds"),
            e("wait(1, time_unit: 5)", "вариант пишется словом", null),
            e("wait(1, ticks, 5)", "лишний аргумент", null),
            e("wait(duration: 1, duration: 2)", "указан дважды", null),
            e("wait(loc(0, 0, 0))", "нужно «число», а передано «местоположение»", "Ожидал аргумент"),
            e("wait(\"abc\")", "«abc» — не число", "Ожидал аргумент"),
            e("player.teleport(5)", "нужно «местоположение», а передано «число»", "loc(x, y, z)"),
            e("player.teleport(\"1 2 3\")", "нужно «местоположение», а передано «текст»", null),
            e("give_items(block(stone))", "нужно «предмет», а передано «блок»", "item(id)"),
            e("player.teleport(value.current_health)", "нужно «местоположение», а передано «число»", null),
            e("var.value(\"x\", 5)", "нужно «переменная», а передано «текст»", null),
            e("if game.block_equals(value.location, item(stone)) {}", "нужно «местоположение», а передано «предмет»", null),
            e("if game.block_equals(value.location, item(stone)) {}", "аргументы перепутаны местами", "block_equals(blocks: …, location: …)"),
            w("give_potion_effect(\"speed\")", "эффекта не выдаёт", "potion(id)"),
            e("give_items(item(diamnd))", "нет предмета «diamnd»", "diamond"),
            e("x = block(stne)", "нет блока «stne»", "stone"),
            e("x = sound(entity.player.levelupp)", "нет звука", "entity.player.levelup"),
            e("x = particle(flam)", "нет частицы", "FLAME"),
            e("x = potion(sped)", "нет зелья «sped»", "speed"),
            e("x = value.helth", "нет игрового значения «helth»", null),
            e("@al send_message(\"x\")", "нет цели @al", "all"),
            e("@all wait(1)", "нет цели", null),
            e("break", "break бывает только внутри повторения", null),
            e("continue", "continue бывает только внутри повторения", null),
            e("return", "return бывает только в функции", null),
            e("x = \"a\" + 1", "текст в формуле не складывают", "${x}"),
            e("x = 1\nselect.player_by_conditional(x == 1 and x == 2)", "and/or нельзя", null),
            e("x = 1\ny = foo(x) + 1", "в формуле нет функции «foo»", null),
            e("y = @killer value.current_health + 1", "в формуле игровое значение берётся без цели", null),
            e("send_message(\"счёт: ${nope}\")", "переменная «nope» нигде не задана", null),
            e("y = value.location + 1", "— местоположение, а не число", null),
            e("x = 1\ny = clamp(x) * 2", "у clamp() другое число аргументов", null),
            file("import \"нет.xero\"\nwhen player_join {}", true, "нет файла «нет.xero»", null),
            e("for i, x in 1..3 {}", "у диапазона одна переменная", null),
            e("for x 1..3 {}", "ожидалось in", null),
            e("send_message(\"a)", "строка не закрыта кавычкой", null),
            file("when player_join {\nsend_message(\"a\")\n", true, "блок не закрыт", null),
            e("send_message(\"a\") send_message(\"b\")", "после команды нужен перевод строки", null),
            file("func send_message() {}", true, "занято действием", null),
            file("func kit() {}\nfunc kit() {}", true, "уже объявлена", null),
            file("func kit() {}\nwhen player_join {\nkitt()\n}", true, "нет действия «kitt»", "kit"),
            file("func kit(n) {}\nwhen player_join {\nkit()\n}", true, "не передан параметр «n»", null),
            file("func kit(n) {}\nwhen player_join {\nkit(1, 2)\n}", true, "лишний аргумент", null),
            file("func kit(n) {}\nwhen player_join {\nkit(m: 1)\n}", true, "нет параметра «m»", "n"),
            file("func kit() {}\nwhen player_join {\nstart kit()\n}", true, "функция: вызывай kit()", null),
            file("func kit(n: number) {}\nwhen player_join {\nkit(loc(0, 0, 0))\n}", true, "нужно «число», а передано «местоположение»", null),
            e("save x = 1\ngame x = 2", "уже объявлена как save", null),
            e("add_custom_crafting_recipe(\"r\", item(stone), [_, _, _, _, _, _, _, _, _, _])", "помещается 9", null),
            w("wait(num(\"%math(1+\"))", "не закрыта скобка", null),
            w("wait(num(\"%math(sqr(4))\"))", "нет функции sqr()", "sqrt"),
            w("wait(num(\"%math(clamp(1, 2))\"))", "у clamp() в %math аргументов 3, а передано 2", null),
            w("wait(num(\"abc\"))", "«abc» — не число", null),
            w("send_message(\"%playr% привет\")", "нет плейсхолдера %playr%", "%player%"),
            w("send_message(\"%vr(x)\")", "нет плейсхолдера %vr(…)", "var"),
            file("when time_skip {\nsend_message(\"x\")\n}", false, "в событии мира у игрока нет цели", "@all"),
            file("@hiden\nfunc f() {}", true, "нет пометки @hiden", "hidden"),
            e("x = send_message(\"a\")", "ничего не записывает в переменную", null),
            e("give_items(item(stone, 100))", "от 1 до 99", null),
            e("x = sound(ui.button.click, source: loud)", "нет источника «loud»", null),
            e("x = item()", "item(id) или item(id, количество)", null),
            e("x = loc(1, 2)", "loc(x, y, z)", null),
            e("x = " + "9".repeat(400), "число слишком большое", "1,8·10³⁰⁸"),
            e("x = " + "9".repeat(200) + " * " + "9".repeat(200), "число слишком большое", null),
            e("y = 1\nx = y + " + "9".repeat(400), "число слишком большое", null),
            e("player.teleport(loc(" + "9".repeat(400) + ", 0, 0))", "число слишком большое", null),
            w("x = 1 / 0", "деление на ноль — сервер молча даст 0", null),
            w("y = 2\nx = y % (3 - 3)", "деление на ноль", null),
            e("give_items(item(diamond, 5.5))", "количество предметов — целое от 1 до 99", null),
            e("give_items(item(diamond, count: 0.5))", "количество предметов — целое от 1 до 99", null),
            e("x = particle(flame, count: -3)", "количество частиц — целое от 0", null),
            e("x = particle(flame, count: 1.5)", "количество частиц — целое от 0", null),
            e("x = potion(speed, duration: 2.5)", "длительность зелья — целое число тиков", "-1"),
            e("x = potion(speed, amplifier: 0.5)", "уровень зелья — целое число", null),
            w("x = potion(speed, duration: -5)", "длительность меньше -1", "бесконечно — -1"),
            w("x = potion(speed, amplifier: 300)", "уровень зелья вне 0–255", null),
            w("x = item(stick, model: 16777217)", "model хранится с меньшей точностью", null),
            w("x = 1\nif in_range(x, 0, 10) {}", "«in_range» — запасной id if_variable_in_range", null),
            file("when player_join {\n    x = 1\n}\nwhen player_quit {\n    send_message(\"${x}\")\n}", false,
                    "«x» — локальная: её задают только в when player_join, а в when player_quit она пустая", "game x"));

    @Test
    void everyMistakeGetsAClearMessage() {
        List<String> failed = new ArrayList<>();
        for (Case c : CASES) {
            Compiler.Result r = Compiler.compile(DATA, NAMES, c.code());
            Diagnostic found = null;
            for (Diagnostic d : r.diagnostics())
                if (d.error() == c.error() && d.message().contains(c.message())) { found = d; break; }
            if (found == null) {
                failed.add(c.code().replace('\n', ' ') + " → ждали «" + c.message() + "», а есть " + r.diagnostics());
                continue;
            }
            if (c.hint() != null && (found.hint() == null || !found.hint().contains(c.hint())))
                failed.add(c.code().replace('\n', ' ') + " → подсказка «" + found.hint() + "», ждали «" + c.hint() + "»");
            if (!c.error() && !r.ok())
                failed.add(c.code().replace('\n', ' ') + " → должно собираться с предупреждением: " + r.diagnostics());
        }
        assertEquals(List.of(), failed);
        assertTrue(CASES.size() >= 50, "случаев " + CASES.size());
    }

    @Test
    void worldEventWithSelectionOrTargetIsQuiet() {
        Compiler.Result a = Compiler.compile(DATA, NAMES, "when time_skip {\n@all send_message(\"x\")\n}");
        assertEquals(List.of(), a.diagnostics());
        Compiler.Result b = Compiler.compile(DATA, NAMES, "when player_join {\nsend_message(\"x\")\n}");
        assertEquals(List.of(), b.diagnostics());
    }

    @Test
    void conversionsTheServerAccepts() {
        String ok = """
                when player_join {
                    send_message([5, loc(1, 2, 3), item(diamond), block(stone)])
                    give_items("emerald")
                    give_items(item(stick), amount: "3")
                    if game.block_equals(blocks: item(stone), location: value.location) {}
                    if game.block_equals(blocks: "stone", location: value.location) {}
                    play_sound(sound: "minecraft:ui.button.click")
                    set_velocity(velocity: "0 0 0")
                    x = 1
                    x += "5"
                    wait(num("%math(%var(x)+1)"))
                }
                """;
        Compiler.Result r = Compiler.compile(DATA, NAMES, ok);
        assertEquals(List.of(), r.diagnostics().stream().filter(Diagnostic::error).toList());
    }
}
