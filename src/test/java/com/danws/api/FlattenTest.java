package com.danws.api;

import com.danws.protocol.DanWSException;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FlattenTest {

    @Test
    void simpleObject() {
        Map<String, Object> result = Flatten.flatten("user", Map.of("name", "Alice", "age", 30));
        assertEquals("Alice", result.get("user.name"));
        assertEquals(30, result.get("user.age"));
        assertEquals(2, result.size());
    }

    @Test
    void nestedObject() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("host", "localhost");
        input.put("port", 5432);
        Map<String, Object> result = Flatten.flatten("db", Map.of("config", input));
        assertEquals("localhost", result.get("db.config.host"));
        assertEquals(5432, result.get("db.config.port"));
    }

    @Test
    void simpleArray() {
        Map<String, Object> result = Flatten.flatten("scores", List.of(10, 20, 30));
        assertEquals(3, result.get("scores.length"));
        assertEquals(10, result.get("scores.0"));
        assertEquals(20, result.get("scores.1"));
        assertEquals(30, result.get("scores.2"));
    }

    @Test
    void arrayOfObjects() {
        List<Map<String, Object>> items = List.of(
            Map.of("id", 1, "name", "A"),
            Map.of("id", 2, "name", "B")
        );
        Map<String, Object> result = Flatten.flatten("items", items);
        assertEquals(2, result.get("items.length"));
        assertEquals(1, result.get("items.0.id"));
        assertEquals("A", result.get("items.0.name"));
        assertEquals(2, result.get("items.1.id"));
        assertEquals("B", result.get("items.1.name"));
    }

    @Test
    void nestedArrayInObject() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tags", List.of("a", "b"));
        input.put("count", 2);
        Map<String, Object> result = Flatten.flatten("data", input);
        assertEquals(2, result.get("data.tags.length"));
        assertEquals("a", result.get("data.tags.0"));
        assertEquals("b", result.get("data.tags.1"));
        assertEquals(2, result.get("data.count"));
    }

    @Test
    void emptyObject() {
        Map<String, Object> result = Flatten.flatten("empty", Map.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void emptyArray() {
        Map<String, Object> result = Flatten.flatten("empty", List.of());
        assertEquals(1, result.size());
        assertEquals(0, result.get("empty.length"));
    }

    @Test
    void depthLimitExceeded() {
        Object nested = Map.of("x", 1);
        for (int i = 0; i < 11; i++) {
            nested = Map.of("n", nested);
        }
        Object finalNested = nested;
        DanWSException ex = assertThrows(DanWSException.class, () -> Flatten.flatten("deep", finalNested));
        assertTrue(ex.getMessage().toLowerCase().contains("depth"));
    }

    @Test
    void circularReferenceMap() {
        Map<String, Object> a = new HashMap<>();
        Map<String, Object> b = new HashMap<>();
        a.put("b", b);
        b.put("a", a);
        DanWSException ex = assertThrows(DanWSException.class, () -> Flatten.flatten("circ", a));
        assertTrue(ex.getMessage().toLowerCase().contains("circular"));
    }

    @Test
    void circularReferenceList() {
        List<Object> arr = new ArrayList<>();
        arr.add(1);
        arr.add(arr); // self-reference
        DanWSException ex = assertThrows(DanWSException.class, () -> Flatten.flatten("circ", arr));
        assertTrue(ex.getMessage().toLowerCase().contains("circular"));
    }

    @Test
    void shouldFlattenDetection() {
        assertTrue(Flatten.shouldFlatten(Map.of("a", 1)));
        assertTrue(Flatten.shouldFlatten(List.of(1, 2)));
        assertFalse(Flatten.shouldFlatten("hello"));
        assertFalse(Flatten.shouldFlatten(42));
        assertFalse(Flatten.shouldFlatten(true));
        assertFalse(Flatten.shouldFlatten(null));
        assertFalse(Flatten.shouldFlatten(new java.util.Date()));
    }

    @Test
    void mixedTypesInObject() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("s", "hello");
        input.put("n", 42.0);
        input.put("b", true);
        input.put("nil", null);
        Map<String, Object> result = Flatten.flatten("m", input);
        assertEquals("hello", result.get("m.s"));
        assertEquals(42.0, result.get("m.n"));
        assertEquals(true, result.get("m.b"));
        assertNull(result.get("m.nil"));
        assertTrue(result.containsKey("m.nil"));
    }

    @Test
    void deeplyNestedValid() {
        // depth 8 should work (under limit of 10)
        Object nested = Map.of("leaf", "deep");
        for (int i = 0; i < 7; i++) {
            nested = Map.of("level", nested);
        }
        Map<String, Object> result = Flatten.flatten("nest", nested);
        assertEquals("deep", result.get("nest.level.level.level.level.level.level.level.leaf"));
    }

    @Test
    void largeFlatObject() {
        Map<String, Object> big = new LinkedHashMap<>();
        for (int i = 0; i < 60; i++) {
            big.put("field" + i, (double) i);
        }
        Map<String, Object> result = Flatten.flatten("big", big);
        assertEquals(60, result.size());
        assertEquals(0.0, result.get("big.field0"));
        assertEquals(59.0, result.get("big.field59"));
    }

    @Test
    void largeArray() {
        List<Object> arr = new ArrayList<>();
        for (int i = 0; i < 100; i++) arr.add(i * 10);
        Map<String, Object> result = Flatten.flatten("nums", arr);
        assertEquals(101, result.size()); // 100 items + length
        assertEquals(100, result.get("nums.length"));
        assertEquals(0, result.get("nums.0"));
        assertEquals(990, result.get("nums.99"));
    }

    @Test
    void primitiveNotFlattened() {
        assertFalse(Flatten.shouldFlatten("hello"));
        assertFalse(Flatten.shouldFlatten(42));
        assertFalse(Flatten.shouldFlatten(3.14));
        assertFalse(Flatten.shouldFlatten(true));
    }

    @Test
    void complexMixed() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> users = new ArrayList<>();
        Map<String, Object> u1 = new LinkedHashMap<>();
        u1.put("name", "Alice");
        u1.put("scores", List.of(100, 200));
        users.add(u1);
        Map<String, Object> u2 = new LinkedHashMap<>();
        u2.put("name", "Bob");
        u2.put("scores", List.of(50));
        users.add(u2);
        data.put("users", users);
        data.put("meta", Map.of("total", 2));

        Map<String, Object> result = Flatten.flatten("data", data);
        assertEquals(2, result.get("data.users.length"));
        assertEquals("Alice", result.get("data.users.0.name"));
        assertEquals(2, result.get("data.users.0.scores.length"));
        assertEquals(100, result.get("data.users.0.scores.0"));
        assertEquals(200, result.get("data.users.0.scores.1"));
        assertEquals("Bob", result.get("data.users.1.name"));
        assertEquals(1, result.get("data.users.1.scores.length"));
        assertEquals(50, result.get("data.users.1.scores.0"));
        assertEquals(2, result.get("data.meta.total"));
    }
}
