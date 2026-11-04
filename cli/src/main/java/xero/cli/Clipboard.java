package xero.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

final class Clipboard {
    static Predicate<String> sink = Clipboard::system;

    static boolean copy(String text) {
        return sink.test(text);
    }

    private static boolean system(String text) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<List<String>> tools = os.contains("win") ? List.of(List.of("clip"))
                : os.contains("mac") ? List.of(List.of("pbcopy"))
                : List.of(List.of("wl-copy"), List.of("xclip", "-selection", "clipboard"), List.of("xsel", "--clipboard", "--input"));
        for (List<String> tool : tools) {
            try {
                Process p = new ProcessBuilder(tool).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                try (OutputStream in = p.getOutputStream()) {
                    in.write(text.getBytes(os.contains("win") ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8));
                }
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) return true;
                p.destroy();
            } catch (IOException e) {
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private Clipboard() {}
}
