package xero.cli;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import xero.compile.Compiler;
import xero.data.Data;
import xero.data.Names;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocsTest {
    private static final Pattern BLOCK = Pattern.compile("^( *)```kotlin\\n(.*?)^ *```$", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)#\\s]+\\.(?:md|xero))(#[^)]*)?\\)");
    private static final Path ROOT = Path.of("..");
    private static final List<String> INNER = List.of("DEV.md", "LANGUAGE.md", "NAMES.md", "SERVER.md");

    private final Data data = Data.load();
    private final Names names = Names.build(data);

    private List<Path> pages() throws IOException {
        List<Path> out = new ArrayList<>();
        Path readme = ROOT.resolve("README.md");
        Assumptions.assumeTrue(Files.isRegularFile(readme));
        out.add(readme);
        try (Stream<Path> walk = Files.walk(ROOT.resolve("docs"))) {
            walk.filter(p -> p.toString().endsWith(".md") && !INNER.contains(p.getFileName().toString())).forEach(out::add);
        }
        return out;
    }

    private boolean builds(String code) {
        return Compiler.compile(data, names, "x.xero", code, path -> path.endsWith(".xero") ? "" : null).ok();
    }

    @Test
    void everyExampleBuilds() throws IOException {
        List<String> broken = new ArrayList<>();
        int total = 0;
        for (Path page : pages()) {
            Matcher m = BLOCK.matcher(Files.readString(page));
            while (m.find()) {
                total++;
                String code = m.group(2);
                if (code.contains("import \"mapi\"")) continue;
                if (!builds(code) && !builds("when player_join {\nx = 1\n" + code + "\n}\n"))
                    broken.add(page.getFileName() + ":\n" + code);
            }
        }
        assertTrue(total > 1000, "примеров " + total);
        assertEquals(List.of(), broken);
    }

    @Test
    void everyLinkLeadsToAFile() throws IOException {
        List<String> broken = new ArrayList<>();
        for (Path page : pages()) {
            Matcher m = LINK.matcher(Files.readString(page));
            while (m.find()) {
                if (m.group(1).startsWith("http")) continue;
                if (!Files.isRegularFile(page.getParent().resolve(m.group(1))))
                    broken.add(page.getFileName() + " → " + m.group(1));
            }
        }
        assertEquals(List.of(), broken);
    }
}
