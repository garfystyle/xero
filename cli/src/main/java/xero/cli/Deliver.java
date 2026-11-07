package xero.cli;

import xero.compile.Decompiler;
import xero.compile.Delivery;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static xero.cli.Main.ERR;
import static xero.cli.Main.OUT;

final class Deliver {
    private static final int LISTED = 12;
    static final int LINE_LIMIT = 20;

    static int upload(String[] args) throws IOException {
        String input = null, world = null, storeUrl = null;
        boolean add = false, drop = false, duplicate = false, inGame = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--add" -> add = true;
                case "--replace" -> {
                    ERR.println("--replace больше не нужен: xero upload и так заменяет код мира, а дописать — --add");
                    return 2;
                }
                case "--drop" -> drop = true;
                case "--game" -> inGame = true;
                case "--duplicate" -> duplicate = true;
                case "--world" -> {
                    if (i + 1 >= args.length) { ERR.println("После --world нужна ссылка из /editor download или файл"); return 2; }
                    world = args[++i];
                }
                case "--store" -> {
                    if (i + 1 >= args.length) { ERR.println("После --store нужен адрес хранилища: http://хост:порт/путь/"); return 2; }
                    storeUrl = args[++i];
                }
                default -> {
                    if (args[i].startsWith("--")) {
                        ERR.println("Нет флага «" + args[i] + "». У upload есть: --add, --game, --world, --drop, --duplicate, --store");
                        return 2;
                    }
                    if (input != null) { ERR.println("Лишний аргумент «" + args[i] + "»"); return 2; }
                    input = args[i];
                }
            }
        }
        if (input == null) { ERR.println("Что залить? Пример: xero upload main.xero"); return 2; }
        if (drop && add) { ERR.println("--drop и --add вместе не бывают: дописывая, сервер ничего не стирает"); return 2; }
        if (duplicate && !add) { ERR.println("--duplicate бывает только с --add: замена не задваивает"); return 2; }
        boolean replace = !add;
        Net.Store store;
        try {
            store = Net.store(storeUrl);
        } catch (IllegalArgumentException e) {
            ERR.println("Не залито: " + e.getMessage() + (storeUrl == null ? " (из " + Net.STORE_VARIABLE + ")" : ""));
            return 2;
        }
        Path in = Path.of(input);
        Map<String, Object> module;
        List<Object> connected = List.of();
        if (Project.isModule(in)) {
            module = readModule(Files.readString(in, StandardCharsets.UTF_8), in.toString());
            if (module == null) return 1;
        } else {
            Project.Built built;
            try {
                built = Project.compile(in);
            } catch (Project.Missing e) {
                ERR.println(e.getMessage());
                return 2;
            }
            built.report(ERR);
            if (!built.ok()) {
                ERR.println("Не залито: ошибок " + built.errors() + (built.warnings() > 0 ? ", предупреждений " + built.warnings() : ""));
                return 1;
            }
            module = Json.object(Json.parse(Json.write(built.module())));
            connected = built.connected();
        }
        List<Delivery.Line> project = Delivery.lines(module);
        if (project.isEmpty()) {
            ERR.println("Не залито: в проекте нет ни одной строки (when, func или process)");
            return 1;
        }
        Snapshot snap = Snapshot.load(Snapshot.locate(Snapshot.home(in)));
        if (world != null) {
            List<Object> handlers = world(world);
            if (handlers == null) return 1;
            snap.pulled(handlers);
            snap.save();
            OUT.println("Копия мира скачана: строк " + handlers.size() + ".");
        }
        Delivery.Plan plan = null;
        if (replace) {
            plan = checkReplace(snap, project, drop);
            if (plan == null) return 1;
            module = plan.module(project);
        } else if (!checkAppend(snap, project, duplicate)) {
            return 1;
        }
        String body = Json.write(module);
        int size = body.getBytes(StandardCharsets.UTF_8).length;
        if (size > Delivery.MAX_BYTES) {
            ERR.println("Не залито: модуль " + megabytes(size) + " МБ, а сервер берёт не больше 5 МБ");
            return 1;
        }
        if (inGame) {
            Game.Sent sent;
            try {
                if (plan != null && snap.exists() && plan.lineByLine(snap.world(), LINE_LIMIT)) {
                    List<Object> rows = plan.lines();
                    if (rows.isEmpty()) {
                        OUT.println("В мире уже это.");
                        connect(connected, true);
                        return 0;
                    }
                    Map<String, Object> wrapped = new java.util.LinkedHashMap<>();
                    wrapped.put("lines", rows);
                    try {
                        Game.link.lines(Json.write(wrapped));
                        OUT.println("Заменено строк: " + rows.size() + ", мир не перезапускался.");
                        snap.replaced(Json.array(module.get("handlers")));
                        snap.save();
                        connect(connected, true);
                        return 0;
                    } catch (Game.Failed e) {
                        OUT.println("Построчно не вышло (" + e.getMessage() + "), заливаю целиком.");
                    }
                }
                sent = Game.link.upload(body, replace);
                connect(connected, true);
            } catch (Game.Failed e) {
                ERR.println("Не залито: " + e.getMessage());
                return 1;
            }
            if (!sent.done()) {
                OUT.println("Отправлено в игру, но сервер не подтвердил заливку — проверь чат");
                for (String line : sent.answers()) OUT.println("  сервер: " + line);
                return 1;
            }
            OUT.println("Залито через игру: строк " + project.size() + ", блоков " + Project.blocks(Json.array(module.get("handlers")))
                    + (replace ? " — код мира заменён" : " — дописано к коду мира"));
            for (String line : sent.answers()) OUT.println("  сервер: " + line);
            if (replace) snap.replaced(Json.array(module.get("handlers")));
            else snap.appended(project);
            snap.save();
            return 0;
        }
        String url = Net.link.upload(body, store);
        String command = "/module load-url " + (replace ? "force " : "") + url;
        boolean copied = Clipboard.copy(command);
        OUT.println("Залито" + (url.startsWith(Net.OFFICIAL) ? " на запасной m.justmc.ru (хранилище не ответило)" : " в хранилище")
                + ": строк " + project.size() + ", блоков " + Project.blocks(Json.array(module.get("handlers")))
                + (replace ? " — заменит весь код мира" : " — допишется к коду мира"));
        OUT.println();
        OUT.println("    " + command);
        OUT.println();
        connect(connected, false);
        OUT.println((copied ? "Команда в буфере обмена: вставь её в чат в /dev" : "Скопируй команду и вставь в чат в /dev")
                + " " + Net.notice(url) + ".");
        if (replace) snap.replaced(Json.array(module.get("handlers")));
        else snap.appended(project);
        snap.save();
        return 0;
    }

    private static void connect(List<Object> modules, boolean inGame) throws IOException {
        for (Object entry : modules) {
            Map<String, Object> m = Json.object(entry);
            if (inGame) OUT.println("Модуль «" + Library.title(m) + "»: " + Game.link.connect(Json.write(entry)));
            else OUT.println("Модуль «" + Library.title(m) + "» подключи в игре: /module, найди его по имени " + m.get("name") + ".");
        }
    }

    static int diff(String[] args) throws IOException {
        String input = null, world = null;
        boolean inGame = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--game" -> inGame = true;
                case "--world" -> {
                    if (i + 1 >= args.length) { ERR.println("После --world нужна ссылка из /editor download"); return 2; }
                    world = args[++i];
                }
                default -> {
                    if (input != null) { ERR.println("Лишний аргумент «" + args[i] + "»"); return 2; }
                    input = args[i];
                }
            }
        }
        if (input == null) { ERR.println("Что сверить? Пример: xero diff main.xero"); return 2; }
        Path in = Path.of(input);
        Project.Built built;
        try {
            built = Project.compile(in);
        } catch (Project.Missing e) {
            ERR.println(e.getMessage());
            return 2;
        }
        built.report(ERR);
        if (!built.ok()) return 1;
        Map<String, Object> module = Json.object(Json.parse(Json.write(built.module())));
        List<Delivery.Line> project = Delivery.lines(module);
        Snapshot snap = Snapshot.load(Snapshot.locate(Snapshot.home(in)));
        List<Delivery.Line> lines;
        String where;
        try {
            if (inGame) {
                lines = Delivery.lines(Map.of("handlers", Game.link.world()));
                where = "мир прямо сейчас";
            } else if (world != null) {
                List<Object> handlers = world(world);
                if (handlers == null) return 1;
                lines = Delivery.lines(Map.of("handlers", handlers));
                where = "мир по ссылке";
            } else if (snap.exists()) {
                lines = snap.world();
                where = "копия: " + snap.describe();
            } else {
                OUT.println("Сверять не с чем: нет копии мира. Возьми её: xero pull, или сверься с игрой: --game");
                return 0;
            }
        } catch (Game.Failed e) {
            ERR.println("Мир не получен: " + e.getMessage());
            return 1;
        }
        Delivery.Plan plan = Delivery.plan(Data.load(), lines, project);
        OUT.println("Сверено: " + where + ".");
        OUT.println("Без изменений " + plan.same().size() + ", изменено " + plan.changed().size() + ", новых " + plan.added().size()
                + ", сотрётся " + plan.missing().size() + ".");
        show("изменено", plan.changed());
        show("новые", plan.added());
        show("сотрётся", plan.missing());
        return 0;
    }

    private static Delivery.Plan checkReplace(Snapshot snap, List<Delivery.Line> project, boolean drop) {
        Data data = Data.load();
        Delivery.Plan plan = Delivery.plan(data, snap.world(), project);
        if (!snap.exists()) {
            OUT.println("Сверять не с чем: этот мир Xero ещё не видел (ни xero pull, ни заливки отсюда) — заменится весь код мира.");
            return plan;
        }
        OUT.println("Сверено с копией мира (" + snap.describe() + "). Правки в /dev после неё Xero не видит.");
        if (!plan.missing().isEmpty() && !drop) {
            ERR.println("Не залито: в мире есть строки, которых нет в проекте, — замена их сотрёт:");
            list(plan.missing(), snap);
            ERR.println("  Добавь их в проект (xero pull <ссылка> выпишет их кодом)"
                    + " или, если их и правда надо стереть, добавь --drop.");
            ERR.println("  Только дописать проект, ничего не стирая: --add");
            return null;
        }
        OUT.println("Код мира заменится проектом: без изменений " + plan.same().size() + ", изменено "
                + plan.changed().size() + ", новых " + plan.added().size()
                + (plan.missing().isEmpty() ? "" : ", сотрётся " + plan.missing().size()));
        show("изменено", plan.changed());
        show("новые", plan.added());
        show("сотрётся", plan.missing());
        if (!plan.same().isEmpty()) OUT.println("  строки без изменений уйдут в мир ровно такими, какие они там сейчас");
        return plan;
    }

    private static boolean checkAppend(Snapshot snap, List<Delivery.Line> project, boolean duplicate) {
        if (!snap.exists()) {
            OUT.println("Задвоение не проверено: копии мира нет. Чтобы Xero его ловил — сначала xero pull <ссылка из /editor download>.");
        } else {
            OUT.println("Сверено с копией мира (" + snap.describe() + ").");
        }
        List<Delivery.Clash> clashes = Delivery.clashes(snap.world(), project);
        List<Delivery.Clash> doubled = clashes.stream().filter(Delivery.Clash::doubled).toList();
        if (!doubled.isEmpty() && !duplicate) {
            ERR.println("Не залито: с --add сервер не заменяет строки, а дописывает рядом — эти задвоятся:");
            for (Delivery.Clash c : doubled.subList(0, Math.min(LISTED, doubled.size())))
                ERR.println("  - " + c.project().kind().russian + " «" + c.project().name() + "» — " + where(c.world(), snap));
            if (doubled.size() > LISTED) ERR.println("  … и ещё " + (doubled.size() - LISTED));
            ERR.println("  Заменить: xero upload … без --add (проект должен содержать весь код мира) или сотри старую строку в /dev.");
            ERR.println("  Залить рядом всё равно: --duplicate");
            return false;
        }
        for (Delivery.Clash c : clashes) {
            if (c.doubled()) continue;
            OUT.println("Внимание: событие " + c.project().name() + " " + where(c.world(), snap)
                    + " — после заливки сработают обе строки.");
        }
        return true;
    }

    private static String where(Delivery.Line world, Snapshot snap) {
        java.time.Instant sent = world.json() == null ? snap.sentAt(world) : null;
        if (sent != null) return "уже отправлена " + Snapshot.when(sent) + " (если команду вставили, она в мире)";
        return world.position() >= 0 ? "уже есть в мире, строка " + world.position() : "уже есть в мире";
    }

    private static void list(List<Delivery.Line> lines, Snapshot snap) {
        for (Delivery.Line l : lines.subList(0, Math.min(LISTED, lines.size()))) {
            java.time.Instant sent = l.json() == null ? snap.sentAt(l) : null;
            ERR.println("  - " + l.title() + (sent != null ? " — отправлена " + Snapshot.when(sent) : ""));
        }
        if (lines.size() > LISTED) ERR.println("  … и ещё " + (lines.size() - LISTED));
    }

    private static void show(String what, List<Delivery.Line> lines) {
        if (lines.isEmpty()) return;
        List<String> names = new ArrayList<>();
        for (Delivery.Line l : lines.subList(0, Math.min(LISTED, lines.size()))) names.add(l.title());
        OUT.println("  " + what + ": " + String.join(", ", names) + (lines.size() > LISTED ? " … и ещё " + (lines.size() - LISTED) : ""));
    }

    static int pull(String[] args) throws IOException {
        String source = null, output = null;
        boolean overwrite = false, inGame = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "-o" -> {
                    if (i + 1 >= args.length) { ERR.println("После -o нужен файл"); return 2; }
                    output = args[++i];
                }
                case "--overwrite" -> overwrite = true;
                case "--game" -> inGame = true;
                default -> {
                    if (source != null) { ERR.println("Лишний аргумент «" + args[i] + "»"); return 2; }
                    source = args[i];
                }
            }
        }
        if (source == null && !inGame) {
            ERR.println("Откуда взять код? В /dev набери /editor download, скопируй ссылку и: xero pull <ссылка>");
            return 2;
        }
        List<Object> handlers;
        try {
            handlers = inGame ? Game.link.world() : world(source);
        } catch (Game.Failed e) {
            ERR.println("Код мира не получен: " + e.getMessage());
            return 1;
        }
        if (handlers == null) return 1;
        Path out = Path.of(output != null ? output : "world.xero");
        Path parent = out.toAbsolutePath().getParent();
        Snapshot snap = Snapshot.load(Snapshot.locate(parent));
        snap.pulled(handlers);
        snap.save();
        Data data = Data.load();
        Map<String, Object> module = Map.of("handlers", handlers);
        Decompiler.Result result = Decompiler.decompile(data, Names.build(data), module);
        String state;
        if (Files.isRegularFile(out)) {
            String old = Files.readString(out, StandardCharsets.UTF_8);
            if (old.equals(result.text())) {
                state = "файл уже такой же";
            } else if (!overwrite) {
                state = null;
            } else {
                Files.writeString(out, result.text(), StandardCharsets.UTF_8);
                state = "перезаписан";
            }
        } else {
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(out, result.text(), StandardCharsets.UTF_8);
            state = "";
        }
        String counts = "строк " + handlers.size() + ", блоков " + Project.blocks(handlers);
        if (state == null) {
            OUT.println("Код мира: " + counts + ". Файл " + out + " уже есть и отличается — не тронут.");
            OUT.println("  Перезаписать: --overwrite, или в другой файл: -o мир.xero");
        } else {
            OUT.println("Код мира: " + counts + " → " + out + (state.isEmpty() ? "" : " (" + state + ")"));
        }
        OUT.println("Копия мира для проверок перед заливкой: " + snap.file);
        Main.notes(result);
        return 0;
    }

    static List<Object> world(String source) throws IOException {
        String text;
        if (Net.looksLikeLink(source)) {
            URI link = Net.worldLink(source);
            if (link == null) {
                ERR.println("Это не ссылка /editor download: нужна вида http://api.creative.justmc.io/public/creative/handlers/…?token=…");
                return null;
            }
            text = Net.link.fetch(link);
        } else {
            Path file = Path.of(source);
            if (!Files.isRegularFile(file)) {
                ERR.println("Нет файла " + file + " — и это не ссылка из /editor download");
                return null;
            }
            text = Files.readString(file, StandardCharsets.UTF_8);
        }
        Map<String, Object> module = readModule(text, source);
        return module == null ? null : Json.array(module.get("handlers"));
    }

    private static Map<String, Object> readModule(String text, String from) {
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (Json.Error e) {
            ERR.println("Это не JSON (" + from + "): " + e.getMessage());
            return null;
        }
        if (!(parsed instanceof Map<?, ?> map) || !(map.get("handlers") instanceof List<?>)) {
            ERR.println("Нет поля handlers — это не код мира (" + from + ")");
            return null;
        }
        return Json.object(parsed);
    }

    private static String megabytes(int bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0);
    }

    private Deliver() {}
}
