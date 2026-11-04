package xero.dialect.jmcc;

import xero.compile.Compiler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

final class Corpus {
    private static final Path REPOS = Path.of("..", "..", "repos");
    private static final List<Path> DUMPS = List.of(Path.of("..", "..", "dump"), Path.of("C:/apps/justmc/dump"));

    private Corpus() {}

    static boolean there() { return Files.isDirectory(REPOS); }

    static Path repos() { return REPOS; }

    static Path world(String name) {
        for (Path d : DUMPS) if (Files.isRegularFile(d.resolve(name))) return d.resolve(name);
        return null;
    }

    static List<Path> files() {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(REPOS)) {
            walk.filter(p -> p.toString().endsWith(".jc")).sorted().forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    static String text(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Compiler.Loader loader() {
        return path -> {
            if (path.startsWith("module:")) return null;
            Path p = Path.of(path);
            return Files.isRegularFile(p) ? text(p) : null;
        };
    }
}
