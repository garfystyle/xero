package xero.lang;

import java.util.ArrayList;
import java.util.List;

public final class Source {
    public final String name;
    public final String text;
    private final int[] lineStarts;

    public Source(String name, String text) {
        this.name = name;
        this.text = text;
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
    }

    public int line(int offset) {
        int lo = 0, hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= offset) lo = mid; else hi = mid - 1;
        }
        return lo + 1;
    }

    public int column(int offset) {
        return offset - lineStarts[line(offset) - 1] + 1;
    }

    public String lineText(int line) {
        int start = lineStarts[line - 1];
        int end = line < lineStarts.length ? lineStarts[line] - 1 : text.length();
        if (end > start && text.charAt(end - 1) == '\r') end--;
        return text.substring(start, Math.max(start, end));
    }
}
