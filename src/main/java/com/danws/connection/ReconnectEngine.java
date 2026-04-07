package com.danws.connection;

import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Reconnection engine with exponential backoff + jitter.
 * Schedules reconnect attempts via a shared ScheduledExecutorService.
 */
public class ReconnectEngine {

    private final boolean enabled;
    private final int maxRetries;       // 0 = unlimited
    private final long baseDelay;       // ms
    private final long maxDelay;        // ms
    private final double backoffMultiplier;
    private final boolean jitter;

    private volatile int attempt;
    private volatile boolean active;
    private ScheduledFuture<?> timer;

    private Runnable onAttempt;
    private BiConsumer<Integer, Long> onReconnecting; // (attempt, delay)
    private Runnable onExhausted;

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "danws-reconnect");
                t.setDaemon(true);
                return t;
            });

    public ReconnectEngine() {
        this(true, 10, 1000, 30000, 2.0, true);
    }

    public ReconnectEngine(boolean enabled, int maxRetries, long baseDelay, long maxDelay, double backoffMultiplier, boolean jitter) {
        this.enabled = enabled;
        this.maxRetries = maxRetries;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.backoffMultiplier = backoffMultiplier;
        this.jitter = jitter;
    }

    public void onAttempt(Runnable fn) { this.onAttempt = fn; }
    public void onReconnecting(BiConsumer<Integer, Long> fn) { this.onReconnecting = fn; }
    public void onExhausted(Runnable fn) { this.onExhausted = fn; }

    public int attempt() { return attempt; }
    public boolean isActive() { return active; }

    public void start() {
        if (!enabled || active) return;
        active = true;
        attempt = 0;
        scheduleNext();
    }

    public void stop() {
        active = false;
        attempt = 0;
        if (timer != null) { timer.cancel(false); timer = null; }
    }

    public void retry() {
        if (active) scheduleNext();
    }

    private void scheduleNext() {
        attempt++;
        if (maxRetries > 0 && attempt > maxRetries) {
            active = false;
            if (onExhausted != null) { try { onExhausted.run(); } catch (Exception ignored) {} }
            return;
        }

        long delay = calculateDelay(attempt);
        if (onReconnecting != null) {
            try { onReconnecting.accept(attempt, delay); } catch (Exception ignored) {}
        }

        timer = SCHEDULER.schedule(() -> {
            timer = null;
            if (onAttempt != null && active) {
                try { onAttempt.run(); } catch (Exception ignored) {}
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    long calculateDelay(int attempt) {
        double raw = baseDelay * Math.pow(backoffMultiplier, attempt - 1);
        long capped = (long) Math.min(raw, maxDelay);
        if (jitter) {
            return (long) (capped * (0.5 + Math.random()));
        }
        return capped;
    }
}
