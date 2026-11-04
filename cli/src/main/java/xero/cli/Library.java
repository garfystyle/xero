package xero.cli;

import xero.compile.Compiler;
import xero.json.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static xero.cli.Main.ERR;
import static xero.cli.Main.OUT;

final class Library {
    interface Catalog {
        String get(String url) throws IOException;
    }

    static final class Absent extends IOException {
        private static final long serialVersionUID = 1L;

        Absent() {
            super("нет такого");
        }
    }

    private static final class Refused extends IOException {
        private static final long serialVersionUID = 1L;
        final String hint;

        Refused(String message, String hint) {
            super(message);
            this.hint = hint;
        }
    }

    private static final String BASE = "https://api.creative.justmc.io/public/creative/modules";
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern COLORS = Pattern.compile("&#[0-9a-fA-F]{6}|&[0-9a-fk-orA-FK-OR]");
    private static final Pattern VERSION = Pattern.compile("\\s*\\[[^\\]]*\\]\\s*$");
    private static final String DIR = "modules";
    private static final int SHOWN = 20;

    static Catalog catalog = Library::fetch;

    private static String fetch(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Xero").GET().build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() == 200) return r.body();
            if (r.statusCode() == 404) throw new Absent();
            if (r.statusCode() == 429) throw new IOException("JustMC даёт 20 запросов в минуту — подожди и собери снова");
            throw new IOException("каталог JustMC ответил " + r.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано");
        }
    }

    static String text(String asked, Path home) {
        boolean connect = asked.startsWith(Compiler.CONNECT);
        String wanted = connect ? asked.substring(Compiler.CONNECT.length()) : asked;
        Path file = file(home, (connect ? "connect_" : "") + wanted);
        try {
            if (Files.isRegularFile(file)) return Files.readString(file, StandardCharsets.UTF_8);
            String json = Json.write(download(wanted, connect));
            Files.createDirectories(file.getParent());
            Files.writeString(file, json, StandardCharsets.UTF_8);
            return json;
        } catch (Refused e) {
            return failure(e.getMessage(), e.hint);
        } catch (IOException e) {
            return failure("модуль «" + wanted + "» не скачан: " + e.getMessage(), null);
        }
    }

