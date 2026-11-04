package xero.cli;

import xero.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class Net {
    interface Link {
        String upload(String module, Store store) throws IOException;

        String fetch(URI world) throws IOException;
    }

    record Store(List<String> uploads, String code) {}

    static final class Refused extends IOException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    static final String CODE = "http://2.27.249.33:8791/xerocode/code/";
    static final String OFFICIAL = "https://m.justmc.ru/api/";
    static final String STORE_VARIABLE = "XERO_STORE";
    static final int LIFETIME_SECONDS = 60;

    private static final String KEY = "xc-0d0e58158f364a5719205d2b652b2651b9d5";
    private static final String UPLOAD = "upload.php?key=" + KEY;
    static final Store DEFAULT = new Store(List.of(
            "https://2-27-249-33.sslip.io/xerocode/" + UPLOAD,
            "http://2.27.249.33:8791/xerocode/" + UPLOAD), CODE);
    private static final String JUSTMC = "api.creative.justmc.io";
    private static final Pattern WORLD_PATH = Pattern.compile("/public/creative/handlers/[0-9a-fA-F-]{32,40}");
    private static final Pattern ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern OFFICIAL_ID = Pattern.compile("[0-9A-Za-z_-]{4,64}");
    private static final String AGENT = "Xero";
    private static final Duration CONNECT = Duration.ofSeconds(10);
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final int MAX_WORLD = 64 << 20;

    static Link link = new Link() {
        @Override
        public String upload(String module, Store store) throws IOException {
            return Net.upload(module, store);
        }

        @Override
        public String fetch(URI world) throws IOException {
            return Net.fetch(world);
        }
    };

    static Store store(String flag) {
        String chosen = flag != null ? flag : System.getenv(STORE_VARIABLE);
        if (chosen == null || chosen.isBlank()) return DEFAULT;
        String base = chosen.trim();
        String lower = base.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://"))
            throw new IllegalArgumentException("адрес хранилища — http://хост:порт/путь/, а дано «" + base + "»");
        URI uri;
        try {
            uri = URI.create(base);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("адрес хранилища не разобрать: «" + base + "»");
        }
        if (uri.getHost() == null) throw new IllegalArgumentException("в адресе хранилища нет хоста: «" + base + "»");
        if (!base.endsWith("/")) base += "/";
        return new Store(List.of(base + UPLOAD), base + "code/");
    }

    static URI worldLink(String text) {
        String t = text.trim();
        if (t.startsWith("<") && t.endsWith(">")) t = t.substring(1, t.length() - 1);
        URI uri;
        try {
            uri = URI.create(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (uri.getScheme() == null || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https")))
            return null;
        if (!JUSTMC.equalsIgnoreCase(uri.getHost()) || uri.getPath() == null || !WORLD_PATH.matcher(uri.getPath()).matches())
            return null;
        if (uri.getRawQuery() == null || !uri.getRawQuery().contains("token=")) return null;
        return uri;
    }

    static boolean looksLikeLink(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("http://") || t.startsWith("https://");
    }

    static String notice(String url) {
        return url.startsWith(OFFICIAL)
                ? "в течение минуты — ссылка с m.justmc.ru живёт около 80 с"
                : "в течение " + LIFETIME_SECONDS + " с — ссылка работает один раз";
    }

    private static String upload(String module, Store store) throws IOException {
        byte[] body = module.getBytes(StandardCharsets.UTF_8);
        IOException own;
        try {
            return store(body, store);
        } catch (IOException e) {
            own = e;
        }
        try {
            return official(body);
        } catch (IOException e) {
            throw new IOException(own.getMessage() + "; запасной m.justmc.ru: " + e.getMessage());
        }
    }

    private static String official(byte[] body) throws IOException {
        try {
            HttpResponse<String> r = client().send(HttpRequest.newBuilder(URI.create(OFFICIAL + "upload"))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", AGENT)
                    .timeout(WAIT)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Object answer;
            try {
                answer = Json.parse(r.body());
            } catch (Json.Error e) {
                throw new Refused("m.justmc.ru ответил " + r.statusCode());
            }
            if (answer instanceof Map<?, ?> m && m.get("error") != null)
                throw new Refused("m.justmc.ru не принял код: " + cut(String.valueOf(m.get("error"))));
            if (r.statusCode() == 200 && answer instanceof Map<?, ?> m && m.get("id") instanceof String id
                    && OFFICIAL_ID.matcher(id).matches()) return OFFICIAL + id;
            throw new Refused("m.justmc.ru ответил " + r.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано");
        } catch (Refused e) {
            throw e;
        } catch (IOException e) {
            throw new IOException(reason(e, "m.justmc.ru"));
        }
    }

    private static String store(byte[] body, Store store) throws IOException {
        IOException last = null;
        for (String where : store.uploads()) {
            try {
                HttpResponse<String> r = client().send(HttpRequest.newBuilder(URI.create(where))
                        .header("Content-Type", "application/json")
                        .timeout(WAIT)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (r.statusCode() != 200) {
                    last = new Refused(refusal(r.statusCode()));
                    if (r.statusCode() != 502 && r.statusCode() != 503) throw last;
                    continue;
                }
                Object answer = Json.parse(r.body());
                if (answer instanceof Map<?, ?> m && m.get("id") instanceof String id && ID.matcher(id).matches())
                    return store.code() + id + ".json";
                throw new Refused("хранилище ответило непонятно: " + cut(r.body()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("прервано");
            } catch (Json.Error e) {
                throw new Refused("хранилище ответило не JSON");
            } catch (Refused e) {
                throw e;
            } catch (IOException e) {
                last = new IOException(reason(e, "хранилище кода"));
            }
        }
        throw last == null ? new IOException("загрузка не удалась") : last;
    }

    private static String refusal(int status) {
        return switch (status) {
            case 403 -> "хранилище не приняло ключ — обнови Xero";
            case 429 -> "хранилище просит подождать: слишком много загрузок подряд";
            case 413, 400 -> "хранилище не приняло код (" + status + ")";
            case 507 -> "хранилище переполнено — попробуй через минуту";
            default -> "хранилище ответило " + status;
        };
    }

    private static String fetch(URI world) throws IOException {
        URI secure = URI.create("https://" + world.getRawAuthority() + world.getRawPath() + "?" + world.getRawQuery());
        IOException last = null;
        for (URI where : new URI[]{secure, world}) {
            try {
                HttpResponse<InputStream> r = client().send(HttpRequest.newBuilder(where).timeout(WAIT).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = r.body()) {
                    byte[] bytes = in.readNBytes(MAX_WORLD + 1);
                    if (r.statusCode() == 404 || r.statusCode() == 403 || r.statusCode() == 410)
                        throw new Refused("сервер JustMC не отдал код (" + r.statusCode()
                                + "): ссылка одноразовая — сделай /editor download ещё раз");
                    if (r.statusCode() != 200) {
                        last = new IOException("сервер JustMC ответил " + r.statusCode());
                        continue;
                    }
                    if (bytes.length > MAX_WORLD) throw new Refused("код мира больше 64 МБ");
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("прервано");
            } catch (Refused e) {
                throw e;
            } catch (IOException e) {
                last = new IOException(reason(e, "сервер кода JustMC"));
            }
        }
        throw last == null ? new IOException("код мира не получен") : last;
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static String reason(IOException e, String who) {
        if (e instanceof HttpTimeoutException) return who + " не ответил за " + WAIT.toSeconds() + " с";
        if (e instanceof UnknownHostException) return who + ": адрес не найден — проверь интернет";
        if (e instanceof ConnectException) return who + ": не достучаться — проверь интернет, VPN или прокси";
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return who + ": " + cut(m);
    }

    private static String cut(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }

    private Net() {}
}
