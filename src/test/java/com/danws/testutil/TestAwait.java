package com.danws.testutil;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Polling-based awaits for tests. Prefer this over {@code Thread.sleep(N)}:
 * sleep-based tests set a fixed budget regardless of how fast (or slow) the
 * actual work is, so they either slow suites down or flake on slow CI.
 * <p>
 * Example:
 * <pre>{@code
 * TestAwait.until(() -> client.get("greeting") != null);
 * TestAwait.until(() -> messages.size() == 3, 2_000);
 * }</pre>
 */
public final class TestAwait {
    private TestAwait() {}

    /** Default polling interval in milliseconds. */
    public static final long POLL_INTERVAL_MS = 10;
    /** Default timeout in milliseconds. */
    public static final long DEFAULT_TIMEOUT_MS = 5_000;

    /** Block until {@code condition} returns true or the default timeout elapses. */
    public static void until(BooleanSupplier condition) {
        until(condition, DEFAULT_TIMEOUT_MS);
    }

    /** Block until {@code condition} returns true or {@code timeoutMs} elapses. */
    public static void until(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            try {
                if (condition.getAsBoolean()) return;
            } catch (Throwable ignored) {
                // Treat exception in condition as "not yet" — let timeout handle it.
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeoutMs + "ms");
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for condition", e);
            }
        }
    }

    /** Block until a {@link CountDownLatch} reaches zero or the timeout elapses. */
    public static void latch(CountDownLatch latch, long timeoutMs) throws TimeoutException {
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Latch not released within " + timeoutMs + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for latch", e);
        }
    }
}
