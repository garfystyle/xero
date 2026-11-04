package xero.cli;

import xero.compile.Delivery;
import xero.data.Data;
import xero.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static xero.cli.Main.ERR;
import static xero.cli.Main.OUT;

final class Watch {
    private static final long POLL_MS = 300;
    private static final long SETTLE_MS = 150;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    static int run(String[] args) throws IOException {
        String input = null, output = null;
        boolean pretty = false, inGame = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "-o" -> {
                    if (i + 1 >= args.length) { ERR.println("После -o нужен файл"); return 2; }
                    output = args[++i];
                }
                case "--pretty" -> pretty = true;
                case "--game" -> inGame = true;
                default -> {
                    if (input != null) { ERR.println("Лишний аргумент «" + args[i] + "»"); return 2; }
                    input = args[i];
                }
            }
        }
        if (input == null) { ERR.println("За чем следить? Пример: xero watch main.xero"); return 2; }
        Path in = Path.of(input);
        if (!Files.exists(in)) { ERR.println("Нет файла " + in); return 2; }
        Path out = Project.output(in, output);
        OUT.println("Слежу за " + in + ": собираю при каждом сохранении в " + out + ". Выход — Ctrl+C.");
        Set<Path> tracked = new LinkedHashSet<>();
        tracked.add(in);
        Map<Path, String> seen = null;
        while (true) {
            Map<Path, String> now = stamps(in, tracked);
            if (!now.equals(seen)) {
                if (seen != null) {
                    pause(SETTLE_MS);
                    now = stamps(in, tracked);
                }
                Set<Path> next = once(in, out, pretty, tracked, inGame);
                seen = next.equals(tracked) ? now : stamps(in, next);
                tracked = next;
            }
            pause(POLL_MS);
        }
    }

    static void send(Path in, Project.Built built, String at) throws IOException {
        Map<String, Object> module = Json.object(Json.parse(Json.write(built.module())));
        List<Delivery.Line> project = Delivery.lines(module);
        Snapshot snap = Snapshot.load(Snapshot.locate(Snapshot.home(in)));
        Delivery.Plan plan = Delivery.plan(Data.load(), snap.world(), project);
        if (snap.exists() && !plan.missing().isEmpty()) {
            ERR.println(at + "Не залито: замена сотрёт строк " + plan.missing().size() + " — смотри xero diff, стереть нарочно: xero upload --drop");
            return;
        }
        if (snap.exists() && plan.changed().isEmpty() && plan.added().isEmpty()) {
            OUT.println(at + "В мире уже это.");
            return;
        }
        Map<String, Object> body = plan.module(project);
        try {
            Game.Sent sent = Game.link.upload(Json.write(body), true);
            OUT.println(at + "Залито в игру" + (sent.answers().isEmpty() ? "." : ": " + String.join(" · ", sent.answers())));
            snap.replaced(Json.array(body.get("handlers")));
            snap.save();
        } catch (Game.Failed e) {
            ERR.println(at + "Не залито: " + e.getMessage());
        }
    }

    private static Set<Path> once(Path in, Path out, boolean pretty, Set<Path> tracked, boolean inGame) {
        String at = "[" + LocalTime.now().format(CLOCK) + "] ";
        try {
            Project.Built built = Project.compile(in);
            built.report(ERR);
            if (!built.ok()) {
                ERR.println(at + "Не собрано: ошибок " + built.errors() + (built.warnings() > 0 ? ", предупреждений " + built.warnings() : ""));
            } else {
                String written = pretty ? Json.pretty(built.module()) : Json.write(built.module());
                Files.writeString(out, written + System.lineSeparator(), StandardCharsets.UTF_8);
                OUT.println(at + "Собрано: строк " + built.lines() + ", блоков "
                        + Project.blocks(Json.array(built.module().get("handlers")))
                        + (built.warnings() > 0 ? ", предупреждений " + built.warnings() : "") + " → " + out);
                if (inGame) send(in, built, at);
            }
            return built.files();
        } catch (IOException e) {
            ERR.println(at + e.getMessage());
            return tracked;
        }
    }

    private static Map<Path, String> stamps(Path in, Set<Path> tracked) {
        Map<Path, String> out = new LinkedHashMap<>();
        Set<Path> all = new LinkedHashSet<>(tracked);
        if (Files.isDirectory(in)) {
            try {
                List<Path> found = Project.sources(in);
                all.addAll(found);
            } catch (IOException e) {
                out.put(in, "?");
            }
        }
        for (Path p : all) {
            try {
                out.put(p, Files.isRegularFile(p) ? Files.getLastModifiedTime(p).toMillis() + ":" + Files.size(p) : "-");
            } catch (IOException e) {
                out.put(p, "?");
            }
        }
        return out;
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("прервано");
        }
    }

    private Watch() {}
}
