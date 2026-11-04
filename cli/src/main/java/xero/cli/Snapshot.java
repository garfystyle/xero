package xero.cli;

import xero.compile.Delivery;
import xero.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Snapshot {
    static final String DIR = ".xero";
    static final String FILE = "world.json";

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    record Sent(Instant at, List<Delivery.Line> lines) {}

    final Path file;
    List<Object> handlers = new ArrayList<>();
    Instant known;
    String how;
    final List<Sent> sent = new ArrayList<>();

    private Snapshot(Path file) {
        this.file = file;
    }

    static Path locate(Path start) {
        Path dir = start.toAbsolutePath().normalize();
        for (Path p = dir; p != null; p = p.getParent())
            if (Files.isRegularFile(p.resolve(DIR).resolve(FILE))) return p.resolve(DIR).resolve(FILE);
        return dir.resolve(DIR).resolve(FILE);
    }

    static Path home(Path input) {
        Path abs = input.toAbsolutePath().normalize();
        return Files.isDirectory(abs) ? abs : abs.getParent();
    }

    static Snapshot load(Path file) throws IOException {
        Snapshot s = new Snapshot(file);
        if (!Files.isRegularFile(file)) return s;
        Object parsed;
        try {
            parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Json.Error e) {
            throw new IOException("копия мира " + file + " испорчена: " + e.getMessage() + " — сделай xero pull заново или удали файл");
        }
        if (!(parsed instanceof Map<?, ?> raw)) return s;
        Map<String, Object> o = Json.object(raw);
        if (o.get("handlers") instanceof List<?> l) s.handlers = new ArrayList<>(l);
        if (o.get("known") instanceof String k) s.known = Instant.parse(k);
        if (o.get("how") instanceof String h) s.how = h;
        if (o.get("sent") instanceof List<?> list) {
            for (Object x : list) {
                Map<String, Object> e = Json.object(x);
                List<Delivery.Line> lines = new ArrayList<>();
                for (Object l : Json.array(e.get("lines"))) {
                    Map<String, Object> line = Json.object(l);
                    lines.add(new Delivery.Line(Delivery.Kind.valueOf((String) line.get("kind")), (String) line.get("name"), -1, null));
                }
                s.sent.add(new Sent(Instant.parse((String) e.get("at")), lines));
            }
        }
        return s;
    }

    boolean exists() {
        return known != null || !sent.isEmpty();
    }

    List<Delivery.Line> world() {
        List<Delivery.Line> out = new ArrayList<>(Delivery.lines(Map.of("handlers", handlers)));
        for (Sent s : sent) out.addAll(s.lines());
        return out;
    }

    Instant sentAt(Delivery.Line line) {
        for (Sent s : sent)
            for (Delivery.Line l : s.lines())
                if (l.key().equals(line.key())) return s.at();
        return null;
    }

    Duration age() {
        return known == null ? null : Duration.between(known, Instant.now());
    }

    void pulled(List<Object> world) {
        handlers = new ArrayList<>(world);
        known = Instant.now();
        how = "pull";
        sent.clear();
    }

    void replaced(List<Object> module) {
        handlers = new ArrayList<>(module);
        known = Instant.now();
        how = "replace";
        sent.clear();
    }

    void appended(List<Delivery.Line> lines) {
        List<Delivery.Line> keys = new ArrayList<>();
        for (Delivery.Line l : lines)
            if (l.kind() != Delivery.Kind.OTHER) keys.add(new Delivery.Line(l.kind(), l.name(), -1, null));
        sent.add(new Sent(Instant.now(), keys));
    }

    void save() throws IOException {
        Files.createDirectories(file.getParent());
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("xero", 1);
        if (known != null) o.put("known", known.toString());
        if (how != null) o.put("how", how);
        List<Object> list = new ArrayList<>();
        for (Sent s : sent) {
            List<Object> lines = new ArrayList<>();
            for (Delivery.Line l : s.lines()) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("kind", l.kind().name());
                line.put("name", l.name());
                lines.add(line);
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("at", s.at().toString());
            e.put("lines", lines);
            list.add(e);
        }
        o.put("sent", list);
        o.put("handlers", handlers);
        Files.writeString(file, Json.write(o) + "\n", StandardCharsets.UTF_8);
    }

    static String when(Instant at) {
        return CLOCK.format(at);
    }

    static String ago(Duration d) {
        long minutes = d.toMinutes();
        if (minutes < 1) return "только что";
        if (minutes < 60) return minutes + " мин назад";
        long hours = d.toHours();
        if (hours < 48) return hours + " ч назад";
        return d.toDays() + " дн назад";
    }

    String describe() {
        if (known == null) return "известны только строки, дописанные отсюда через --add";
        String what = "replace".equals(how) ? "то, что Xero залил заменой" : "скачана xero pull";
        return what + " " + when(known) + ", " + ago(age()) + (sent.isEmpty() ? "" : ", и дописанное после через --add");
    }
}
