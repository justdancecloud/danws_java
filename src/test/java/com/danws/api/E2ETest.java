package com.danws.api;

import com.danws.protocol.DanWSException;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class E2ETest {

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

    @Test
    void broadcastSetAndReceive() throws Exception {
        server = new DanWebSocketServer(19101, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("greeting", "Hello");
        server.set("count", 42.0);

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19101);
        List<String> receivedKeys = new CopyOnWriteArrayList<>();

        client.onReady(ready::countDown);
        client.onReceive((key, val) -> receivedKeys.add(key));
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals("Hello", client.get("greeting"));
        assertEquals(42.0, client.get("count"));
        assertTrue(receivedKeys.contains("greeting"));
    }

    @Test
    void broadcastUpdate() throws Exception {
        server = new DanWebSocketServer(19102, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("score", 0.0);

        CountDownLatch ready = new CountDownLatch(1);
        List<Object> scores = new CopyOnWriteArrayList<>();
        DanWebSocketClient client = makeClient(19102);

        client.onReady(ready::countDown);
        client.onReceive((key, val) -> { if ("score".equals(key)) scores.add(val); });
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);
        assertTrue(scores.contains(0.0));

        server.set("score", 100.0);
        Thread.sleep(1200);
        assertTrue(scores.contains(100.0));
    }

    @Test
    void broadcastGetKeyClear() {
        server = new DanWebSocketServer(19103, DanWebSocketServer.Mode.BROADCAST);
        server.set("a", 1.0);
        server.set("b", "hello");

        assertEquals(1.0, server.get("a"));
        assertTrue(server.keys().containsAll(List.of("a", "b")));

        server.clear("a");
        assertNull(server.get("a"));

        server.clear();
        assertTrue(server.keys().isEmpty());
    }

    @Test
    void broadcastRejectsPrincipal() {
        server = new DanWebSocketServer(19104, DanWebSocketServer.Mode.BROADCAST);
        assertThrows(DanWSException.class, () -> server.principal("alice"));
    }

    @Test
    void individualPrincipalData() throws Exception {
        server = new DanWebSocketServer(19110, DanWebSocketServer.Mode.INDIVIDUAL);
        Thread.sleep(100);

        server.principal("default").set("greeting", "Hello");

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19110);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals("Hello", client.get("greeting"));
    }

    @Test
    void individualDifferentPrincipals() throws Exception {
        server = new DanWebSocketServer(19111, DanWebSocketServer.Mode.INDIVIDUAL);
        Thread.sleep(100);

        server.principal("alice").set("name", "Alice");
        server.principal("bob").set("name", "Bob");

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, token));

        CountDownLatch rA = new CountDownLatch(1), rB = new CountDownLatch(1);

        DanWebSocketClient cA = makeClient(19111);
        cA.onConnect(() -> cA.authorize("alice"));
        cA.onReady(rA::countDown);
        cA.connect();

        DanWebSocketClient cB = makeClient(19111);
        cB.onConnect(() -> cB.authorize("bob"));
        cB.onReady(rB::countDown);
        cB.connect();

        assertTrue(rA.await(3, TimeUnit.SECONDS));
        assertTrue(rB.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals("Alice", cA.get("name"));
        assertEquals("Bob", cB.get("name"));
    }

    @Test
    void individualRejectsServerSet() {
        server = new DanWebSocketServer(19112, DanWebSocketServer.Mode.INDIVIDUAL);
        assertThrows(DanWSException.class, () -> server.set("key", "val"));
    }

    @Test
    void authReject() throws Exception {
        server = new DanWebSocketServer(19120, DanWebSocketServer.Mode.INDIVIDUAL);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.reject(uuid, "denied"));

        CountDownLatch done = new CountDownLatch(1);
        List<String> errors = new CopyOnWriteArrayList<>();

        DanWebSocketClient client = makeClient(19120);
        client.onConnect(() -> client.authorize("bad"));
        client.onError(err -> { errors.add(err.code()); done.countDown(); });
        client.connect();

        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertTrue(errors.contains("AUTH_REJECTED"));
    }

    @Test
    void sessionManagement() throws Exception {
        server = new DanWebSocketServer(19130, DanWebSocketServer.Mode.INDIVIDUAL);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, "alice"));
        server.principal("alice").set("x", true);

        String[] uuid = {""};
        server.onConnection(s -> uuid[0] = s.id());

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19130);
        client.onConnect(() -> client.authorize("token"));
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));

        assertTrue(server.isConnected(uuid[0]));
        assertNotNull(server.getSession(uuid[0]));
        assertEquals("alice", server.getSession(uuid[0]).principal());
        assertEquals(1, server.getSessionsByPrincipal("alice").size());

        client.disconnect();
        Thread.sleep(1200);

        assertFalse(server.isConnected(uuid[0]));
        assertNotNull(server.getSession(uuid[0])); // Within TTL
    }
}
