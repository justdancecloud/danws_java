package com.danws.api;

import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class TopicHandle {

    private final String name;
    private Map<String, Object> params;
    private final TopicPayload payload;
    private final DanWebSocketSession session;

    private TopicCallback callback;
    private Timer timer;
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
        timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                if (callback != null) {
                    try { callback.accept(EventType.DELAYED_TASK, TopicHandle.this, session); } catch (Exception ignored) {}
                }
            }
        }, ms, ms);
    }

    public void clearDelayedTask() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    void updateParams(Map<String, Object> newParams) {
        this.params = newParams;
        boolean hadTimer = timer != null;
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
