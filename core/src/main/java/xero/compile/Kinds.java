package xero.compile;

import xero.data.Data;

import java.util.Locale;
import java.util.Map;

final class Kinds {
    record Problem(boolean error, String message, String hint) {}

    static final String TEXT = "Текст", NUMBER = "Число", LOCATION = "Местоположение", VECTOR = "Вектор",
            ITEM = "Предмет", BLOCK = "Блок", SOUND = "Звук", PARTICLE = "Эффект частиц", POTION = "Зелье",
            LIST = "Список", MAP = "Словарь", VARIABLE = "Переменная", BOOL = "Условие";

    private static final Map<String, String> BY_TYPE = Map.ofEntries(
            Map.entry("text", TEXT), Map.entry("number", NUMBER), Map.entry("location", LOCATION),
            Map.entry("vector", VECTOR), Map.entry("item", ITEM), Map.entry("block", BLOCK),
            Map.entry("sound", SOUND), Map.entry("particle", PARTICLE), Map.entry("potion", POTION),
            Map.entry("array", LIST), Map.entry("map", MAP));

    private static final Map<String, String> PARAM_TYPES = Map.ofEntries(
            Map.entry("text", TEXT), Map.entry("number", NUMBER), Map.entry("location", LOCATION),
            Map.entry("vector", VECTOR), Map.entry("item", ITEM), Map.entry("block", BLOCK),
            Map.entry("sound", SOUND), Map.entry("particle", PARTICLE), Map.entry("potion", POTION),
            Map.entry("array", LIST), Map.entry("map", MAP), Map.entry("variable", VARIABLE));

    private static final Map<String, String> EXAMPLES = Map.ofEntries(
            Map.entry(NUMBER, "число, num(\"…\") или переменная"), Map.entry(LOCATION, "loc(x, y, z) или переменная"),
            Map.entry(VECTOR, "vec(x, y, z)"), Map.entry(ITEM, "item(id)"), Map.entry(BLOCK, "block(id)"),
            Map.entry(SOUND, "sound(id)"), Map.entry(PARTICLE, "particle(id)"), Map.entry(POTION, "potion(id)"),
            Map.entry(LIST, "[…] или переменная со списком"), Map.entry(MAP, "{ключ: значение} или переменная"),
            Map.entry(VARIABLE, "имя переменной"));

    static String slot(String kind) { return BOOL.equals(kind) ? NUMBER : kind; }

    static String ofType(String type) { return type == null ? null : BY_TYPE.get(type); }

    static String ofParam(String valueType) { return valueType == null ? null : PARAM_TYPES.get(valueType); }

    static boolean spatial(String kind) { return VECTOR.equals(kind) || LOCATION.equals(kind); }

    static String ofGameValue(Data data, String id) {
        Data.GameValue g = data.gameValues().get(id);
        if (g == null || g.returns().isEmpty() || g.returns().equals(Data.ANY)) return null;
        return g.returns();
    }

    static Problem check(String slot, String value, String text) {
        if (slot == null || value == null || slot.equals(Data.ANY) || slot.equals(TEXT) || slot.equals(value)) return null;
        if (slot.equals(VARIABLE)) return wrong(slot, value);
        if (value.equals(TEXT)) {
            switch (slot) {
                case NUMBER -> {
                    if (text == null) return new Problem(false,
                            "текстовое значение в «" + lower(slot) + "»: сервер поймёт его, только если там число", null);
                    if (Texts.numeric(text) || text.contains("%")) return null;
                    return new Problem(true, "«" + text + "» — не число",
                            "сервер остановит строку: «Ожидал аргумент … с типом Число, но получил Текст»; пиши " + EXAMPLES.get(NUMBER));
                }
                case ITEM, BLOCK, SOUND, VECTOR, PARTICLE -> { return null; }
                case POTION -> {
                    return new Problem(false, "текст в «зелье» сервер принимает без ошибки, но эффекта не выдаёт",
                            "пиши " + EXAMPLES.get(POTION));
                }
                default -> { return wrong(slot, value); }
            }
        }
        if (value.equals(ITEM) && slot.equals(BLOCK)) return null;
        return wrong(slot, value);
    }

    private static Problem wrong(String slot, String value) {
        return new Problem(true, "здесь нужно «" + lower(slot) + "», а передано «" + lower(value) + "»",
                "сервер остановит строку: «Ожидал аргумент … с типом " + slot + ", но получил " + value + "»"
                        + (EXAMPLES.containsKey(slot) ? "; пиши " + EXAMPLES.get(slot) : ""));
    }

    static String lower(String kind) {
        return kind.toLowerCase(Locale.ROOT);
    }

    private Kinds() {}
}
