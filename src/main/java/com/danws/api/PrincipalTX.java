package com.danws.api;

import com.danws.protocol.*;
import com.danws.state.KeyRegistry;
import com.danws.state.KeyRegistry.KeyEntry;

import java.util.*;
import java.util.function.Consumer;

public class PrincipalTX {
    private final String name;
    private final KeyRegistry registry = new KeyRegistry();
    private final Map<Integer, Object> store = new HashMap<>();
    private Consumer<Frame> onValueSet;
    private Runnable onKeysChanged;
    private TriConsumer<Frame, Frame, Frame> onIncrementalKey;
    private final Map<String, Set<String>> flattenedKeys = new HashMap<>();
    private List<Frame> cachedKeyFrames = null;
    private final Map<String, List<Object>> previousArrays = new HashMap<>();

    @FunctionalInterface
    interface TriConsumer<A, B, C> { void accept(A a, B b, C c); }

    private final int maxValueSize;

    public PrincipalTX(String name) {
        this(name, 65_536);
    }

    public PrincipalTX(String name, int maxValueSize) {
        this.name = name;
        this.maxValueSize = maxValueSize;
    }

    public String name() { return name; }

    void setOnValue(Consumer<Frame> fn) { this.onValueSet = fn; }
    void setOnResync(Runnable fn) { this.onKeysChanged = fn; }
    void setOnIncremental(TriConsumer<Frame, Frame, Frame> fn) { this.onIncrementalKey = fn; }

    public void set(String key, Object value) {
        if (Flatten.shouldFlatten(value)) {
            if (value instanceof List<?> newArr) {
                List<Object> oldArr = previousArrays.get(key);
                if (oldArr != null && !oldArr.isEmpty() && !newArr.isEmpty() && ArrayDiffUtil.isPrimitiveArray(newArr)) {
                    int[] shift = ArrayDiffUtil.detectShift(oldArr, newArr);
                    if (shift[0] != 0) {
                        var ctx = buildShiftContext();
                        if (shift[0] == 1) { ArrayDiffUtil.applyShiftLeft(ctx, key, oldArr, newArr, shift[1]); return; }
                        if (shift[0] == 2) { ArrayDiffUtil.applyShiftRight(ctx, key, oldArr, newArr, shift[1]); return; }
                    }
                }
                previousArrays.put(key, new ArrayList<>(newArr));
            }

            Map<String, Object> flattened = Flatten.flatten(key, value);
            Set<String> newKeys = flattened.keySet();
            Set<String> oldKeys = flattenedKeys.get(key);
            if (oldKeys != null) {
                for (String oldPath : oldKeys) {
                    if (!newKeys.contains(oldPath)) {
                        if (!ArrayDiffUtil.isArrayIndexKey(key, oldPath)) clearLeaf(oldPath);
                    }
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

    private ArrayDiffUtil.ShiftContext buildShiftContext() {
        return new ArrayDiffUtil.ShiftContext() {
            public int getKeyId(String key) { var e = registry.getByPath(key); return e != null ? e.keyId() : -1; }
            public DataType getType(String key) { var e = registry.getByPath(key); return e != null ? e.type() : null; }
            public void setStoreValue(String key, Object value) { var e = registry.getByPath(key); if (e != null) store.put(e.keyId(), value); }
            public void setLeaf(String key, Object value) { PrincipalTX.this.setLeaf(key, value); }
            public void enqueue(Frame frame) { if (onValueSet != null) onValueSet.accept(frame); }
            public void setFlattenedKeys(String key, Set<String> keys) { flattenedKeys.put(key, keys); }
            public void setPreviousArray(String key, List<Object> arr) { previousArrays.put(key, arr); }
        };
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
        byte[] serialized = Serializer.serialize(newType, value);
        if (maxValueSize > 0 && serialized.length > maxValueSize) {
            throw new DanWSException("VALUE_TOO_LARGE", "Serialized value for \"" + key + "\" is " + serialized.length + " bytes, exceeds maxValueSize (" + maxValueSize + ")");
        }

        KeyEntry existing = registry.getByPath(key);

        if (existing == null) {
            int keyId = registry.registerNew(key, newType);
            store.put(keyId, value);
            cachedKeyFrames = null;
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

        if (Objects.equals(store.get(existing.keyId()), value)) return;

        store.put(existing.keyId(), value);
        if (onValueSet != null) {
            onValueSet.accept(Frame.value(existing.keyId(), existing.type(), value));
        }
    }

    /** Create a ring-buffer backed array for efficient sliding-window sync. */
    public ArraySync array(String key, int capacity) {
        return new ArraySync(key, capacity, this::set);
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
            previousArrays.remove(key);
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
            previousArrays.clear();
            triggerResync();
        }
    }

    List<Frame> buildKeyFrames() {
        if (cachedKeyFrames != null) return cachedKeyFrames;
        List<Frame> frames = new ArrayList<>();
        for (KeyEntry entry : registry.entries()) {
            frames.add(Frame.keyRegistration(entry.keyId(), entry.type(), entry.path()));
        }
        // Always include ServerSync so client transitions from synchronizing to ready
        frames.add(Frame.signal(FrameType.SERVER_SYNC));
        cachedKeyFrames = frames;
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
        cachedKeyFrames = null;
        if (onKeysChanged != null) onKeysChanged.run();
    }
}
