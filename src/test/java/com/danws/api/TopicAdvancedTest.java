package com.danws.api;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TopicAdvancedTest {

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

    // 1. Full event lifecycle: Subscribe -> SUBSCRIBE event -> DelayedTask ticks -> setParams -> CHANGED_PARAMS -> more ticks -> unsubscribe
    @Test
    void fullEventLifecycle() throws Exception {
        server = new DanWebSocketServer(19400, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        List<EventType> events = new CopyOnWriteArrayList<>();
        AtomicInteger tickCount = new AtomicInteger(0);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                events.add(event);
                if (event == EventType.SUBSCRIBE) {
                    t.payload().set("status", "subscribed");
                } else if (event == EventType.CHANGED_PARAMS) {
                    t.payload().set("page", t.params().getOrDefault("page", 1.0));
                } else if (event == EventType.DELAYED_TASK) {
                    tickCount.incrementAndGet();
                    t.payload().set("tick", (double) tickCount.get());
                }
            });
            topic.setDelayedTask(100);
        });

        List<String> unsubscribed = new CopyOnWriteArrayList<>();
        server.topic().onUnsubscribe((session, topic) -> unsubscribed.add(topic.name()));

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19400);
        client.onReady(ready::countDown);
        client.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));

        // Subscribe
        client.subscribe("lifecycle", Map.of("page", 1.0));
        Thread.sleep(500);

        assertTrue(events.contains(EventType.SUBSCRIBE), "SUBSCRIBE event should fire");
        assertTrue(events.contains(EventType.DELAYED_TASK), "DELAYED_TASK events should fire");
        assertEquals("subscribed", client.topic("lifecycle").get("status"));
        assertTrue(tickCount.get() >= 2, "Expected at least 2 ticks, got " + tickCount.get());

        // Change params
        int ticksBefore = tickCount.get();
        client.setParams("lifecycle", Map.of("page", 2.0));
        Thread.sleep(500);

        assertTrue(events.contains(EventType.CHANGED_PARAMS), "CHANGED_PARAMS event should fire");
        assertEquals(2.0, client.topic("lifecycle").get("page"));
        assertTrue(tickCount.get() > ticksBefore, "Ticks should continue after params change");

        // Unsubscribe
        client.unsubscribe("lifecycle");
        Thread.sleep(300);

        assertTrue(unsubscribed.contains("lifecycle"));
    }

    // 2. Rapid subscribe/unsubscribe: subscribe, immediately unsubscribe, verify timer stopped
    @Test
    void rapidSubscribeUnsubscribe() throws Exception {
        server = new DanWebSocketServer(19401, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        AtomicInteger tickCount = new AtomicInteger(0);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.DELAYED_TASK) {
                    tickCount.incrementAndGet();
                }
            });
            topic.setDelayedTask(50);
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19401);
        client.onReady(ready::countDown);
        client.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));

        client.subscribe("fast");
        // Immediately unsubscribe
        client.unsubscribe("fast");
        Thread.sleep(500);

        int ticksAfterUnsub = tickCount.get();
        Thread.sleep(300);

        // Timer should be stopped, no new ticks
        assertEquals(ticksAfterUnsub, tickCount.get(),
                "No new ticks should occur after unsubscribe, but got " + tickCount.get());
    }

    // 3. Multi-session topic isolation: 3 clients subscribe to same topic, each gets own scoped data
    @Test
    void multiSessionTopicIsolation() throws Exception {
        server = new DanWebSocketServer(19402, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    t.payload().set("sessionId", s.id());
                    t.payload().set("greeting", "Hello " + s.id().substring(0, 8));
                }
            });
        });

        CountDownLatch ready1 = new CountDownLatch(1);
        CountDownLatch ready2 = new CountDownLatch(1);
        CountDownLatch ready3 = new CountDownLatch(1);

        DanWebSocketClient c1 = makeClient(19402);
        DanWebSocketClient c2 = makeClient(19402);
        DanWebSocketClient c3 = makeClient(19402);

        c1.onReady(ready1::countDown);
        c2.onReady(ready2::countDown);
        c3.onReady(ready3::countDown);

        c1.connect();
        c2.connect();
        c3.connect();

        assertTrue(ready1.await(3, TimeUnit.SECONDS));
        assertTrue(ready2.await(3, TimeUnit.SECONDS));
        assertTrue(ready3.await(3, TimeUnit.SECONDS));

        c1.subscribe("room");
        c2.subscribe("room");
        c3.subscribe("room");
        Thread.sleep(500);

        String id1 = (String) c1.topic("room").get("sessionId");
        String id2 = (String) c2.topic("room").get("sessionId");
        String id3 = (String) c3.topic("room").get("sessionId");

        assertNotNull(id1);
        assertNotNull(id2);
        assertNotNull(id3);

        // All three should be different (each gets their own session data)
        assertNotEquals(id1, id2, "Client 1 and 2 should have different session data");
        assertNotEquals(id2, id3, "Client 2 and 3 should have different session data");
        assertNotEquals(id1, id3, "Client 1 and 3 should have different session data");

        // Each greeting should reflect its own session ID
        assertTrue(((String) c1.topic("room").get("greeting")).contains(id1.substring(0, 8)));
        assertTrue(((String) c2.topic("room").get("greeting")).contains(id2.substring(0, 8)));
        assertTrue(((String) c3.topic("room").get("greeting")).contains(id3.substring(0, 8)));
    }

    // 4. clearDelayedTask stops polling: start timer, verify ticks, clearDelayedTask, verify stopped
    @Test
    void clearDelayedTaskStopsPolling() throws Exception {
        server = new DanWebSocketServer(19403, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        AtomicInteger tickCount = new AtomicInteger(0);
        AtomicReference<TopicHandle> handleRef = new AtomicReference<>();

        server.topic().onSubscribe((session, topic) -> {
            handleRef.set(topic);
            topic.setCallback((event, t, s) -> {
                if (event == EventType.DELAYED_TASK) {
                    tickCount.incrementAndGet();
                    t.payload().set("tick", (double) tickCount.get());
                }
            });
            topic.setDelayedTask(80);
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19403);
        client.onReady(ready::countDown);
        client.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));

        client.subscribe("polling");
        Thread.sleep(400);

        // Should have some ticks
        assertTrue(tickCount.get() >= 2, "Expected at least 2 ticks before clearing, got " + tickCount.get());

        // Clear the delayed task from server side
        handleRef.get().clearDelayedTask();
        int ticksAtClear = tickCount.get();
        Thread.sleep(400);

        // No new ticks after clearing
        assertEquals(ticksAtClear, tickCount.get(),
                "No ticks should occur after clearDelayedTask, was " + ticksAtClear + " now " + tickCount.get());
    }

    // 5. Topic params change restarts timer: setDelayedTask, change params, verify timer continues
    @Test
    void topicParamsChangeRestartsTimer() throws Exception {
        server = new DanWebSocketServer(19404, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        AtomicInteger tickCount = new AtomicInteger(0);
        List<EventType> events = new CopyOnWriteArrayList<>();

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                events.add(event);
                if (event == EventType.DELAYED_TASK) {
                    tickCount.incrementAndGet();
                    t.payload().set("tick", (double) tickCount.get());
                }
                if (event == EventType.CHANGED_PARAMS) {
                    t.payload().set("filter", t.params().getOrDefault("filter", "none"));
                }
            });
            topic.setDelayedTask(100);
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19404);
        client.onReady(ready::countDown);
        client.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));

        client.subscribe("data", Map.of("filter", "all"));
        Thread.sleep(400);

        int ticksBeforeChange = tickCount.get();
        assertTrue(ticksBeforeChange >= 2, "Expected ticks before param change");

        // Change params — timer should restart (updateParams in TopicHandle clears and re-sets timer)
        client.setParams("data", Map.of("filter", "active"));
        Thread.sleep(400);

        assertTrue(events.contains(EventType.CHANGED_PARAMS));
        assertEquals("active", client.topic("data").get("filter"));
        assertTrue(tickCount.get() > ticksBeforeChange, "Timer should continue ticking after params change");
    }

    // 6. Error in callback doesn't crash server: callback throws exception, server stays alive
    @Test
    void errorInCallbackDoesNotCrashServer() throws Exception {
        server = new DanWebSocketServer(19405, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        AtomicInteger subscribeCount = new AtomicInteger(0);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                subscribeCount.incrementAndGet();
                if (event == EventType.SUBSCRIBE) {
                    // Throw an exception inside the callback
                    throw new RuntimeException("Intentional test error in callback");
                }
                if (event == EventType.DELAYED_TASK) {
                    t.payload().set("alive", true);
                }
            });
            topic.setDelayedTask(100);
        });

        CountDownLatch ready1 = new CountDownLatch(1);
        DanWebSocketClient client1 = makeClient(19405);
        client1.onReady(ready1::countDown);
        client1.connect();
        assertTrue(ready1.await(3, TimeUnit.SECONDS));

        // This subscribe triggers an exception in callback
        client1.subscribe("crashy");
        Thread.sleep(500);

        // Server should still be alive — a second client can connect and subscribe
        CountDownLatch ready2 = new CountDownLatch(1);
        DanWebSocketClient client2 = makeClient(19405);
        client2.onReady(ready2::countDown);
        client2.connect();
        assertTrue(ready2.await(3, TimeUnit.SECONDS), "Server should still accept connections after callback error");

        client2.subscribe("crashy");
        Thread.sleep(500);

        assertTrue(subscribeCount.get() >= 2, "Both subscriptions should have been processed");
    }

    // 7. Multiple topics per session: subscribe to 3 topics simultaneously, all get correct data
    @Test
    void multipleTopicsPerSession() throws Exception {
        server = new DanWebSocketServer(19406, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    t.payload().set("name", t.name());
                    t.payload().set("index", (double) t.payload().index());
                }
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19406);
        client.onReady(ready::countDown);
        client.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));

        client.subscribe("alpha");
        client.subscribe("beta");
        client.subscribe("gamma");
        Thread.sleep(500);

        assertEquals("alpha", client.topic("alpha").get("name"));
        assertEquals("beta", client.topic("beta").get("name"));
        assertEquals("gamma", client.topic("gamma").get("name"));

        // All three topics should have keys
        assertFalse(client.topic("alpha").keys().isEmpty(), "alpha should have keys");
        assertFalse(client.topic("beta").keys().isEmpty(), "beta should have keys");
        assertFalse(client.topic("gamma").keys().isEmpty(), "gamma should have keys");
    }

    // 8. Unsubscribe one topic, others continue: subscribe A+B, unsubscribe A, B still ticks
    @Test
    void unsubscribeOneTopicOthersContinue() throws Exception {
        server = new DanWebSocketServer(19407, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        AtomicInteger ticksA = new AtomicInteger(0);
        AtomicInteger ticksB = new AtomicInteger(0);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.DELAYED_TASK || event == EventType.SUBSCRIBE) {
                    if ("topicA".equals(t.name())) {
                        ticksA.incrementAndGet();
                        t.payload().set("tickA", (double) ticksA.get());
                    } else if ("topicB".equals(t.name())) {
                        ticksB.incrementAndGet();
                        t.payload().set("tickB", (double) ticksB.get());
                    }
                }
            });
            topic.setDelayedTask(100);
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19407);
        client.onReady(ready::countDown);
        client.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));

        client.subscribe("topicA");
        client.subscribe("topicB");
        Thread.sleep(400);

        assertTrue(ticksA.get() >= 2, "topicA should have ticks");
        assertTrue(ticksB.get() >= 2, "topicB should have ticks");

        // Unsubscribe A only
        client.unsubscribe("topicA");
        Thread.sleep(150); // let in-flight timer settle
        int ticksAAfterSettle = ticksA.get();
        int ticksBAtUnsub = ticksB.get();
        Thread.sleep(400);

        // A should stop, B should continue
        assertEquals(ticksAAfterSettle, ticksA.get(), "topicA ticks should stop after unsubscribe");
        assertTrue(ticksB.get() > ticksBAtUnsub, "topicB should continue ticking after topicA unsubscribed");
    }

    // 9. Subscribe after disconnect/reconnect: subscribe, disconnect, reconnect, subscribe again
    @Test
    void subscribeAfterDisconnectReconnect() throws Exception {
        server = new DanWebSocketServer(19408, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        AtomicInteger subscribeCount = new AtomicInteger(0);

        server.topic().onSubscribe((session, topic) -> {
            subscribeCount.incrementAndGet();
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    t.payload().set("connected", true);
                }
            });
        });

        CountDownLatch ready1 = new CountDownLatch(1);
        DanWebSocketClient client1 = makeClient(19408);
        client1.onReady(ready1::countDown);
        client1.connect();
        assertTrue(ready1.await(3, TimeUnit.SECONDS));

        client1.subscribe("channel");
        Thread.sleep(300);

        assertEquals(true, client1.topic("channel").get("connected"));
        client1.disconnect();
        Thread.sleep(200);

        // Create a new client (simulating reconnect with new session)
        CountDownLatch ready2 = new CountDownLatch(1);
        DanWebSocketClient client2 = makeClient(19408);
        client2.onReady(ready2::countDown);
        client2.connect();
        assertTrue(ready2.await(3, TimeUnit.SECONDS));

        client2.subscribe("channel");
        Thread.sleep(300);

        assertEquals(true, client2.topic("channel").get("connected"));
        assertTrue(subscribeCount.get() >= 2, "Should have processed subscriptions from both sessions");
    }

    // 10. Session.set() alongside topic payload.set: use both session flat set and topic payload.set
    @Test
    void sessionSetAlongsideTopicPayload() throws Exception {
        server = new DanWebSocketServer(19409, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    // Set data via topic payload
                    t.payload().set("topicValue", "fromTopic");
                    // Set data via session flat store
                    s.set("sessionValue", "fromSession");
                }
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19409);
        List<String> allReceivedKeys = new CopyOnWriteArrayList<>();

        client.onReady(ready::countDown);
        client.onReceive((key, val) -> allReceivedKeys.add(key));
        client.topic("dual").onReceive((key, val) -> allReceivedKeys.add("topic:" + key));
        client.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));

        client.subscribe("dual");
        Thread.sleep(500);

        // Topic payload data
        assertEquals("fromTopic", client.topic("dual").get("topicValue"));
        // Session flat data
        assertEquals("fromSession", client.get("sessionValue"));
    }

    // 11. Backward compat: onTopicSubscribe fires alongside new API
    @Test
    void backwardCompatOnTopicSubscribeFires() throws Exception {
        server = new DanWebSocketServer(19410, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        List<String> oldApiSubscribed = new CopyOnWriteArrayList<>();
        List<String> newApiSubscribed = new CopyOnWriteArrayList<>();

        // Old-style callback
        server.onTopicSubscribe((session, info) -> oldApiSubscribed.add(info.name()));

        // New-style callback
        server.topic().onSubscribe((session, topic) -> {
            newApiSubscribed.add(topic.name());
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    t.payload().set("ready", true);
                }
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19410);
        client.onReady(ready::countDown);
        client.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));

        client.subscribe("compat");
        Thread.sleep(300);

        assertTrue(oldApiSubscribed.contains("compat"), "Old API onTopicSubscribe should fire");
        assertTrue(newApiSubscribed.contains("compat"), "New API topic().onSubscribe should fire");
        assertEquals(true, client.topic("compat").get("ready"));
    }

    // 12. Empty params: subscribe with no params, verify works
    @Test
    void emptyParams() throws Exception {
        server = new DanWebSocketServer(19411, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        AtomicReference<Map<String, Object>> receivedParams = new AtomicReference<>();

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    receivedParams.set(t.params());
                    t.payload().set("ok", true);
                }
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19411);
        client.onReady(ready::countDown);
        client.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));

        // Subscribe with no params
        client.subscribe("simple");
        Thread.sleep(300);

        assertNotNull(receivedParams.get(), "Params should not be null");
        assertTrue(receivedParams.get().isEmpty(), "Params should be empty");
        assertEquals(true, client.topic("simple").get("ok"));
    }

    // 13. Topic with many params: subscribe with 10+ params, all received correctly
    @Test
    void topicWithManyParams() throws Exception {
        server = new DanWebSocketServer(19412, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        AtomicReference<Map<String, Object>> receivedParams = new AtomicReference<>();

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    receivedParams.set(new HashMap<>(t.params()));
                    t.payload().set("paramCount", (double) t.params().size());
                }
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19412);
        client.onReady(ready::countDown);
        client.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));

        Map<String, Object> manyParams = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            manyParams.put("param" + i, (double) i);
        }

        client.subscribe("complex", manyParams);
        Thread.sleep(500);

        assertNotNull(receivedParams.get(), "Server should receive params");
        assertEquals(12, receivedParams.get().size(), "All 12 params should be received");
        for (int i = 0; i < 12; i++) {
            assertEquals((double) i, receivedParams.get().get("param" + i),
                    "param" + i + " should have correct value");
        }
        assertEquals(12.0, client.topic("complex").get("paramCount"));
    }

    // 14. server.close() disposes all timers: start timers, close server, verify no more ticks
    @Test
    void serverCloseDisposesAllTimers() throws Exception {
        server = new DanWebSocketServer(19413, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        AtomicInteger globalTicks = new AtomicInteger(0);

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.DELAYED_TASK) {
                    globalTicks.incrementAndGet();
                }
            });
            topic.setDelayedTask(80);
        });

        CountDownLatch ready1 = new CountDownLatch(1);
        CountDownLatch ready2 = new CountDownLatch(1);
        DanWebSocketClient c1 = makeClient(19413);
        DanWebSocketClient c2 = makeClient(19413);

        c1.onReady(ready1::countDown);
        c2.onReady(ready2::countDown);
        c1.connect();
        c2.connect();

        assertTrue(ready1.await(3, TimeUnit.SECONDS));
        assertTrue(ready2.await(3, TimeUnit.SECONDS));

        c1.subscribe("timer1");
        c2.subscribe("timer2");
        Thread.sleep(400);

        assertTrue(globalTicks.get() >= 4, "Expected ticks from both timers, got " + globalTicks.get());

        // Close server — should dispose all topic handles and their timers
        server.close();
        server = null; // prevent tearDown from closing again
        int ticksAtClose = globalTicks.get();
        Thread.sleep(400);

        assertEquals(ticksAtClose, globalTicks.get(),
                "No more ticks should occur after server.close(), was " + ticksAtClose + " now " + globalTicks.get());
    }

    // 15. onUnsubscribe callback fires: verify topic.onUnsubscribe fires with correct TopicHandle
    @Test
    void onUnsubscribeCallbackFires() throws Exception {
        server = new DanWebSocketServer(19414, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        List<String> unsubscribedNames = new CopyOnWriteArrayList<>();
        AtomicReference<String> unsubscribedHandleName = new AtomicReference<>();

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    t.payload().set("active", true);
                }
            });
        });

        server.topic().onUnsubscribe((session, topic) -> {
            unsubscribedNames.add(topic.name());
            unsubscribedHandleName.set(topic.name());
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19414);
        client.onReady(ready::countDown);
        client.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));

        client.subscribe("monitor");
        Thread.sleep(300);

        assertEquals(true, client.topic("monitor").get("active"));

        client.unsubscribe("monitor");
        Thread.sleep(300);

        assertTrue(unsubscribedNames.contains("monitor"),
                "onUnsubscribe should fire with correct topic name");
        assertEquals("monitor", unsubscribedHandleName.get(),
                "TopicHandle passed to onUnsubscribe should have the correct name");
    }
}
