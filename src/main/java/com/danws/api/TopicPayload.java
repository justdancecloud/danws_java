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
    private final Map<String, String> wirePathCache = new HashMap<>();
    private final Map<String, List<Object>> previousArrays = new HashMap<>();

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
            // Array shift detection for List values
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
            boolean needsResync = false;
            if (oldKeys != null) {
                for (String oldPath : oldKeys) {
                    if (!newKeys.contains(oldPath)) {
                        if (ArrayDiffUtil.isArrayIndexKey(key, oldPath)) continue; // stale array index — client uses .length
                        entries.remove(oldPath);
                        needsResync = true;
                    }
                }
            }
            flattenedKeys.put(key, new HashSet<>(newKeys));
            for (var entry : flattened.entrySet()) {
                if (setLeafInternal(entry.getKey(), entry.getValue())) needsResync = true;
            }
            if (needsResync && onResync != null) onResync.run();
            return;
        }
        setLeafDirect(key, value);
    }

    private ArrayDiffUtil.ShiftContext buildShiftContext() {
        return new ArrayDiffUtil.ShiftContext() {
            public int getKeyId(String key) { var e = entries.get(key); return e != null ? e.keyId() : -1; }
            public DataType getType(String key) { var e = entries.get(key); return e != null ? e.type() : null; }
            public void setStoreValue(String key, Object value) {
                var e = entries.get(key);
                if (e != null) entries.put(key, new Entry(e.keyId(), DataType.detect(value), value));
            }
            public void setLeaf(String key, Object value) { setLeafDirect(key, value); }
            public void enqueue(Frame frame) { if (TopicPayload.this.enqueue != null) TopicPayload.this.enqueue.accept(frame); }
            public void setFlattenedKeys(String key, Set<String> keys) { flattenedKeys.put(key, keys); }
            public void setPreviousArray(String key, List<Object> arr) { previousArrays.put(key, arr); }
        };
    }

    /** Set leaf, return true if resync needed. */
    private boolean setLeafInternal(String key, Object value) {
        com.danws.state.KeyRegistry.validateKeyPath(key);
        DataType newType = DataType.detect(value);
        Serializer.serialize(newType, value);

        Entry existing = entries.get(key);

        if (existing == null) {
            int keyId = allocateKeyId.getAsInt();
            entries.put(key, new Entry(keyId, newType, value));
            if (enqueue != null) {
                String wirePath = wirePathCache.computeIfAbsent(key, k -> "t." + index + "." + k);
                enqueue.accept(Frame.keyRegistration(keyId, newType, wirePath));
                enqueue.accept(Frame.signal(FrameType.SERVER_SYNC));
                enqueue.accept(Frame.value(keyId, newType, value));
            }
            return false;  // no resync needed — sent incrementally
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
            int keyId = allocateKeyId.getAsInt();
            entries.put(key, new Entry(keyId, newType, value));
            if (enqueue != null) {
                String wirePath = wirePathCache.computeIfAbsent(key, k -> "t." + index + "." + k);
                enqueue.accept(Frame.keyRegistration(keyId, newType, wirePath));
                enqueue.accept(Frame.signal(FrameType.SERVER_SYNC));
                enqueue.accept(Frame.value(keyId, newType, value));
            }
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

    /** Create a ring-buffer backed array for efficient sliding-window sync. */
    public ArraySync array(String key, int capacity) {
        return new ArraySync(key, capacity, this::set);
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
            for (String path : flatKeys) {
                entries.remove(path);
                wirePathCache.remove(path);
            }
            flattenedKeys.remove(key);
            previousArrays.remove(key);
            if (onResync != null) onResync.run();
        } else if (entries.remove(key) != null) {
            wirePathCache.remove(key);
            previousArrays.remove(key);
            if (onResync != null) onResync.run();
        }
    }

    public void clear() {
        if (!entries.isEmpty()) {
            entries.clear();
            flattenedKeys.clear();
            wirePathCache.clear();
            previousArrays.clear();
            if (onResync != null) onResync.run();
        }
    }

    List<Frame> buildKeyFrames() {
        List<Frame> frames = new ArrayList<>();
        for (var e : entries.entrySet()) {
            String wirePath = wirePathCache.computeIfAbsent(e.getKey(), k -> "t." + index + "." + k);
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
