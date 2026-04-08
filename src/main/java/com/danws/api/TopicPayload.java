package com.danws.api;

import com.danws.protocol.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

public class TopicPayload {

    private record Entry(int keyId, DataType type, Object value) {}

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<Integer, String> keyIdToPath = new HashMap<>();
    private final int index;
    private final IntSupplier allocateKeyId;
    private final IntConsumer freeKeyId;
    private Consumer<Frame> enqueue;
    private Runnable onResync;
    private final Map<String, Set<String>> flattenedKeys = new HashMap<>();
    private final Map<String, String> wirePathCache = new HashMap<>();
    private final Map<String, List<Object>> previousArrays = new HashMap<>();
    private final int maxValueSize;

    public TopicPayload(int index, IntSupplier allocateKeyId) {
        this(index, allocateKeyId, id -> {}, 65_536);
    }

    public TopicPayload(int index, IntSupplier allocateKeyId, int maxValueSize) {
        this(index, allocateKeyId, id -> {}, maxValueSize);
    }

    public TopicPayload(int index, IntSupplier allocateKeyId, IntConsumer freeKeyId, int maxValueSize) {
        this.index = index;
        this.allocateKeyId = allocateKeyId;
        this.freeKeyId = freeKeyId;
        this.maxValueSize = maxValueSize;
    }

    void bind(Consumer<Frame> enqueue, Runnable onResync) {
        this.enqueue = enqueue;
        this.onResync = onResync;
    }

    public void set(String key, Object value) {
        FlatStateHelper.set(key, value, flattenedKeys, previousArrays,
                this::buildShiftContext, this::setLeafDirect, this::deleteFlatKey, true);
    }

    private void deleteFlatKey(String path) {
        Entry removed = entries.remove(path);
        if (removed != null) {
            keyIdToPath.remove(removed.keyId());
            freeKeyId.accept(removed.keyId());
            if (enqueue != null) enqueue.accept(Frame.keyDelete(removed.keyId()));
        }
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

    private void setLeafDirect(String key, Object value) {
        com.danws.state.KeyRegistry.validateKeyPath(key);
        DataType newType = DataType.detect(value);
        byte[] serialized = Serializer.serialize(newType, value);
        if (maxValueSize > 0 && serialized.length > maxValueSize) {
            throw new DanWSException("VALUE_TOO_LARGE", "Serialized value for \"" + key + "\" is " + serialized.length + " bytes, exceeds maxValueSize (" + maxValueSize + ")");
        }

        Entry existing = entries.get(key);

        if (existing == null) {
            int keyId = allocateKeyId.getAsInt();
            entries.put(key, new Entry(keyId, newType, value));
            keyIdToPath.put(keyId, key);
            if (enqueue != null) {
                String wirePath = wirePathCache.computeIfAbsent(key, k -> "t." + index + "." + k);
                enqueue.accept(Frame.keyRegistration(keyId, newType, wirePath));
                enqueue.accept(Frame.signal(FrameType.SERVER_SYNC));
                enqueue.accept(Frame.value(keyId, newType, value));
            }
            return;
        }

        if (existing.type() != newType) {
            // Type changed — delete old + re-register
            keyIdToPath.remove(existing.keyId());
            freeKeyId.accept(existing.keyId());
            if (enqueue != null) enqueue.accept(Frame.keyDelete(existing.keyId()));
            int keyId = allocateKeyId.getAsInt();
            entries.put(key, new Entry(keyId, newType, value));
            keyIdToPath.put(keyId, key);
            if (enqueue != null) {
                String wirePath = wirePathCache.computeIfAbsent(key, k -> "t." + index + "." + k);
                enqueue.accept(Frame.keyRegistration(keyId, newType, wirePath));
                enqueue.accept(Frame.signal(FrameType.SERVER_SYNC));
                enqueue.accept(Frame.value(keyId, newType, value));
            }
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
                Entry e = entries.remove(path);
                wirePathCache.remove(path);
                if (e != null) {
                    keyIdToPath.remove(e.keyId());
                    freeKeyId.accept(e.keyId());
                    if (enqueue != null) enqueue.accept(Frame.keyDelete(e.keyId()));
                }
            }
            flattenedKeys.remove(key);
            previousArrays.remove(key);
        } else {
            Entry e = entries.remove(key);
            if (e != null) {
                keyIdToPath.remove(e.keyId());
                wirePathCache.remove(key);
                previousArrays.remove(key);
                freeKeyId.accept(e.keyId());
                if (enqueue != null) enqueue.accept(Frame.keyDelete(e.keyId()));
            }
        }
    }

    public void clear() {
        if (!entries.isEmpty()) {
            for (Entry e : entries.values()) {
                freeKeyId.accept(e.keyId());
            }
            entries.clear();
            keyIdToPath.clear();
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

    /** Single-pass build of both key and value frames, avoiding double iteration. */
    record AllFrames(List<Frame> keyFrames, List<Frame> valueFrames) {}

    AllFrames buildAllFrames() {
        List<Frame> keyFrames = new ArrayList<>();
        List<Frame> valueFrames = new ArrayList<>();
        for (var e : entries.entrySet()) {
            String wirePath = wirePathCache.computeIfAbsent(e.getKey(), k -> "t." + index + "." + k);
            keyFrames.add(Frame.keyRegistration(e.getValue().keyId(), e.getValue().type(), wirePath));
            if (e.getValue().value() != null) {
                valueFrames.add(Frame.value(e.getValue().keyId(), e.getValue().type(), e.getValue().value()));
            }
        }
        return new AllFrames(keyFrames, valueFrames);
    }

    /**
     * O(1) lookup: returns key registration + value frames for a specific keyId,
     * or null if not found. Uses the keyIdToPath reverse index.
     */
    Frame[] getFramesByKeyId(int keyId) {
        String path = keyIdToPath.get(keyId);
        if (path == null) return null;
        Entry e = entries.get(path);
        if (e == null) return null;
        String wirePath = wirePathCache.computeIfAbsent(path, k -> "t." + index + "." + k);
        Frame keyFrame = Frame.keyRegistration(e.keyId(), e.type(), wirePath);
        Frame valueFrame = e.value() != null
                ? Frame.value(e.keyId(), e.type(), e.value())
                : null;
        return new Frame[]{ keyFrame, valueFrame };
    }

    int index() { return index; }
}
