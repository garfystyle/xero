package xero.lang;

import java.util.List;

public record Diagnostic(boolean error, String message, int start, int end, String hint, String file, List<Fix> fixes) {
    public record Fix(String title, int start, int end, String text) {
        public static Fix replace(int start, int end, String text) {
            return new Fix("Заменить на «" + text + "»", start, end, text);
        }
    }

    public Diagnostic {
        fixes = fixes == null ? List.of() : List.copyOf(fixes);
    }

    public Diagnostic(boolean error, String message, int start, int end, String hint) {
        this(error, message, start, end, hint, null, List.of());
    }

    public Diagnostic(boolean error, String message, int start, int end, String hint, List<Fix> fixes) {
        this(error, message, start, end, hint, null, fixes);
    }

    public static Diagnostic error(String message, int start, int end) {
        return new Diagnostic(true, message, start, end, null);
    }

    public static Diagnostic error(String message, int start, int end, String hint) {
        return new Diagnostic(true, message, start, end, hint);
    }

    public static Diagnostic warning(String message, int start, int end) {
        return new Diagnostic(false, message, start, end, null);
    }

    public Diagnostic in(String file) {
        return new Diagnostic(error, message, start, end, hint, file, fixes);
    }

    public String render(Source source) {
        int line = source.line(start);
        int column = source.column(start);
        String text = source.lineText(line);
        int from = Math.min(column - 1, text.length());
        int width = Math.max(1, Math.min(end, start + text.length() - from) - start);
        StringBuilder out = new StringBuilder();
        out.append(source.name).append(':').append(line).append(':').append(column).append(": ")
                .append(error ? "ошибка" : "предупреждение").append(": ").append(message).append('\n');
        String gutter = String.valueOf(line);
        out.append(gutter).append(" | ").append(text).append('\n');
        out.append(" ".repeat(gutter.length())).append(" | ");
        for (int i = 0; i < from; i++) out.append(text.charAt(i) == '\t' ? '\t' : ' ');
        out.append("^".repeat(width)).append('\n');
        if (hint != null) out.append(" ".repeat(gutter.length())).append(" = ").append(hint).append('\n');
        return out.toString();
    }
}
