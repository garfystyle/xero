package xero.compile;

import xero.json.Json;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Styled {
    public record Style(String color, Boolean bold, Boolean italic, Boolean underlined, Boolean strikethrough,
                        Boolean obfuscated) {
        static final Style NONE = new Style(null, null, null, null, null, null);

        Style color(String c) { return new Style(c, bold, italic, underlined, strikethrough, obfuscated); }

        Style with(String deco, Boolean on) {
            return switch (deco) {
                case "bold" -> new Style(color, on, italic, underlined, strikethrough, obfuscated);
                case "italic" -> new Style(color, bold, on, underlined, strikethrough, obfuscated);
                case "underlined" -> new Style(color, bold, italic, on, strikethrough, obfuscated);
                case "strikethrough" -> new Style(color, bold, italic, underlined, on, obfuscated);
                default -> new Style(color, bold, italic, underlined, strikethrough, on);
            };
        }

        Boolean get(String deco) {
            return switch (deco) {
                case "bold" -> bold;
                case "italic" -> italic;
                case "underlined" -> underlined;
                case "strikethrough" -> strikethrough;
                default -> obfuscated;
            };
        }
    }

    public record Run(String text, Style style) {}

    static final List<String> DECOS = List.of("bold", "italic", "underlined", "strikethrough", "obfuscated");
    private static final String DECO_CODES = "lonmk";

    static final List<String> COLORS = List.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red",
            "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");
    private static final int[] RGB = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
    private static final String CODES = "0123456789abcdef";

    public static final class Problem extends Exception {
        private static final long serialVersionUID = 1L;

        Problem(String message) { super(message, null, false, false); }
    }

    public static List<Run> runs(String text, String parsing) {
        return switch (parsing) {
            case "plain" -> text.isEmpty() ? List.of() : List.of(new Run(text, Style.NONE));
            case "minimessage" -> mini(text);
            default -> legacy(text);
        };
    }

    static List<Run> legacy(String s) {
        List<Run> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        Style style = Style.NONE;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < s.length()) {
                char next = Character.toLowerCase(s.charAt(i + 1));
                if (next == '#' && i + 8 <= s.length() && hex(s.substring(i + 2, i + 8)) != null) {
                    push(out, buf, style);
                    style = Style.NONE.color(hex(s.substring(i + 2, i + 8)));
                    i += 8;
                    continue;
                }
                int color = CODES.indexOf(next);
                int deco = DECO_CODES.indexOf(next);
                if (color >= 0 || deco >= 0 || next == 'r') {
                    push(out, buf, style);
                    if (color >= 0) style = Style.NONE.color(COLORS.get(color));
                    else if (deco >= 0) style = style.with(DECOS.get(deco), Boolean.TRUE);
                    else style = Style.NONE;
                    i += 2;
                    continue;
                }
            }
            buf.append(c);
            i++;
        }
        push(out, buf, style);
        return out;
    }

    private static void push(List<Run> out, StringBuilder buf, Style style) {
        if (buf.isEmpty()) return;
        out.add(new Run(buf.toString(), style));
        buf.setLength(0);
    }

    private static String hex(String s) {
        String h = s.startsWith("#") ? s.substring(1) : s;
        if (h.length() != 6) return null;
        for (int i = 0; i < 6; i++) if (Character.digit(h.charAt(i), 16) < 0) return null;
        return "#" + h.toUpperCase(Locale.ROOT);
    }

    static List<Run> mini(String s) {
        List<Run> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        Deque<Style> stack = new ArrayDeque<>();
        Style style = Style.NONE;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && (s.charAt(i + 1) == '<' || s.charAt(i + 1) == '\\')) {
                buf.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            int close = c == '<' ? s.indexOf('>', i + 1) : -1;
            if (close < 0) {
                buf.append(c);
                i++;
                continue;
            }
            String body = s.substring(i + 1, close).trim().toLowerCase(Locale.ROOT);
            boolean closing = body.startsWith("/");
            if (closing) body = body.substring(1);
            String name = body.contains(":") ? body.substring(0, body.indexOf(':')) : body;
            if (!closing && (name.equals("gradient") || name.equals("rainbow"))) {
                int end = matching(s, close + 1, name);
                String inner = s.substring(close + 1, end < 0 ? s.length() : end);
                push(out, buf, style);
                out.addAll(paint(mini(inner), style, name, body));
                i = end < 0 ? s.length() : s.indexOf('>', end) + 1;
                continue;
            }
            if (closing && (name.equals("gradient") || name.equals("rainbow"))) {
                i = close + 1;
                continue;
            }
            Style applied = tag(style, body);
            if (applied == null) {
                buf.append(s, i, close + 1);
                i = close + 1;
                continue;
            }
            push(out, buf, style);
            if (closing) style = stack.isEmpty() ? Style.NONE : stack.pop();
            else if (name.equals("reset")) {
                stack.clear();
                style = Style.NONE;
            } else {
                stack.push(style);
                style = applied;
            }
            i = close + 1;
        }
        push(out, buf, style);
        List<Run> merged = new ArrayList<>();
        for (Run r : out) {
            Run last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last.style().equals(r.style())) merged.set(merged.size() - 1, new Run(last.text() + r.text(), r.style()));
            else merged.add(r);
        }
        return merged;
    }

    private static int matching(String s, int from, String name) {
        int depth = 0;
        int i = from;
        while (i < s.length()) {
            int lt = s.indexOf('<', i);
            if (lt < 0) return -1;
            int gt = s.indexOf('>', lt + 1);
            if (gt < 0) return -1;
            String tag = s.substring(lt + 1, gt).trim().toLowerCase(Locale.ROOT);
            String head = tag.startsWith("/") ? tag.substring(1) : tag;
            if (head.contains(":")) head = head.substring(0, head.indexOf(':'));
            if (head.equals(name)) {
                if (tag.startsWith("/")) {
                    if (depth == 0) return lt;
                    depth--;
                } else {
                    depth++;
                }
            }
            i = gt + 1;
        }
        return -1;
    }

    private static Style tag(Style style, String body) {
        if (body.isEmpty()) return null;
        String name = body.contains(":") ? body.substring(0, body.indexOf(':')) : body;
        if (name.equals("reset")) return Style.NONE;
        boolean off = name.startsWith("!");
        if (off) name = name.substring(1);
        if (name.startsWith("#")) {
            String h = hex(name);
            return h == null ? null : style.color(h);
        }
        if (name.equals("color") || name.equals("colour") || name.equals("c")) {
            if (!body.contains(":")) return null;
            String c = color(body.substring(body.indexOf(':') + 1));
            return c == null ? null : style.color(c);
        }
        if (COLORS.contains(name)) return style.color(name);
        Boolean on = off ? Boolean.FALSE : Boolean.TRUE;
        return switch (name) {
            case "bold", "b" -> style.with("bold", on);
            case "italic", "i", "em" -> style.with("italic", on);
            case "underlined", "u" -> style.with("underlined", on);
            case "strikethrough", "st" -> style.with("strikethrough", on);
            case "obfuscated", "obf" -> style.with("obfuscated", on);
            default -> null;
        };
    }

    private static String color(String name) {
        if (name.startsWith("#")) return hex(name);
        return COLORS.contains(name) ? name : null;
    }

    private static int rgb(String color) {
        if (color.startsWith("#")) return Integer.parseInt(color.substring(1), 16);
        return RGB[COLORS.indexOf(color)];
    }

    private static List<Run> paint(List<Run> inner, Style outer, String kind, String body) {
        int total = 0;
        for (Run r : inner) total += r.text().length();
        if (total == 0) return inner;
        List<String> args = new ArrayList<>();
        int colon = body.indexOf(':');
        if (colon >= 0) for (String a : body.substring(colon + 1).split(":")) args.add(a.trim());
        List<Integer> stops = new ArrayList<>();
        double phase = 0;
        for (String a : args) {
            String c = color(a);
            if (c != null && kind.equals("gradient")) stops.add(rgb(c));
            else {
                try {
                    phase = Double.parseDouble(a.startsWith("!") ? a.substring(1) : a);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (kind.equals("gradient") && stops.size() < 2) {
            stops.clear();
            stops.add(0xFFFFFF);
            stops.add(0x000000);
        }
        List<Run> out = new ArrayList<>();
        int index = 0;
        for (Run r : inner) {
            for (int k = 0; k < r.text().length(); k++) {
                int color;
                if (kind.equals("rainbow")) {
                    color = hsv((float) (((index / (double) total) + phase) % 1.0));
                } else {
                    double t = total == 1 ? 0 : index / (double) (total - 1);
                    color = gradient(stops, Math.min(1, Math.max(0, t + phase)));
                }
                String hex = Integer.toHexString(0x1000000 | color).substring(1).toUpperCase(Locale.ROOT);
                out.add(new Run(String.valueOf(r.text().charAt(k)), r.style().color("#" + hex)));
                index++;
            }
        }
        return out;
    }

    private static int gradient(List<Integer> stops, double t) {
        double scaled = t * (stops.size() - 1);
        int i = Math.min(stops.size() - 2, (int) Math.floor(scaled));
        double f = scaled - i;
        int a = stops.get(i), b = stops.get(i + 1);
        int r = (int) Math.round(((a >> 16) & 0xff) + (((b >> 16) & 0xff) - ((a >> 16) & 0xff)) * f);
        int g = (int) Math.round(((a >> 8) & 0xff) + (((b >> 8) & 0xff) - ((a >> 8) & 0xff)) * f);
        int bl = (int) Math.round((a & 0xff) + ((b & 0xff) - (a & 0xff)) * f);
        return (r << 16) | (g << 8) | bl;
    }

    private static int hsv(float h) {
        float s = 1, v = 1;
        int i = (int) Math.floor(h * 6) % 6;
        float f = h * 6 - (float) Math.floor(h * 6);
        float p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        float r, g, b;
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    public static Nbt.Tag component(String text, String parsing, boolean lore) throws Problem {
        if (parsing.equals("json")) return json(text);
        Style root = new Style(lore ? "gray" : null, null, Boolean.FALSE, null, null, null);
        List<Run> runs = runs(text, parsing);
        if (runs.size() == 1) return compound(runs.get(0).text(), merge(root, runs.get(0).style()));
        Nbt.Compound c = compound("", root);
        if (runs.isEmpty()) return c;
        List<Nbt.Tag> extra = new ArrayList<>();
        for (Run r : runs) extra.add(compound(r.text(), r.style()));
        c.put("extra", new Nbt.ListTag(Nbt.COMPOUND, extra));
        return c;
    }

    private static Style merge(Style root, Style run) {
        Style s = run.color() == null && root.color() != null ? run.color(root.color()) : run;
        for (String d : DECOS) if (s.get(d) == null && root.get(d) != null) s = s.with(d, root.get(d));
        return s;
    }

    private static Nbt.Compound compound(String text, Style s) {
        Nbt.Compound c = new Nbt.Compound().put("text", new Nbt.Text(text));
        if (s.color() != null) c.put("color", new Nbt.Text(s.color()));
        for (String d : DECOS) if (s.get(d) != null) c.put(d, Nbt.Num.ofByte(s.get(d) ? 1 : 0));
        return c;
    }

    private static Nbt.Tag json(String text) throws Problem {
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (Json.Error e) {
            throw new Problem("в json\"…\" не JSON: " + e.getMessage());
        }
        return fromJson(parsed);
    }

    private static Nbt.Tag fromJson(Object v) throws Problem {
        if (v instanceof String s) return new Nbt.Text(s);
        if (v instanceof Boolean b) return Nbt.Num.ofByte(b ? 1 : 0);
        if (v instanceof Json.Num n) {
            String t = n.text();
            if (t.matches("-?\\d{1,9}")) return Nbt.Num.ofInt(Integer.parseInt(t));
            return Nbt.Num.ofDouble(Double.parseDouble(t));
        }
        if (v instanceof List<?> list) {
            List<Nbt.Tag> items = new ArrayList<>();
            for (Object o : list) items.add(fromJson(o));
            return Nbt.list(items);
        }
        if (v instanceof Map<?, ?> m) {
            Nbt.Compound c = new Nbt.Compound();
            for (Map.Entry<?, ?> e : m.entrySet()) c.put((String) e.getKey(), fromJson(e.getValue()));
            return c;
        }
        throw new Problem("в json\"…\" нет значения");
    }

    private static Nbt.Compound run(Nbt.Tag item) {
        if (item instanceof Nbt.Text t) return new Nbt.Compound().put("text", t);
        if (!(item instanceof Nbt.Compound c)) return null;
        if (c.entries().size() == 1 && c.get("") instanceof Nbt.Text t) return new Nbt.Compound().put("text", t);
        if (!(c.get("text") instanceof Nbt.Text)) return null;
        Nbt.Compound out = new Nbt.Compound().put("text", c.get("text"));
        if (c.get("color") != null) out.put("color", c.get("color"));
        for (String d : DECOS) if (c.get(d) != null) out.put(d, c.get(d));
        for (Map.Entry<String, Nbt.Tag> e : c.entries().entrySet())
            if (out.get(e.getKey()) == null) out.put(e.getKey(), e.getValue());
        return out;
    }

    private static boolean styleOnly(Nbt.Compound c, boolean root) {
        for (String key : c.entries().keySet()) {
            boolean known = key.equals("text") || key.equals("color") || DECOS.contains(key) || root && key.equals("extra");
            if (!known) return false;
        }
        return true;
    }

    static Nbt.Tag flat(Nbt.Tag tag) {
        if (!(tag instanceof Nbt.Compound c) || !(c.get("text") instanceof Nbt.Text t) || !t.value().isEmpty()) return tag;
        if (!(c.get("extra") instanceof Nbt.ListTag extra) || extra.items().isEmpty() || !styleOnly(c, true)) return tag;
        List<Nbt.Tag> runs = new ArrayList<>();
        for (Nbt.Tag item : extra.items()) {
            Nbt.Compound r = run(item);
            if (r == null || !styleOnly(r, false)) return tag;
            runs.add(r);
        }
        Nbt.Compound out = new Nbt.Compound();
        if (runs.size() > 1) {
            out.put("text", t);
            if (c.get("color") != null) out.put("color", c.get("color"));
            for (String d : DECOS) if (c.get(d) != null) out.put(d, c.get(d));
            out.put("extra", new Nbt.ListTag(Nbt.COMPOUND, runs));
            return out;
        }
        Nbt.Compound p = (Nbt.Compound) runs.get(0);
        out.put("text", p.get("text"));
        Nbt.Tag color = p.get("color") != null ? p.get("color") : c.get("color");
        if (color != null) out.put("color", color);
        for (String d : DECOS) {
            Nbt.Tag v = p.get(d) != null ? p.get(d) : c.get(d);
            if (v != null) out.put(d, v);
        }
        return out;
    }

    public static String legacyOf(Nbt.Tag tag, boolean lore) {
        if (!(tag instanceof Nbt.Compound c)) return null;
        List<Nbt.Compound> parts = new ArrayList<>();
        if (c.get("extra") instanceof Nbt.ListTag extra) {
            for (Nbt.Tag t : extra.items()) {
                if (!(t instanceof Nbt.Compound p)) return null;
                parts.add(p);
            }
        } else {
            parts.add(c);
        }
        StringBuilder out = new StringBuilder();
        boolean styled = false;
        for (Nbt.Compound p : parts) {
            if (!(p.get("text") instanceof Nbt.Text t)) return null;
            String color = p.get("color") instanceof Nbt.Text col ? col.value() : null;
            if (parts.size() == 1 && lore && "gray".equals(color)) color = null;
            StringBuilder codes = new StringBuilder();
            if (color != null) {
                int i = COLORS.indexOf(color);
                if (i >= 0) codes.append('&').append(CODES.charAt(i));
                else if (hex(color) != null) codes.append("&#").append(color.substring(1));
                else return null;
            } else if (styled) {
                codes.append("&r");
            }
            boolean decorated = false;
            for (int d = 0; d < DECOS.size(); d++) {
                if (p.get(DECOS.get(d)) instanceof Nbt.Num n && n.bits() == 1) {
                    codes.append('&').append(DECO_CODES.charAt(d));
                    decorated = true;
                }
            }
            styled = color != null || decorated;
            out.append(codes).append(t.value());
        }
        String candidate = out.toString();
        try {
            return Nbt.same(component(candidate, "legacy", lore), tag) ? candidate : null;
        } catch (Problem e) {
            return null;
        }
    }

    private Styled() {}
}
