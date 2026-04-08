package com.danws.api;

import com.danws.protocol.*;
import com.danws.state.KeyRegistry;
import com.danws.state.KeyRegistry.KeyEntry;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
    private static final int FREED_POOL_CAP = 10_000;
    private final List<Integer> freedKeyIds = new ArrayList<>();

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    /**
     * Thread-local collector for frames/callbacks that must be dispatched
     * AFTER the write lock is released, to avoid deadlocks.
     */
    private final ThreadLocal<List<Runnable>> deferredActions = ThreadLocal.withInitial(ArrayList::new);

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

    private int allocateKeyId() {
        if (!freedKeyIds.isEmpty()) {
            return freedKeyIds.remove(freedKeyIds.size() - 1);
        }
        return registry.nextId();
    }

    public void set(String key, Object value) {
        List<Runnable> deferred = deferredActions.get();
        deferred.clear();
        rwLock.writeLock().lock();
        try {
            FlatStateHelper.set(key, value, flattenedKeys, previousArrays,
                    this::buildShiftContext, this::setLeaf, this::clearLeaf, true);
        } finally {
            rwLock.writeLock().unlock();
        }
        // Dispatch all deferred frame sends/callbacks outside the lock
        for (Runnable action : deferred) {
            action.run();
        }
        deferred.clear();
    }

    private ArrayDiffUtil.ShiftContext buildShiftContext() {
        // Called while write lock is held
        List<Runnable> deferred = deferredActions.get();
        return new ArrayDiffUtil.ShiftContext() {
            public int getKeyId(String key) { var e = registry.getByPath(key); return e != null ? e.keyId() : -1; }
            public DataType getType(String key) { var e = registry.getByPath(key); return e != null ? e.type() : null; }
            public void setStoreValue(String key, Object value) { var e = registry.getByPath(key); if (e != null) store.put(e.keyId(), value); }
            public void setLeaf(String key, Object value) { PrincipalTX.this.setLeaf(key, value); }
            public void enqueue(Frame frame) {
                // Defer the enqueue to after lock release
                Consumer<Frame> cb = onValueSet;
                if (cb != null) deferred.add(() -> cb.accept(frame));
            }
            public void setFlattenedKeys(String key, Set<String> keys) { flattenedKeys.put(key, keys); }
            public void setPreviousArray(String key, List<Object> arr) { previousArrays.put(key, arr); }
        };
    }

    private void clearLeaf(String path) {
        // Called while write lock is held
        List<Runnable> deferred = deferredActions.get();
        KeyEntry entry = registry.getByPath(path);
        if (entry != null) {
            registry.remove(path);
            store.remove(entry.keyId());
            if (freedKeyIds.size() < FREED_POOL_CAP) freedKeyIds.add(entry.keyId());
            cachedKeyFrames = null;
            Consumer<Frame> cb = onValueSet;
            if (cb != null) {
                Frame deleteFrame = Frame.keyDelete(entry.keyId());
                deferred.add(() -> cb.accept(deleteFrame));
            }
        }
    }

    private void setLeaf(String key, Object value) {
        // Called while write lock is held
        List<Runnable> deferred = deferredActions.get();
        KeyRegistry.validateKeyPath(key);
        DataType newType = DataType.detect(value);
        byte[] serialized = Serializer.serialize(newType, value);
        if (maxValueSize > 0 && serialized.length > maxValueSize) {
            throw new DanWSException("VALUE_TOO_LARGE", "Serialized value for \"" + key + "\" is " + serialized.length + " bytes, exceeds maxValueSize (" + maxValueSize + ")");
        }

        KeyEntry existing = registry.getByPath(key);

        if (existing == null) {
            int keyId = allocateKeyId();
            registry.registerOne(keyId, key, newType);
            store.put(keyId, value);
            cachedKeyFrames = null;
            TriConsumer<Frame, Frame, Frame> incCb = onIncrementalKey;
            if (incCb != null) {
                Frame regFrame = Frame.keyRegistration(keyId, newType, key);
                Frame syncFrame = Frame.signal(FrameType.SERVER_SYNC);
                Frame valFrame = Frame.value(keyId, newType, value);
                deferred.add(() -> incCb.accept(regFrame, syncFrame, valFrame));
            } else {
                // triggerResync deferred
                cachedKeyFrames = null;
                Runnable resyncCb = onKeysChanged;
                if (resyncCb != null) {
                    deferred.add(resyncCb);
                }
            }
            return;
        }

        if (existing.type() != newType) {
            // Type changed — delete old + re-register with new type
            registry.remove(key);
            store.remove(existing.keyId());
            if (freedKeyIds.size() < FREED_POOL_CAP) freedKeyIds.add(existing.keyId());
            cachedKeyFrames = null;
            Consumer<Frame> valCb = onValueSet;
            if (valCb != null) {
                Frame deleteFrame = Frame.keyDelete(existing.keyId());
                deferred.add(() -> valCb.accept(deleteFrame));
            }
            int keyId = allocateKeyId();
            registry.registerOne(keyId, key, newType);
            store.put(keyId, value);
            TriConsumer<Frame, Frame, Frame> incCb = onIncrementalKey;
            if (incCb != null) {
                Frame regFrame = Frame.keyRegistration(keyId, newType, key);
                Frame syncFrame = Frame.signal(FrameType.SERVER_SYNC);
                Frame valFrame = Frame.value(keyId, newType, value);
                deferred.add(() -> incCb.accept(regFrame, syncFrame, valFrame));
            } else {
                cachedKeyFrames = null;
                Runnable resyncCb = onKeysChanged;
                if (resyncCb != null) {
                    deferred.add(resyncCb);
                }
            }
            return;
        }

        if (Objects.equals(store.get(existing.keyId()), value)) return;

        store.put(existing.keyId(), value);
        Consumer<Frame> valCb = onValueSet;
        if (valCb != null) {
            Frame valFrame = Frame.value(existing.keyId(), existing.type(), value);
            deferred.add(() -> valCb.accept(valFrame));
        }
    }

    /** Create a ring-buffer backed array for efficient sliding-window sync. */
    public ArraySync array(String key, int capacity) {
        return new ArraySync(key, capacity, this::set);
    }

    public Object get(String key) {
        rwLock.readLock().lock();
        try {
            KeyEntry entry = registry.getByPath(key);
            if (entry == null) return null;
            Object val = store.get(entry.keyId());
            return DeepCopy.copy(val);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public List<String> keys() {
        rwLock.readLock().lock();
        try {
            return registry.paths(); // KeyRegistry.paths() already returns a new ArrayList copy
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
                for (String path : flatKeys) clearLeaf(path);
                flattenedKeys.remove(key);
                previousArrays.remove(key);
            } else {
                clearLeaf(key);
                previousArrays.remove(key);
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
        List<Runnable> deferred = deferredActions.get();
        deferred.clear();
        rwLock.writeLock().lock();
        try {
            if (registry.size() > 0) {
                for (KeyEntry entry : registry.entries()) {
                    if (freedKeyIds.size() < FREED_POOL_CAP) freedKeyIds.add(entry.keyId());
                }
                registry.clear();
                store.clear();
                flattenedKeys.clear();
                previousArrays.clear();
                cachedKeyFrames = null;
                Runnable resyncCb = onKeysChanged;
                if (resyncCb != null) {
                    deferred.add(resyncCb);
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

    List<Frame> buildKeyFrames() {
        rwLock.readLock().lock();
        try {
            if (cachedKeyFrames != null) return cachedKeyFrames;
            List<Frame> frames = new ArrayList<>();
            for (KeyEntry entry : registry.entries()) {
                frames.add(Frame.keyRegistration(entry.keyId(), entry.type(), entry.path()));
            }
            // Always include ServerSync so client transitions from synchronizing to ready
            frames.add(Frame.signal(FrameType.SERVER_SYNC));
            cachedKeyFrames = frames;
            return frames;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    List<Frame> buildValueFrames() {
        rwLock.readLock().lock();
        try {
            List<Frame> frames = new ArrayList<>();
            for (KeyEntry entry : registry.entries()) {
                Object val = store.get(entry.keyId());
                if (val != null) {
                    frames.add(Frame.value(entry.keyId(), entry.type(), val));
                }
            }
            return frames;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** Single-pass build of both key and value frames, avoiding double iteration of the registry. */
    record AllFrames(List<Frame> keyFrames, List<Frame> valueFrames) {}

    AllFrames buildAllFrames() {
        rwLock.readLock().lock();
        try {
            List<Frame> keyFrames = new ArrayList<>();
            List<Frame> valueFrames = new ArrayList<>();
            for (KeyEntry entry : registry.entries()) {
                keyFrames.add(Frame.keyRegistration(entry.keyId(), entry.type(), entry.path()));
                Object val = store.get(entry.keyId());
                if (val != null) {
                    valueFrames.add(Frame.value(entry.keyId(), entry.type(), val));
                }
            }
            // Always include ServerSync so client transitions from synchronizing to ready
            keyFrames.add(Frame.signal(FrameType.SERVER_SYNC));
            return new AllFrames(keyFrames, valueFrames);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * O(1) lookup for a specific keyId. Returns key registration + value frames,
     * or null if not found. Uses KeyRegistry's HashMap-backed getByKeyId.
     */
    Frame[] getFramesByKeyId(int keyId) {
        rwLock.readLock().lock();
        try {
            KeyEntry entry = registry.getByKeyId(keyId);
            if (entry == null) return null;
            Frame keyFrame = Frame.keyRegistration(entry.keyId(), entry.type(), entry.path());
            Object val = store.get(entry.keyId());
            Frame valueFrame = val != null ? Frame.value(entry.keyId(), entry.type(), val) : null;
            return new Frame[]{ keyFrame, valueFrame };
        } finally {
            rwLock.readLock().unlock();
        }
    }

    private void triggerResync() {
        cachedKeyFrames = null;
        if (onKeysChanged != null) onKeysChanged.run();
    }
}
