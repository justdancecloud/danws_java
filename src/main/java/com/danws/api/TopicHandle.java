package com.danws.api;

import java.util.Map;
import java.util.concurrent.*;

public class TopicHandle {

    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "danws-topic-task");
        t.setDaemon(true);
        return t;
    });

    private final String name;
    private Map<String, Object> params;
    private final TopicPayload payload;
    private final DanWebSocketSession session;

    private TopicCallback callback;
    private ScheduledFuture<?> timerFuture;
    private long delayMs;

    public TopicHandle(String name, Map<String, Object> params, TopicPayload payload, DanWebSocketSession session) {
        this.name = name;
        this.params = params;
        this.payload = payload;
        this.session = session;
    }

    public String name() { return name; }
    public Map<String, Object> params() { return params; }
    public TopicPayload payload() { return payload; }

    public void setCallback(TopicCallback fn) {
        this.callback = fn;
        try { fn.accept(EventType.SUBSCRIBE, this, session); } catch (Exception ignored) {}
    }

    public void setDelayedTask(long ms) {
        clearDelayedTask();
        this.delayMs = ms;
        timerFuture = SCHEDULER.scheduleAtFixedRate(() -> {
            if (callback != null) {
                try { callback.accept(EventType.DELAYED_TASK, TopicHandle.this, session); } catch (Exception ignored) {}
            }
        }, ms, ms, TimeUnit.MILLISECONDS);
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
            try { callback.accept(EventType.CHANGED_PARAMS, this, session); } catch (Exception ignored) {}
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
