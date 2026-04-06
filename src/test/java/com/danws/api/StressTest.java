package com.danws.api;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Progressive connection stress test: 512 → 1024 → 2048 → 4096 → 8192 → 16384
 * Each level: connect all, send flattened object, verify delivery, report metrics.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StressTest {

    private static DanWebSocketServer server;
    private static final int PORT = 19600;
    private static final List<DanWebSocketClient> allClients = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startServer() throws Exception {
        server = new DanWebSocketServer(PORT, "/", DanWebSocketServer.Mode.BROADCAST, 600_000);
        Thread.sleep(200);
        server.set("payload", Map.of(
            "status", "online",
            "count", 42.0,
            "nested", Map.of("a", 1.0, "b", 2.0)
        ));
    }

    @AfterAll
    static void stopAll() {
        System.out.println("\n  Disconnecting " + allClients.size() + " clients...");
        long start = System.currentTimeMillis();
        // Batch disconnect
        int batch = 500;
        for (int i = 0; i < allClients.size(); i += batch) {
            int end = Math.min(i + batch, allClients.size());
            for (int j = i; j < end; j++) {
                try { allClients.get(j).disconnect(); } catch (Exception ignored) {}
            }
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
        allClients.clear();
        if (server != null) { server.close(); server = null; }
        System.out.println("  Cleanup done in " + (System.currentTimeMillis() - start) + "ms");
    }

    private StressResult runLevel(int targetCount) throws Exception {
        int alreadyConnected = allClients.size();
        int toConnect = targetCount - alreadyConnected;
        if (toConnect <= 0) {
            return new StressResult(targetCount, 0, allClients.size(), 0, 0);
        }

        // Send a fresh update so new clients get it
        server.set("level", (double) targetCount);

        AtomicInteger readyCount = new AtomicInteger(0);
        CountDownLatch allReady = new CountDownLatch(toConnect);
        List<DanWebSocketClient> newClients = new ArrayList<>();

        long connectStart = System.currentTimeMillis();

        // Connect in batches of 256
        int batchSize = 256;
        for (int i = 0; i < toConnect; i += batchSize) {
            int count = Math.min(batchSize, toConnect - i);
            for (int j = 0; j < count; j++) {
                DanWebSocketClient c = new DanWebSocketClient("ws://127.0.0.1:" + PORT);
                c.onReady(() -> {
                    readyCount.incrementAndGet();
                    allReady.countDown();
                });
                newClients.add(c);
                allClients.add(c);
                c.connect();
            }
            // Small pause between batches to avoid SYN flood
            if (i + batchSize < toConnect) Thread.sleep(100);
        }

        // Wait for connections (max 30s)
        boolean allDone = allReady.await(30, TimeUnit.SECONDS);
        long connectTime = System.currentTimeMillis() - connectStart;

        // Wait for data propagation
        Thread.sleep(Math.min(2000, targetCount / 2 + 500));

        // Verify data delivery
        int gotData = 0;
        for (DanWebSocketClient c : allClients) {
            if (c.get("payload.status") != null && "online".equals(c.get("payload.status"))) {
                gotData++;
            }
        }

        int gotLevel = 0;
        for (DanWebSocketClient c : allClients) {
            Object v = c.get("level");
            if (v != null && ((Number) v).doubleValue() == targetCount) {
                gotLevel++;
            }
        }

        return new StressResult(targetCount, connectTime, readyCount.get() + alreadyConnected, gotData, gotLevel);
    }

    record StressResult(int target, long connectTimeMs, int connected, int gotStaticData, int gotLiveUpdate) {
        double connectRate() { return connected * 100.0 / target; }
        double staticRate() { return gotStaticData * 100.0 / target; }
        double liveRate() { return gotLiveUpdate * 100.0 / target; }

        void print() {
            System.out.printf("  %-6d | connect: %5dms | ready: %5d (%5.1f%%) | static: %5d (%5.1f%%) | live: %5d (%5.1f%%)%n",
                target, connectTimeMs, connected, connectRate(), gotStaticData, staticRate(), gotLiveUpdate, liveRate());
        }
    }

    @Test @Order(1)
    void stress_0512() throws Exception {
        System.out.println("\n  ══════ Stress Test: Progressive Connection Scaling ══════");
        System.out.println("  Target | Connect Time  | Ready              | Static Data        | Live Update");
        StressResult r = runLevel(512);
        r.print();
        assertTrue(r.connectRate() >= 90, "512: connect rate " + r.connectRate() + "% < 90%");
        assertTrue(r.staticRate() >= 85, "512: data rate " + r.staticRate() + "% < 85%");
    }

    @Test @Order(2)
    void stress_1024() throws Exception {
        StressResult r = runLevel(1024);
        r.print();
        assertTrue(r.connectRate() >= 85, "1024: connect rate " + r.connectRate() + "% < 85%");
        assertTrue(r.staticRate() >= 80, "1024: data rate " + r.staticRate() + "% < 80%");
    }

    @Test @Order(3)
    void stress_2048() throws Exception {
        StressResult r = runLevel(2048);
        r.print();
        assertTrue(r.connectRate() >= 80, "2048: connect rate " + r.connectRate() + "% < 80%");
        assertTrue(r.staticRate() >= 75, "2048: data rate " + r.staticRate() + "% < 75%");
    }

    @Test @Order(4)
    void stress_4096() throws Exception {
        StressResult r = runLevel(4096);
        r.print();
        assertTrue(r.connectRate() >= 70, "4096: connect rate " + r.connectRate() + "% < 70%");
        assertTrue(r.staticRate() >= 65, "4096: data rate " + r.staticRate() + "% < 65%");
    }

    @Test @Order(5)
    void stress_8192() throws Exception {
        StressResult r = runLevel(8192);
        r.print();
        assertTrue(r.connectRate() >= 60, "8192: connect rate " + r.connectRate() + "% < 60%");
    }

    @Test @Order(6)
    void stress_16384() throws Exception {
        StressResult r = runLevel(16384);
        r.print();
        System.out.println("  ══════════════════════════════════════════════════════════");
        // At this scale, just report — don't fail
        System.out.println("  16384 target: " + r.connected + " connected, " + r.gotStaticData + " got data");
        assertTrue(r.connected > 0, "At least some clients should connect");
    }
}