    private static String failure(String why, String hint) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("error", why);
        if (hint != null) o.put("hint", hint);
        return Json.write(o);
    }

    private static Path file(Path home, String wanted) {
        String safe = wanted.strip().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_-]", "_");
        return home.resolve(DIR).resolve(safe + ".json");
    }

    private static List<Map<String, Object>> search(String text) throws IOException {
        String body = catalog.get(BASE + "?limit=100&search=" + URLEncoder.encode(text.strip(), StandardCharsets.UTF_8));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object m : Json.array(Json.parse(body))) out.add(Json.object(m));
        out.sort(Comparator.comparingInt(Library::installs).reversed());
        return out;
    }

    private static int installs(Map<String, Object> m) {
        return m.get("history") instanceof Json.Num n ? n.intValue() : 0;
    }

    static String title(Map<String, Object> m) {
        String all = m.get("displayName") instanceof String s ? s : "";
        for (String line : all.split("\\\\n")) {
            String plain = VERSION.matcher(COLORS.matcher(line).replaceAll("")).replaceAll("").strip();
            if (!plain.isEmpty()) return plain;
        }
        return String.valueOf(m.get("name"));
    }

    private static List<Map<String, Object>> same(List<Map<String, Object>> found, String wanted) {
        List<Map<String, Object>> byName = new ArrayList<>(), byTitle = new ArrayList<>();
        for (Map<String, Object> m : found) {
            if (wanted.equalsIgnoreCase(String.valueOf(m.get("name")))) byName.add(m);
            else if (wanted.equalsIgnoreCase(title(m))) byTitle.add(m);
        }
        return byName.isEmpty() ? byTitle : byName;
    }

    private static Map<String, Object> download(String asked, boolean connect) throws IOException {
        String wanted = asked.strip();
        Map<String, Object> chosen;
        String note = null;
        if (ID.matcher(wanted).matches()) {
            try {
                chosen = Json.object(Json.parse(catalog.get(BASE + "/" + wanted)));
            } catch (Absent e) {
                throw new Refused("нет модуля с id " + wanted, null);
            }
        } else {
            List<Map<String, Object>> found = search(wanted);
            List<Map<String, Object>> same = same(found, wanted);
            if (same.isEmpty()) throw new Refused("нет модуля «" + wanted + "» в каталоге JustMC", hint(found, wanted));
            chosen = same.get(0);
            if (same.size() > 1)
                note = "модулей «" + wanted + "» в каталоге " + same.size() + " — взят самый установленный: «" + title(chosen)
                        + "», установок " + installs(chosen) + "; другой — по id, список: xero module find " + wanted;
        }
        String shown = title(chosen);
        boolean closed = Boolean.FALSE.equals(chosen.get("codeVisible"));
        if (closed && !connect)
            throw new Refused("автор закрыл код модуля «" + shown + "»", "подключить его можно и так: import \"" + wanted + "\" connect");
        if (chosen.get("price") instanceof Json.Num price && price.intValue() > 0)
            throw new Refused("модуль «" + shown + "» платный — его покупают и подключают в игре через /module", null);
        String id = Json.string(chosen.get("id"));
        Object handlers = List.of();
        if (!closed) {
            try {
                handlers = Json.object(Json.parse(catalog.get(BASE + "/" + id + "/code"))).get("handlers");
            } catch (Absent e) {
                if (!connect) throw new Refused("сервер не отдал код модуля «" + shown + "»", "подключить его можно и так: import \"" + wanted + "\" connect");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wanted", wanted);
        out.put("id", id);
        out.put("name", chosen.get("name"));
        out.put("title", shown);
        out.put("updated", chosen.get("updatedAt"));
        if (note != null) out.put("note", note);
        out.put("entry", chosen);
        out.put("handlers", handlers);
        return out;
    }

    private static String hint(List<Map<String, Object>> found, String wanted) {
        if (found.isEmpty()) return "файл подключают с расширением: import \"" + wanted + ".xero\"";
        List<String> names = new ArrayList<>();
        for (Map<String, Object> m : found.subList(0, Math.min(5, found.size()))) names.add(String.valueOf(m.get("name")));
        return "похожие: " + String.join(", ", names);
    }

    static int run(String[] args) throws IOException {
        if (args.length >= 3 && args[1].equals("find")) return find(String.join(" ", List.of(args).subList(2, args.length)));
        if (args.length == 2 && args[1].equals("update")) return update();
        ERR.println("Пример: xero module find mapi, xero module update");
        return 2;
    }

    private static int find(String text) throws IOException {
        List<Map<String, Object>> found = search(text);
        if (found.isEmpty()) {
            OUT.println("Ничего не нашлось.");
            return 0;
        }
        for (Map<String, Object> m : found.subList(0, Math.min(SHOWN, found.size()))) {
            String closed = Boolean.FALSE.equals(m.get("codeVisible")) ? "  код закрыт" : "";
            OUT.println(m.get("name") + "  «" + title(m) + "»  установок " + installs(m) + closed);
            OUT.println("    import \"" + m.get("id") + "\"");
        }
        if (found.size() > SHOWN) OUT.println("… и ещё " + (found.size() - SHOWN));
        return 0;
    }

    private static int update() throws IOException {
        Path dir = Snapshot.locate(Path.of("")).getParent().resolve(DIR);
        int gone = 0;
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
                for (Path f : files) {
                    Files.delete(f);
                    gone++;
                }
            }
        }
        OUT.println(gone == 0 ? "Скачанных модулей нет." : "Модулей сброшено: " + gone + ". Свежие скачаются при следующей сборке.");
        return 0;
    }

    static boolean module(String path) {
        return path.startsWith(Compiler.MODULE);
    }

    private Library() {}
}
