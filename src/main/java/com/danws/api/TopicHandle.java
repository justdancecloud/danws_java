package com.danws.api;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class TopicHandle {

    private final String name;
    private Map<String, Object> params;
    private final TopicPayload payload;
    private final DanWebSocketSession session;
    private final EventLoop eventLoop;
    private final BiConsumer<String, Exception> log;

    private TopicCallback callback;
    private ScheduledFuture<?> timerFuture;
    private long delayMs;

    public TopicHandle(String name, Map<String, Object> params, TopicPayload payload, DanWebSocketSession session, EventLoop eventLoop) {
        this(name, params, payload, session, eventLoop, null);
    }

    public TopicHandle(String name, Map<String, Object> params, TopicPayload payload, DanWebSocketSession session, EventLoop eventLoop, BiConsumer<String, Exception> log) {
        this.name = name;
        this.params = params;
        this.payload = payload;
        this.session = session;
        this.eventLoop = eventLoop;
        this.log = log;
    }

    public String name() { return name; }
    public Map<String, Object> params() { return params; }
    public TopicPayload payload() { return payload; }

    public void setCallback(TopicCallback fn) {
        this.callback = fn;
        try { fn.accept(EventType.SUBSCRIBE, this, session); } catch (Exception e) { if (log != null) log.accept("TopicHandle SUBSCRIBE callback error", e); }
    }

    public void setDelayedTask(long ms) {
        clearDelayedTask();
        this.delayMs = ms;
        if (eventLoop != null) {
            timerFuture = eventLoop.scheduleAtFixedRate(() -> {
                if (callback != null) {
                    try { callback.accept(EventType.DELAYED_TASK, TopicHandle.this, session); } catch (Exception e) { if (log != null) log.accept("TopicHandle DELAYED_TASK callback error", e); }
                }
            }, ms, ms, TimeUnit.MILLISECONDS);
        }
    }

    public void clearDelayedTask() {
        if (timerFuture != null) {
            timerFuture.cancel(false);
            timerFuture = null;
        }
    }

    void updateParams(Map<String, Object> newParams) {
        this.params = newParams;
        boolean hadTimer = timerFuture != null;
        long savedMs = delayMs;

        clearDelayedTask();

        if (callback != null) {
            try { callback.accept(EventType.CHANGED_PARAMS, this, session); } catch (Exception e) { if (log != null) log.accept("TopicHandle CHANGED_PARAMS callback error", e); }
        }

        if (hadTimer && savedMs > 0) {
            setDelayedTask(savedMs);
        }
    }

    void dispose() {
        clearDelayedTask();
        callback = null;
    }
}
