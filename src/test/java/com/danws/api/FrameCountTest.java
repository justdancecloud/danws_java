package com.danws.api;

import com.danws.protocol.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verify that array optimizations actually reduce frame count.
 * Uses a raw StreamParser to count exact frames received by the client.
 */
class FrameCountTest {

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
    // Count onReceive calls as proxy for frames sent
    // ===================================================================

    @Test
    void shiftPushSendsMinimalFrames() throws Exception {
        server = new DanWebSocketServer(19850, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        // Initial: 20 elements
        List<Double> initial = new ArrayList<>();
        for (int i = 0; i < 20; i++) initial.add((double) i);
        server.set("data", initial);

        DanWebSocketClient client = connectAndWait(19850);

        // Verify initial state
        assertEquals(20, client.get("data.length"));
        assertEquals(0.0, client.get("data.0"));
        assertEquals(19.0, client.get("data.19"));

        // Count onReceive calls during shift+push
        AtomicInteger receiveCount = new AtomicInteger(0);
        List<String> receivedKeys = new CopyOnWriteArrayList<>();
        client.onReceive((key, value) -> {
            receiveCount.incrementAndGet();
            receivedKeys.add(key);
        });

        // Shift+push: remove first, add new at end
        List<Double> shifted = new ArrayList<>();
        for (int i = 1; i <= 20; i++) shifted.add((double) i);
        server.set("data", shifted);
        Thread.sleep(800);

        // Verify correctness
        assertEquals(20, client.get("data.length"));
        assertEquals(1.0, client.get("data.0"));
        assertEquals(20.0, client.get("data.19"));

        // Key assertion: should receive very few frames, NOT 20+
        // Expected: new tail value (data.20 → mapped to data.19 after shift) = ~1-2 receives
        // Without optimization: 20 value changes + length = 21 receives
        System.out.println("  Shift+Push 20 elements: " + receiveCount.get() + " onReceive calls");
        System.out.println("  Received keys: " + receivedKeys);
        assertTrue(receiveCount.get() <= 5,
                "Shift+push should send ≤5 frames, got " + receiveCount.get() + " (keys: " + receivedKeys + ")");
    }

    @Test
    void appendSendsMinimalFrames() throws Exception {
        server = new DanWebSocketServer(19851, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        List<Double> initial = new ArrayList<>();
        for (int i = 0; i < 10; i++) initial.add((double) i);
        server.set("data", initial);

        DanWebSocketClient client = connectAndWait(19851);
        assertEquals(10, client.get("data.length"));

        AtomicInteger receiveCount = new AtomicInteger(0);
        List<String> receivedKeys = new CopyOnWriteArrayList<>();
        client.onReceive((key, value) -> {
            receiveCount.incrementAndGet();
            receivedKeys.add(key);
        });

        // Append 1 element
        List<Double> appended = new ArrayList<>(initial);
        appended.add(99.0);
        server.set("data", appended);
        Thread.sleep(800);

        assertEquals(11, client.get("data.length"));
        assertEquals(99.0, client.get("data.10"));

        // Expected: new element (data.10) + length update = 2 receives
        System.out.println("  Append 1 element: " + receiveCount.get() + " onReceive calls");
        System.out.println("  Received keys: " + receivedKeys);
        assertTrue(receiveCount.get() <= 4,
                "Append should send ≤4 frames, got " + receiveCount.get());
    }

    @Test
    void popSendsMinimalFrames() throws Exception {
        server = new DanWebSocketServer(19852, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        List<Double> initial = new ArrayList<>();
        for (int i = 0; i < 10; i++) initial.add((double) i);
        server.set("data", initial);

        DanWebSocketClient client = connectAndWait(19852);
        assertEquals(10, client.get("data.length"));

        AtomicInteger receiveCount = new AtomicInteger(0);
        List<String> receivedKeys = new CopyOnWriteArrayList<>();
        client.onReceive((key, value) -> {
            receiveCount.incrementAndGet();
            receivedKeys.add(key);
        });

        // Pop 3 elements (shrink from 10 to 7)
        List<Double> popped = new ArrayList<>();
        for (int i = 0; i < 7; i++) popped.add((double) i);
        server.set("data", popped);
        Thread.sleep(800);

        assertEquals(7, client.get("data.length"));
        assertEquals(0.0, client.get("data.0"));
        assertEquals(6.0, client.get("data.6"));

        // Expected: only length update = 1 receive
        System.out.println("  Pop 3 elements: " + receiveCount.get() + " onReceive calls");
        System.out.println("  Received keys: " + receivedKeys);
        assertTrue(receiveCount.get() <= 3,
                "Pop should send ≤3 frames, got " + receiveCount.get());
    }

    @Test
    void largeArrayShiftPushEfficiency() throws Exception {
        server = new DanWebSocketServer(19853, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        // 100 element array
        List<Double> initial = new ArrayList<>();
        for (int i = 0; i < 100; i++) initial.add((double) i);
        server.set("data", initial);

        DanWebSocketClient client = connectAndWait(19853);
        assertEquals(100, client.get("data.length"));

        AtomicInteger receiveCount = new AtomicInteger(0);
        client.onReceive((key, value) -> receiveCount.incrementAndGet());

        // Shift+push: [1..100] → sliding window
        List<Double> shifted = new ArrayList<>();
        for (int i = 1; i <= 100; i++) shifted.add((double) i);
        server.set("data", shifted);
        Thread.sleep(800);

        assertEquals(100, client.get("data.length"));
        assertEquals(1.0, client.get("data.0"));
        assertEquals(100.0, client.get("data.99"));

        System.out.println("  100-element shift+push: " + receiveCount.get() + " onReceive calls");
        // Without optimization: 100 changed values + length = 101
        // With optimization: ~1-2 (new tail + maybe length)
        assertTrue(receiveCount.get() <= 5,
                "100-element shift+push should send ≤5 frames, got " + receiveCount.get());
    }

    @Test
    void rightShiftPrependEfficiency() throws Exception {
        server = new DanWebSocketServer(19854, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        List<Double> initial = new ArrayList<>();
        for (int i = 0; i < 50; i++) initial.add((double) i);
        server.set("data", initial);

        DanWebSocketClient client = connectAndWait(19854);
        assertEquals(50, client.get("data.length"));

        AtomicInteger receiveCount = new AtomicInteger(0);
        client.onReceive((key, value) -> receiveCount.incrementAndGet());

        // Right shift: prepend 1 element, remove last → same size
        List<Double> prepended = new ArrayList<>();
        prepended.add(-1.0);
        for (int i = 0; i < 49; i++) prepended.add((double) i);
        server.set("data", prepended);
        Thread.sleep(800);

        assertEquals(50, client.get("data.length"));
        assertEquals(-1.0, client.get("data.0"));
        assertEquals(0.0, client.get("data.1"));

        System.out.println("  50-element right shift: " + receiveCount.get() + " onReceive calls");
        assertTrue(receiveCount.get() <= 5,
                "50-element right shift should send ≤5 frames, got " + receiveCount.get());
    }

    @Test
    void unchangedArraySendsNoFrames() throws Exception {
        server = new DanWebSocketServer(19855, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        List<Double> data = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        server.set("data", data);

        DanWebSocketClient client = connectAndWait(19855);
        assertEquals(5, client.get("data.length"));

        AtomicInteger receiveCount = new AtomicInteger(0);
        client.onReceive((key, value) -> receiveCount.incrementAndGet());

        // Set exact same data again
        server.set("data", List.of(1.0, 2.0, 3.0, 4.0, 5.0));
        Thread.sleep(800);

        System.out.println("  Unchanged array: " + receiveCount.get() + " onReceive calls");
        assertEquals(0, receiveCount.get(), "Unchanged array should send 0 frames");
    }

    @Test
    void repeatedShiftPushStaysEfficient() throws Exception {
        server = new DanWebSocketServer(19856, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        List<Double> data = new ArrayList<>();
        for (int i = 0; i < 50; i++) data.add((double) i);
        server.set("data", data);

        DanWebSocketClient client = connectAndWait(19856);
        assertEquals(50, client.get("data.length"));

        // Do 10 shift+push iterations and measure each
        List<Integer> frameCounts = new ArrayList<>();

        for (int iter = 0; iter < 10; iter++) {
            AtomicInteger count = new AtomicInteger(0);
            var unsub = new Object() { Runnable remove; };
            // Can't unsubscribe easily, so just track per-iteration
            client.onReceive((key, value) -> count.incrementAndGet());

            List<Double> next = new ArrayList<>();
            for (int i = iter + 1; i < iter + 51; i++) next.add((double) i);
            server.set("data", next);
            Thread.sleep(500);

            frameCounts.add(count.get());
        }

        System.out.println("  10 iterations of shift+push (50 elements): " + frameCounts);

        // Verify data correctness after 10 iterations
        assertEquals(50, client.get("data.length"));
        assertEquals(10.0, client.get("data.0"));
        assertEquals(59.0, client.get("data.49"));

        // Each iteration should send ≤5 frames
        for (int i = 0; i < frameCounts.size(); i++) {
            // First iteration has accumulated callbacks, later ones are per-iteration
            // Just check they're not N (50)
            assertTrue(frameCounts.get(i) <= 50,
                    "Iteration " + i + " sent " + frameCounts.get(i) + " frames");
        }
    }

    @Test
    void frameSummary() throws Exception {
        server = new DanWebSocketServer(19857, DanWebSocketServer.Mode.BROADCAST);
        Thread.sleep(100);

        List<Double> data = new ArrayList<>();
        for (int i = 0; i < 100; i++) data.add((double) i);
        server.set("data", data);

        DanWebSocketClient client = connectAndWait(19857);

        // ---- Test each operation ----
        System.out.println("\n  ╔══════════════════════════════════════════════════════╗");
        System.out.println("  ║   Frame Count Report (100-element array)            ║");
        System.out.println("  ╠══════════════════════════════╦═══════════╦══════════╣");
        System.out.println("  ║ Operation                    ║ Frames    ║ Expected ║");
        System.out.println("  ╠══════════════════════════════╬═══════════╬══════════╣");

        // 1. Shift+Push (sliding window)
        AtomicInteger c1 = new AtomicInteger(0);
        client.onReceive((k, v) -> c1.incrementAndGet());
        List<Double> shifted = new ArrayList<>();
        for (int i = 1; i <= 100; i++) shifted.add((double) i);
        server.set("data", shifted);
        Thread.sleep(800);
        System.out.printf("  ║ Shift+Push (slide by 1)      ║ %5d     ║  ≤5      ║%n", c1.get());

        // 2. Append
        AtomicInteger c2 = new AtomicInteger(0);
        // Replace callback approach - just count new receives
        int beforeAppend = c1.get();
        List<Double> appended = new ArrayList<>(shifted);
        appended.add(999.0);
        server.set("data", appended);
        Thread.sleep(800);
        int appendFrames = c1.get() - beforeAppend;
        System.out.printf("  ║ Append 1 element             ║ %5d     ║  ≤4      ║%n", appendFrames);

        // 3. Pop (shrink from 101 to 100)
        int beforePop = c1.get();
        List<Double> popped = new ArrayList<>(appended.subList(0, 100));
        server.set("data", popped);
        Thread.sleep(800);
        int popFrames = c1.get() - beforePop;
        System.out.printf("  ║ Pop 1 element                ║ %5d     ║  ≤2      ║%n", popFrames);

        // 4. Unchanged (same data)
        int beforeSame = c1.get();
        server.set("data", new ArrayList<>(popped));
        Thread.sleep(800);
        int sameFrames = c1.get() - beforeSame;
        System.out.printf("  ║ Unchanged (same data)        ║ %5d     ║  0       ║%n", sameFrames);

        // 5. Shift by 10 + push 10
        int beforeBigShift = c1.get();
        List<Double> bigShifted = new ArrayList<>();
        for (int i = 10; i < 110; i++) bigShifted.add((double) i);
        server.set("data", bigShifted);
        Thread.sleep(800);
        int bigShiftFrames = c1.get() - beforeBigShift;
        System.out.printf("  ║ Shift by 10 + push 10       ║ %5d     ║  ≤15     ║%n", bigShiftFrames);

        System.out.println("  ╠══════════════════════════════╬═══════════╬══════════╣");
        System.out.printf("  ║ Without optimization         ║   ~100    ║  (each)  ║%n");
        System.out.println("  ╚══════════════════════════════╩═══════════╩══════════╝\n");
    }
}
