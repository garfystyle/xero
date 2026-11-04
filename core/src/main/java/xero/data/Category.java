package xero.data;

public enum Category {
    PLAYER("Действие над игроком", "player", false),
    ENTITY("Действие над сущностью", "entity", false),
    GAME("Действие над миром", "game", false),
    VARIABLE("Действие с переменной", "var", false),
    SELECT("Выбрать цель", "select", false),
    REPEAT("Повторение", "repeat", false),
    CONTROL("Контроль действий", "control", false),
    CONTROLLER("Контроллер", "controller", false),
    IF_PLAYER("Если игрок", "player", true),
    IF_ENTITY("Если сущность", "entity", true),
    IF_GAME("Если в мире", "game", true),
    IF_VARIABLE("Если переменная", "var", true);

    public final String russian;
    public final String qualifier;
    public final boolean condition;

    Category(String russian, String qualifier, boolean condition) {
        this.russian = russian;
        this.qualifier = qualifier;
        this.condition = condition;
    }

    public static Category of(String russian) {
        for (Category c : values()) if (c.russian.equals(russian)) return c;
        return null;
    }
}
