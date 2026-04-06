package com.danws.connection;

import com.danws.protocol.Codec;
import com.danws.protocol.Frame;
import com.danws.protocol.FrameType;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Batches frames every 100ms on the Netty EventLoop.
 * No synchronized blocks needed — all operations run on the same EventLoop thread.
 * Deduplicates ServerValue frames by keyId (keeps latest).
 */
public class BulkQueue {

    private static final long FLUSH_INTERVAL_MS = 100;

    private final EventLoop eventLoop;
    private final List<Frame> queue = new ArrayList<>();
    private final Map<Integer, Integer> valueDedupIndex = new HashMap<>();
    private Consumer<byte[]> onFlush;
    private ScheduledFuture<?> flushTask;
    private boolean disposed;

    public BulkQueue(EventLoop eventLoop) {
        this.eventLoop = eventLoop;
    }

    public void onFlush(Consumer<byte[]> fn) {
        if (eventLoop.inEventLoop()) {
            doOnFlush(fn);
        } else {
            eventLoop.execute(() -> doOnFlush(fn));
        }
    }

    private void doOnFlush(Consumer<byte[]> fn) {
        this.onFlush = fn;
        if (flushTask == null && !disposed) {
            flushTask = eventLoop.scheduleAtFixedRate(this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    public void enqueue(Frame frame) {
        if (eventLoop.inEventLoop()) {
            doEnqueue(frame);
        } else {
            eventLoop.execute(() -> doEnqueue(frame));
        }
    }

    private void doEnqueue(Frame frame) {
        if (disposed) return;

        if (frame.frameType() == FrameType.SERVER_RESET) {
            valueDedupIndex.clear();
        }

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

    private void flush() {
        if (queue.isEmpty() || onFlush == null) return;

        List<Frame> batch = new ArrayList<>(queue);
        queue.clear();
        valueDedupIndex.clear();

        byte[] encoded = Codec.encodeBatch(batch);
        try {
            onFlush.accept(encoded);
        } catch (Exception ignored) {}
    }

    public void clear() {
        if (eventLoop.inEventLoop()) {
            doClear();
        } else {
            eventLoop.execute(this::doClear);
        }
    }

    private void doClear() {
        queue.clear();
        valueDedupIndex.clear();
    }

    public void dispose() {
        if (eventLoop.inEventLoop()) {
            doDispose();
        } else {
            eventLoop.execute(this::doDispose);
        }
    }

    private void doDispose() {
        disposed = true;
        queue.clear();
        valueDedupIndex.clear();
        if (flushTask != null) {
            flushTask.cancel(false);
            flushTask = null;
        }
    }
}
