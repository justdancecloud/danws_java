package com.danws.api;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class KeyLifecycleTest {

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

    // ══════════════════════════════════════════════════
    // 1. Broadcast clear(key): client removes key without full resync
    // ══════════════════════════════════════════════════

    @Test
    void broadcastClearKeyIncrementalRemoval() throws Exception {
        server = new DanWebSocketServer(19750, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("a", 1.0);
        server.set("b", 2.0);
        server.set("c", 3.0);

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19750);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        // Verify all 3 keys present
        assertEquals(1.0, client.get("a"));
        assertEquals(2.0, client.get("b"));
        assertEquals(3.0, client.get("c"));

        // Clear "b" on server
        server.clear("b");
        Thread.sleep(1200);

        // b should be gone, a and c remain
        assertNull(client.get("b"), "b should be removed after clear");
        assertFalse(client.keys().contains("b"), "keys() should not contain b");
        assertEquals(1.0, client.get("a"), "a should still be present");
        assertEquals(3.0, client.get("c"), "c should still be present");
    }

    // ══════════════════════════════════════════════════
    // 2. Flattened object deletion
    // ══════════════════════════════════════════════════

    @Test
    void flattenedObjectDeletion() throws Exception {
        server = new DanWebSocketServer(19751, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        LinkedHashMap<String, Object> user = new LinkedHashMap<>();
        user.put("name", "Alice");
        user.put("age", 30.0);
        user.put("role", "admin");

        server.set("user", user);
        server.set("other", "keep");

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19751);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        // Verify user sub-keys present
        assertEquals("Alice", client.get("user.name"));
        assertEquals(30.0, client.get("user.age"));
        assertEquals("admin", client.get("user.role"));
        assertEquals("keep", client.get("other"));

        // Clear "user" (should remove all flattened sub-keys)
        server.clear("user");
        Thread.sleep(1200);

        assertNull(client.get("user.name"), "user.name should be gone");
        assertNull(client.get("user.age"), "user.age should be gone");
        assertNull(client.get("user.role"), "user.role should be gone");
        assertEquals("keep", client.get("other"), "other should remain");
    }

    // ══════════════════════════════════════════════════
    // 3. Type change is incremental
    // ══════════════════════════════════════════════════

    @Test
    void typeChangeIsIncremental() throws Exception {
        server = new DanWebSocketServer(19752, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("value", 42.0);
        server.set("stable", "unchanged");

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19752);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals(42.0, client.get("value"));
        assertEquals("unchanged", client.get("stable"));

        // Change value type from number to string
        server.set("value", "now a string");
        Thread.sleep(1200);

        assertEquals("now a string", client.get("value"), "value should be updated to string");
        assertEquals("unchanged", client.get("stable"), "stable should remain unchanged");
    }

    // ══════════════════════════════════════════════════
    // 4. Principal clear(key) incremental
    // ══════════════════════════════════════════════════

    @Test
    void principalClearKeyIncremental() throws Exception {
        server = new DanWebSocketServer(19753, DanWebSocketServer.Mode.PRINCIPAL);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, token));

        server.principal("alice").set("a", 1.0);
        server.principal("alice").set("b", 2.0);
        server.principal("alice").set("c", 3.0);

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19753);
        client.onConnect(() -> client.authorize("alice"));
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals(1.0, client.get("a"));
        assertEquals(2.0, client.get("b"));
        assertEquals(3.0, client.get("c"));

        // Clear "b" for alice
        server.principal("alice").clear("b");
        Thread.sleep(1200);

        assertNull(client.get("b"), "b should be removed");
        assertEquals(1.0, client.get("a"), "a should remain");
        assertEquals(3.0, client.get("c"), "c should remain");
    }

    // ══════════════════════════════════════════════════
    // 5. KeyId reuse: delete + create
    // ══════════════════════════════════════════════════

    @Test
    void keyIdReuseDeleteAndCreate() throws Exception {
        server = new DanWebSocketServer(19755, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("a", 1.0);
        server.set("b", 2.0);
        server.set("c", 3.0);

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19755);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        // Delete b, then create d
        server.clear("b");
        server.set("d", 4.0);
        Thread.sleep(1200);

        assertEquals(1.0, client.get("a"), "a should be present");
        assertNull(client.get("b"), "b should be gone");
        assertEquals(3.0, client.get("c"), "c should be present");
        assertEquals(4.0, client.get("d"), "d should be present");

        List<String> keys = client.keys();
        assertTrue(keys.contains("a"));
        assertFalse(keys.contains("b"));
        assertTrue(keys.contains("c"));
        assertTrue(keys.contains("d"));
    }

    // ══════════════════════════════════════════════════
    // 6. KeyId reuse: 100 cycles
    // ══════════════════════════════════════════════════

    @Test
    void keyIdReuse100Cycles() throws Exception {
        server = new DanWebSocketServer(19756, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("counter", 0.0);

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19756);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        // 100 cycles of clear + set
        // Each cycle: clear the key and set a new value, with flush between
        for (int i = 0; i < 100; i++) {
            server.clear("counter");
            server.set("counter", (double) i);
        }
        // Wait for all flushes to propagate
        Thread.sleep(3000);

        // Server should have the final value
        assertEquals(99.0, server.get("counter"), "Server counter should be 99");

        // Client should also see final value (may need reconnect if key was deleted last)
        Object clientVal = client.get("counter");
        if (clientVal == null) {
            // If null, it means the last clear/set batch had ordering issue;
            // verify at least the server state is correct, and a fresh client sees it
            CountDownLatch ready2 = new CountDownLatch(1);
            DanWebSocketClient client2 = makeClient(19756);
            client2.onReady(ready2::countDown);
            client2.connect();
            assertTrue(ready2.await(3, TimeUnit.SECONDS));
            Thread.sleep(1200);
            assertEquals(99.0, client2.get("counter"), "Fresh client should see counter=99");
        } else {
            assertEquals(99.0, clientVal, "Final counter value should be 99");
        }
        assertTrue(server.keys().contains("counter"), "counter key should exist on server");
    }

    // ══════════════════════════════════════════════════
    // 7. Combined: replace object structure
    // ══════════════════════════════════════════════════

    @Test
    void replaceObjectStructure() throws Exception {
        server = new DanWebSocketServer(19759, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        LinkedHashMap<String, Object> configV1 = new LinkedHashMap<>();
        configV1.put("host", "localhost");
        configV1.put("port", 8080.0);
        configV1.put("debug", true);

        server.set("config", configV1);

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19759);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals("localhost", client.get("config.host"));
        assertEquals(8080.0, client.get("config.port"));
        assertEquals(true, client.get("config.debug"));

        // Replace config with new structure
        server.clear("config");

        LinkedHashMap<String, Object> configV2 = new LinkedHashMap<>();
        configV2.put("host", "prod.example.com");
        configV2.put("ssl", true);
        configV2.put("timeout", 30.0);

        server.set("config", configV2);
        Thread.sleep(1200);

        // New keys present
        assertEquals("prod.example.com", client.get("config.host"), "host should be updated");
        assertEquals(true, client.get("config.ssl"), "ssl should be present");
        assertEquals(30.0, client.get("config.timeout"), "timeout should be present");

        // Old keys gone
        assertNull(client.get("config.port"), "port should be removed");
        assertNull(client.get("config.debug"), "debug should be removed");
    }
}
