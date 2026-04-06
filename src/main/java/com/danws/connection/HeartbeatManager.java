package com.danws.connection;

import com.danws.protocol.Codec;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Bidirectional heartbeat on Netty EventLoop: sends every 10s, times out if no receive in 15s.
 * No synchronized/volatile needed — all operations run on the same EventLoop thread.
 */
public class HeartbeatManager {

    private static final long SEND_INTERVAL = 10_000;
    private static final long TIMEOUT_THRESHOLD = 15_000;

    private final EventLoop eventLoop;
    private Consumer<byte[]> onSend;
    private Runnable onTimeout;
    private long lastReceived;
    private ScheduledFuture<?> sendTask;
    private ScheduledFuture<?> checkTask;

    public HeartbeatManager(EventLoop eventLoop) {
        this.eventLoop = eventLoop;
    }

    public void onSend(Consumer<byte[]> fn) { this.onSend = fn; }
    public void onTimeout(Runnable fn) { this.onTimeout = fn; }

    public void start() {
        if (eventLoop.inEventLoop()) {
            doStart();
        } else {
            eventLoop.execute(this::doStart);
        }
    }

    private void doStart() {
        doStop();
        lastReceived = System.currentTimeMillis();

        sendTask = eventLoop.scheduleAtFixedRate(() -> {
            if (onSend != null) {
                try { onSend.accept(Codec.encodeHeartbeat()); } catch (Exception ignored) {}
            }
        }, SEND_INTERVAL, SEND_INTERVAL, TimeUnit.MILLISECONDS);

        checkTask = eventLoop.scheduleAtFixedRate(() -> {
            if (System.currentTimeMillis() - lastReceived > TIMEOUT_THRESHOLD) {
                if (onTimeout != null) {
                    try { onTimeout.run(); } catch (Exception ignored) {}
                }
            }
        }, TIMEOUT_THRESHOLD, 5000, TimeUnit.MILLISECONDS);
    }

    public void received() {
        if (eventLoop.inEventLoop()) {
            lastReceived = System.currentTimeMillis();
        } else {
            eventLoop.execute(() -> lastReceived = System.currentTimeMillis());
        }
    }

    public void stop() {
        if (eventLoop.inEventLoop()) {
            doStop();
        } else {
            eventLoop.execute(this::doStop);
        }
    }

    private void doStop() {
        if (sendTask != null) { sendTask.cancel(false); sendTask = null; }
        if (checkTask != null) { checkTask.cancel(false); checkTask = null; }
    }
}
