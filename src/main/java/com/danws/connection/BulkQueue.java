package com.danws.connection;

import com.danws.protocol.Codec;
import com.danws.protocol.Frame;
import com.danws.protocol.FrameType;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Batches frames every 100ms. Deduplicates ServerValue frames by keyId (keeps latest).
 */
public class BulkQueue {

    private static final long FLUSH_INTERVAL_MS = 100;
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "danws-bulk");
        t.setDaemon(true);
        return t;
    });

    private final List<Frame> queue = new ArrayList<>();
    private final Map<Integer, Integer> valueDedupIndex = new HashMap<>(); // keyId → index in queue
    private Consumer<byte[]> onFlush;
    private ScheduledFuture<?> flushTask;
    private boolean disposed;

    public synchronized void onFlush(Consumer<byte[]> fn) {
        this.onFlush = fn;
        if (flushTask == null && !disposed) {
            flushTask = SCHEDULER.scheduleAtFixedRate(this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void enqueue(Frame frame) {
        if (disposed) return;

        // Reset dedup index on ServerReset (values before reset are invalidated)
        if (frame.frameType() == FrameType.SERVER_RESET) {
            valueDedupIndex.clear();
        }

        // Dedup: ServerValue for same keyId → replace previous (within same sync cycle)
        if (frame.frameType() == FrameType.SERVER_VALUE) {
            Integer existingIdx = valueDedupIndex.get(frame.keyId());
            if (existingIdx != null && existingIdx < queue.size()) {
                queue.set(existingIdx, frame);
                return;
            }
            valueDedupIndex.put(frame.keyId(), queue.size());
        }

        queue.add(frame);
    }

    private synchronized void flush() {
        if (queue.isEmpty() || onFlush == null) return;

        List<Frame> batch = new ArrayList<>(queue);
        queue.clear();
        valueDedupIndex.clear();

        byte[] encoded = Codec.encodeBatch(batch);
        try {
            onFlush.accept(encoded);
        } catch (Exception ignored) {}
    }

    public synchronized void clear() {
        queue.clear();
        valueDedupIndex.clear();
    }

    public synchronized void dispose() {
        disposed = true;
        queue.clear();
        valueDedupIndex.clear();
        if (flushTask != null) {
            flushTask.cancel(false);
            flushTask = null;
        }
    }
}
