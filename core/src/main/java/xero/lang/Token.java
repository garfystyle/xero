package xero.lang;

import java.util.List;

public record Token(Kind kind, String text, String prefix, int start, int end, boolean newline, List<Object> parts) {
    public enum Kind { IDENT, NUMBER, STRING, NAME, SYMBOL, EOF }

    public record Code(int start, int end) {}

    public Token(Kind kind, String text, String prefix, int start, int end, boolean newline) {
        this(kind, text, prefix, start, end, newline, null);
    }

    public boolean is(String symbol) {
        return (kind == Kind.SYMBOL || kind == Kind.IDENT) && text.equals(symbol);
    }

    public boolean word(String... words) {
        if (kind != Kind.IDENT) return false;
        for (String w : words) if (text.equals(w)) return true;
        return false;
    }

    public boolean keyword(String... meanings) {
        return kind == Kind.IDENT && Keywords.means(text, meanings);
    }
}
