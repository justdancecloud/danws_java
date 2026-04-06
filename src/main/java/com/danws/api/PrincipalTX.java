package com.danws.api;

import com.danws.protocol.*;
import com.danws.state.KeyRegistry;
import com.danws.state.KeyRegistry.KeyEntry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class PrincipalTX {
    private final String name;
    private final KeyRegistry registry = new KeyRegistry();
    private final Map<Integer, Object> store = new ConcurrentHashMap<>();
    private Consumer<Frame> onValueSet;
    private Runnable onKeysChanged;
    private TriConsumer<Frame, Frame, Frame> onIncrementalKey;
    private final Map<String, Set<String>> flattenedKeys = new HashMap<>();

    @FunctionalInterface
    interface TriConsumer<A, B, C> { void accept(A a, B b, C c); }

    public PrincipalTX(String name) {
        this.name = name;
    }

    public String name() { return name; }

    void setOnValue(Consumer<Frame> fn) { this.onValueSet = fn; }
    void setOnResync(Runnable fn) { this.onKeysChanged = fn; }
    void setOnIncremental(TriConsumer<Frame, Frame, Frame> fn) { this.onIncrementalKey = fn; }

    public void set(String key, Object value) {
        if (Flatten.shouldFlatten(value)) {
            Map<String, Object> flattened = Flatten.flatten(key, value);
            Set<String> newKeys = flattened.keySet();
            Set<String> oldKeys = flattenedKeys.get(key);
            if (oldKeys != null) {
                for (String oldPath : oldKeys) {
                    if (!newKeys.contains(oldPath)) clearLeaf(oldPath);
                }
            }
            flattenedKeys.put(key, new HashSet<>(newKeys));
            for (var entry : flattened.entrySet()) {
                setLeaf(entry.getKey(), entry.getValue());
            }
            return;
        }
        setLeaf(key, value);
    }

    private void clearLeaf(String path) {
        KeyEntry entry = registry.getByPath(path);
        if (entry != null) {
            registry.remove(path);
            store.remove(entry.keyId());
            triggerResync();
        }
    }

    private void setLeaf(String key, Object value) {
        KeyRegistry.validateKeyPath(key);
        DataType newType = DataType.detect(value);
        Serializer.serialize(newType, value);

        KeyEntry existing = registry.getByPath(key);

        if (existing == null) {
            int keyId = registry.registerNew(key, newType);
            store.put(keyId, value);
            if (onIncrementalKey != null) {
                onIncrementalKey.accept(
                    Frame.keyRegistration(keyId, newType, key),
                    Frame.signal(FrameType.SERVER_SYNC),
                    Frame.value(keyId, newType, value)
                );
            } else {
                triggerResync();
            }
            return;
        }

        if (existing.type() != newType) {
            // Type changed — re-register
            registry.remove(key);
            int keyId = registry.registerNew(key, newType);
            store.remove(existing.keyId());
            store.put(keyId, value);
            triggerResync();
            return;
        }

        store.put(existing.keyId(), value);
        if (onValueSet != null) {
            onValueSet.accept(Frame.value(existing.keyId(), existing.type(), value));
        }
    }

    public Object get(String key) {
        KeyEntry entry = registry.getByPath(key);
        if (entry == null) return null;
        return store.get(entry.keyId());
    }

    public List<String> keys() {
        return registry.paths();
    }

    public void clear(String key) {
        Set<String> flatKeys = flattenedKeys.get(key);
        if (flatKeys != null) {
            for (String path : flatKeys) clearLeaf(path);
            flattenedKeys.remove(key);
            triggerResync();
        } else {
            KeyEntry entry = registry.getByPath(key);
            if (entry != null) {
                registry.remove(key);
                store.remove(entry.keyId());
                triggerResync();
            }
        }
    }

    public void clear() {
        if (registry.size() > 0) {
            registry.clear();
            store.clear();
            flattenedKeys.clear();
            triggerResync();
        }
    }

    List<Frame> buildKeyFrames() {
        List<Frame> frames = new ArrayList<>();
        for (KeyEntry entry : registry.entries()) {
            frames.add(Frame.keyRegistration(entry.keyId(), entry.type(), entry.path()));
        }
        // Always include ServerSync so client transitions from synchronizing to ready
        frames.add(Frame.signal(FrameType.SERVER_SYNC));
        return frames;
    }

    List<Frame> buildValueFrames() {
        List<Frame> frames = new ArrayList<>();
        for (KeyEntry entry : registry.entries()) {
            Object val = store.get(entry.keyId());
            if (val != null) {
                frames.add(Frame.value(entry.keyId(), entry.type(), val));
            }
        }
        return frames;
    }

    private void triggerResync() {
        if (onKeysChanged != null) onKeysChanged.run();
    }
}
