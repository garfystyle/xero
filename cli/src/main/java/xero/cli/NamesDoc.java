package xero.cli;

import xero.data.Category;
import xero.data.Data;
import xero.data.Names;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class NamesDoc {
    static String render(Data data, Names names) {
        StringBuilder md = new StringBuilder();
        md.append("# Имена Xero\n\n");
        md.append("Файл собран командой `./gradlew :cli:names` из данных сервера от ")
                .append(data.meta().updated()).append(". Руками не править.\n\n");
        md.append("## Правило\n\n");
        md.append("Имя действия — id сервера без приставки категории: `player_send_message` → ")
                .append("`send_message`. Полная форма — `категория.имя` (`player.send_message`) — ")
                .append("работает всегда. Короткая — только если имя одно на все категории и не ")
                .append("совпадает со словом языка. Условия живут отдельно: их пишут после `if`.\n\n")
                .append("У ").append(names.entries().stream().filter(e -> e.action().alias()).count())
                .append(" записей в данных есть запасной id — старый, который сервер ещё понимает. Его имя ")
                .append("ведёт ровно на него (так код из мира собирается обратно точно), а компилятор ")
                .append("предупреждает, какой id сервер ставит сам из палитры. Если имя у запасного то же, ")
                .append("что у живого, побеждает живой.\n\n");

        md.append("## Сводка\n\n| | всего | пишутся коротко | только полностью | совпадений имён |\n")
                .append("|---|---|---|---|---|\n");
        for (Names.Space space : Names.Space.values()) {
            long all = names.entries().stream().filter(e -> e.space() == space).count();
            long bare = names.entries().stream().filter(e -> e.space() == space && e.bare()).count();
            md.append("| ").append(title(space)).append(" | ").append(all).append(" | ").append(bare)
                    .append(" | ").append(all - bare).append(" | ")
                    .append(names.collisions(space).size()).append(" |\n");
        }
        md.append("| События | ").append(data.events().size())
                .append(" | все: имя = id | | |\n");
        md.append("| Игровые значения | ").append(data.gameValues().size())
                .append(" | все: `value.id` | | |\n\n");

        if (!names.duplicates().isEmpty()) {
            md.append("**Неразличимые полные имена:** ").append(String.join(", ", names.duplicates()))
                    .append("\n\n");
        }

        md.append("## Совпадения имён\n\nЭти имена есть в нескольких категориях, поэтому категория ")
                .append("пишется всегда.\n\n");
        for (Names.Space space : Names.Space.values()) {
            Map<String, List<Names.Entry>> collisions = names.collisions(space);
            if (collisions.isEmpty()) continue;
            md.append("### ").append(title(space)).append("\n\n| имя | как писать | id |\n|---|---|---|\n");
            collisions.keySet().stream().sorted().forEach(name -> {
                List<String> writes = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                for (Names.Entry e : collisions.get(name)) {
                    writes.add("`" + e.qualified() + "`");
                    ids.add("`" + e.action().id() + "`");
                }
                md.append("| `").append(name).append("` | ").append(String.join(", ", writes))
                        .append(" | ").append(String.join(", ", ids)).append(" |\n");
            });
            md.append('\n');
        }

        md.append("## Совпадают со словами языка\n\n| id | как писать |\n|---|---|\n");
        names.entries().stream()
                .filter(e -> Names.KEYWORD.equals(e.reason()))
                .sorted(Comparator.comparing(Names.Entry::qualified))
                .forEach(e -> md.append("| `").append(e.action().id()).append("` | `")
                        .append(e.qualified()).append("` |\n"));
        md.append('\n');

        md.append("## Цели\n\nПишутся через `@`: `@killer send_message(\"…\")`.\n\n");
        for (String family : names.selectorFamilies()) {
            md.append("**").append(familyTitle(family)).append(":** ");
            List<String> parts = new ArrayList<>();
            names.selectors(family).forEach((name, id) ->
                    parts.add(name.equals(id) ? "`@" + name + "`" : "`@" + name + "` (`" + id + "`)"));
            md.append(String.join(", ", parts)).append("\n\n");
        }

        md.append("## Все действия и условия\n\nЖирным — то, что пишется коротко.\n");
        for (Category category : Category.values()) {
            List<Names.Entry> list = names.entries().stream()
                    .filter(e -> e.action().category() == category)
                    .sorted(Comparator.comparing(Names.Entry::name))
                    .toList();
            if (list.isEmpty()) continue;
            md.append("\n### ").append(category.russian).append(" (").append(list.size())
                    .append(")\n\n| Xero | id | в игре |\n|---|---|---|\n");
            for (Names.Entry e : list) {
                String write = e.bare() ? "**`" + e.name() + "`**" : "`" + e.qualified() + "`";
                String id = "`" + e.action().id() + "`";
                if (e.action().alias()) id += " — запасной, сервер ставит `" + e.live() + "`";
                md.append("| ").append(write).append(" | ").append(id).append(" | ")
                        .append(e.action().label().replace("|", "\\|")).append(" |\n");
            }
        }
        return md.toString();
    }

    private static String title(Names.Space space) {
        return space == Names.Space.ACTION ? "Действия" : "Условия";
    }

    private static String familyTitle(String family) {
        return switch (family) {
            case "p" -> "Игрок";
            case "e" -> "Сущность";
            case "value" -> "У игровых значений";
            default -> family;
        };
    }

    private NamesDoc() {}
}
