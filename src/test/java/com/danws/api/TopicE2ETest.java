package com.danws.api;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TopicE2ETest {

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
    void topicSubscribeAndPayloadSet() throws Exception {
        server = new DanWebSocketServer(19200, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    t.payload().set("greeting", "Hello from " + t.name());
                }
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19200);
        List<String> receivedKeys = new CopyOnWriteArrayList<>();

        client.onReady(ready::countDown);
        client.topic("chat").onReceive((key, val) -> receivedKeys.add(key));
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("chat");
        Thread.sleep(500);

        assertTrue(receivedKeys.contains("greeting"));
        assertEquals("Hello from chat", client.topic("chat").get("greeting"));
    }

    @Test
    void topicPayloadScopingIsolation() throws Exception {
        server = new DanWebSocketServer(19201, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    t.payload().set("name", t.name());
                    t.payload().set("count", 0.0);
                }
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19201);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("alpha");
        client.subscribe("beta");
        Thread.sleep(500);

        assertEquals("alpha", client.topic("alpha").get("name"));
        assertEquals("beta", client.topic("beta").get("name"));

        List<String> alphaKeys = client.topic("alpha").keys();
        List<String> betaKeys = client.topic("beta").keys();
        assertTrue(alphaKeys.contains("name"));
        assertTrue(alphaKeys.contains("count"));
        assertTrue(betaKeys.contains("name"));
        assertTrue(betaKeys.contains("count"));
    }

    @Test
    void topicDelayedTask() throws Exception {
        server = new DanWebSocketServer(19202, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                double current = t.payload().get("tick") != null ? (double) t.payload().get("tick") : 0.0;
                t.payload().set("tick", current + 1.0);
            });
            topic.setDelayedTask(100);
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19202);
        List<Object> ticks = new CopyOnWriteArrayList<>();

        client.onReady(ready::countDown);
        client.topic("timer").onReceive((key, val) -> { if ("tick".equals(key)) ticks.add(val); });
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("timer");
        Thread.sleep(800);

        // Should have multiple ticks (subscribe + at least a few delayed tasks)
        assertTrue(ticks.size() >= 3, "Expected at least 3 ticks, got " + ticks.size());
    }

    @Test
    void topicChangedParamsEvent() throws Exception {
        server = new DanWebSocketServer(19203, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        List<EventType> events = new CopyOnWriteArrayList<>();

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                events.add(event);
                t.payload().set("page", t.params().getOrDefault("page", 1.0));
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19203);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("posts", Map.of("page", 1.0));
        Thread.sleep(300);

        assertEquals(1.0, client.topic("posts").get("page"));
        assertTrue(events.contains(EventType.SUBSCRIBE));

        client.setParams("posts", Map.of("page", 2.0));
        Thread.sleep(300);

        assertEquals(2.0, client.topic("posts").get("page"));
        assertTrue(events.contains(EventType.CHANGED_PARAMS));
    }

    @Test
    void topicUnsubscribeDisposesTimer() throws Exception {
        server = new DanWebSocketServer(19204, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        List<String> unsubscribed = new CopyOnWriteArrayList<>();

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                t.payload().set("active", true);
            });
            topic.setDelayedTask(50);
        });

        server.topic().onUnsubscribe((session, topic) -> {
            unsubscribed.add(topic.name());
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19204);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("monitor");
        Thread.sleep(300);

        client.unsubscribe("monitor");
        Thread.sleep(300);

        assertTrue(unsubscribed.contains("monitor"));
    }

    @Test
    void sessionPrincipalTopicWithAuth() throws Exception {
        server = new DanWebSocketServer(19205, DanWebSocketServer.Mode.SESSION_PRINCIPAL_TOPIC);
        Thread.sleep(100);

        server.enableAuthorization(true);
        server.onAuthorize((uuid, token) -> server.authorize(uuid, token, token));

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                t.payload().set("user", s.principal());
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19205);
        client.onConnect(() -> client.authorize("alice"));
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("profile");
        Thread.sleep(300);

        assertEquals("alice", client.topic("profile").get("user"));
    }

    @Test
    void backwardCompatTopicCallbacks() throws Exception {
        server = new DanWebSocketServer(19206, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        List<String> subscribed = new CopyOnWriteArrayList<>();
        List<String> unsubscribed = new CopyOnWriteArrayList<>();
        List<String> paramsChanged = new CopyOnWriteArrayList<>();

        // Old-style backward compat callbacks
        server.onTopicSubscribe((session, info) -> subscribed.add(info.name()));
        server.onTopicUnsubscribe((session, name) -> unsubscribed.add(name));
        server.onTopicParamsChange((session, info) -> paramsChanged.add(info.name()));

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19206);
        client.onReady(ready::countDown);
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));

        client.subscribe("news", Map.of("category", "tech"));
        Thread.sleep(300);
        assertTrue(subscribed.contains("news"));

        client.setParams("news", Map.of("category", "sports"));
        Thread.sleep(300);
        assertTrue(paramsChanged.contains("news"));

        client.unsubscribe("news");
        Thread.sleep(300);
        assertTrue(unsubscribed.contains("news"));
    }

    @Test
    void sessionFlatDataWithTopics() throws Exception {
        server = new DanWebSocketServer(19207, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                t.payload().set("topicData", "from topic");
                // Also set flat session data
                s.set("flatData", "from session");
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19207);
        List<String> allKeys = new CopyOnWriteArrayList<>();

        client.onReady(ready::countDown);
        client.onReceive((key, val) -> allKeys.add(key));
        client.topic("test").onReceive((key, val) -> allKeys.add("topic:" + key));
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("test");
        Thread.sleep(500);

        assertEquals("from topic", client.topic("test").get("topicData"));
        assertEquals("from session", client.get("flatData"));
    }

    @Test
    void topicOnUpdateCallback() throws Exception {
        server = new DanWebSocketServer(19208, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                t.payload().set("a", 1.0);
                t.payload().set("b", 2.0);
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19208);
        List<List<String>> updateKeys = new CopyOnWriteArrayList<>();

        client.onReady(ready::countDown);
        client.topic("data").onUpdate(payload -> {
            updateKeys.add(new ArrayList<>(payload.keys()));
        });
        client.connect();

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("data");
        Thread.sleep(500);

        assertFalse(updateKeys.isEmpty());
        // At least one update should have both keys
        boolean hasBoth = updateKeys.stream().anyMatch(keys -> keys.contains("a") && keys.contains("b"));
        assertTrue(hasBoth, "Expected update with both keys a and b");
    }

    @Test
    void multiSessionIsolation() throws Exception {
        server = new DanWebSocketServer(19209, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                t.payload().set("sessionId", s.id());
            });
        });

        CountDownLatch ready1 = new CountDownLatch(1);
        CountDownLatch ready2 = new CountDownLatch(1);
        DanWebSocketClient client1 = makeClient(19209);
        DanWebSocketClient client2 = makeClient(19209);

        client1.onReady(ready1::countDown);
        client2.onReady(ready2::countDown);
        client1.connect();
        client2.connect();

        assertTrue(ready1.await(3, TimeUnit.SECONDS));
        assertTrue(ready2.await(3, TimeUnit.SECONDS));

        client1.subscribe("room");
        client2.subscribe("room");
        Thread.sleep(500);

        String id1 = (String) client1.topic("room").get("sessionId");
        String id2 = (String) client2.topic("room").get("sessionId");
        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2, "Each session should get its own data");
    }
}
