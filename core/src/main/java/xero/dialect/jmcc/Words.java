package xero.dialect.jmcc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class Words {
    private static final Map<String, String> MEANING = new LinkedHashMap<>();
    private static final Map<String, String> OBJECTS = new LinkedHashMap<>();
    private static final Map<String, String> FACTORIES = new LinkedHashMap<>();
    private static final Map<String, String> TYPES = new LinkedHashMap<>();

    static final Set<String> SCOPES = Set.of("local", "game", "save", "line", "inline", "jmcc", "bracket");
    static final Set<String> TRUE = Set.of("true", "истина", "правда");
    static final Set<String> FALSE = Set.of("false", "ложь");

    private static void word(String meaning, String... spellings) {
        MEANING.put(meaning, meaning);
        for (String s : spellings) MEANING.put(s, meaning);
    }

    private static void object(String canonical, String... spellings) {
        OBJECTS.put(canonical, canonical);
        for (String s : spellings) OBJECTS.put(s, canonical);
    }

    private static void factory(String canonical, String... spellings) {
        FACTORIES.put(canonical, canonical);
        for (String s : spellings) FACTORIES.put(s, canonical);
    }

    private static void type(String xero, String... spellings) {
        for (String s : spellings) TYPES.put(s, xero);
    }

    static {
        word("import", "импорт");
        word("export", "экспорт");
        word("from", "из");
        word("var", "переменная", "перем", "пусть");
        word("const", "константа");
        word("function", "функция", "def", "определение", "fun", "действие");
        word("process", "процесс");
        word("event", "событие");
        word("class", "класс");
        word("interface", "интерфейс");
        word("enum", "перечисление");
        word("type", "тип", "typealias", "псевдоним");
        word("inline", "встраиваемый");
        word("cached", "кэшированный", "кешированный", "кэш", "кеш");
        word("line", "строка");
        word("local", "локальный");
        word("game", "игра");
        word("save", "сохранить", "сохранение");
        word("jmcc");
        word("bracket");
        word("ref", "ссылка");
        word("if", "если");
        word("else", "иначе");
        word("elif", "иначе_если", "иначеесли");
        word("while", "пока");
        word("for", "для");
        word("in", "в");
        word("break", "прервать");
        word("continue", "продолжить");
        word("return", "вернуть", "возврат");
        word("match", "выбор", "сопоставить");
        word("case", "вариант", "случай");
        word("default", "по_умолчанию");
        word("try", "попытка");
        word("catch", "исключение", "перехват", "поймать");
        word("throw", "выбросить", "бросить");
        word("as", "как");
        word("not", "не");
        word("and", "и");
        word("or", "или");
        object("player", "игрок");
        object("entity", "сущность");
        object("world", "мир", "game");
        object("variable", "переменная", "var");
        object("code", "код", "control");
        object("select", "выборка");
        object("repeat", "цикл", "повторить");
        object("controller", "контроллер");
        factory("item", "предмет");
        factory("block", "блок");
        factory("location", "местоположение", "локация", "координаты", "loc");
        factory("vector", "вектор", "vec");
        factory("sound", "звук");
        factory("particle", "частица");
        factory("potion", "зелье");
        factory("text", "текст");
        factory("array", "массив", "список", "list");
        factory("map", "словарь", "карта", "dict");
        factory("value", "значение");
        factory("enum", "перечисление");
        type("number", "number", "число", "integer", "int", "float", "double", "boolean", "bool", "логический");
        type("text", "text", "текст", "string", "str", "строка");
        type("location", "location", "loc", "местоположение", "локация", "координаты");
        type("vector", "vector", "vec", "вектор");
        type("block", "block", "блок");
        type("item", "item", "предмет");
        type("sound", "sound", "звук");
        type("particle", "particle", "частица");
        type("potion", "potion", "зелье");
        type("array", "array", "list", "массив", "список");
        type("map", "map", "dict", "словарь", "карта");
        type("variable", "variable", "переменная");
        type("any", "any", "любой", "unknown");
    }

    private Words() {}

    static String meaning(String word) { return MEANING.get(word); }

    static boolean means(String word, String meaning) { return meaning.equals(MEANING.get(word)); }

    static String object(String word) { return OBJECTS.get(word); }

    static String factory(String word) { return FACTORIES.get(word); }

    static String type(String word) { return TYPES.get(word); }

    static boolean keyword(String word) { return MEANING.containsKey(word) || TRUE.contains(word) || FALSE.contains(word); }

    static Set<String> keywords() { return Collections.unmodifiableSet(MEANING.keySet()); }

    static Set<String> types() { return Collections.unmodifiableSet(TYPES.keySet()); }
}
