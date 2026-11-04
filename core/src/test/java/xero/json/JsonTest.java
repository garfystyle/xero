package xero.json;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {
    @Test
    void roundTripKeepsOrderAndNumbers() {
        String text = "{\"b\":1,\"a\":[1.50,-2e3,true,null,\"x\"],\"c\":{}}";
        assertEquals(text, Json.write(Json.parse(text)));
    }

    @Test
    void numbersStayExact() {
        Map<String, Object> o = Json.object(Json.parse("{\"n\":1.0,\"m\":\"1\"}"));
        assertEquals(new Json.Num("1.0"), o.get("n"));
        assertEquals("1", o.get("m"));
    }

    @Test
    void stringsEscapeBothWays() {
        String s = "кавычка \" слэш \\ строка\nтаб\t \u0001 ⛏";
        Object back = Json.parse(Json.write(s));
        assertEquals(s, back);
        assertEquals("ё", Json.parse("\"\\u0451\""));
    }

    @Test
    void prettyParsesBack() {
        Object value = Json.parse("{\"handlers\":[{\"type\":\"event\",\"operations\":[]}]}");
        assertEquals(value, Json.parse(Json.pretty(value)));
        assertTrue(Json.pretty(value).contains("\n"));
    }

    @Test
    void errorsPointAtPlace() {
        Json.Error e = assertThrows(Json.Error.class, () -> Json.parse("{\"a\":1,}"));
        assertEquals(7, e.position);
        assertThrows(Json.Error.class, () -> Json.parse("[1 2]"));
        assertThrows(Json.Error.class, () -> Json.parse("\"открыта"));
        assertThrows(Json.Error.class, () -> Json.parse("{} {}"));
    }

    @Test
    void emptyContainers() {
        assertEquals(List.of(), Json.parse("[ ]"));
        assertEquals(Map.of(), Json.parse("{ }"));
    }
}
