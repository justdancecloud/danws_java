package com.danws.api;

import com.danws.protocol.DanWSException;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ServerOptionsTest {

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

    // ════════════════════════════════
    // Session TTL
    // ════════════════════════════════

    @Test
    void sessionTTLExpiry() throws Exception {
        server = new DanWebSocketServer(19500, "/", DanWebSocketServer.Mode.BROADCAST, 200);
        Thread.sleep(100);
        server.set("x", 1.0);

        String[] sessionId = {""};
        server.onConnection(s -> sessionId[0] = s.id());

        List<String> expired = new CopyOnWriteArrayList<>();
        server.onSessionExpired(s -> expired.add(s.id()));

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient c = makeClient(19500);
        c.onReady(ready::countDown);
        c.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));

        c.disconnect();
        Thread.sleep(500); // TTL=200ms

        assertTrue(expired.contains(sessionId[0]));
    }

    // ════════════════════════════════
    // Auth
    // ════════════════════════════════

    @Test
    void authRejectFlow() throws Exception {
        server = new DanWebSocketServer(19501, DanWebSocketServer.Mode.PRINCIPAL);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.reject(uuid, "no access"));

        CountDownLatch done = new CountDownLatch(1);
        List<String> errors = new CopyOnWriteArrayList<>();

        DanWebSocketClient c = makeClient(19501);
        c.onConnect(() -> c.authorize("bad-token"));
        c.onError(err -> { errors.add(err.code()); done.countDown(); });
        c.connect();

        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertTrue(errors.contains("AUTH_REJECTED"));
    }

    @Test
    void authAcceptAndPrincipalBinding() throws Exception {
        server = new DanWebSocketServer(19502, DanWebSocketServer.Mode.PRINCIPAL);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, token));

        server.principal("alice").set("score", 100.0);
        server.principal("bob").set("score", 50.0);

        CountDownLatch rA = new CountDownLatch(1), rB = new CountDownLatch(1);

        DanWebSocketClient cA = makeClient(19502);
        cA.onConnect(() -> cA.authorize("alice"));
        cA.onReady(rA::countDown);
        cA.connect();

        DanWebSocketClient cB = makeClient(19502);
        cB.onConnect(() -> cB.authorize("bob"));
        cB.onReady(rB::countDown);
        cB.connect();

        assertTrue(rA.await(3, TimeUnit.SECONDS));
        assertTrue(rB.await(3, TimeUnit.SECONDS));
        Thread.sleep(300);

        assertEquals(100.0, cA.get("score"));
        assertEquals(50.0, cB.get("score"));
    }

    // ════════════════════════════════
    // Multi-client broadcast
    // ════════════════════════════════

    @Test
    void fiveClientsBroadcastSameData() throws Exception {
        server = new DanWebSocketServer(19503, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("shared", Map.of("count", 42.0, "active", true));

        CountDownLatch allReady = new CountDownLatch(5);
        List<DanWebSocketClient> cs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            DanWebSocketClient c = makeClient(19503);
            cs.add(c);
            c.onReady(allReady::countDown);
            c.connect();
        }

        assertTrue(allReady.await(5, TimeUnit.SECONDS));
        Thread.sleep(300);

        for (DanWebSocketClient c : cs) {
            assertEquals(42.0, c.get("shared.count"));
            assertEquals(true, c.get("shared.active"));
        }
    }

    // ════════════════════════════════
    // Principal data persistence after TTL
    // ════════════════════════════════

    @Test
    void principalDataSurvivesAfterLastSessionDisconnect() throws Exception {
        server = new DanWebSocketServer(19504, "/", DanWebSocketServer.Mode.PRINCIPAL, 200);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, token));

        server.principal("alice").set("score", 100.0);

        // Connect and verify
        CountDownLatch r1 = new CountDownLatch(1);
        DanWebSocketClient c1 = makeClient(19504);
        c1.onConnect(() -> c1.authorize("alice"));
        c1.onReady(r1::countDown);
        c1.connect();
        assertTrue(r1.await(3, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertEquals(100.0, c1.get("score"));
        c1.disconnect();

        // Wait past TTL
        Thread.sleep(500);

        // Data should still be in principal store (v0.4.0 fix)
        assertEquals(100.0, server.principal("alice").get("score"));

        // Reconnect with new client
        CountDownLatch r2 = new CountDownLatch(1);
        DanWebSocketClient c2 = makeClient(19504);
        c2.onConnect(() -> c2.authorize("alice"));
        c2.onReady(r2::countDown);
        c2.connect();
        assertTrue(r2.await(3, TimeUnit.SECONDS));
        Thread.sleep(200);

        assertEquals(100.0, c2.get("score"));
    }

    // ════════════════════════════════
    // 4-byte KeyId range
    // ════════════════════════════════

    @Test
    void manyKeysNoCollision() throws Exception {
        server = new DanWebSocketServer(19505, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        // Set many objects to generate lots of keyIds
        for (int i = 0; i < 100; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", (double) i);
            m.put("label", "item-" + i);
            server.set("item" + i, m);
        }

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient c = makeClient(19505);
        c.onReady(ready::countDown);
        c.connect();
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        assertEquals(0.0, c.get("item0.value"));
        assertEquals(50.0, c.get("item50.value"));
        assertEquals("item-99", c.get("item99.label"));
    }

    // ════════════════════════════════
    // Server close
    // ════════════════════════════════

    @Test
    void serverCloseDisconnectsClients() throws Exception {
        server = new DanWebSocketServer(19506, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);
        server.set("x", 1.0);

        CountDownLatch ready = new CountDownLatch(1);
        AtomicBoolean disconnected = new AtomicBoolean(false);
        DanWebSocketClient c = makeClient(19506);
        c.onReady(ready::countDown);
        c.onDisconnect(() -> disconnected.set(true));
        c.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));

        server.close();
        server = null;
        Thread.sleep(500);

        assertTrue(disconnected.get());
    }

    // ════════════════════════════════
    // Session management
    // ════════════════════════════════

    @Test
    void getSessionAndGetSessionsByPrincipal() throws Exception {
        server = new DanWebSocketServer(19507, DanWebSocketServer.Mode.PRINCIPAL);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, "alice"));
        server.principal("alice").set("x", true);

        String[] uuid = {""};
        server.onConnection(s -> uuid[0] = s.id());

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient c = makeClient(19507);
        c.onConnect(() -> c.authorize("token"));
        c.onReady(ready::countDown);
        c.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));

        assertTrue(server.isConnected(uuid[0]));
        assertNotNull(server.getSession(uuid[0]));
        assertEquals("alice", server.getSession(uuid[0]).principal());
        assertEquals(1, server.getSessionsByPrincipal("alice").size());

        c.disconnect();
        Thread.sleep(300);
        assertFalse(server.isConnected(uuid[0]));
    }

    // ════════════════════════════════
    // Broadcast live updates
    // ════════════════════════════════

    @Test
    void broadcastLiveUpdateReceived() throws Exception {
        server = new DanWebSocketServer(19508, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);
        server.set("score", 0.0);

        CountDownLatch ready = new CountDownLatch(1);
        List<Object> scores = new CopyOnWriteArrayList<>();
        DanWebSocketClient c = makeClient(19508);
        c.onReady(ready::countDown);
        c.onReceive((key, val) -> { if ("score".equals(key)) scores.add(val); });
        c.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(200);

        server.set("score", 100.0);
        Thread.sleep(300);

        assertTrue(scores.contains(100.0));
    }

    // ════════════════════════════════
    // Mode guards
    // ════════════════════════════════

    @Test
    void broadcastRejectsPrincipal() {
        server = new DanWebSocketServer(19509, DanWebSocketServer.Mode.BROADCAST);
        assertThrows(DanWSException.class, () -> server.principal("alice"));
    }

    @Test
    void principalRejectsServerSet() {
        server = new DanWebSocketServer(19510, DanWebSocketServer.Mode.PRINCIPAL);
        assertThrows(DanWSException.class, () -> server.set("key", "val"));
    }

    // ════════════════════════════════
    // Rapid updates dedup
    // ════════════════════════════════

    @Test
    void rapidObjectUpdates() throws Exception {
        server = new DanWebSocketServer(19511, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);
        server.set("counter", Map.of("value", 0.0));

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient c = makeClient(19511);
        c.onReady(ready::countDown);
        c.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(100);

        for (int i = 1; i <= 20; i++) {
            server.set("counter", Map.of("value", (double) i));
        }
        Thread.sleep(500);

        assertEquals(20.0, c.get("counter.value"));
    }

    // ════════════════════════════════
    // Incremental key registration
    // ════════════════════════════════

    @Test
    void newKeyDoesNotResetExistingValues() throws Exception {
        server = new DanWebSocketServer(19512, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("a", 1.0);

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient c = makeClient(19512);
        c.onReady(ready::countDown);
        c.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(200);

        assertEquals(1.0, c.get("a"));

        // Add new key — should use incremental, not reset
        server.set("b", 2.0);
        Thread.sleep(300);

        assertEquals(1.0, c.get("a")); // still present
        assertEquals(2.0, c.get("b")); // new key received
    }
}
