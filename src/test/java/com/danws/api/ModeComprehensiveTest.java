package com.danws.api;

import com.danws.protocol.DanWSException;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ModeComprehensiveTest {

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
    // BROADCAST MODE — comprehensive
    // ══════════════════════════════════════════════════

    @Test
    void broadcastAutoFlattenNestedObjects() throws Exception {
        server = new DanWebSocketServer(19600, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        LinkedHashMap<String, Object> memory = new LinkedHashMap<>();
        memory.put("used", 8.2);
        memory.put("total", 16.0);

        LinkedHashMap<String, Object> proc0 = new LinkedHashMap<>();
        proc0.put("pid", 1234);
        proc0.put("name", "node");
        LinkedHashMap<String, Object> proc1 = new LinkedHashMap<>();
        proc1.put("pid", 5678);
        proc1.put("name", "nginx");

        LinkedHashMap<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("cpu", 72.5);
        dashboard.put("memory", memory);
        dashboard.put("processes", List.of(proc0, proc1));

        server.set("dashboard", dashboard);

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19600);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals(72.5, client.get("dashboard.cpu"));
        assertEquals(8.2, client.get("dashboard.memory.used"));
        assertEquals(16.0, client.get("dashboard.memory.total"));
        assertEquals("node", client.get("dashboard.processes.0.name"));
        assertEquals(5678, client.get("dashboard.processes.1.pid"));
        assertEquals(2, client.get("dashboard.processes.length"));
    }

    @Test
    void broadcastIncrementalUpdatesOnlyChangedFields() throws Exception {
        server = new DanWebSocketServer(19601, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("data", Map.of("a", 1.0, "b", 2.0, "c", 3.0));

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19601);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        List<String> received = new CopyOnWriteArrayList<>();
        client.onReceive((key, val) -> received.add(key));

        // Only b changes
        server.set("data", Map.of("a", 1.0, "b", 99.0, "c", 3.0));
        Thread.sleep(1200);

        assertTrue(received.contains("data.b"), "data.b should be received");
        assertFalse(received.contains("data.a"), "data.a should not be received (unchanged)");
        assertFalse(received.contains("data.c"), "data.c should not be received (unchanged)");
        assertEquals(99.0, client.get("data.b"));
    }

    @Test
    void broadcastOnUpdateFiresOncePerBatch() throws Exception {
        server = new DanWebSocketServer(19602, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19602);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(200);

        AtomicInteger updateCount = new AtomicInteger(0);
        client.onUpdate(() -> updateCount.incrementAndGet());

        server.set("x", 1.0);
        server.set("y", 2.0);
        server.set("z", 3.0);
        Thread.sleep(1200);

        // Should be 1 or very few (batched within flush interval)
        assertTrue(updateCount.get() >= 1, "Expected at least 1 update");
        assertTrue(updateCount.get() <= 2, "Expected at most 2 updates (batched), got " + updateCount.get());
    }

    @Test
    void broadcastServerClearRemovesKeys() {
        server = new DanWebSocketServer(19603, DanWebSocketServer.Mode.BROADCAST);

        server.set("a", 1.0);
        server.set("b", 2.0);
        server.set("c", 3.0);

        assertEquals(1.0, server.get("a"));
        List<String> keys = server.keys();
        assertTrue(keys.containsAll(List.of("a", "b", "c")));

        server.clear("a");
        assertNull(server.get("a"));
        assertFalse(server.keys().contains("a"));
        assertTrue(server.keys().contains("b"));

        server.clear();
        assertTrue(server.keys().isEmpty());
    }

    @Test
    void broadcastClearTriggersResyncNewClientSeesCorrectState() throws Exception {
        server = new DanWebSocketServer(19604, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("keep", "yes");
        server.set("remove", "bye");
        server.clear("remove");

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19604);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals("yes", client.get("keep"));
        assertFalse(client.keys().contains("remove"));
    }

    // ══════════════════════════════════════════════════
    // PRINCIPAL MODE — comprehensive
    // ══════════════════════════════════════════════════

    @Test
    void principalAutoFlattenPerUserObjects() throws Exception {
        server = new DanWebSocketServer(19610, DanWebSocketServer.Mode.PRINCIPAL);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, token));

        LinkedHashMap<String, Object> aliceStats = new LinkedHashMap<>();
        aliceStats.put("hp", 100.0);
        aliceStats.put("mp", 50.0);
        LinkedHashMap<String, Object> aliceProfile = new LinkedHashMap<>();
        aliceProfile.put("name", "Alice");
        aliceProfile.put("level", 10.0);
        aliceProfile.put("stats", aliceStats);

        LinkedHashMap<String, Object> bobStats = new LinkedHashMap<>();
        bobStats.put("hp", 80.0);
        bobStats.put("mp", 30.0);
        LinkedHashMap<String, Object> bobProfile = new LinkedHashMap<>();
        bobProfile.put("name", "Bob");
        bobProfile.put("level", 5.0);
        bobProfile.put("stats", bobStats);

        server.principal("alice").set("profile", aliceProfile);
        server.principal("bob").set("profile", bobProfile);

        CountDownLatch rA = new CountDownLatch(1), rB = new CountDownLatch(1);

        DanWebSocketClient cA = makeClient(19610);
        cA.onConnect(() -> cA.authorize("alice"));
        cA.onReady(rA::countDown);
        cA.connect();

        DanWebSocketClient cB = makeClient(19610);
        cB.onConnect(() -> cB.authorize("bob"));
        cB.onReady(rB::countDown);
        cB.connect();

        assertTrue(rA.await(3, TimeUnit.SECONDS));
        assertTrue(rB.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        // Alice sees only alice's data
        assertEquals("Alice", cA.get("profile.name"));
        assertEquals(100.0, cA.get("profile.stats.hp"));
        assertEquals(10.0, cA.get("profile.level"));

        // Bob sees only bob's data
        assertEquals("Bob", cB.get("profile.name"));
        assertEquals(30.0, cB.get("profile.stats.mp"));
    }

    @Test
    void principalMultiDeviceSync() throws Exception {
        server = new DanWebSocketServer(19611, DanWebSocketServer.Mode.PRINCIPAL);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, "alice"));

        server.principal("alice").set("score", 0.0);

        CountDownLatch rPC = new CountDownLatch(1), rMobile = new CountDownLatch(1);

        DanWebSocketClient pc = makeClient(19611);
        DanWebSocketClient mobile = makeClient(19611);
        pc.onConnect(() -> pc.authorize("token1"));
        mobile.onConnect(() -> mobile.authorize("token2"));
        pc.onReady(rPC::countDown);
        mobile.onReady(rMobile::countDown);
        pc.connect();
        mobile.connect();

        assertTrue(rPC.await(3, TimeUnit.SECONDS));
        assertTrue(rMobile.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals(0.0, pc.get("score"));
        assertEquals(0.0, mobile.get("score"));

        // Update score — both devices get it
        server.principal("alice").set("score", 100.0);
        Thread.sleep(1200);

        assertEquals(100.0, pc.get("score"));
        assertEquals(100.0, mobile.get("score"));
    }

    @Test
    void principalLiveUpdateAfterConnect() throws Exception {
        server = new DanWebSocketServer(19612, DanWebSocketServer.Mode.PRINCIPAL);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, "player1"));

        server.principal("player1").set("hp", 100.0);

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19612);
        client.onConnect(() -> client.authorize("jwt"));
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertEquals(100.0, client.get("hp"));

        List<String> receivedKeys = new CopyOnWriteArrayList<>();
        List<Object> receivedVals = new CopyOnWriteArrayList<>();
        client.onReceive((key, val) -> { receivedKeys.add(key); receivedVals.add(val); });

        server.principal("player1").set("hp", 75.0);
        Thread.sleep(1200);

        assertTrue(receivedKeys.contains("hp"), "Should receive hp update");
        assertEquals(75.0, client.get("hp"));
    }

    @Test
    void principalAuthRejectFlow() throws Exception {
        server = new DanWebSocketServer(19613, DanWebSocketServer.Mode.PRINCIPAL);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.reject(uuid, "invalid credentials"));

        CountDownLatch done = new CountDownLatch(1);
        List<String> errors = new CopyOnWriteArrayList<>();

        DanWebSocketClient client = makeClient(19613);
        client.onConnect(() -> client.authorize("bad-token"));
        client.onError(err -> { errors.add(err.code()); done.countDown(); });
        client.connect();

        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertTrue(errors.contains("AUTH_REJECTED"));
    }

    @Test
    void principalServerSideClear() {
        server = new DanWebSocketServer(19615, DanWebSocketServer.Mode.PRINCIPAL);

        server.principal("alice").set("a", 1.0);
        server.principal("alice").set("b", 2.0);

        assertEquals(1.0, server.principal("alice").get("a"));
        server.principal("alice").clear("a");
        assertNull(server.principal("alice").get("a"));
        assertEquals(2.0, server.principal("alice").get("b"));
    }

    @Test
    void principalClearClientSeesCorrectState() throws Exception {
        server = new DanWebSocketServer(19616, DanWebSocketServer.Mode.PRINCIPAL);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, "alice"));

        server.principal("alice").set("a", 1.0);
        server.principal("alice").set("b", 2.0);
        server.principal("alice").clear("a");
        server.principal("alice").set("c", 3.0);

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19616);
        client.onConnect(() -> client.authorize("t"));
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);

        assertFalse(client.keys().contains("a"));
        assertEquals(2.0, client.get("b"));
        assertEquals(3.0, client.get("c"));
    }

    // ══════════════════════════════════════════════════
    // SESSION_TOPIC MODE — comprehensive
    // ══════════════════════════════════════════════════

    @Test
    void sessionTopicPayloadSetWithNestedObjects() throws Exception {
        server = new DanWebSocketServer(19620, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    LinkedHashMap<String, Object> item0 = new LinkedHashMap<>();
                    item0.put("id", 1);
                    item0.put("title", "Hello");
                    LinkedHashMap<String, Object> item1 = new LinkedHashMap<>();
                    item1.put("id", 2);
                    item1.put("title", "World");

                    LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
                    meta.put("totalCount", 42);
                    meta.put("page", 1);

                    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                    result.put("items", List.of(item0, item1));
                    result.put("meta", meta);

                    t.payload().set("result", result);
                }
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19620);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("posts");
        Thread.sleep(2000);

        assertEquals("Hello", client.topic("posts").get("result.items.0.title"));
        assertEquals(2, client.topic("posts").get("result.items.1.id"));
        assertEquals(42, client.topic("posts").get("result.meta.totalCount"));
        assertEquals(2, client.topic("posts").get("result.items.length"));
    }

    @Test
    void sessionTopicSetDelayedTaskPolling() throws Exception {
        server = new DanWebSocketServer(19621, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        AtomicInteger callCount = new AtomicInteger(0);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                int count = callCount.incrementAndGet();
                t.payload().set("counter", (double) count);
            });
            topic.setDelayedTask(100);
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19621);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("ticker");
        Thread.sleep(2000);

        assertTrue(callCount.get() >= 4, "Expected at least 4 callback fires, got " + callCount.get());
        double counter = (double) client.topic("ticker").get("counter");
        assertTrue(counter >= 4, "Expected counter >= 4, got " + counter);
    }

    @Test
    void sessionTopicChangedParamsEvent() throws Exception {
        server = new DanWebSocketServer(19622, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        List<EventType> events = new CopyOnWriteArrayList<>();

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                events.add(event);
                Object page = t.params().getOrDefault("page", 1.0);
                t.payload().set("page", page);
                t.payload().set("data", "page-" + page + "-data");
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19622);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("feed", Map.of("page", 1.0));
        Thread.sleep(2000);

        assertTrue(events.contains(EventType.SUBSCRIBE));
        assertEquals(1.0, client.topic("feed").get("page"));

        client.setParams("feed", Map.of("page", 3.0));
        Thread.sleep(2000);

        assertTrue(events.contains(EventType.CHANGED_PARAMS));
        assertEquals(3.0, client.topic("feed").get("page"));
        assertEquals("page-3.0-data", client.topic("feed").get("data"));
    }

    @Test
    void sessionTopicTwoClientsIsolatedData() throws Exception {
        server = new DanWebSocketServer(19623, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    Object symbol = t.params().getOrDefault("symbol", "UNKNOWN");
                    t.payload().set("price", "BTC".equals(symbol) ? 67000.0 : 3200.0);
                    t.payload().set("symbol", symbol);
                }
            });
        });

        CountDownLatch r1 = new CountDownLatch(1), r2 = new CountDownLatch(1);
        DanWebSocketClient c1 = makeClient(19623);
        DanWebSocketClient c2 = makeClient(19623);
        c1.onReady(r1::countDown);
        c2.onReady(r2::countDown);
        c1.connect();
        c2.connect();

        assertTrue(r1.await(3, TimeUnit.SECONDS));
        assertTrue(r2.await(3, TimeUnit.SECONDS));

        c1.subscribe("chart", Map.of("symbol", "BTC"));
        c2.subscribe("chart", Map.of("symbol", "ETH"));
        Thread.sleep(2000);

        assertEquals(67000.0, c1.topic("chart").get("price"));
        assertEquals("BTC", c1.topic("chart").get("symbol"));
        assertEquals(3200.0, c2.topic("chart").get("price"));
        assertEquals("ETH", c2.topic("chart").get("symbol"));
    }

    @Test
    void sessionTopicUnsubscribeStopsTimer() throws Exception {
        server = new DanWebSocketServer(19624, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        AtomicInteger callCount = new AtomicInteger(0);
        List<String> unsubscribed = new CopyOnWriteArrayList<>();

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> callCount.incrementAndGet());
            topic.setDelayedTask(50);
        });
        server.topic().onUnsubscribe((session, topic) -> unsubscribed.add(topic.name()));

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19624);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("ticker");
        Thread.sleep(1200);

        int countBefore = callCount.get();
        assertTrue(countBefore >= 3, "Expected at least 3 calls, got " + countBefore);

        client.unsubscribe("ticker");
        Thread.sleep(1200);
        int countAfter = callCount.get();

        // Timer should have stopped — count should barely increase
        assertTrue(countAfter - countBefore <= 1,
                "Timer should stop after unsubscribe, but diff = " + (countAfter - countBefore));
        assertTrue(unsubscribed.contains("ticker"));
    }

    @Test
    void sessionTopicMultipleTopicsPerSession() throws Exception {
        server = new DanWebSocketServer(19625, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    t.payload().set("name", t.name());
                    t.payload().set("value", (double) t.name().length());
                }
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19625);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("alpha");
        client.subscribe("beta");
        client.subscribe("gamma");
        Thread.sleep(2000);

        assertEquals("alpha", client.topic("alpha").get("name"));
        assertEquals("beta", client.topic("beta").get("name"));
        assertEquals("gamma", client.topic("gamma").get("name"));

        assertEquals(5.0, client.topic("alpha").get("value"));  // "alpha".length()
        assertEquals(4.0, client.topic("beta").get("value"));   // "beta".length()
        assertEquals(5.0, client.topic("gamma").get("value"));  // "gamma".length()
    }

    // ══════════════════════════════════════════════════
    // SESSION_PRINCIPAL_TOPIC MODE — comprehensive
    // ══════════════════════════════════════════════════

    @Test
    void sessionPrincipalTopicFullLifecycle() throws Exception {
        server = new DanWebSocketServer(19640, DanWebSocketServer.Mode.SESSION_PRINCIPAL_TOPIC);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, token));

        List<EventType> events = new CopyOnWriteArrayList<>();
        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                events.add(event);
                if (event == EventType.CHANGED_PARAMS) {
                    t.payload().clear();
                }
                t.payload().set("user", s.principal());
                t.payload().set("view", t.params().getOrDefault("view", "default"));
            });
        });

        List<String> unsubNames = new CopyOnWriteArrayList<>();
        server.topic().onUnsubscribe((session, topic) -> unsubNames.add(topic.name()));

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19640);
        client.onConnect(() -> client.authorize("alice"));
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));

        // Subscribe
        client.subscribe("my.dashboard", Map.of("view", "compact"));
        Thread.sleep(2000);

        assertTrue(events.contains(EventType.SUBSCRIBE));
        assertEquals("alice", client.topic("my.dashboard").get("user"));
        assertEquals("compact", client.topic("my.dashboard").get("view"));

        // Change params
        client.setParams("my.dashboard", Map.of("view", "full"));
        Thread.sleep(2000);

        assertTrue(events.contains(EventType.CHANGED_PARAMS));
        assertEquals("full", client.topic("my.dashboard").get("view"));

        // Unsubscribe
        client.unsubscribe("my.dashboard");
        Thread.sleep(2000);

        assertTrue(unsubNames.contains("my.dashboard"));
    }

    @Test
    void sessionPrincipalTopicAuthRejectPreventsTopic() throws Exception {
        server = new DanWebSocketServer(19641, DanWebSocketServer.Mode.SESSION_PRINCIPAL_TOPIC);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.reject(uuid, "unauthorized"));

        AtomicInteger subscribeCount = new AtomicInteger(0);
        server.topic().onSubscribe((session, topic) -> subscribeCount.incrementAndGet());

        CountDownLatch done = new CountDownLatch(1);
        List<String> errors = new CopyOnWriteArrayList<>();

        DanWebSocketClient client = makeClient(19641);
        client.onConnect(() -> client.authorize("bad-token"));
        client.onError(err -> { errors.add(err.code()); done.countDown(); });
        client.connect();

        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertTrue(errors.contains("AUTH_REJECTED"));
        assertEquals(0, subscribeCount.get(), "Rejected client should not trigger onSubscribe");
    }

    @Test
    void sessionPrincipalTopicTwoUsersIsolatedData() throws Exception {
        server = new DanWebSocketServer(19642, DanWebSocketServer.Mode.SESSION_PRINCIPAL_TOPIC);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, token));

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                String user = s.principal();
                t.payload().set("greeting", "Hello " + user);

                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("orders", "alice".equals(user) ? 10.0 : 5.0);
                data.put("notifications", "alice".equals(user) ? 3.0 : 0.0);
                t.payload().set("data", data);
            });
        });

        CountDownLatch rA = new CountDownLatch(1), rB = new CountDownLatch(1);

        DanWebSocketClient cA = makeClient(19642);
        DanWebSocketClient cB = makeClient(19642);
        cA.onConnect(() -> cA.authorize("alice"));
        cB.onConnect(() -> cB.authorize("bob"));
        cA.onReady(rA::countDown);
        cB.onReady(rB::countDown);
        cA.connect();
        cB.connect();

        assertTrue(rA.await(3, TimeUnit.SECONDS));
        assertTrue(rB.await(3, TimeUnit.SECONDS));

        cA.subscribe("my.dashboard");
        cB.subscribe("my.dashboard");
        Thread.sleep(2000);

        // Alice's data
        assertEquals("Hello alice", cA.topic("my.dashboard").get("greeting"));
        assertEquals(10.0, cA.topic("my.dashboard").get("data.orders"));
        assertEquals(3.0, cA.topic("my.dashboard").get("data.notifications"));

        // Bob's data — different
        assertEquals("Hello bob", cB.topic("my.dashboard").get("greeting"));
        assertEquals(5.0, cB.topic("my.dashboard").get("data.orders"));
        assertEquals(0.0, cB.topic("my.dashboard").get("data.notifications"));
    }

    @Test
    void sessionPrincipalTopicDelayedTaskWithPrincipalContext() throws Exception {
        server = new DanWebSocketServer(19643, DanWebSocketServer.Mode.SESSION_PRINCIPAL_TOPIC);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, token));

        AtomicReference<String> principalInTask = new AtomicReference<>("");
        AtomicInteger taskCount = new AtomicInteger(0);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                principalInTask.set(s.principal());
                int count = taskCount.incrementAndGet();
                t.payload().set("tick", (double) count);
            });
            topic.setDelayedTask(100);
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19643);
        client.onConnect(() -> client.authorize("charlie"));
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("monitor");
        Thread.sleep(2000);

        assertEquals("charlie", principalInTask.get());
        assertTrue(taskCount.get() >= 3, "Expected at least 3 task fires, got " + taskCount.get());
        double tick = (double) client.topic("monitor").get("tick");
        assertTrue(tick >= 3, "Expected tick >= 3, got " + tick);
    }

    @Test
    void sessionPrincipalTopicMultipleTopicsPerUser() throws Exception {
        server = new DanWebSocketServer(19644, DanWebSocketServer.Mode.SESSION_PRINCIPAL_TOPIC);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, token));

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    if ("orders".equals(t.name())) {
                        t.payload().set("count", 42.0);
                        t.payload().set("status", "open");
                    } else if ("notifications".equals(t.name())) {
                        t.payload().set("unread", 7.0);
                        t.payload().set("latest", "New message");
                    }
                }
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19644);
        client.onConnect(() -> client.authorize("alice"));
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("orders");
        client.subscribe("notifications");
        Thread.sleep(2000);

        // Orders topic
        assertEquals(42.0, client.topic("orders").get("count"));
        assertEquals("open", client.topic("orders").get("status"));

        // Notifications topic — separate scope
        assertEquals(7.0, client.topic("notifications").get("unread"));
        assertEquals("New message", client.topic("notifications").get("latest"));

        // No cross-contamination
        assertNull(client.topic("orders").get("unread"));
        assertNull(client.topic("notifications").get("count"));
    }

    @Test
    void sessionPrincipalTopicParamsChangeWithPrincipal() throws Exception {
        server = new DanWebSocketServer(19645, DanWebSocketServer.Mode.SESSION_PRINCIPAL_TOPIC);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, token));

        List<Map<String, Object>> paramsLog = new CopyOnWriteArrayList<>();

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                paramsLog.add(new HashMap<>(t.params()));
                Object status = t.params().getOrDefault("status", "open");
                t.payload().set("filter", s.principal() + ":" + status);
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19645);
        client.onConnect(() -> client.authorize("alice"));
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("my.orders", Map.of("status", "open"));
        Thread.sleep(2000);

        assertEquals("alice:open", client.topic("my.orders").get("filter"));

        client.setParams("my.orders", Map.of("status", "closed"));
        Thread.sleep(2000);

        assertEquals("alice:closed", client.topic("my.orders").get("filter"));
        assertEquals(2, paramsLog.size());
        assertEquals("open", paramsLog.get(0).get("status"));
        assertEquals("closed", paramsLog.get(1).get("status"));
    }

    @Test
    void sessionPrincipalTopicDisconnectCleansUpTimers() throws Exception {
        server = new DanWebSocketServer(19646, DanWebSocketServer.Mode.SESSION_PRINCIPAL_TOPIC);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, token));

        AtomicInteger taskCount = new AtomicInteger(0);
        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> taskCount.incrementAndGet());
            topic.setDelayedTask(50);
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19646);
        client.onConnect(() -> client.authorize("alice"));
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("live");
        Thread.sleep(1200);

        int countBefore = taskCount.get();
        assertTrue(countBefore >= 3, "Expected at least 3 task fires, got " + countBefore);

        client.disconnect();
        Thread.sleep(1200);
        int countAfter = taskCount.get();

        // Timer should have stopped
        assertTrue(countAfter - countBefore <= 1,
                "Timer should stop after disconnect, diff = " + (countAfter - countBefore));
    }

    // ══════════════════════════════════════════════════
    // MODE GUARDS — comprehensive
    // ══════════════════════════════════════════════════

    @Test
    void modeGuardBroadcastRejectsPrincipal() {
        server = new DanWebSocketServer(19660, DanWebSocketServer.Mode.BROADCAST);
        assertThrows(DanWSException.class, () -> server.principal("x"));
    }

    @Test
    void modeGuardPrincipalRejectsSet() {
        server = new DanWebSocketServer(19661, DanWebSocketServer.Mode.PRINCIPAL);
        assertThrows(DanWSException.class, () -> server.set("x", 1));
    }

    @Test
    void modeGuardSessionTopicRejectsSetAndPrincipal() {
        server = new DanWebSocketServer(19662, DanWebSocketServer.Mode.SESSION_TOPIC);
        assertThrows(DanWSException.class, () -> server.set("x", 1));
        assertThrows(DanWSException.class, () -> server.principal("x"));
    }

    @Test
    void modeGuardSessionPrincipalTopicRejectsSet() {
        server = new DanWebSocketServer(19663, DanWebSocketServer.Mode.SESSION_PRINCIPAL_TOPIC);
        assertThrows(DanWSException.class, () -> server.set("x", 1));
    }

    @Test
    void modeGuardSessionPrincipalTopicAllowsPrincipal() {
        server = new DanWebSocketServer(19664, DanWebSocketServer.Mode.SESSION_PRINCIPAL_TOPIC);
        // Should NOT throw — session_principal_topic supports principal()
        assertDoesNotThrow(() -> server.principal("alice"));
    }

    // ══════════════════════════════════════════════════
    // maxValueSize — value size limit
    // ══════════════════════════════════════════════════

    @Test
    void maxValueSizeBroadcastLargeValueThrows() {
        server = new DanWebSocketServer(19670, "/", DanWebSocketServer.Mode.BROADCAST, 600_000, 100, 1_048_576, 100);

        // Small value should work
        server.set("small", "hello");
        assertEquals("hello", server.get("small"));

        // Large value should throw
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append('x');
        String bigString = sb.toString();

        DanWSException ex = assertThrows(DanWSException.class, () -> server.set("big", bigString));
        assertEquals("VALUE_TOO_LARGE", ex.code());
    }

    @Test
    void maxValueSizePrincipalLargeValueThrows() {
        server = new DanWebSocketServer(19671, "/", DanWebSocketServer.Mode.PRINCIPAL, 600_000, 100, 1_048_576, 50);

        server.principal("alice").set("ok", "short");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append('x');
        String bigString = sb.toString();

        DanWSException ex = assertThrows(DanWSException.class, () -> server.principal("alice").set("big", bigString));
        assertEquals("VALUE_TOO_LARGE", ex.code());
    }
}
