package xero.cli;

import xero.compile.Decompiler;
import xero.data.Data;
import xero.data.Names;
import xero.json.Json;

import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Main {
    private static final String VERSION = version();
    private static final int FIND_LIMIT = 40;

    private static final String USAGE = """
            Xero %s — компилятор кода JustMC

              xero build ПУТЬ            собрать .xero (файл или папку) в JSON для /module loadUrl
                   -o ФАЙЛ.json          куда записать (по умолчанию рядом)
                   --pretty              с отступами
                   --json                ошибки списком JSON (для редакторов)
              xero watch ПУТЬ            собирать при каждом сохранении (-o, --pretty — как у build)
                   --game                и сразу заливать в игру с модом XeroCode
              xero format ПУТЬ…          привести .xero (файлы или папки) к одному стилю, комментарии
                                         остаются на месте
                   --check               только проверить: код выхода 1, если что-то не в стиле
                   -                     читать код из ввода, писать в вывод (для редакторов)
              xero upload ПУТЬ           залить в мир, заменив весь код мира (force): команда
                                         /module loadUrl … сразу в буфере обмена; отказ, если в
                                         последней копии мира есть строки, которых нет в проекте
                   --add                 не заменять, а дописать к коду мира, ничего не стирая
                   --game                отправить сразу в игру с модом XeroCode, без буфера обмена
                   --world ССЫЛКА        сначала скачать копию мира: ссылка из /editor download
                   --drop                стереть строки мира, которых нет в проекте
                   --duplicate           с --add: дописать, даже если такая функция в мире уже есть
                   --store АДРЕС         своё хранилище кода (иначе XERO_STORE или хранилище Xero)
              xero pull ССЫЛКА           код мира (/editor download) → world.xero и копия мира
                   -o ФАЙЛ.xero          куда записать
                   --overwrite           перезаписать, если файл уже есть
                   --game                взять код из игры с модом XeroCode: без ссылки и без ранга
              xero diff ПУТЬ             что изменится в мире при заливке, ничего не отправляя
                   --game                сверить с миром в игре, а не с копией
                   --world ССЫЛКА        сверить с миром по ссылке из /editor download
              xero module find ТЕКСТ     найти модули в каталоге JustMC: имя, установки, id
              xero module update         сбросить скачанные модули: при сборке скачаются свежие
              xero decompile ФАЙЛ.json   код мира из файла → .xero
                   -o ФАЙЛ.xero          куда записать (по умолчанию рядом)
              xero data                  какие данные сервера внутри
              xero names                 сводка по именам действий
              xero names --md ФАЙЛ       записать полную таблицу имён в Markdown
              xero names --docs ПАПКА    собрать справочник: действия, события, значения
                   --lang ФАЙЛ           полные описания из локализации сервера
              xero find ТЕКСТ            найти действие, условие, событие или значение
              xero version               версия
            """.formatted(VERSION);

    private static String version() {
        try (InputStream in = Main.class.getResourceAsStream("version.txt")) {
            return in == null ? "?" : new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "?";
        }
    }

    static PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    static PrintStream ERR =
            new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            OUT.print(USAGE);
            return args.length == 0 ? 2 : 0;
        }
        try {
            return switch (args[0]) {
                case "version", "--version" -> { OUT.println("xero " + VERSION); yield 0; }
                case "build" -> build(args);
                case "watch" -> Watch.run(args);
                case "format" -> Format.run(args);
                case "upload" -> Deliver.upload(args);
                case "pull" -> Deliver.pull(args);
                case "module" -> Library.run(args);
                case "diff" -> Deliver.diff(args);
                case "decompile" -> decompile(args);
                case "data" -> data();
                case "names" -> names(args);
                case "find" -> find(args);
                default -> {
                    ERR.println("Неизвестная команда «" + args[0] + "». Список: xero help");
                    yield 2;
                }
            };
        } catch (IOException | RuntimeException e) {
            ERR.println("Ошибка: " + e.getMessage());
            return 1;
        }
    }

    private static int build(String[] args) throws IOException {
        String input = null, output = null;
        boolean pretty = false, json = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "-o" -> {
                    if (i + 1 >= args.length) { ERR.println("После -o нужен файл"); return 2; }
                    output = args[++i];
                }
                case "--pretty" -> pretty = true;
                case "--json" -> json = true;
                default -> {
                    if (input != null) { ERR.println("Лишний аргумент «" + args[i] + "»"); return 2; }
                    input = args[i];
                }
            }
        }
        if (input == null) { ERR.println("Что собрать? Пример: xero build main.xero"); return 2; }
        Path in = Path.of(input);
        Project.Built built;
        try {
            built = Project.compile(in);
        } catch (Project.Missing e) {
            ERR.println(e.getMessage());
            return 2;
        }
        if (json) OUT.println(Json.write(built.reportJson()));
        else built.report(ERR);
        if (!built.ok()) {
            if (!json) ERR.println("Не собрано: ошибок " + built.errors() + (built.warnings() > 0 ? ", предупреждений " + built.warnings() : ""));
            return 1;
        }
        Path out = Project.output(in, output);
        String written = pretty ? Json.pretty(built.module()) : Json.write(built.module());
        Files.writeString(out, written + System.lineSeparator(), StandardCharsets.UTF_8);
        if (!json) OUT.println("Собрано: строк " + built.lines() + ", блоков "
                + Project.blocks(Json.array(built.module().get("handlers"))) + " → " + out);
        return 0;
    }

    private static int decompile(String[] args) throws IOException {
        String input = null, output = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("-o")) {
                if (i + 1 >= args.length) { ERR.println("После -o нужен файл"); return 2; }
                output = args[++i];
            } else if (input == null) {
                input = args[i];
            } else {
                ERR.println("Лишний аргумент «" + args[i] + "»");
                return 2;
            }
        }
        if (input == null) { ERR.println("Что разобрать? Пример: xero decompile world.json"); return 2; }
        Path in = Path.of(input);
        if (!Files.isRegularFile(in)) { ERR.println("Нет файла " + in); return 2; }
        Object parsed;
        try {
            parsed = Json.parse(Files.readString(in, StandardCharsets.UTF_8));
        } catch (Json.Error e) {
            ERR.println("Это не JSON: " + e.getMessage());
            return 1;
        }
        if (!(parsed instanceof java.util.Map<?, ?> map) || !(map.get("handlers") instanceof List<?>)) {
            ERR.println("Нет поля handlers — это не код мира");
            return 1;
        }
        Data data = Data.load();
        Decompiler.Result result = Decompiler.decompile(data, Names.build(data), Json.object(parsed));
        Path out = output != null ? Path.of(output) : Project.sibling(in, ".xero");
        Files.writeString(out, result.text(), StandardCharsets.UTF_8);
        OUT.println("Разобрано: строк " + Json.array(map.get("handlers")).size() + " → " + out);
        notes(result);
        return 0;
    }

    static void notes(Decompiler.Result result) {
        long raw = result.notes().stream().filter(n -> n.startsWith("записано") || n.startsWith("значение")).count();
        if (raw > 0) OUT.println("  как есть, через raw: " + raw);
        for (String note : result.notes())
            if (note.startsWith("строка") || note.startsWith("события")) ERR.println("  " + note);
    }

    private static int data() {
        Data data = Data.load();
        Data.Meta meta = data.meta();
        long blanks = data.actions().values().stream().filter(Data.Action::blank).count();
        OUT.println("Данные сервера: " + meta.updated());
        OUT.println("  действий:          " + data.actions().size() + " (из них пустых блоков " + blanks + ")");
        OUT.println("  событий:           " + data.events().size());
        OUT.println("  игровых значений:  " + data.gameValues().size());
        if (!data.unknownCategories().isEmpty()) {
            OUT.println("  неизвестные категории: " + data.unknownCategories());
            return 1;
        }
        return 0;
    }

    private static int names(String[] args) throws IOException {
        Names names = Names.build(Data.load());
        if (args.length >= 3 && args[1].equals("--docs")) {
            Path folder = Path.of(args[2]);
            java.util.Properties lang = new java.util.Properties();
            if (args.length >= 5 && args[3].equals("--lang")) {
                try (var reader = Files.newBufferedReader(Path.of(args[4]), StandardCharsets.UTF_8)) {
                    lang.load(reader);
                }
            }
            ReferenceDoc.Rendered docs = ReferenceDoc.render(Data.load(), names, lang);
            for (var file : docs.files().entrySet()) {
                Path path = folder.resolve(file.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
            }
            docs.broken().forEach(ERR::println);
            OUT.println("Записано: файлов " + docs.files().size() + ", примеров без сборки " + docs.broken().size());
            return docs.broken().isEmpty() ? 0 : 1;
        }
        if (args.length >= 3 && args[1].equals("--md")) {
            Path file = Path.of(args[2]);
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, NamesDoc.render(Data.load(), names), StandardCharsets.UTF_8);
            OUT.println("Записано: " + file);
            return names.duplicates().isEmpty() ? 0 : 1;
        }
        for (Names.Space space : Names.Space.values()) {
            long all = names.entries().stream().filter(e -> e.space() == space).count();
            long bare = names.entries().stream().filter(e -> e.space() == space && e.bare()).count();
            OUT.println((space == Names.Space.ACTION ? "Действия" : "Условия") + ": " + all
                    + ", без категории пишутся " + bare
                    + ", совпадений имён " + names.collisions(space).size());
        }
        if (!names.duplicates().isEmpty()) {
            OUT.println("Неразличимые полные имена: " + names.duplicates());
            return 1;
        }
        return 0;
    }

    private static int find(String[] args) {
        if (args.length < 2) {
            ERR.println("Что искать? Пример: xero find крафт");
            return 2;
        }
        String query = String.join(" ", List.of(args).subList(1, args.length)).toLowerCase(Locale.ROOT);
        Data data = Data.load();
        Names names = Names.build(data);
        List<String> rows = new ArrayList<>();
        for (Names.Entry e : names.entries()) {
            Data.Action a = e.action();
            if (!matches(query, a.id(), a.label(), e.qualified())) continue;
            String write = e.bare() ? e.name() : e.qualified();
            String kind = e.space() == Names.Space.CONDITION ? "условие " : "действие";
            String spare = a.alias() ? "  (запасной id, сервер ставит " + a.canonical() + ")" : "";
            rows.add(kind + "  " + pad(write, 40) + pad(a.id(), 46) + a.category().russian
                    + " → " + a.label() + spare);
        }
        for (Data.Event ev : data.events().values())
            if (matches(query, ev.id(), ev.label()))
                rows.add("событие   " + pad("when " + ev.id(), 40) + pad(ev.id(), 46)
                        + ev.group() + " → " + ev.label());
        for (Data.GameValue v : data.gameValues().values())
            if (matches(query, v.id(), v.label()))
                rows.add("значение  " + pad("value." + v.id(), 40) + pad(v.id(), 46)
                        + v.group() + " → " + v.label());
        if (rows.isEmpty()) {
            OUT.println("Ничего не нашлось по «" + query + "».");
            return 1;
        }
        rows.stream().limit(FIND_LIMIT).forEach(OUT::println);
        if (rows.size() > FIND_LIMIT) OUT.println("… и ещё " + (rows.size() - FIND_LIMIT));
        return 0;
    }

    private static boolean matches(String query, String... fields) {
        for (String f : fields) if (f != null && f.toLowerCase(Locale.ROOT).contains(query)) return true;
        return false;
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s + " " : s + " ".repeat(width - s.length());
    }

    private Main() {}
}
