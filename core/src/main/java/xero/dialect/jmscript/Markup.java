package xero.dialect.jmscript;

import xero.json.Json;

final class Markup {
    private static final String LEGACY = "#0123456789abcdefABCDEFklmnorKLMNOR";

    private Markup() {}

    static String detect(String text) {
        if (json(text)) return "json";
        if (minimessage(text)) return "mm";
        if (legacy(text)) return null;
        boolean bare = text.indexOf('&') < 0 && text.indexOf('§') < 0 && text.indexOf('\\') < 0;
        return bare ? null : "plain";
    }

    private static boolean json(String text) {
        String t = text.strip();
        if (t.length() < 2 || t.charAt(0) != '{' || t.charAt(t.length() - 1) != '}') return false;
        try {
            Json.parse(t);
            return true;
        } catch (RuntimeException | StackOverflowError e) {
            return false;
        }
    }

    private static boolean minimessage(String t) {
        for (int i = 0; i + 2 < t.length(); i++) {
            if (t.charAt(i) != '<') continue;
            char c = t.charAt(i + 1);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '#' || c == '/')
                return t.indexOf('>', i + 2) >= 0;
        }
        return false;
    }

    private static boolean legacy(String t) {
        for (int i = 0; i + 1 < t.length(); i++)
            if (t.charAt(i) == '&' && LEGACY.indexOf(t.charAt(i + 1)) >= 0) return true;
        return false;
    }
}
