package com.danws.connection;

import com.danws.protocol.Codec;

import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Bidirectional heartbeat: sends every 10s, times out if no receive in 15s.
 */
public class HeartbeatManager {

    private static final long SEND_INTERVAL = 10_000;
    private static final long TIMEOUT_THRESHOLD = 15_000;
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "danws-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private Consumer<byte[]> onSend;
    private Runnable onTimeout;
    private volatile long lastReceived;
    private ScheduledFuture<?> sendTask;
    private ScheduledFuture<?> checkTask;

    public void onSend(Consumer<byte[]> fn) { this.onSend = fn; }
    public void onTimeout(Runnable fn) { this.onTimeout = fn; }

    public void start() {
        stop();
        lastReceived = System.currentTimeMillis();

        sendTask = SCHEDULER.scheduleAtFixedRate(() -> {
            if (onSend != null) {
                try { onSend.accept(Codec.encodeHeartbeat()); } catch (Exception ignored) {}
            }
        }, SEND_INTERVAL, SEND_INTERVAL, TimeUnit.MILLISECONDS);

        checkTask = SCHEDULER.scheduleAtFixedRate(() -> {
            if (System.currentTimeMillis() - lastReceived > TIMEOUT_THRESHOLD) {
                if (onTimeout != null) {
                    try { onTimeout.run(); } catch (Exception ignored) {}
                }
            }
        }, TIMEOUT_THRESHOLD, 5000, TimeUnit.MILLISECONDS);
    }

    public void received() {
        lastReceived = System.currentTimeMillis();
    }

    public void stop() {
        if (sendTask != null) { sendTask.cancel(false); sendTask = null; }
        if (checkTask != null) { checkTask.cancel(false); checkTask = null; }
    }
}
