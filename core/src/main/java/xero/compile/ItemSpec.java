package xero.compile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ItemSpec {
    public static final List<String> KEYS = List.of("name", "lore", "enchantments", "unbreakable", "damage", "glint",
            "model", "hide_tooltip", "tags", "components");

    static final String NAME = "minecraft:custom_name", LORE = "minecraft:lore", ENCHANTMENTS = "minecraft:enchantments",
            UNBREAKABLE = "minecraft:unbreakable", DAMAGE = "minecraft:damage",
            GLINT = "minecraft:enchantment_glint_override", MODEL = "minecraft:custom_model_data",
            TOOLTIP = "minecraft:tooltip_display", DATA = "minecraft:custom_data";
    static final String BUKKIT = "PublicBukkitValues", TAG_PREFIX = "justcreativeplus:";

    public record Line(String text, String parsing) {}

    public sealed interface Part permits Name, Lore, Enchantments, Flag, Whole, Model, Tags, Raw {}

    public record Name(Line text) implements Part {}

    public record Lore(List<Line> lines) implements Part {}

    public record Enchantments(Map<String, Integer> levels) implements Part {}

    public record Flag(String key, boolean on) implements Part {}

    public record Whole(String key, int value) implements Part {}

    public record Model(float value) implements Part {}

    public record Tags(Map<String, String> values) implements Part {}

    public record Raw(Nbt.Compound entries) implements Part {}

    public static final class Clash extends Exception {
        private static final long serialVersionUID = 1L;
        public final int part;

        Clash(String message, int part) {
            super(message, null, false, false);
            this.part = part;
        }
    }

    static String component(Part p) {
        return switch (p) {
            case Name n -> NAME;
            case Lore l -> LORE;
            case Enchantments e -> ENCHANTMENTS;
            case Flag f -> f.key().equals("glint") ? GLINT : f.key().equals("unbreakable") ? UNBREAKABLE : TOOLTIP;
            case Whole w -> DAMAGE;
            case Model m -> MODEL;
            case Tags t -> DATA;
            case Raw r -> null;
        };
    }

    public static String namespaced(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    public static Nbt.Compound build(List<Part> parts) throws Clash, Styled.Problem {
        Nbt.Compound out = new Nbt.Compound();
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            if (p instanceof Raw r) {
                for (Map.Entry<String, Nbt.Tag> e : r.entries().entries().entrySet()) {
                    String key = namespaced(e.getKey());
                    if (out.entries().containsKey(key)) throw new Clash("компонент «" + key + "» задан дважды", i);
                    out.put(key, e.getValue());
                }
                continue;
            }
            String key = component(p);
            if (out.entries().containsKey(key)) throw new Clash("компонент «" + key + "» задан дважды", i);
            out.put(key, tag(p));
        }
        return out;
    }

    private static Nbt.Tag tag(Part p) throws Styled.Problem {
        return switch (p) {
            case Name n -> Styled.component(n.text().text(), n.text().parsing(), false);
            case Lore l -> {
                List<Nbt.Tag> lines = new ArrayList<>();
                for (Line line : l.lines()) lines.add(Styled.component(line.text(), line.parsing(), true));
                yield Nbt.list(lines);
            }
            case Enchantments e -> {
                Nbt.Compound c = new Nbt.Compound();
                for (Map.Entry<String, Integer> lv : e.levels().entrySet())
                    c.put(namespaced(lv.getKey()), Nbt.Num.ofInt(lv.getValue()));
                yield c;
            }
            case Flag f -> switch (f.key()) {
                case "glint" -> Nbt.Num.ofByte(f.on() ? 1 : 0);
                case "unbreakable" -> new Nbt.Compound();
                default -> new Nbt.Compound().put("hide_tooltip", Nbt.Num.ofByte(1));
            };
            case Whole w -> Nbt.Num.ofInt(w.value());
            case Model m -> new Nbt.Compound().put("floats", new Nbt.ListTag(Nbt.FLOAT, List.of(Nbt.Num.ofFloat(m.value()))));
            case Tags t -> {
                Nbt.Compound values = new Nbt.Compound();
                for (Map.Entry<String, String> e : t.values().entrySet())
                    values.put(TAG_PREFIX + e.getKey(), new Nbt.Text(e.getValue()));
                yield new Nbt.Compound().put(BUKKIT, values);
            }
            case Raw r -> r.entries();
        };
    }

    static Nbt.Compound tidy(Nbt.Compound components) {
        Nbt.Compound out = new Nbt.Compound();
        for (Map.Entry<String, Nbt.Tag> e : components.entries().entrySet()) {
            Nbt.Tag value = e.getValue();
            if (NAME.equals(e.getKey())) value = Styled.flat(value);
            else if (LORE.equals(e.getKey()) && value instanceof Nbt.ListTag list) {
                List<Nbt.Tag> lines = new ArrayList<>();
                for (Nbt.Tag t : list.items()) lines.add(Styled.flat(t));
                value = new Nbt.ListTag(list.element(), lines);
            }
            out.put(e.getKey(), value);
        }
        return out;
    }

    public static List<Part> read(Nbt.Compound components, Set<String> enchantments) {
        List<Part> parts = new ArrayList<>();
        Nbt.Compound raw = null;
        int groups = 0;
        for (Map.Entry<String, Nbt.Tag> e : components.entries().entrySet()) {
            Part p = sugar(e.getKey(), e.getValue(), enchantments);
            if (p != null) {
                parts.add(p);
                raw = null;
                continue;
            }
            if (raw == null) {
                raw = new Nbt.Compound();
                parts.add(new Raw(raw));
                groups++;
            }
            raw.put(e.getKey(), e.getValue());
        }
        if (groups <= 1 && exact(parts, components)) return parts;
        List<Part> whole = List.of(new Raw(components));
        return exact(whole, components) ? whole : null;
    }

    private static boolean exact(List<Part> parts, Nbt.Compound components) {
        try {
            return Nbt.same(build(parts), components);
        } catch (Clash | Styled.Problem | RuntimeException e) {
            return false;
        }
    }

    private static Part sugar(String key, Nbt.Tag value, Set<String> enchantments) {
        switch (key) {
            case NAME -> {
                String text = Styled.legacyOf(value, false);
                return text == null ? null : new Name(new Line(text, "legacy"));
            }
            case LORE -> {
                if (!(value instanceof Nbt.ListTag list) || list.element() != Nbt.COMPOUND || list.items().isEmpty()) return null;
                List<Line> lines = new ArrayList<>();
                for (Nbt.Tag t : list.items()) {
                    String text = Styled.legacyOf(t, true);
                    if (text == null) return null;
                    lines.add(new Line(text, "legacy"));
                }
                return new Lore(lines);
            }
            case ENCHANTMENTS -> {
                if (!(value instanceof Nbt.Compound c) || c.entries().isEmpty()) return null;
                Map<String, Integer> levels = new LinkedHashMap<>();
                for (Map.Entry<String, Nbt.Tag> e : c.entries().entrySet()) {
                    if (!enchantments.contains(e.getKey()) || !(e.getValue() instanceof Nbt.Num n) || n.type() != Nbt.INT) return null;
                    levels.put(e.getKey().substring("minecraft:".length()), (int) n.bits());
                }
                return new Enchantments(levels);
            }
            case UNBREAKABLE -> {
                return value instanceof Nbt.Compound c && c.entries().isEmpty() ? new Flag("unbreakable", true) : null;
            }
            case GLINT -> {
                return value instanceof Nbt.Num n && n.type() == Nbt.BYTE && (n.bits() == 0 || n.bits() == 1)
                        ? new Flag("glint", n.bits() == 1) : null;
            }
            case TOOLTIP -> {
                return value instanceof Nbt.Compound c && c.entries().size() == 1
                        && c.get("hide_tooltip") instanceof Nbt.Num n && n.type() == Nbt.BYTE && n.bits() == 1
                        ? new Flag("hide_tooltip", true) : null;
            }
            case DAMAGE -> {
                return value instanceof Nbt.Num n && n.type() == Nbt.INT ? new Whole("damage", (int) n.bits()) : null;
            }
            case MODEL -> {
                if (value instanceof Nbt.Compound c && c.entries().size() == 1 && c.get("floats") instanceof Nbt.ListTag l
                        && l.element() == Nbt.FLOAT && l.items().size() == 1) {
                    float f = ((Nbt.Num) l.items().get(0)).asFloat();
                    return Float.isFinite(f) ? new Model(f) : null;
                }
                return null;
            }
            case DATA -> {
                if (!(value instanceof Nbt.Compound c) || c.entries().size() != 1
                        || !(c.get(BUKKIT) instanceof Nbt.Compound bukkit) || bukkit.entries().isEmpty()) return null;
                Map<String, String> values = new LinkedHashMap<>();
                for (Map.Entry<String, Nbt.Tag> e : bukkit.entries().entrySet()) {
                    if (!e.getKey().startsWith(TAG_PREFIX) || !(e.getValue() instanceof Nbt.Text t)) return null;
                    values.put(e.getKey().substring(TAG_PREFIX.length()), t.value());
                }
                return new Tags(values);
            }
            default -> {
                return null;
            }
        }
    }

    private ItemSpec() {}
}
