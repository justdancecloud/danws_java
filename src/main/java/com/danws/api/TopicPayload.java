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
    private final Map<String, Set<String>> flattenedKeys = new HashMap<>();

    public TopicPayload(int index, IntSupplier allocateKeyId) {
        this.index = index;
        this.allocateKeyId = allocateKeyId;
    }

    void bind(Consumer<Frame> enqueue, Runnable onResync) {
        this.enqueue = enqueue;
        this.onResync = onResync;
    }

    public void set(String key, Object value) {
        if (Flatten.shouldFlatten(value)) {
            Map<String, Object> flattened = Flatten.flatten(key, value);
            Set<String> newKeys = flattened.keySet();
            Set<String> oldKeys = flattenedKeys.get(key);
            boolean deleted = false;
            if (oldKeys != null) {
                for (String oldPath : oldKeys) {
                    if (!newKeys.contains(oldPath)) {
                        entries.remove(oldPath);
                        deleted = true;
                    }
                }
            }
            flattenedKeys.put(key, new HashSet<>(newKeys));
            boolean needsResync = deleted;
            for (var entry : flattened.entrySet()) {
                if (setLeafInternal(entry.getKey(), entry.getValue())) needsResync = true;
            }
            if (needsResync && onResync != null) onResync.run();
            return;
        }
        setLeafDirect(key, value);
    }

    /** Set leaf, return true if resync needed. */
    private boolean setLeafInternal(String key, Object value) {
        com.danws.state.KeyRegistry.validateKeyPath(key);
        DataType newType = DataType.detect(value);
        Serializer.serialize(newType, value);

        Entry existing = entries.get(key);

        if (existing == null) {
            entries.put(key, new Entry(allocateKeyId.getAsInt(), newType, value));
            return true;
        }

        if (existing.type() != newType) {
            entries.put(key, new Entry(existing.keyId(), newType, value));
            return true;
        }

        if (Objects.equals(existing.value(), value)) return false;

        entries.put(key, new Entry(existing.keyId(), existing.type(), value));
        if (enqueue != null) {
            enqueue.accept(Frame.value(existing.keyId(), existing.type(), value));
        }
        return false;
    }

    private void setLeafDirect(String key, Object value) {
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
        Set<String> flatKeys = flattenedKeys.get(key);
        if (flatKeys != null) {
            for (String path : flatKeys) entries.remove(path);
            flattenedKeys.remove(key);
            if (onResync != null) onResync.run();
        } else if (entries.remove(key) != null && onResync != null) {
            onResync.run();
        }
    }

    public void clear() {
        if (!entries.isEmpty()) {
            entries.clear();
            flattenedKeys.clear();
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
