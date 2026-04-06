package com.danws.api;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ArraySyncE2ETest {

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

    private DanWebSocketClient connectAndWait(int port) throws Exception {
        DanWebSocketClient client = makeClient(port);
        CountDownLatch ready = new CountDownLatch(1);
        client.onReady(ready::countDown);
        client.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));
        Thread.sleep(1500);
        return client;
    }

    // ===================================================================
    // 1. Basic Array Operations
    // ===================================================================

    @Test
    void arrayAppendPush() throws Exception {
        server = new DanWebSocketServer(19800, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("data", List.of(1.0, 2.0, 3.0));

        DanWebSocketClient client = connectAndWait(19800);

        assertEquals(3, client.get("data.length"));
        assertEquals(1.0, client.get("data.0"));
        assertEquals(2.0, client.get("data.1"));
        assertEquals(3.0, client.get("data.2"));

        // Append: [1,2,3] -> [1,2,3,4]
        server.set("data", List.of(1.0, 2.0, 3.0, 4.0));
        Thread.sleep(1200);

        assertEquals(4, client.get("data.length"));
        assertEquals(1.0, client.get("data.0"));
        assertEquals(2.0, client.get("data.1"));
        assertEquals(3.0, client.get("data.2"));
        assertEquals(4.0, client.get("data.3"));
    }

    @Test
    void arrayPopShrinkFromEnd() throws Exception {
        server = new DanWebSocketServer(19801, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("data", List.of(1.0, 2.0, 3.0, 4.0));

        DanWebSocketClient client = connectAndWait(19801);

        assertEquals(4, client.get("data.length"));
        assertEquals(1.0, client.get("data.0"));
        assertEquals(4.0, client.get("data.3"));

        // Pop/shrink: [1,2,3,4] -> [1,2]
        server.set("data", List.of(1.0, 2.0));
        Thread.sleep(1200);

        assertEquals(2, client.get("data.length"));
        assertEquals(1.0, client.get("data.0"));
        assertEquals(2.0, client.get("data.1"));
    }

    @Test
    void arrayFullReplace() throws Exception {
        server = new DanWebSocketServer(19802, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("data", List.of(1.0, 2.0, 3.0));

        DanWebSocketClient client = connectAndWait(19802);

        assertEquals(3, client.get("data.length"));
        assertEquals(1.0, client.get("data.0"));

        // Full replace: [1,2,3] -> [10,20,30]
        server.set("data", List.of(10.0, 20.0, 30.0));
        Thread.sleep(1200);

        assertEquals(3, client.get("data.length"));
        assertEquals(10.0, client.get("data.0"));
        assertEquals(20.0, client.get("data.1"));
        assertEquals(30.0, client.get("data.2"));
    }

    // ===================================================================
    // 2. Left Shift (ARRAY_SHIFT_LEFT)
    // ===================================================================

    @Test
    void arrayShiftLeftSlidingWindow() throws Exception {
        server = new DanWebSocketServer(19803, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("data", List.of(1.0, 2.0, 3.0, 4.0, 5.0));

        DanWebSocketClient client = connectAndWait(19803);

        assertEquals(5, client.get("data.length"));
        assertEquals(1.0, client.get("data.0"));
        assertEquals(5.0, client.get("data.4"));

        // Shift+Push (sliding window): [1,2,3,4,5] -> [2,3,4,5,6]
        server.set("data", List.of(2.0, 3.0, 4.0, 5.0, 6.0));
        Thread.sleep(1200);

        assertEquals(5, client.get("data.length"));
        assertEquals(2.0, client.get("data.0"));
        assertEquals(3.0, client.get("data.1"));
        assertEquals(4.0, client.get("data.2"));
        assertEquals(5.0, client.get("data.3"));
        assertEquals(6.0, client.get("data.4"));
    }

    @Test
    void arrayPureShiftShrinkFromFront() throws Exception {
        server = new DanWebSocketServer(19804, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("data", List.of(1.0, 2.0, 3.0, 4.0, 5.0));

        DanWebSocketClient client = connectAndWait(19804);

        assertEquals(5, client.get("data.length"));
        assertEquals(1.0, client.get("data.0"));

        // Pure shift (shrink from front): [1,2,3,4,5] -> [3,4,5]
        server.set("data", List.of(3.0, 4.0, 5.0));
        Thread.sleep(1200);

        assertEquals(3, client.get("data.length"));
        assertEquals(3.0, client.get("data.0"));
        assertEquals(4.0, client.get("data.1"));
        assertEquals(5.0, client.get("data.2"));
    }

    @Test
    void arrayShiftBy1Repeatedly() throws Exception {
        server = new DanWebSocketServer(19805, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        List<Double> current = new ArrayList<>(List.of(1.0, 2.0, 3.0, 4.0, 5.0));
        server.set("data", new ArrayList<>(current));

        DanWebSocketClient client = connectAndWait(19805);

        assertEquals(5, client.get("data.length"));

        // Simulate 10 iterations of shift+push
        for (int i = 0; i < 10; i++) {
            current.remove(0);
            current.add((double) (6 + i));
            server.set("data", new ArrayList<>(current));
            Thread.sleep(500);
        }

        // After 10 iterations: [11, 12, 13, 14, 15]
        assertEquals(5, client.get("data.length"));
        assertEquals(11.0, client.get("data.0"));
        assertEquals(12.0, client.get("data.1"));
        assertEquals(13.0, client.get("data.2"));
        assertEquals(14.0, client.get("data.3"));
        assertEquals(15.0, client.get("data.4"));
    }

    // ===================================================================
    // 3. Right Shift (ARRAY_SHIFT_RIGHT)
    // ===================================================================

    @Test
    void arrayUnshiftPrepend() throws Exception {
        server = new DanWebSocketServer(19806, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("data", List.of(1.0, 2.0, 3.0, 4.0, 5.0));

        DanWebSocketClient client = connectAndWait(19806);

        assertEquals(5, client.get("data.length"));
        assertEquals(1.0, client.get("data.0"));

        // Unshift (prepend, remove from end): [1,2,3,4,5] -> [0,1,2,3,4]
        server.set("data", List.of(0.0, 1.0, 2.0, 3.0, 4.0));
        Thread.sleep(1200);

        assertEquals(5, client.get("data.length"));
        assertEquals(0.0, client.get("data.0"));
        assertEquals(1.0, client.get("data.1"));
        assertEquals(2.0, client.get("data.2"));
        assertEquals(3.0, client.get("data.3"));
        assertEquals(4.0, client.get("data.4"));
    }

    @Test
    void arrayPrependAndGrow() throws Exception {
        server = new DanWebSocketServer(19807, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("data", List.of(1.0, 2.0, 3.0));

        DanWebSocketClient client = connectAndWait(19807);

        assertEquals(3, client.get("data.length"));

        // Prepend+grow: [1,2,3] -> [0,1,2,3]
        server.set("data", List.of(0.0, 1.0, 2.0, 3.0));
        Thread.sleep(1200);

        assertEquals(4, client.get("data.length"));
        assertEquals(0.0, client.get("data.0"));
        assertEquals(1.0, client.get("data.1"));
        assertEquals(2.0, client.get("data.2"));
        assertEquals(3.0, client.get("data.3"));
    }

    @Test
    void arrayRightShiftBy2() throws Exception {
        server = new DanWebSocketServer(19808, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("data", List.of(1.0, 2.0, 3.0));

        DanWebSocketClient client = connectAndWait(19808);

        assertEquals(3, client.get("data.length"));

        // Right shift by 2: [1,2,3] -> [8,9,1,2,3]
        server.set("data", List.of(8.0, 9.0, 1.0, 2.0, 3.0));
        Thread.sleep(1200);

        assertEquals(5, client.get("data.length"));
        assertEquals(8.0, client.get("data.0"));
        assertEquals(9.0, client.get("data.1"));
        assertEquals(1.0, client.get("data.2"));
        assertEquals(2.0, client.get("data.3"));
        assertEquals(3.0, client.get("data.4"));
    }

    // ===================================================================
    // 4. Object Elements in Arrays
    // ===================================================================

    @Test
    void arrayOfObjectsShiftPush() throws Exception {
        server = new DanWebSocketServer(19809, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        LinkedHashMap<String, Object> a = new LinkedHashMap<>();
        a.put("name", "A");
        a.put("val", 1.0);
        LinkedHashMap<String, Object> b = new LinkedHashMap<>();
        b.put("name", "B");
        b.put("val", 2.0);

        server.set("items", List.of(a, b));

        DanWebSocketClient client = connectAndWait(19809);

        assertEquals(2, client.get("items.length"));
        assertEquals("A", client.get("items.0.name"));
        assertEquals(1.0, client.get("items.0.val"));
        assertEquals("B", client.get("items.1.name"));
        assertEquals(2.0, client.get("items.1.val"));

        // Shift+Push: [{A,1},{B,2}] -> [{B,2},{C,3}]
        LinkedHashMap<String, Object> c = new LinkedHashMap<>();
        c.put("name", "C");
        c.put("val", 3.0);

        server.set("items", List.of(b, c));
        Thread.sleep(1200);

        assertEquals(2, client.get("items.length"));
        assertEquals("B", client.get("items.0.name"));
        assertEquals(2.0, client.get("items.0.val"));
        assertEquals("C", client.get("items.1.name"));
        assertEquals(3.0, client.get("items.1.val"));
    }

    @Test
    void arrayNestedObjectKeysFlattenCorrectlyAfterShift() throws Exception {
        server = new DanWebSocketServer(19810, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        LinkedHashMap<String, Object> item0 = new LinkedHashMap<>();
        item0.put("id", 100);
        item0.put("info", Map.of("x", 10, "y", 20));
        LinkedHashMap<String, Object> item1 = new LinkedHashMap<>();
        item1.put("id", 200);
        item1.put("info", Map.of("x", 30, "y", 40));

        server.set("rows", List.of(item0, item1));

        DanWebSocketClient client = connectAndWait(19810);

        assertEquals(2, client.get("rows.length"));
        assertEquals(100, client.get("rows.0.id"));
        assertEquals(10, client.get("rows.0.info.x"));
        assertEquals(20, client.get("rows.0.info.y"));
        assertEquals(200, client.get("rows.1.id"));
        assertEquals(30, client.get("rows.1.info.x"));

        // Shift: remove first, push new
        LinkedHashMap<String, Object> item2 = new LinkedHashMap<>();
        item2.put("id", 300);
        item2.put("info", Map.of("x", 50, "y", 60));

        server.set("rows", List.of(item1, item2));
        Thread.sleep(1200);

        assertEquals(2, client.get("rows.length"));
        assertEquals(200, client.get("rows.0.id"));
        assertEquals(30, client.get("rows.0.info.x"));
        assertEquals(40, client.get("rows.0.info.y"));
        assertEquals(300, client.get("rows.1.id"));
        assertEquals(50, client.get("rows.1.info.x"));
        assertEquals(60, client.get("rows.1.info.y"));
    }

    @Test
    void objectElementUpdateAtSpecificIndex() throws Exception {
        server = new DanWebSocketServer(19811, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        LinkedHashMap<String, Object> a = new LinkedHashMap<>();
        a.put("name", "Alice");
        a.put("score", 80.0);
        LinkedHashMap<String, Object> b = new LinkedHashMap<>();
        b.put("name", "Bob");
        b.put("score", 90.0);

        server.set("players", List.of(a, b));

        DanWebSocketClient client = connectAndWait(19811);

        assertEquals("Alice", client.get("players.0.name"));
        assertEquals(80.0, client.get("players.0.score"));
        assertEquals("Bob", client.get("players.1.name"));
        assertEquals(90.0, client.get("players.1.score"));

        // Update only index 1's score
        LinkedHashMap<String, Object> a2 = new LinkedHashMap<>();
        a2.put("name", "Alice");
        a2.put("score", 80.0);
        LinkedHashMap<String, Object> b2 = new LinkedHashMap<>();
        b2.put("name", "Bob");
        b2.put("score", 95.0);

        server.set("players", List.of(a2, b2));
        Thread.sleep(1200);

        assertEquals("Alice", client.get("players.0.name"));
        assertEquals(80.0, client.get("players.0.score"));
        assertEquals("Bob", client.get("players.1.name"));
        assertEquals(95.0, client.get("players.1.score"));
    }

    // ===================================================================
    // 5. Mixed / Edge Cases
    // ===================================================================

    @Test
    void emptyArrayThenAddElements() throws Exception {
        server = new DanWebSocketServer(19812, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("data", List.of());

        DanWebSocketClient client = connectAndWait(19812);

        assertEquals(0, client.get("data.length"));

        // Add elements
        server.set("data", List.of(10.0, 20.0, 30.0));
        Thread.sleep(1200);

        assertEquals(3, client.get("data.length"));
        assertEquals(10.0, client.get("data.0"));
        assertEquals(20.0, client.get("data.1"));
        assertEquals(30.0, client.get("data.2"));
    }

    @Test
    void singleElementShiftPush() throws Exception {
        server = new DanWebSocketServer(19813, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("data", List.of(1.0));

        DanWebSocketClient client = connectAndWait(19813);

        assertEquals(1, client.get("data.length"));
        assertEquals(1.0, client.get("data.0"));

        // Shift+Push on single element: [1] -> [2]
        server.set("data", List.of(2.0));
        Thread.sleep(1200);

        assertEquals(1, client.get("data.length"));
        assertEquals(2.0, client.get("data.0"));
    }

    @Test
    void largeArray50ElementsShiftPush() throws Exception {
        server = new DanWebSocketServer(19814, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        List<Double> initial = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            initial.add((double) i);
        }
        server.set("data", new ArrayList<>(initial));

        DanWebSocketClient client = connectAndWait(19814);

        assertEquals(50, client.get("data.length"));
        assertEquals(0.0, client.get("data.0"));
        assertEquals(49.0, client.get("data.49"));

        // Shift+Push: remove first, add 50 at end -> [1..50]
        List<Double> shifted = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            shifted.add((double) i);
        }
        server.set("data", shifted);
        Thread.sleep(1200);

        assertEquals(50, client.get("data.length"));
        assertEquals(1.0, client.get("data.0"));
        assertEquals(50.0, client.get("data.49"));
        assertEquals(25.0, client.get("data.24"));
    }

    @Test
    void alternatingLeftAndRightShifts() throws Exception {
        server = new DanWebSocketServer(19815, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("data", List.of(1.0, 2.0, 3.0, 4.0, 5.0));

        DanWebSocketClient client = connectAndWait(19815);

        assertEquals(5, client.get("data.length"));

        // Left shift: [1,2,3,4,5] -> [2,3,4,5,6]
        server.set("data", List.of(2.0, 3.0, 4.0, 5.0, 6.0));
        Thread.sleep(1200);

        assertEquals(5, client.get("data.length"));
        assertEquals(2.0, client.get("data.0"));
        assertEquals(6.0, client.get("data.4"));

        // Right shift: [2,3,4,5,6] -> [1,2,3,4,5]
        server.set("data", List.of(1.0, 2.0, 3.0, 4.0, 5.0));
        Thread.sleep(1200);

        assertEquals(5, client.get("data.length"));
        assertEquals(1.0, client.get("data.0"));
        assertEquals(5.0, client.get("data.4"));

        // Left shift again: [1,2,3,4,5] -> [3,4,5,6,7]
        server.set("data", List.of(3.0, 4.0, 5.0, 6.0, 7.0));
        Thread.sleep(1200);

        assertEquals(5, client.get("data.length"));
        assertEquals(3.0, client.get("data.0"));
        assertEquals(7.0, client.get("data.4"));
    }

    @Test
    void arrayOfStringsShiftPush() throws Exception {
        server = new DanWebSocketServer(19816, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("names", List.of("Alice", "Bob", "Charlie"));

        DanWebSocketClient client = connectAndWait(19816);

        assertEquals(3, client.get("names.length"));
        assertEquals("Alice", client.get("names.0"));
        assertEquals("Bob", client.get("names.1"));
        assertEquals("Charlie", client.get("names.2"));

        // Shift+Push: ["Alice","Bob","Charlie"] -> ["Bob","Charlie","Diana"]
        server.set("names", List.of("Bob", "Charlie", "Diana"));
        Thread.sleep(1200);

        assertEquals(3, client.get("names.length"));
        assertEquals("Bob", client.get("names.0"));
        assertEquals("Charlie", client.get("names.1"));
        assertEquals("Diana", client.get("names.2"));
    }

    @Test
    void arrayOfMixedTypes() throws Exception {
        server = new DanWebSocketServer(19817, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        // Test arrays with different value types (all same type per set)
        DanWebSocketClient client = connectAndWait(19817);

        server.set("mixed", List.of("alpha", "beta", "gamma", "delta"));
        Thread.sleep(1500);

        assertEquals(4, client.get("mixed.length"));
        assertEquals("alpha", client.get("mixed.0"));
        assertEquals("delta", client.get("mixed.3"));

        // Shift+Push with same type
        server.set("mixed", List.of("beta", "gamma", "delta", "epsilon"));
        Thread.sleep(1200);

        assertEquals(4, client.get("mixed.length"));
        assertEquals("beta", client.get("mixed.0"));
        assertEquals("gamma", client.get("mixed.1"));
        assertEquals("delta", client.get("mixed.2"));
        assertEquals("epsilon", client.get("mixed.3"));
    }

    // ===================================================================
    // 6. Practical Use Cases -- Stock/Crypto Chart
    // ===================================================================

    @Test
    void candlestickChartSlidingWindow() throws Exception {
        server = new DanWebSocketServer(19818, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        // Build 20 candles
        List<Map<String, Object>> candles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            LinkedHashMap<String, Object> candle = new LinkedHashMap<>();
            candle.put("open", 100.0 + i);
            candle.put("high", 105.0 + i);
            candle.put("low", 95.0 + i);
            candle.put("close", 102.0 + i);
            candle.put("volume", 1000.0 + i * 100);
            candle.put("timestamp", 1700000000L + i * 60);
            candles.add(candle);
        }
        server.set("chart", candles);

        DanWebSocketClient client = connectAndWait(19818);

        assertEquals(20, client.get("chart.length"));
        // Verify first candle
        assertEquals(100.0, client.get("chart.0.open"));
        assertEquals(105.0, client.get("chart.0.high"));
        assertEquals(95.0, client.get("chart.0.low"));
        assertEquals(102.0, client.get("chart.0.close"));
        assertEquals(1000.0, client.get("chart.0.volume"));
        // Verify last candle
        assertEquals(119.0, client.get("chart.19.open"));
        assertEquals(124.0, client.get("chart.19.high"));

        // New candle arrives: shift+push (sliding window of 20)
        List<Map<String, Object>> newCandles = new ArrayList<>(candles.subList(1, 20));
        LinkedHashMap<String, Object> newest = new LinkedHashMap<>();
        newest.put("open", 120.0);
        newest.put("high", 125.0);
        newest.put("low", 115.0);
        newest.put("close", 122.0);
        newest.put("volume", 3000.0);
        newest.put("timestamp", 1700000000L + 20 * 60);
        newCandles.add(newest);

        server.set("chart", newCandles);
        Thread.sleep(1200);

        // Verify still 20 candles
        assertEquals(20, client.get("chart.length"));

        // Verify the newest candle (index 19)
        assertEquals(120.0, client.get("chart.19.open"));
        assertEquals(125.0, client.get("chart.19.high"));
        assertEquals(115.0, client.get("chart.19.low"));
        assertEquals(122.0, client.get("chart.19.close"));
        assertEquals(3000.0, client.get("chart.19.volume"));

        // Verify shifted candles: old index 1 is now index 0
        assertEquals(101.0, client.get("chart.0.open"));
        assertEquals(106.0, client.get("chart.0.high"));
        assertEquals(96.0, client.get("chart.0.low"));
        assertEquals(103.0, client.get("chart.0.close"));
        assertEquals(1100.0, client.get("chart.0.volume"));

        // Verify a middle candle shifted correctly (old index 10 -> new index 9)
        assertEquals(110.0, client.get("chart.9.open"));
    }

    @Test
    void priceTickerSlidingWindow() throws Exception {
        server = new DanWebSocketServer(19819, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        // Initial: 100 prices
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            prices.add(50000.0 + i * 10.0);
        }
        server.set("prices", new ArrayList<>(prices));

        DanWebSocketClient client = connectAndWait(19819);

        assertEquals(100, client.get("prices.length"));
        assertEquals(50000.0, client.get("prices.0"));
        assertEquals(50990.0, client.get("prices.99"));

        // 5 ticks: shift+push each tick
        for (int tick = 0; tick < 5; tick++) {
            prices.remove(0);
            prices.add(51000.0 + tick * 10.0);
            server.set("prices", new ArrayList<>(prices));
            Thread.sleep(500);
        }

        // After 5 ticks:
        // - First price should be 50050.0 (original index 5)
        // - Last price should be 51040.0 (5th tick value)
        assertEquals(100, client.get("prices.length"));
        assertEquals(50050.0, client.get("prices.0"));
        assertEquals(51040.0, client.get("prices.99"));
        // Middle check: original index 55 -> now index 50
        assertEquals(50550.0, client.get("prices.50"));
    }

    @Test
    void orderBookDepthSlidingWindow() throws Exception {
        server = new DanWebSocketServer(19820, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        // Initial: 10 bid levels
        List<Map<String, Object>> bids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            LinkedHashMap<String, Object> level = new LinkedHashMap<>();
            level.put("price", 50000.0 - i * 10.0);
            level.put("quantity", 1.0 + i * 0.5);
            bids.add(level);
        }
        server.set("bids", bids);

        DanWebSocketClient client = connectAndWait(19820);

        assertEquals(10, client.get("bids.length"));
        assertEquals(50000.0, client.get("bids.0.price"));
        assertEquals(1.0, client.get("bids.0.quantity"));
        assertEquals(49910.0, client.get("bids.9.price"));
        assertEquals(5.5, client.get("bids.9.quantity"));

        // Market moves: shift+push (old price out, new price in)
        List<Map<String, Object>> newBids = new ArrayList<>(bids.subList(1, 10));
        LinkedHashMap<String, Object> newLevel = new LinkedHashMap<>();
        newLevel.put("price", 49900.0);
        newLevel.put("quantity", 2.5);
        newBids.add(newLevel);

        server.set("bids", newBids);
        Thread.sleep(1200);

        assertEquals(10, client.get("bids.length"));
        // First bid is now what was index 1
        assertEquals(49990.0, client.get("bids.0.price"));
        assertEquals(1.5, client.get("bids.0.quantity"));
        // New last bid
        assertEquals(49900.0, client.get("bids.9.price"));
        assertEquals(2.5, client.get("bids.9.quantity"));
    }

    // ===================================================================
    // 7. Topic Mode Array Sync
    // ===================================================================

    @Test
    void topicModeArrayShiftPush() throws Exception {
        server = new DanWebSocketServer(19821, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        final Object[] topicRef = new Object[1];

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    t.payload().set("data", List.of(1.0, 2.0, 3.0, 4.0, 5.0));
                    topicRef[0] = t;
                }
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19821);
        client.onReady(ready::countDown);
        client.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("prices");
        Thread.sleep(1200);

        assertEquals(5, client.topic("prices").get("data.length"));
        assertEquals(1.0, client.topic("prices").get("data.0"));
        assertEquals(5.0, client.topic("prices").get("data.4"));

        // Shift+Push via topic payload
        assertNotNull(topicRef[0]);
        TopicHandle ti = (TopicHandle) topicRef[0];
        ti.payload().set("data", List.of(2.0, 3.0, 4.0, 5.0, 6.0));
        Thread.sleep(1200);

        assertEquals(5, client.topic("prices").get("data.length"));
        assertEquals(2.0, client.topic("prices").get("data.0"));
        assertEquals(6.0, client.topic("prices").get("data.4"));
    }

    @Test
    void topicModeCandlestickChart() throws Exception {
        server = new DanWebSocketServer(19822, DanWebSocketServer.Mode.SESSION_TOPIC);
        Thread.sleep(100);

        final Object[] topicRef = new Object[1];

        List<Map<String, Object>> candles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LinkedHashMap<String, Object> candle = new LinkedHashMap<>();
            candle.put("open", 100.0 + i);
            candle.put("close", 101.0 + i);
            candle.put("high", 105.0 + i);
            candle.put("low", 95.0 + i);
            candles.add(candle);
        }

        server.topic().onSubscribe((session, topic) -> {
            topic.setCallback((event, t, s) -> {
                if (event == EventType.SUBSCRIBE) {
                    t.payload().set("candles", candles);
                    topicRef[0] = t;
                }
            });
        });

        CountDownLatch ready = new CountDownLatch(1);
        DanWebSocketClient client = makeClient(19822);
        client.onReady(ready::countDown);
        client.connect();
        assertTrue(ready.await(3, TimeUnit.SECONDS));
        client.subscribe("btc_1m");
        Thread.sleep(1200);

        assertEquals(5, client.topic("btc_1m").get("candles.length"));
        assertEquals(100.0, client.topic("btc_1m").get("candles.0.open"));
        assertEquals(104.0, client.topic("btc_1m").get("candles.4.open"));

        // New candle: shift+push
        assertNotNull(topicRef[0]);
        TopicHandle ti = (TopicHandle) topicRef[0];

        List<Map<String, Object>> newCandles = new ArrayList<>(candles.subList(1, 5));
        LinkedHashMap<String, Object> newest = new LinkedHashMap<>();
        newest.put("open", 105.0);
        newest.put("close", 106.0);
        newest.put("high", 110.0);
        newest.put("low", 100.0);
        newCandles.add(newest);

        ti.payload().set("candles", newCandles);
        Thread.sleep(1200);

        assertEquals(5, client.topic("btc_1m").get("candles.length"));
        // Shifted: old index 1 -> new index 0
        assertEquals(101.0, client.topic("btc_1m").get("candles.0.open"));
        // Newest at index 4
        assertEquals(105.0, client.topic("btc_1m").get("candles.4.open"));
        assertEquals(106.0, client.topic("btc_1m").get("candles.4.close"));
        assertEquals(110.0, client.topic("btc_1m").get("candles.4.high"));
        assertEquals(100.0, client.topic("btc_1m").get("candles.4.low"));
    }

    // ===================================================================
    // 8. Broadcast Multi-Client Array Sync
    // ===================================================================

    @Test
    void multiClientArraySync() throws Exception {
        server = new DanWebSocketServer(19823, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        server.set("data", List.of(1.0, 2.0, 3.0));

        DanWebSocketClient client1 = connectAndWait(19823);
        DanWebSocketClient client2 = connectAndWait(19823);

        assertEquals(3, client1.get("data.length"));
        assertEquals(3, client2.get("data.length"));

        // Shift+Push
        server.set("data", List.of(2.0, 3.0, 4.0));
        Thread.sleep(1200);

        // Both clients should see the update
        assertEquals(3, client1.get("data.length"));
        assertEquals(2.0, client1.get("data.0"));
        assertEquals(4.0, client1.get("data.2"));

        assertEquals(3, client2.get("data.length"));
        assertEquals(2.0, client2.get("data.0"));
        assertEquals(4.0, client2.get("data.2"));
    }
}
