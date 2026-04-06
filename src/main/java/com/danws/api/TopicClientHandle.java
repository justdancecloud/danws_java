package com.danws.api;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class TopicClientHandle {

    private final String name;
    private int index = -1;

    private final DanWebSocketClient client;
    private final List<BiConsumer<String, Object>> onReceiveCbs = new ArrayList<>();
    private final List<Consumer<TopicClientPayloadView>> onUpdateCbs = new ArrayList<>();

    TopicClientHandle(String name, DanWebSocketClient client) {
        this.name = name;
        this.client = client;
    }

    public String name() { return name; }

    public Object get(String key) {
        if (index < 0) return null;
        return client.get("t." + index + "." + key);
    }

    public List<String> keys() {
        if (index < 0) return List.of();
        String prefix = "t." + index + ".";
        List<String> result = new ArrayList<>();
        for (String path : client.keys()) {
            if (path.startsWith(prefix)) {
                result.add(path.substring(prefix.length()));
            }
        }
        return result;
    }

    public void onReceive(BiConsumer<String, Object> cb) { onReceiveCbs.add(cb); }
    public void onUpdate(Consumer<TopicClientPayloadView> cb) { onUpdateCbs.add(cb); }

    void setIndex(int index) { this.index = index; }
    int index() { return index; }

    void notify(String key, Object value) {
        for (var cb : onReceiveCbs) { try { cb.accept(key, value); } catch (Exception ignored) {} }
        if (!onUpdateCbs.isEmpty()) {
            TopicClientPayloadView view = new TopicClientPayloadView(this);
            for (var cb : onUpdateCbs) { try { cb.accept(view); } catch (Exception ignored) {} }
        }
    }
}
