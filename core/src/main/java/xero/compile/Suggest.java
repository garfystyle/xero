package xero.compile;

import java.util.Collection;
import java.util.Locale;

final class Suggest {
    static String best(String word, Collection<String> candidates) {
        String w = word.toLowerCase(Locale.ROOT);
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (String c : candidates) {
            String lc = c.toLowerCase(Locale.ROOT);
            int d = distance(w, lc);
            if (lc.contains(w) && w.length() >= 4) d = Math.min(d, 1 + (lc.length() - w.length()) / 4);
            if (d > 1 && otherWord(w, lc)) continue;
            if (d < bestScore || (d == bestScore && best != null && c.length() < best.length())) {
                bestScore = d;
                best = c;
            }
        }
        if (best == null) return null;
        int limit = w.length() <= 3 ? 1 : w.length() <= 6 ? 2 : 3;
        return bestScore <= limit ? best : null;
    }

    private static boolean otherWord(String word, String candidate) {
        String[] a = word.split("_");
        String[] b = candidate.split("_");
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            int shortest = Math.min(a[i].length(), b[i].length());
            if (distance(a[i], b[i]) > Math.max(1, shortest / 2)) return true;
        }
        return false;
    }

    static String hint(String word, Collection<String> candidates) {
        return meant(best(word, candidates));
    }

    static String meant(String best) {
        return best == null ? null : "возможно, вы имели в виду «" + best + "»";
    }

    private static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
            }
        }
        return d[a.length()][b.length()];
    }

    private Suggest() {}
}
