package xero.data;

import org.junit.jupiter.api.Test;
import xero.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataTest {
    private static final Data DATA = Data.load();

    @Test
    void everyActionHasKnownCategory() {
        assertEquals(List.of(), DATA.unknownCategories());
    }

    @Test
    void countsMatchMeta() {
        assertEquals(DATA.meta().actions(), DATA.actions().size());
        assertEquals(DATA.meta().events(), DATA.events().size());
        assertTrue(DATA.gameValues().size() > 200);
    }

    @Test
    void sourceMatchesMeta() throws IOException {
        try (InputStream in = Data.class.getResourceAsStream("/xero/data/SOURCE.json")) {
            assertNotNull(in, "нет SOURCE.json — запусти ./gradlew syncData");
            Map<String, Object> source = Json.object(
                    Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            assertEquals(DATA.meta().updated(), source.get("updated"));
        }
    }

    @Test
    void customCraftingRecipe() {
        Data.Action a = DATA.actions().get("game_add_custom_crafting_recipe");
        assertNotNull(a);
        assertEquals(Category.GAME, a.category());
        assertEquals("Добавить рецепт крафта", a.label());
        assertEquals(List.of("name", "result", "items"), a.args().stream().map(Data.Arg::id).toList());
        assertEquals(List.of("Текст", "Предмет", "Предмет"), a.args().stream().map(Data.Arg::kind).toList());
        assertTrue(a.arg("items").slotted());
        assertEquals(9, a.arg("items").capacity());
        assertTrue(DATA.actions().get("player_set_items").arg("items").slotted());
        assertFalse(DATA.actions().get("player_give_items").arg("items").slotted());
        assertTrue(a.settings().stream().anyMatch(s -> s.id().equals("recipe_type")
                && s.options().containsKey("shaped") && s.options().containsKey("shapeless")));
    }

    @Test
    void pluralAndFamily() {
        Data.Action message = DATA.actions().get("player_send_message");
        assertTrue(message.plural().contains("messages"));
        assertEquals("p", message.family());
        assertEquals("e", DATA.actions().get("entity_teleport").family());
    }

    @Test
    void selectorsLoaded() {
        assertEquals(8, DATA.selectors("p").size());
        assertEquals(11, DATA.selectors("e").size());
        assertTrue(DATA.valueSelectors().size() > 0);
    }
}
