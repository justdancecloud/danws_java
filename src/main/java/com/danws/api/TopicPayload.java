package com.danws.api;

import com.danws.protocol.*;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
    private Consumer<Frame<?>> enqueue;
    private Runnable onResync;
    private final Map<String, Set<String>> flattenedKeys = new HashMap<>();
    private final Map<String, String> wirePathCache = new HashMap<>();
    private final Map<String, List<Object>> previousArrays = new HashMap<>();
    private final int maxValueSize;

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final ThreadLocal<List<Runnable>> deferredActions = ThreadLocal.withInitial(ArrayList::new);

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

    void bind(Consumer<Frame<?>> enqueue, Runnable onResync) {
        this.enqueue = enqueue;
        this.onResync = onResync;
    }

    public void set(String key, Object value) {
        List<Runnable> deferred = deferredActions.get();
        deferred.clear();
        rwLock.writeLock().lock();
        try {
            FlatStateHelper.set(key, value, flattenedKeys, previousArrays,
                    this::buildShiftContext, this::setLeafDirect, this::deleteFlatKey, true);
        } finally {
            rwLock.writeLock().unlock();
        }
        for (Runnable action : deferred) {
            action.run();
        }
        deferred.clear();
    }

    private void deleteFlatKey(String path) {
        // Called while write lock is held
        List<Runnable> deferred = deferredActions.get();
        Entry removed = entries.remove(path);
        if (removed != null) {
            keyIdToPath.remove(removed.keyId());
            freeKeyId.accept(removed.keyId());
            Consumer<Frame<?>> enq = enqueue;
            if (enq != null) {
                Frame<?> deleteFrame = Frame.keyDelete(removed.keyId());
                deferred.add(() -> enq.accept(deleteFrame));
            }
        }
    }

    private ArrayDiffUtil.ShiftContext buildShiftContext() {
        // Called while write lock is held
        List<Runnable> deferred = deferredActions.get();
        return new ArrayDiffUtil.ShiftContext() {
            public int getKeyId(String key) { var e = entries.get(key); return e != null ? e.keyId() : -1; }
            public DataType getType(String key) { var e = entries.get(key); return e != null ? e.type() : null; }
            public void setStoreValue(String key, Object value) {
                var e = entries.get(key);
                if (e != null) entries.put(key, new Entry(e.keyId(), DataType.detect(value), value));
            }
            public void setLeaf(String key, Object value) { setLeafDirect(key, value); }
            public void enqueue(Frame<?> frame) {
                Consumer<Frame<?>> enq = TopicPayload.this.enqueue;
                if (enq != null) deferred.add(() -> enq.accept(frame));
            }
            public void setFlattenedKeys(String key, Set<String> keys) { flattenedKeys.put(key, keys); }
            public void setPreviousArray(String key, List<Object> arr) { previousArrays.put(key, arr); }
        };
    }

    private void setLeafDirect(String key, Object value) {
        // Called while write lock is held
        List<Runnable> deferred = deferredActions.get();
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
            Consumer<Frame<?>> enq = enqueue;
            if (enq != null) {
                String wirePath = wirePathCache.computeIfAbsent(key, k -> "t." + index + "." + k);
                Frame<?> regFrame = Frame.keyRegistration(keyId, newType, wirePath);
                Frame<?> syncFrame = Frame.signal(FrameType.SERVER_SYNC);
                Frame<?> valFrame = Frame.value(keyId, newType, value);
                deferred.add(() -> {
                    enq.accept(regFrame);
                    enq.accept(syncFrame);
                    enq.accept(valFrame);
                });
            }
            return;
        }

        if (existing.type() != newType) {
            // Type changed — delete old + re-register
            keyIdToPath.remove(existing.keyId());
            freeKeyId.accept(existing.keyId());
            Consumer<Frame<?>> enq = enqueue;
            if (enq != null) {
                Frame<?> deleteFrame = Frame.keyDelete(existing.keyId());
                deferred.add(() -> enq.accept(deleteFrame));
            }
            int keyId = allocateKeyId.getAsInt();
            entries.put(key, new Entry(keyId, newType, value));
            keyIdToPath.put(keyId, key);
            if (enq != null) {
                String wirePath = wirePathCache.computeIfAbsent(key, k -> "t." + index + "." + k);
                Frame<?> regFrame = Frame.keyRegistration(keyId, newType, wirePath);
                Frame<?> syncFrame = Frame.signal(FrameType.SERVER_SYNC);
                Frame<?> valFrame = Frame.value(keyId, newType, value);
                deferred.add(() -> {
                    enq.accept(regFrame);
                    enq.accept(syncFrame);
                    enq.accept(valFrame);
                });
            }
            return;
        }

        if (Objects.equals(existing.value(), value)) return;

        entries.put(key, new Entry(existing.keyId(), existing.type(), value));
        Consumer<Frame<?>> enq = enqueue;
        if (enq != null) {
            Frame<?> valFrame = Frame.value(existing.keyId(), existing.type(), value);
            deferred.add(() -> enq.accept(valFrame));
        }
    }

    /** Create a ring-buffer backed array for efficient sliding-window sync. */
    public ArraySync array(String key, int capacity) {
        return new ArraySync(key, capacity, this::set);
    }

    public Object get(String key) {
        rwLock.readLock().lock();
        try {
            Entry e = entries.get(key);
            return e != null ? DeepCopy.copy(e.value()) : null;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public List<String> keys() {
        rwLock.readLock().lock();
        try {
            return new ArrayList<>(entries.keySet());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void clear(String key) {
        List<Runnable> deferred = deferredActions.get();
        deferred.clear();
        rwLock.writeLock().lock();
        try {
            Set<String> flatKeys = flattenedKeys.get(key);
            if (flatKeys != null) {
                for (String path : flatKeys) {
                    Entry e = entries.remove(path);
                    wirePathCache.remove(path);
                    if (e != null) {
                        keyIdToPath.remove(e.keyId());
                        freeKeyId.accept(e.keyId());
                        Consumer<Frame<?>> enq = enqueue;
                        if (enq != null) {
                            Frame<?> deleteFrame = Frame.keyDelete(e.keyId());
                            deferred.add(() -> enq.accept(deleteFrame));
                        }
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
                    Consumer<Frame<?>> enq = enqueue;
                    if (enq != null) {
                        Frame<?> deleteFrame = Frame.keyDelete(e.keyId());
                        deferred.add(() -> enq.accept(deleteFrame));
                    }
                }
            }
        } finally {
            rwLock.writeLock().unlock();
        }
        for (Runnable action : deferred) {
            action.run();
        }
        deferred.clear();
    }

    public void clear() {
        Runnable resyncAction = null;
        rwLock.writeLock().lock();
        try {
            if (!entries.isEmpty()) {
                for (Entry e : entries.values()) {
                    freeKeyId.accept(e.keyId());
                }
                entries.clear();
                keyIdToPath.clear();
                flattenedKeys.clear();
                wirePathCache.clear();
                previousArrays.clear();
                Runnable cb = onResync;
                if (cb != null) {
                    resyncAction = cb;
                }
            }
        } finally {
            rwLock.writeLock().unlock();
        }
        if (resyncAction != null) {
            resyncAction.run();
        }
    }

    List<Frame<?>> buildKeyFrames() {
        rwLock.readLock().lock();
        try {
            List<Frame<?>> frames = new ArrayList<>();
            for (var e : entries.entrySet()) {
                String wirePath = resolveWirePath(e.getKey());
                frames.add(Frame.keyRegistration(e.getValue().keyId(), e.getValue().type(), wirePath));
            }
            return frames;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    List<Frame<?>> buildValueFrames() {
        rwLock.readLock().lock();
        try {
            List<Frame<?>> frames = new ArrayList<>();
            for (var e : entries.values()) {
                if (e.value() != null) {
                    frames.add(Frame.value(e.keyId(), e.type(), e.value()));
                }
            }
            return frames;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** Single-pass build of both key and value frames, avoiding double iteration. */
    record AllFrames(List<Frame<?>> keyFrames, List<Frame<?>> valueFrames) {}

    AllFrames buildAllFrames() {
        rwLock.readLock().lock();
        try {
            List<Frame<?>> keyFrames = new ArrayList<>();
            List<Frame<?>> valueFrames = new ArrayList<>();
            for (var e : entries.entrySet()) {
                String wirePath = resolveWirePath(e.getKey());
                keyFrames.add(Frame.keyRegistration(e.getValue().keyId(), e.getValue().type(), wirePath));
                if (e.getValue().value() != null) {
                    valueFrames.add(Frame.value(e.getValue().keyId(), e.getValue().type(), e.getValue().value()));
                }
            }
            return new AllFrames(keyFrames, valueFrames);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * O(1) lookup: returns key registration + value frames for a specific keyId,
     * or null if not found. Uses the keyIdToPath reverse index.
     */
    Frame<?>[] getFramesByKeyId(int keyId) {
        rwLock.readLock().lock();
        try {
            String path = keyIdToPath.get(keyId);
            if (path == null) return null;
            Entry e = entries.get(path);
            if (e == null) return null;
            String wirePath = resolveWirePath(path);
            Frame<?> keyFrame = Frame.keyRegistration(e.keyId(), e.type(), wirePath);
            Frame<?> valueFrame = e.value() != null
                    ? Frame.value(e.keyId(), e.type(), e.value())
                    : null;
            return new Frame<?>[]{ keyFrame, valueFrame };
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Resolve the wire path for a key. Uses the cache if available,
     * otherwise computes it without mutating the cache (safe under read lock).
     * The cache is populated during write operations (setLeafDirect).
     */
    private String resolveWirePath(String key) {
        String cached = wirePathCache.get(key);
        return cached != null ? cached : "t." + index + "." + key;
    }

    int index() { return index; }
}
