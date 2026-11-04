package xero.dialect;

import java.util.Map;

public final class Reindent {
    private static final String STEP = "    ";

    private Reindent() {}

    public static String apply(Syntax s, String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int depth = 0, state = Ink.NORMAL;
        for (int k = 0; k < lines.length; k++) {
            boolean kept = state != Ink.NORMAL;
            String line = kept ? lines[k].stripTrailing() : lines[k].strip();
            byte[] ink = new byte[line.length()];
            int next = Ink.scan(s, Map.of(), line, state, ink);
            int lead = 0, shift = 0;
            boolean head = true;
            for (int i = 0; i < line.length(); i++) {
                if (ink[i] != Ink.PUNCT) {
                    head = false;
                    continue;
                }
                char c = line.charAt(i);
                if (c == '{' || c == '(' || c == '[') {
                    shift++;
                    head = false;
                } else if (c == '}' || c == ')' || c == ']') {
                    shift--;
                    if (head) lead++;
                } else head = false;
            }
            if (kept) out.append(line);
            else if (!line.isEmpty()) out.append(STEP.repeat(Math.max(0, depth - lead))).append(line);
            depth = Math.max(0, depth + shift);
            state = next;
            if (k + 1 < lines.length) out.append('\n');
        }
        return out.toString();
    }
}
