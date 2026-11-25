package xero.compile;

import xero.data.Data;
import xero.json.Json;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class Sources {
    public static final String MARK = "xs1:";
    public static final int ROOM = Declarations.CUSTOM_DATA_LIMIT;

    private static final String CUSTOM_DATA = Declarations.CUSTOM_DATA;
    private static final int HEAD = MARK.length() + 4;
    private static final int BODY = ROOM - HEAD;
    private static final int BITS = 14;
    private static final int SPAN = 1 << BITS;
    private static final char ZERO = '一';
    private static final int MAX_BYTES = (1 << 24) - 1;
    private static final String FILES = "files", LINES = "lines";

    public record File(String path, String text) {}

    public record Stamped(Map<String, Object> module, int chunks, int room) {
        public boolean fits() { return chunks <= room; }
    }

    public record Found(String id, int total, int have, List<File> files, int expected, int matched) {
        public boolean complete() { return files != null; }

        public boolean exact() { return complete() && matched == expected; }
    }

    public record Reading(List<Found> found, List<Object> rest) {}

    private Sources() {}

    public static boolean mark(Object customData) {
        return customData instanceof String s && s.startsWith(MARK);
    }

    public static Compiler.Loader recording(Compiler.Loader loader, Map<String, String> read) {
        if (loader == null) return null;
        return path -> {
            String text = loader.load(path);
            if (text != null && !path.startsWith(Compiler.MODULE)) read.putIfAbsent(path, text);
            return text;
        };
    }

    public static List<File> files(String entry, String entryText, Map<String, String> read) {
        String name = clean(entry == null ? "" : entry);
        boolean folder = name.endsWith("/");
        Map<String, String> all = new LinkedHashMap<>();
        if (!folder && entryText != null) all.put(name, entryText);
        for (Map.Entry<String, String> e : read.entrySet()) all.putIfAbsent(clean(e.getKey()), e.getValue());
        String base = folder ? name : name.substring(0, name.lastIndexOf('/') + 1);
        for (String path : all.keySet()) base = common(base, path);
        List<File> out = new ArrayList<>();
        for (Map.Entry<String, String> e : all.entrySet()) out.add(new File(e.getKey().substring(base.length()), e.getValue()));
        return out;
    }

    public static boolean safe(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.indexOf(':') >= 0 || path.indexOf('\\') >= 0
                || path.indexOf('\0') >= 0) return false;
        for (String part : path.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }

    private static String common(String base, String path) {
        String dir = path.substring(0, path.lastIndexOf('/') + 1);
        int end = Math.min(base.length(), dir.length()), cut = 0;
        for (int i = 0; i < end && base.charAt(i) == dir.charAt(i); i++) if (base.charAt(i) == '/') cut = i + 1;
        return base.length() <= dir.length() && dir.startsWith(base) ? base : base.substring(0, cut);
    }

    public static Stamped stamp(Map<String, Object> module, List<File> files, Data data) {
        Map<String, Object> copy = Json.object(Json.parse(Json.write(module)));
        List<Map<String, Object>> lines = ordered(copy);
        List<Object> hashes = new ArrayList<>();
        for (Map<String, Object> line : lines) hashes.add(hash(data, line));
        List<Object> listed = new ArrayList<>();
        for (File f : files) listed.add(new ArrayList<Object>(List.of(clean(f.path()), f.text())));
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put(FILES, listed);
        bundle.put(LINES, hashes);
        byte[] plain = Json.write(bundle).getBytes(StandardCharsets.UTF_8);
        List<String> chunks = plain.length > MAX_BYTES ? null : chunks(id(plain), encode(packed(plain)));
        List<Map<String, Object>> free = new ArrayList<>();
        for (Map<String, Object> line : lines) if (!line.containsKey(CUSTOM_DATA) || mark(line.get(CUSTOM_DATA))) free.add(line);
        if (chunks == null || chunks.size() > free.size()) return new Stamped(module, chunks == null ? Integer.MAX_VALUE : chunks.size(), free.size());
        for (int i = 0; i < free.size(); i++) {
            if (i < chunks.size()) free.get(i).put(CUSTOM_DATA, chunks.get(i));
            else free.get(i).remove(CUSTOM_DATA);
        }
        return new Stamped(copy, chunks.size(), free.size());
    }

    public static Reading read(List<Object> handlers, Data data) {
        Map<String, Map<Integer, String>> parts = new LinkedHashMap<>();
        Map<String, Integer> totals = new HashMap<>();
        for (Object raw : handlers) {
            if (!(raw instanceof Map<?, ?> m) || !(m.get(CUSTOM_DATA) instanceof String s) || !s.startsWith(MARK) || s.length() < HEAD) continue;
            String id = s.substring(MARK.length(), MARK.length() + 2);
            int index = s.charAt(MARK.length() + 2) - ZERO, total = s.charAt(MARK.length() + 3) - ZERO;
            if (index < 0 || total <= 0 || index >= total || total >= SPAN) continue;
            Integer known = totals.putIfAbsent(id, total);
            if (known != null && known != total) continue;
            parts.computeIfAbsent(id, k -> new TreeMap<>()).putIfAbsent(index, s.substring(HEAD));
        }
        Map<String, Deque<Object>> world = new HashMap<>();
        for (Object raw : handlers)
            if (raw instanceof Map<?, ?> m) world.computeIfAbsent(hash(data, Json.object(m)), k -> new ArrayDeque<>()).add(raw);
        Set<Object> taken = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Found> found = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, String>> e : parts.entrySet()) {
            int total = totals.get(e.getKey());
            Map<String, Object> bundle = e.getValue().size() == total ? bundle(e.getKey(), e.getValue()) : null;
            if (bundle == null) {
                found.add(new Found(e.getKey(), total, e.getValue().size(), null, 0, 0));
                continue;
            }
            List<File> files = new ArrayList<>();
            for (Object f : Json.array(bundle.get(FILES))) {
                List<Object> pair = Json.array(f);
                files.add(new File((String) pair.get(0), (String) pair.get(1)));
            }
            List<Object> expected = Json.array(bundle.get(LINES));
            int matched = 0;
            for (Object h : expected) {
                Deque<Object> same = world.get(String.valueOf(h));
                Object line = same == null ? null : same.poll();
                if (line == null) continue;
                taken.add(line);
                matched++;
            }
            found.add(new Found(e.getKey(), total, total, files, expected.size(), matched));
        }
        List<Object> rest = new ArrayList<>();
        for (Object raw : handlers) if (!taken.contains(raw)) rest.add(raw);
        return new Reading(found, rest);
    }

    static Map<String, Object> withMarkOf(Map<String, Object> line, Map<String, Object> from) {
        Object have = line.get(CUSTOM_DATA);
        if (have != null && !mark(have)) return line;
        Object want = from == null ? null : from.get(CUSTOM_DATA);
        if (!mark(want) && !mark(have)) return line;
        Map<String, Object> copy = new LinkedHashMap<>(line);
        if (mark(want)) copy.put(CUSTOM_DATA, want);
        else copy.remove(CUSTOM_DATA);
        return copy;
    }

    private static Map<String, Object> bundle(String id, Map<Integer, String> parts) {
        StringBuilder body = new StringBuilder();
        for (String part : parts.values()) body.append(part);
        byte[] plain = unpacked(decode(body));
        if (plain == null || !key(id(plain)).equals(id)) return null;
        try {
            Map<String, Object> bundle = Json.object(Json.parse(new String(plain, StandardCharsets.UTF_8)));
            java.util.Set<String> paths = new java.util.HashSet<>();
            for (Object f : Json.array(bundle.get(FILES))) {
                List<Object> pair = Json.array(f);
                if (pair.size() != 2 || !(pair.get(0) instanceof String path) || !(pair.get(1) instanceof String)) return null;
                if (!safe(path) || !paths.add(path.toLowerCase(java.util.Locale.ROOT))) return null;
            }
            Json.array(bundle.get(LINES));
            return bundle;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<Map<String, Object>> ordered(Map<String, Object> module) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object raw : Json.array(module.get("handlers"))) if (raw instanceof Map<?, ?> m) out.add(Json.object(m));
        out.sort(Comparator.comparingInt(Sources::position));
        return out;
    }

    private static int position(Map<String, Object> line) {
        return line.get("position") instanceof Json.Num n ? n.intValue()
                : line.get("position") instanceof Number n ? n.intValue() : Integer.MAX_VALUE;
    }

    private static String clean(String path) {
        return path.replace('\\', '/');
    }

    static String hash(Data data, Map<String, Object> line) {
        return Integer.toHexString(fnv(Json.write(Delivery.normal(data, line)).getBytes(StandardCharsets.UTF_8)));
    }

    private static int fnv(byte[] bytes) {
        int h = 0x811C9DC5;
        for (byte b : bytes) {
            h ^= b & 0xFF;
            h *= 0x01000193;
        }
        return h;
    }

    private static int id(byte[] plain) {
        return fnv(plain) & (SPAN * SPAN - 1);
    }

    private static String key(int id) {
        return "" + (char) (ZERO + (id >>> BITS)) + (char) (ZERO + (id & (SPAN - 1)));
    }

    private static List<String> chunks(int id, String body) {
        int total = Math.max(1, (body.length() + BODY - 1) / BODY);
        if (total >= SPAN) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < total; i++)
            out.add(MARK + key(id) + (char) (ZERO + i) + (char) (ZERO + total)
                    + body.substring(i * BODY, Math.min(body.length(), (i + 1) * BODY)));
        return out;
    }

    private static byte[] packed(byte[] plain) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setInput(plain);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain.length >>> 16);
        out.write(plain.length >>> 8);
        out.write(plain.length);
        byte[] buf = new byte[4096];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return out.toByteArray();
    }

    private static byte[] unpacked(byte[] packed) {
        if (packed == null || packed.length < 3) return null;
        int size = (packed[0] & 0xFF) << 16 | (packed[1] & 0xFF) << 8 | packed[2] & 0xFF;
        byte[] input = new byte[packed.length - 2];
        System.arraycopy(packed, 3, input, 0, packed.length - 3);
        Inflater in = new Inflater(true);
        in.setInput(input);
        byte[] out = new byte[size];
        int got = 0;
        try {
            while (got < size) {
                int n = in.inflate(out, got, size - got);
                if (n == 0 && (in.finished() || in.needsInput() || in.needsDictionary())) break;
                got += n;
            }
        } catch (DataFormatException | RuntimeException e) {
            return null;
        } finally {
            in.end();
        }
        return got == size ? out : null;
    }

    private static String encode(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int acc = 0, bits = 0;
        for (byte b : bytes) {
            acc = acc << 8 | b & 0xFF;
            bits += 8;
            while (bits >= BITS) {
                bits -= BITS;
                out.append((char) (ZERO + (acc >>> bits & (SPAN - 1))));
            }
            acc &= (1 << bits) - 1;
        }
        if (bits > 0) out.append((char) (ZERO + (acc << (BITS - bits) & (SPAN - 1))));
        return out.toString();
    }

    private static byte[] decode(CharSequence chars) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int acc = 0, bits = 0;
        for (int i = 0; i < chars.length(); i++) {
            int v = chars.charAt(i) - ZERO;
            if (v < 0 || v >= SPAN) return null;
            acc = acc << BITS | v;
            bits += BITS;
            while (bits >= 8) {
                bits -= 8;
                out.write(acc >>> bits & 0xFF);
            }
            acc &= (1 << bits) - 1;
        }
        return out.toByteArray();
    }
}
