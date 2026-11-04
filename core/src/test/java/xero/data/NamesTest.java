package xero.data;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamesTest {
    private static final Data DATA = Data.load();
    private static final Names NAMES = Names.build(DATA);

    @Test
    void everyIdHasExactlyOneName() {
        assertEquals(DATA.actions().size(), NAMES.entries().size());
        for (String id : DATA.actions().keySet()) assertNotNull(NAMES.byId(id), id);
    }

    @Test
    void fullNamesAreUnique() {
        assertEquals(List.of(), NAMES.duplicates());
    }

    @Test
    void everyFullNameLeadsToItsIdOrTheLiveOne() {
        for (Names.Entry e : NAMES.entries()) {
            String got = NAMES.resolve(e.space(), e.qualified()).action().id();
            if (!e.action().alias()) assertEquals(e.action().id(), got, e.qualified());
            else assertTrue(got.equals(e.action().id()) || got.equals(e.live()), e.qualified());
        }
    }

    @Test
    void bareNamesLeadToTheirIdsAndAvoidKeywords() {
        for (Names.Entry e : NAMES.entries()) {
            if (!e.bare()) continue;
            String got = NAMES.resolve(e.space(), e.name()).action().id();
            if (!e.action().alias()) assertEquals(e.action().id(), got, e.name());
            assertFalse(Names.RESERVED.contains(e.name()), e.name());
        }
    }

    @Test
    void spareIdsAreWrittenExactly() {
        assertEquals("if_variable_in_range", NAMES.resolve(Names.Space.CONDITION, "in_range").action().id());
        assertEquals("if_variable_number_in_range",
                NAMES.resolve(Names.Space.CONDITION, "number_in_range").action().id());
        assertEquals("entity_clear_potion_effects",
                NAMES.resolve(Names.Space.ACTION, "entity.clear_potion_effects").action().id());
        assertEquals("player_teleport_sequence",
                NAMES.resolve(Names.Space.ACTION, "player.teleport_sequence").action().id());
        assertEquals("player_teleport_sequence", NAMES.byId("entity_teleport_sequence").live());
        assertTrue(NAMES.byId("repeat_sphere").action().alias());
    }

    @Test
    void customCraftIsShort() {
        Names.Entry e = NAMES.resolve(Names.Space.ACTION, "add_custom_crafting_recipe");
        assertNotNull(e);
        assertEquals("game_add_custom_crafting_recipe", e.action().id());
        assertTrue(e.bare());
    }

    @Test
    void sharedNamesNeedCategory() {
        assertNull(NAMES.resolve(Names.Space.ACTION, "teleport"));
        assertEquals("player_teleport", NAMES.resolve(Names.Space.ACTION, "player.teleport").action().id());
        assertEquals("entity_teleport", NAMES.resolve(Names.Space.ACTION, "entity.teleport").action().id());
    }

    @Test
    void keywordNamesNeedCategory() {
        Names.Entry value = NAMES.byId("set_variable_value");
        assertFalse(value.bare());
        assertEquals("var.value", value.qualified());
    }

    @Test
    void conditionsLiveApart() {
        Names.Entry greater = NAMES.resolve(Names.Space.CONDITION, "greater");
        assertNotNull(greater);
        assertEquals("if_variable_greater", greater.action().id());
        assertNull(NAMES.resolve(Names.Space.ACTION, "greater"));
    }

    @Test
    void selectorNamesAreUniqueAndShort() {
        for (String family : NAMES.selectorFamilies()) {
            Map<String, String> map = NAMES.selectors(family);
            Set<String> ids = new HashSet<>(map.values());
            assertEquals(map.size(), ids.size(), family);
        }
        assertEquals("all_players", NAMES.selectors("p").get("all"));
        assertEquals("all_entities", NAMES.selectors("e").get("all"));
        assertEquals("killer_entity", NAMES.selectors("e").get("killer"));
    }
}
