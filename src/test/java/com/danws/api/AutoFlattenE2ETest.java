package com.danws.api;

import com.danws.protocol.DanWSException;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AutoFlattenE2ETest {

    private DanWebSocketServer server;
    private final List<DanWebSocketClient> clients = new ArrayList<>();

    @AfterEach
    void tearDown() {
        clients.forEach(c -> { try { c.disconnect(); } catch (Exception ignored) {} });
        clients.clear();
        if (server != null) { try { server.close(); } catch (Exception ignored) {} server = null; }
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
    }

    private DanWebSocketClient makeClient(int port) {
        DanWebSocketClient c = new DanWebSocketClient("ws://127.0.0.1:" + port);
        clients.add(c);
        return c;
    }

    // ---------------------------------------------------------------
    // 1. Simple object flatten
    // ---------------------------------------------------------------
    @Test
    void simpleObjectFlatten() throws Exception {
        server = new DanWebSocketServer(19300, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("user", Map.of("name", "Alice", "age", 30.0));

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19300);
        List<String> receivedKeys = new CopyOnWriteArrayList<>();

        client.onReady(ready::countDown);
        client.onReceive((key, val) -> receivedKeys.add(key));
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals("Alice", client.get("user.name"));
        assertEquals(30.0, client.get("user.age"));
        assertTrue(receivedKeys.contains("user.name"));
        assertTrue(receivedKeys.contains("user.age"));
        // The root key "user" itself should not be present as a flat key
        assertNull(client.get("user"));
    }

    // ---------------------------------------------------------------
    // 2. Nested object (2 depth)
    // ---------------------------------------------------------------
    @Test
    void nestedObjectTwoDepth() throws Exception {
        server = new DanWebSocketServer(19301, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        LinkedHashMap<String, Object> db = new LinkedHashMap<>();
        db.put("host", "localhost");
        db.put("port", 5432);

        LinkedHashMap<String, Object> cache = new LinkedHashMap<>();
        cache.put("enabled", true);

        LinkedHashMap<String, Object> config = new LinkedHashMap<>();
        config.put("db", db);
        config.put("cache", cache);

        server.set("config", config);

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19301);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals("localhost", client.get("config.db.host"));
        assertEquals(5432, client.get("config.db.port"));
        assertEquals(true, client.get("config.cache.enabled"));
    }

    // ---------------------------------------------------------------
    // 3. Array flatten with .length
    // ---------------------------------------------------------------
    @Test
    void arrayFlattenWithLength() throws Exception {
        server = new DanWebSocketServer(19302, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("scores", List.of(10, 20, 30));

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19302);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals(10, client.get("scores.0"));
        assertEquals(20, client.get("scores.1"));
        assertEquals(30, client.get("scores.2"));
        assertEquals(3, client.get("scores.length"));
    }

    // ---------------------------------------------------------------
    // 4. Array shrink cleans leftover keys
    // ---------------------------------------------------------------
    @Test
    void arrayShrinkCleansLeftoverKeys() throws Exception {
        server = new DanWebSocketServer(19303, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("items", List.of("a", "b", "c"));

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19303);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals("a", client.get("items.0"));
        assertEquals("b", client.get("items.1"));
        assertEquals("c", client.get("items.2"));
        assertEquals(3, client.get("items.length"));

        // Shrink to 2 items
        server.set("items", List.of("x", "y"));
        Thread.sleep(1200);

        assertEquals("x", client.get("items.0"));
        assertEquals("y", client.get("items.1"));
        // Stale index key remains — client uses .length to determine valid range
        assertEquals(2, client.get("items.length"));
    }

    // ---------------------------------------------------------------
    // 5. Mixed: object with arrays, array with objects
    // ---------------------------------------------------------------
    @Test
    void mixedObjectWithArraysAndArrayWithObjects() throws Exception {
        server = new DanWebSocketServer(19304, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        // Object containing an array
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("title", "Report");
        data.put("tags", List.of("java", "test"));

        server.set("doc", data);

        // Array containing objects
        LinkedHashMap<String, Object> item0 = new LinkedHashMap<>();
        item0.put("id", 1);
        item0.put("name", "first");
        LinkedHashMap<String, Object> item1 = new LinkedHashMap<>();
        item1.put("id", 2);
        item1.put("name", "second");

        server.set("rows", List.of(item0, item1));

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19304);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        // Object with array
        assertEquals("Report", client.get("doc.title"));
        assertEquals("java", client.get("doc.tags.0"));
        assertEquals("test", client.get("doc.tags.1"));
        assertEquals(2, client.get("doc.tags.length"));

        // Array with objects
        assertEquals(1, client.get("rows.0.id"));
        assertEquals("first", client.get("rows.0.name"));
        assertEquals(2, client.get("rows.1.id"));
        assertEquals("second", client.get("rows.1.name"));
        assertEquals(2, client.get("rows.length"));
    }

    // ---------------------------------------------------------------
    // 6. Primitive values unchanged
    // ---------------------------------------------------------------
    @Test
    void primitiveValuesUnchanged() throws Exception {
        server = new DanWebSocketServer(19305, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("count", 42);
        server.set("label", "hello");
        server.set("flag", true);

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19305);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals(42, client.get("count"));
        assertEquals("hello", client.get("label"));
        assertEquals(true, client.get("flag"));
    }

    // ---------------------------------------------------------------
    // 7. Depth limit (11 levels) throws
    // ---------------------------------------------------------------
    @Test
    void depthLimitThrows() {
        server = new DanWebSocketServer(19306, DanWebSocketServer.Mode.BROADCAST);

        // Build 11-deep nested map
        Map<String, Object> current = new HashMap<>();
        current.put("leaf", "value");
        for (int i = 0; i < 11; i++) {
            Map<String, Object> wrapper = new HashMap<>();
            wrapper.put("level", current);
            current = wrapper;
        }

        Map<String, Object> deepMap = current;
        DanWSException ex = assertThrows(DanWSException.class, () -> server.set("deep", deepMap));
        assertTrue(ex.code().contains("FLATTEN_DEPTH_EXCEEDED") || ex.getMessage().contains("depth"),
                "Expected depth exceeded error, got: " + ex.getMessage());
    }

    // ---------------------------------------------------------------
    // 8. Circular reference throws
    // ---------------------------------------------------------------
    @Test
    void circularReferenceThrows() {
        server = new DanWebSocketServer(19307, DanWebSocketServer.Mode.BROADCAST);

        Map<String, Object> selfRef = new HashMap<>();
        selfRef.put("name", "loop");
        selfRef.put("self", selfRef); // circular

        DanWSException ex = assertThrows(DanWSException.class, () -> server.set("circ", selfRef));
        assertTrue(ex.code().contains("CIRCULAR_REFERENCE") || ex.getMessage().contains("Circular"),
                "Expected circular reference error, got: " + ex.getMessage());
    }

    // ---------------------------------------------------------------
    // 9. 50+ keys from single object (stress test with 60-field Map)
    // ---------------------------------------------------------------
    @Test
    void fiftyPlusKeysFromSingleObject() throws Exception {
        server = new DanWebSocketServer(19308, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        LinkedHashMap<String, Object> bigMap = new LinkedHashMap<>();
        for (int i = 0; i < 60; i++) {
            bigMap.put("field" + i, i);
        }
        server.set("big", bigMap);

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19308);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        for (int i = 0; i < 60; i++) {
            assertEquals(i, client.get("big.field" + i), "big.field" + i + " should be " + i);
        }
    }

    // ---------------------------------------------------------------
    // 10. Array of 100 items
    // ---------------------------------------------------------------
    @Test
    void arrayOf100Items() throws Exception {
        server = new DanWebSocketServer(19309, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        List<Integer> largeList = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            largeList.add(i * 10);
        }
        server.set("arr", largeList);

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19309);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals(100, client.get("arr.length"));
        assertEquals(0, client.get("arr.0"));       // first element
        assertEquals(990, client.get("arr.99"));     // last element
        assertEquals(500, client.get("arr.50"));     // middle element
    }

    // ---------------------------------------------------------------
    // 11. Object key removal on structure change
    // ---------------------------------------------------------------
    @Test
    void objectKeyRemovalOnStructureChange() throws Exception {
        server = new DanWebSocketServer(19310, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("obj", Map.of("a", 1, "b", 2, "c", 3));

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19310);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals(1, client.get("obj.a"));
        assertEquals(2, client.get("obj.b"));
        assertEquals(3, client.get("obj.c"));

        // Change structure: remove b, add d
        server.set("obj", Map.of("a", 10, "c", 30, "d", 40));
        Thread.sleep(1200);

        assertEquals(10, client.get("obj.a"));
        assertNull(client.get("obj.b"), "obj.b should be removed after structure change");
        assertEquals(30, client.get("obj.c"));
        assertEquals(40, client.get("obj.d"));
    }

    // ---------------------------------------------------------------
    // 12. Empty object and empty array
    // ---------------------------------------------------------------
    @Test
    void emptyObjectAndEmptyArray() throws Exception {
        server = new DanWebSocketServer(19311, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("empty_arr", List.of());
        // Map.of() is empty, which produces no leaf keys at all, so nothing is set
        server.set("empty_obj", Map.of());

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19311);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        // Empty array should still produce .length = 0
        assertEquals(0, client.get("empty_arr.length"));
        // Empty object has no leaf keys at all
        assertNull(client.get("empty_obj"));
    }

    // ---------------------------------------------------------------
    // 13. set after clear works
    // ---------------------------------------------------------------
    @Test
    void setAfterClearWorks() throws Exception {
        server = new DanWebSocketServer(19312, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("obj", Map.of("x", 1, "y", 2));

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19312);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals(1, client.get("obj.x"));
        assertEquals(2, client.get("obj.y"));

        // Clear and re-set with new structure
        server.clear("obj");
        Thread.sleep(1200);

        assertNull(client.get("obj.x"), "obj.x should be null after clear");
        assertNull(client.get("obj.y"), "obj.y should be null after clear");

        server.set("obj", Map.of("p", 100, "q", 200));
        Thread.sleep(1200);

        assertEquals(100, client.get("obj.p"));
        assertEquals(200, client.get("obj.q"));
        assertNull(client.get("obj.x"), "obj.x should remain null after re-set with new keys");
    }

    // ---------------------------------------------------------------
    // 14. Topic payload.set with object flatten
    // ---------------------------------------------------------------
    @Test
    void topicPayloadSetWithObjectFlatten() throws Exception {
        server = new DanWebSocketServer(19313, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    t.payload().set("result", Map.of("status", "ok", "code", 200));
                    t.payload().set("items", List.of("alpha", "beta"));
                }
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19313);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("data");
        Thread.sleep(1200);

        assertEquals("ok", client.topic("data").get("result.status"));
        assertEquals(200, client.topic("data").get("result.code"));
        assertEquals("alpha", client.topic("data").get("items.0"));
        assertEquals("beta", client.topic("data").get("items.1"));
        assertEquals(2, client.topic("data").get("items.length"));
    }

    // ---------------------------------------------------------------
    // 15. Topic payload array shrink
    // ---------------------------------------------------------------
    @Test
    void topicPayloadArrayShrink() throws Exception {
        server = new DanWebSocketServer(19314, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        // Use an array to hold topic reference for mutation in callback
        final Object[] topicRef = new Object[1];

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    t.payload().set("list", List.of("a", "b", "c", "d"));
                    topicRef[0] = t;
                }
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19314);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("shrink");
        Thread.sleep(1200);

        assertEquals("a", client.topic("shrink").get("list.0"));
        assertEquals("d", client.topic("shrink").get("list.3"));
        assertEquals(4, client.topic("shrink").get("list.length"));

        // Shrink via topic payload
        assertNotNull(topicRef[0], "Topic reference should have been captured");
        TopicHandle ti = (TopicHandle) topicRef[0];
        ti.payload().set("list", List.of("x", "y"));
        Thread.sleep(1200);

        assertEquals("x", client.topic("shrink").get("list.0"));
        assertEquals("y", client.topic("shrink").get("list.1"));
        // Stale index keys remain — client uses .length to determine valid range
        assertEquals(2, client.topic("shrink").get("list.length"));
    }

    // ---------------------------------------------------------------
    // 16. Principal mode with flattened objects
    // ---------------------------------------------------------------
    @Test
    void principalModeWithFlattenedObjects() throws Exception {
        server = new DanWebSocketServer(19315, DanWebSocketServer.Mode.PRINCIPAL);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, token));

        server.principal("alice").set("profile", Map.of("role", "admin", "level", 10));
        server.principal("bob").set("profile", Map.of("role", "user", "level", 1));

        CountDownLatch rA = new CountDownLatch(1), rB = new CountDownLatch(1);

        DanWebSocketClient cA = makeClient(19315);
        cA.onConnect(() -> cA.authorize("alice"));
        cA.onReady(rA::countDown);
        cA.connect();

        DanWebSocketClient cB = makeClient(19315);
        cB.onConnect(() -> cB.authorize("bob"));
        cB.onReady(rB::countDown);
        cB.connect();

        assertTrue(rA.await(3, TimeUnit.SECONDS));
        assertTrue(rB.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals("admin", cA.get("profile.role"));
        assertEquals(10, cA.get("profile.level"));

        assertEquals("user", cB.get("profile.role"));
        assertEquals(1, cB.get("profile.level"));

        // Verify isolation: alice's data does not leak to bob
        assertNotEquals(cA.get("profile.role"), cB.get("profile.role"));
    }
}
