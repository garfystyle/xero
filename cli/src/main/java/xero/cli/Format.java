package xero.cli;

import xero.format.Formatter;
import xero.lang.Diagnostic;
import xero.lang.Source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static xero.cli.Main.ERR;
import static xero.cli.Main.OUT;

final class Format {
    static int run(String[] args) throws IOException {
        boolean check = false, stdin = false;
        List<String> inputs = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--check" -> check = true;
                case "-" -> stdin = true;
                default -> {
                    if (args[i].startsWith("--")) { ERR.println("Нет флага «" + args[i] + "». У format есть: --check, -"); return 2; }
                    inputs.add(args[i]);
                }
            }
        }
        if (stdin) {
            if (!inputs.isEmpty()) { ERR.println("«-» читает код из ввода — файлы с ним не пишут"); return 2; }
            String text = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            Formatter.Result r = Formatter.format(text);
            if (!r.ok()) {
                report("<ввод>", text, r.errors());
                return 1;
            }
            OUT.print(r.text());
            return 0;
        }
        if (inputs.isEmpty()) { ERR.println("Что форматировать? Пример: xero format main.xero или xero format ."); return 2; }
        List<Path> files = new ArrayList<>();
        for (String input : inputs) {
            Path p = Path.of(input);
            if (Files.isDirectory(p)) files.addAll(Project.sources(p));
            else if (Files.isRegularFile(p)) files.add(p);
            else { ERR.println("Нет файла " + p); return 2; }
        }
        int changed = 0, broken = 0;
        List<Path> unformatted = new ArrayList<>();
        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            Formatter.Result r = Formatter.format(text);
            if (!r.ok()) {
                report(f.toString(), text, r.errors());
                broken++;
                continue;
            }
            if (r.text().equals(text)) continue;
            changed++;
            if (check) unformatted.add(f);
            else Files.writeString(f, r.text(), StandardCharsets.UTF_8);
        }
        if (check) {
            for (Path f : unformatted) OUT.println("не в стиле: " + f);
            OUT.println(unformatted.isEmpty() && broken == 0 ? "Все файлы в стиле: " + files.size()
                    : "Не в стиле: " + unformatted.size() + " из " + files.size() + (broken > 0 ? ", с ошибками: " + broken : ""));
            return unformatted.isEmpty() && broken == 0 ? 0 : 1;
        }
        OUT.println("Отформатировано файлов: " + files.size() + ", изменено: " + changed
                + (broken > 0 ? ", пропущено с ошибками: " + broken : ""));
        return broken > 0 ? 1 : 0;
    }

    private static void report(String name, String text, List<Diagnostic> errors) {
        Source source = new Source(name, text);
        for (Diagnostic d : errors) ERR.print(d.render(source));
        ERR.println("Не отформатирован " + name + ": сначала исправь ошибки разбора");
    }

    private Format() {}
}
