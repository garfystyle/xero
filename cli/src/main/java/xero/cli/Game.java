package xero.cli;

import xero.json.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class Game {
    interface Link {
        Sent upload(String module, boolean replace) throws IOException;

        List<Object> world() throws IOException;

        List<String> lines(String lines) throws IOException;

        String connect(String entry) throws IOException;
    }

    record Sent(List<String> answers, boolean done) {}

    static final class Failed extends IOException {
        private static final long serialVersionUID = 1L;

        Failed(String message) {
            super(message);
        }
    }

    private static final Duration SEND = Duration.ofSeconds(150);
    private static final Duration READ = Duration.ofSeconds(610);
    private static final Duration LINES = Duration.ofSeconds(310);

    static Link link = new Link() {
        @Override
        public Sent upload(String module, boolean replace) throws IOException {
            Map<String, Object> answer = ask("POST", "/upload?mode=" + (replace ? "replace" : "add"), module, SEND);
            List<String> said = new ArrayList<>();
            if (answer.get("answers") instanceof List<?> lines) for (Object line : lines) said.add(String.valueOf(line));
            return new Sent(said, !Boolean.FALSE.equals(answer.get("done")));
        }

        @Override
        public List<Object> world() throws IOException {
            return Json.array(ask("GET", "/world", null, READ).get("handlers"));
        }

        @Override
        public List<String> lines(String lines) throws IOException {
            List<String> said = new ArrayList<>();
            for (Object row : Json.array(ask("POST", "/lines", lines, LINES).get("lines"))) {
                Map<String, Object> o = Json.object(row);
                if (!Boolean.TRUE.equals(o.get("ok"))) throw new Failed("строка " + o.get("position") + ": " + o.get("said"));
                said.add(String.valueOf(o.get("said")));
            }
            return said;
        }

        @Override
        public String connect(String entry) throws IOException {
            return String.valueOf(ask("POST", "/connect", entry, SEND).get("said"));
        }
    };

    private static Map<String, Object> ask(String method, String path, String body, Duration wait) throws IOException {
        Path file = Path.of(System.getProperty("user.home"), ".xerocode", "link.json");
        if (!Files.isRegularFile(file)) throw new Failed("игра с XeroCode не запущена");
        Map<String, Object> link = Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
        URI where = URI.create("http://127.0.0.1:" + link.get("port") + path);
        HttpRequest.Builder request = HttpRequest.newBuilder(where).timeout(wait)
                .header("X-Xero-Token", Json.string(link.get("token")));
        if (body == null) request.GET();
        else request.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build()) {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> answer = Json.object(Json.parse(response.body()));
            if (Boolean.TRUE.equals(answer.get("ok"))) return answer;
            throw new Failed(answer.get("error") instanceof String why ? why : "игра ответила " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failed("прервано");
        } catch (java.net.ConnectException e) {
            throw new Failed("игра с XeroCode не отвечает");
        }
    }

    private Game() {}
}
