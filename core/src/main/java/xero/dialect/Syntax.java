package xero.dialect;

import java.util.Set;

public record Syntax(Set<String> keywords, Set<String> literals, Set<String> types, String lineComment,
                     String otherComment, String blockOpen, String blockClose, boolean nested, String quotes,
                     String prefixes, boolean multiline, boolean dollar, boolean backtick, String backtickPrefixes,
                     String joiner, char decorator) {
    public static Builder of(Set<String> keywords) { return new Builder(keywords); }

    public static final class Builder {
        private final Set<String> keywords;
        private Set<String> literals = Set.of("true", "false");
        private Set<String> types = Set.of();
        private String lineComment = "//", otherComment, blockOpen = "/*", blockClose = "*/";
        private boolean nested;
        private String quotes = "\"", prefixes = "";
        private boolean multiline, dollar, backtick;
        private String backtickPrefixes = "";
        private String joiner;
        private char decorator;

        private Builder(Set<String> keywords) { this.keywords = keywords; }

        public Builder literals(Set<String> v) { literals = v; return this; }

        public Builder types(Set<String> v) { types = v; return this; }

        public Builder comments(String line, String other, String open, String close, boolean nest) {
            lineComment = line;
            otherComment = other;
            blockOpen = open;
            blockClose = close;
            nested = nest;
            return this;
        }

        public Builder strings(String q, String p, boolean lines, boolean interpolated) {
            quotes = q;
            prefixes = p;
            multiline = lines;
            dollar = interpolated;
            return this;
        }

        public Builder backtick(String p) {
            backtick = true;
            backtickPrefixes = p;
            return this;
        }

        public Builder joiner(String v) { joiner = v; return this; }

        public Builder decorator(char v) { decorator = v; return this; }

        public Syntax build() {
            return new Syntax(keywords, literals, types, lineComment, otherComment, blockOpen, blockClose, nested,
                    quotes, prefixes, multiline, dollar, backtick, backtickPrefixes, joiner, decorator);
        }
    }
}
