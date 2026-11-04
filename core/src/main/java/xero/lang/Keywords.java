package xero.lang;

import xero.data.Category;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class Keywords {
    public enum Kind { RESERVED, CONTEXTUAL }

    private record Word(String meaning, Kind kind) {}

    private static final Map<String, Word> WORDS = new LinkedHashMap<>();

    static {
        reserve("when", "когда");
        reserve("func", "функция");
        reserve("process", "процесс");
        reserve("import", "импорт");
        reserve("if", "если");
        reserve("else", "иначе");
        reserve("not", "не");
        reserve("and", "и");
        reserve("or", "или");
        reserve("return", "вернуть");
        reserve("break", "прервать");
        reserve("continue", "продолжить");
        reserve("start", "запустить");
        reserve("repeat", "повторить");
        reserve("for", "для");
        reserve("while", "пока");
        reserve("forever", "всегда");
        reserve("_");
        allow("in", "в");
        allow("const", "константа");
        allow("step", "шаг");
        allow("game");
        allow("save");
        allow("local");
        allow("line");
        allow("value");
    }

    private static void reserve(String... spellings) {
        for (String s : spellings) WORDS.put(s, new Word(spellings[0], Kind.RESERVED));
    }

    private static void allow(String... spellings) {
        for (String s : spellings) WORDS.put(s, new Word(spellings[0], Kind.CONTEXTUAL));
    }

    public static Set<String> all() { return Collections.unmodifiableSet(WORDS.keySet()); }

    public static final Set<String> SCOPES = Set.of("game", "save", "local", "line");

    private static final Set<String> QUALIFIERS = new LinkedHashSet<>(SCOPES);

    static {
        QUALIFIERS.add("value");
        for (Category c : Category.values()) QUALIFIERS.add(c.qualifier);
    }

    public static boolean qualifier(String s) {
        return QUALIFIERS.contains(s);
    }

    public static boolean dotted(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length < 2 || qualifier(parts[0])) return false;
        for (String p : parts) if (!plainName(p)) return false;
        return true;
    }

    public static String function(String s) {
        return dotted(s) ? s : name(s);
    }

    public static String meaning(String word) {
        Word w = WORDS.get(word);
        return w == null ? null : w.meaning();
    }

    public static boolean word(String s) {
        return WORDS.containsKey(s);
    }

    public static boolean reserved(String s) {
        Word w = WORDS.get(s);
        return w != null && w.kind() == Kind.RESERVED;
    }

    public static boolean contextual(String s) {
        Word w = WORDS.get(s);
        return w != null && w.kind() == Kind.CONTEXTUAL;
    }

    public static boolean means(String s, String... meanings) {
        String m = meaning(s);
        if (m == null) return false;
        for (String x : meanings) if (x.equals(m)) return true;
        return false;
    }

    public static Set<String> spellings(String meaning) {
        Set<String> out = new LinkedHashSet<>();
        WORDS.forEach((s, w) -> { if (w.meaning().equals(meaning)) out.add(s); });
        return Collections.unmodifiableSet(out);
    }

    public static Set<String> english() {
        Set<String> out = new LinkedHashSet<>();
        for (Word w : WORDS.values()) out.add(w.meaning());
        return Collections.unmodifiableSet(out);
    }

    public static boolean plainName(String s) {
        return Lexer.identifier(s) && !word(s);
    }

    public static String name(String s) {
        return plainName(s) ? s : "`" + s + "`";
    }

    private Keywords() {}
}
