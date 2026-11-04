package xero.dialect.jmcc;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import xero.dialect.Dialect;
import xero.ide.Service;
import xero.lang.Diagnostic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

class ReportTest {
    private static final JmccDialect JMCC = new JmccDialect(Service.load());

    @Test
    void report() throws IOException {
        Assumptions.assumeTrue(Corpus.there());
        StringBuilder out = new StringBuilder();
        int ok = 0;
        int parsed = 0;
        int total = 0;
        Map<String, Integer> reasons = new TreeMap<>();
        for (Path p : Corpus.files()) {
            total++;
            long t0 = System.currentTimeMillis();
            Dialect.Built b = JMCC.build(p.toString().replace('\\', '/'), Corpus.text(p), Corpus.loader());
            long ms = System.currentTimeMillis() - t0;
            List<Diagnostic> errors = b.diagnostics().stream().filter(Diagnostic::error).toList();
            long warnings = b.diagnostics().size() - errors.size();
            if (b.ok()) ok++;
            java.util.List<Diagnostic> syntax = new java.util.ArrayList<>();
            Read.parse(null, Corpus.text(p), syntax);
            if (syntax.isEmpty()) parsed++;
            out.append(b.ok() ? "OK   " : "FAIL ").append(p).append(" errors=").append(errors.size()).append(" warnings=").append(warnings)
                    .append(" ms=").append(ms).append('\n');
            int shown = 0;
            for (Diagnostic d : errors) {
                reasons.merge(d.message().replaceAll("«[^»]*»", "«…»"), 1, Integer::sum);
                if (shown++ < 12) out.append("    ").append(d.file() == null ? "" : d.file().replaceAll(".*/", "")).append(':').append(d.start()).append(' ').append(d.message()).append(d.hint() == null ? "" : " | " + d.hint()).append('\n');
            }
        }
        out.append("\nСОБРАЛОСЬ ").append(ok).append(" из ").append(total).append(", разобралось без синтаксических ошибок ").append(parsed).append('\n');
        reasons.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(60)
                .forEach(e -> out.append(e.getValue()).append("  ").append(e.getKey()).append('\n'));
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "jmcc-report.txt"), out.toString(), StandardCharsets.UTF_8);
    }
}
