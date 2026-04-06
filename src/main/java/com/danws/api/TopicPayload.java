package com.danws.api;

import com.danws.protocol.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

public class TopicPayload {

    private record Entry(int keyId, DataType type, Object value) {}

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final int index;
    private final IntSupplier allocateKeyId;
    private Consumer<Frame> enqueue;
    private Runnable onResync;

    public TopicPayload(int index, IntSupplier allocateKeyId) {
        this.index = index;
        this.allocateKeyId = allocateKeyId;
    }

    void bind(Consumer<Frame> enqueue, Runnable onResync) {
        this.enqueue = enqueue;
        this.onResync = onResync;
    }

    public void set(String key, Object value) {
        com.danws.state.KeyRegistry.validateKeyPath(key);
        DataType newType = DataType.detect(value);
        Serializer.serialize(newType, value);

        Entry existing = entries.get(key);

        if (existing == null) {
            entries.put(key, new Entry(allocateKeyId.getAsInt(), newType, value));
            if (onResync != null) onResync.run();
            return;
        }

        if (existing.type() != newType) {
            entries.put(key, new Entry(existing.keyId(), newType, value));
            if (onResync != null) onResync.run();
            return;
        }

        if (Objects.equals(existing.value(), value)) return;

        entries.put(key, new Entry(existing.keyId(), existing.type(), value));
        if (enqueue != null) {
            enqueue.accept(Frame.value(existing.keyId(), existing.type(), value));
        }
    }

    public Object get(String key) {
        Entry e = entries.get(key);
        return e != null ? e.value() : null;
    }

    public List<String> keys() {
        return new ArrayList<>(entries.keySet());
    }

    public void clear(String key) {
        if (entries.remove(key) != null && onResync != null) onResync.run();
    }

    public void clear() {
        if (!entries.isEmpty()) {
            entries.clear();
            if (onResync != null) onResync.run();
        }
    }

    List<Frame> buildKeyFrames() {
        List<Frame> frames = new ArrayList<>();
        for (var e : entries.entrySet()) {
            String wirePath = "t." + index + "." + e.getKey();
            frames.add(Frame.keyRegistration(e.getValue().keyId(), e.getValue().type(), wirePath));
        }
        return frames;
    }

    List<Frame> buildValueFrames() {
        List<Frame> frames = new ArrayList<>();
        for (var e : entries.values()) {
            if (e.value() != null) {
                frames.add(Frame.value(e.keyId(), e.type(), e.value()));
            }
        }
        return frames;
    }

    int index() { return index; }
}
