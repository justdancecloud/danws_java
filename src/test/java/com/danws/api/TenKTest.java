package com.danws.api;

import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TenKTest {

    private DanWebSocketServer server;
    private final List<DanWebSocketClient> clients = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void setupClientGroup() {
        DanWebSocketClient.setSharedGroup(new NioEventLoopGroup(128));
    }

    @AfterEach
    void tearDown() {
        System.out.println("  Disconnecting " + clients.size() + " clients...");
        long start = System.currentTimeMillis();
        for (int i = 0; i < clients.size(); i += 1000) {
            int end = Math.min(i + 1000, clients.size());
            for (int j = i; j < end; j++) {
                try { clients.get(j).disconnect(); } catch (Exception ignored) {}
            }
            try { Thread.sleep(30); } catch (InterruptedException ignored) {}
        }
        clients.clear();
        if (server != null) { server.close(); server = null; }
        System.out.println("  Cleanup done in " + (System.currentTimeMillis() - start) + "ms");
    }

    @Test
    void connect10000() throws Exception {
        runConnectionTest(10_000, 19700);
    }

    @Test
    void connectMax() throws Exception {
        // Push to the Windows ephemeral port limit (~64K)
        // Leave 2000 ports headroom for OS services
        runConnectionTest(60_000, 19701);
    }

    private void runConnectionTest(int TARGET, int port) throws Exception {
        server = new DanWebSocketServer(port, "/", DanWebSocketServer.Mode.BROADCAST, 600_000);
        Thread.sleep(300);

        server.set("status", "online");

        AtomicInteger readyCount = new AtomicInteger(0);
        AtomicInteger disconnectCount = new AtomicInteger(0);
        CountDownLatch allReady = new CountDownLatch(TARGET);

        long connectStart = System.currentTimeMillis();

        int batchSize = 200;
        for (int i = 0; i < TARGET; i += batchSize) {
            int count = Math.min(batchSize, TARGET - i);
            for (int j = 0; j < count; j++) {
                DanWebSocketClient c = new DanWebSocketClient("ws://127.0.0.1:" + port);
                c.onReady(() -> {
                    readyCount.incrementAndGet();
                    allReady.countDown();
                });
                c.onDisconnect(() -> {
                    disconnectCount.incrementAndGet();
                    allReady.countDown();
                });
                clients.add(c);
                c.connect();
            }
            if (i + batchSize < TARGET) Thread.sleep(50);
            int done = i + count;
            if (done % 5000 == 0) {
                System.out.printf("  [%6dms] %,6d launched | %,6d ready | %,6d disconnected%n",
                        System.currentTimeMillis() - connectStart, done, readyCount.get(), disconnectCount.get());
            }
        }

        System.out.printf("  All %,d launched. Waiting up to 120s...%n", TARGET);
        allReady.await(120, TimeUnit.SECONDS);
        long connectTime = System.currentTimeMillis() - connectStart;

        Thread.sleep(5000);

        int gotData = 0;
        for (DanWebSocketClient c : clients) {
            if ("online".equals(c.get("status"))) gotData++;
        }

        Map<String, Integer> stateCounts = new LinkedHashMap<>();
        for (DanWebSocketClient c : clients) {
            stateCounts.merge(c.state().name(), 1, Integer::sum);
        }

        System.out.println();
        System.out.println("  ══════════════════════════════════════════════════════");
        System.out.printf("  %,dK Connection Test — Netty EventLoop Architecture%n", TARGET / 1000);
        System.out.println("  ──────────────────────────────────────────────────────");
        System.out.printf("  Target:        %,d%n", TARGET);
        System.out.printf("  Connected:     %,d (%.1f%%)%n", readyCount.get(), readyCount.get() * 100.0 / TARGET);
        System.out.printf("  Got Data:      %,d (%.1f%%)%n", gotData, gotData * 100.0 / TARGET);
        System.out.printf("  Disconnected:  %,d%n", disconnectCount.get());
        System.out.printf("  Total Time:    %,dms (%.1fs)%n", connectTime, connectTime / 1000.0);
        System.out.printf("  Rate:          %,.0f conn/sec%n", readyCount.get() * 1000.0 / connectTime);
        System.out.println("  ──────────────────────────────────────────────────────");
        System.out.println("  Client States:");
        stateCounts.forEach((k, v) -> System.out.printf("    %-15s %,d%n", k, v));
        System.out.println("  ══════════════════════════════════════════════════════");

        assertTrue(readyCount.get() >= TARGET * 0.5,
                "At least 50% should connect: " + readyCount.get() + "/" + TARGET);
    }
}
