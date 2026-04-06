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
    private List<Frame> cachedKeyFrames = null;
    private final Map<String, Integer> arrayShiftCounters = new HashMap<>();
    private final Map<String, List<Object>> previousArrays = new HashMap<>();

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
            // Array shift detection for List values
            if (value instanceof List<?> newArr) {
                List<Object> oldArr = previousArrays.get(key);
                if (oldArr != null && !oldArr.isEmpty() && !newArr.isEmpty() && isPrimitiveArray(newArr)) {
                    int[] shift = detectArrayShiftBoth(oldArr, newArr);
                    if (shift[0] == 1) { // left
                        applyArrayShiftLeft(key, oldArr, newArr, shift[1]);
                        return;
                    }
                    if (shift[0] == 2) { // right
                        applyArrayShiftRight(key, oldArr, newArr, shift[1]);
                        return;
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
                        // Array index keys (e.g., "data.3") — leave stale, client uses .length
                        if (!isArrayIndexKey(key, oldPath)) clearLeaf(oldPath);
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

    /**
     * Detect left or right shift between old and new arrays.
     * Returns int[2]: [direction, count] where direction: 0=none, 1=left, 2=right.
     * Uses smart lookup: find old[0] in newArr (right shift) or new[0] in oldArr (left shift).
     */
    private int[] detectArrayShiftBoth(List<Object> oldArr, List<?> newArr) {
        int oldLen = oldArr.size();
        int newLen = newArr.size();

        // 1. Left shift: find new[0] in oldArr → gives shift amount k
        Object newFirst = newArr.get(0);
        for (int k = 1; k < oldLen; k++) {
            if (!Objects.equals(oldArr.get(k), newFirst)) continue;
            int matchLen = Math.min(oldLen - k, newLen);
            if (matchLen <= 0) continue;
            boolean match = true;
            for (int i = 1; i < matchLen; i++) {
                if (!Objects.equals(oldArr.get(i + k), newArr.get(i))) { match = false; break; }
            }
            if (match) return new int[]{1, k};
        }

        // 2. Right shift: find old[0] in newArr → gives shift amount k
        Object oldFirst = oldArr.get(0);
        for (int k = 1; k < newLen; k++) {
            if (!Objects.equals(newArr.get(k), oldFirst)) continue;
            int matchLen = Math.min(oldLen, newLen - k);
            if (matchLen <= 0) continue;
            boolean match = true;
            for (int i = 1; i < matchLen; i++) {
                if (!Objects.equals(oldArr.get(i), newArr.get(i + k))) { match = false; break; }
            }
            if (match) return new int[]{2, k};
        }

        return new int[]{0, 0};
    }

    /**
     * Apply left array shift: send ARRAY_SHIFT_LEFT frame + update internal store +
     * send new tail elements + update length if changed.
     */
    private void applyArrayShiftLeft(String key, List<Object> oldArr, List<?> newArr, int shiftCount) {
        int oldLen = oldArr.size();
        int newLen = newArr.size();

        // 1. Send ARRAY_SHIFT_LEFT frame
        KeyRegistry.KeyEntry lengthEntry = registry.getByPath(key + ".length");
        if (lengthEntry != null && onValueSet != null) {
            onValueSet.accept(new Frame(FrameType.ARRAY_SHIFT_LEFT, lengthEntry.keyId(), DataType.INT32, shiftCount));
        }

        // 2. Silently update internal store for shifted indices (low to high)
        for (int i = 0; i < newLen && i < oldLen - shiftCount; i++) {
            KeyRegistry.KeyEntry entry = registry.getByPath(key + "." + i);
            if (entry != null) {
                store.put(entry.keyId(), newArr.get(i));
            }
        }

        // 3. Send new tail elements (beyond what was shifted from old)
        int existingAfterShift = oldLen - shiftCount;
        for (int i = existingAfterShift; i < newLen; i++) {
            Object elem = newArr.get(i);
            if (Flatten.shouldFlatten(elem)) {
                Map<String, Object> elemFlat = Flatten.flatten(key + "." + i, elem);
                for (var e : elemFlat.entrySet()) setLeaf(e.getKey(), e.getValue());
            } else {
                setLeaf(key + "." + i, elem);
            }
        }

        // 4. Update length if changed
        if (newLen != oldLen) {
            setLeaf(key + ".length", newLen);
        }

        // 5. Update flattenedKeys
        Map<String, Object> flattened = Flatten.flatten(key, newArr);
        flattenedKeys.put(key, new HashSet<>(flattened.keySet()));

        // 6. Update previousArrays
        previousArrays.put(key, new ArrayList<>(newArr));
    }

    /**
     * Apply right array shift: send ARRAY_SHIFT_RIGHT frame + update internal store +
     * send new head elements + update length if changed.
     */
    private void applyArrayShiftRight(String key, List<Object> oldArr, List<?> newArr, int shiftCount) {
        int oldLen = oldArr.size();
        int newLen = newArr.size();

        // 1. Send ARRAY_SHIFT_RIGHT frame
        KeyRegistry.KeyEntry lengthEntry = registry.getByPath(key + ".length");
        if (lengthEntry != null && onValueSet != null) {
            onValueSet.accept(new Frame(FrameType.ARRAY_SHIFT_RIGHT, lengthEntry.keyId(), DataType.INT32, shiftCount));
        }

        // 2. Update internal store for shifted indices (high to low)
        for (int i = oldLen - 1; i >= 0; i--) {
            KeyRegistry.KeyEntry dstEntry = registry.getByPath(key + "." + (i + shiftCount));
            if (dstEntry != null) {
                store.put(dstEntry.keyId(), oldArr.get(i));
            } else {
                // New index — register and send
                setLeaf(key + "." + (i + shiftCount), oldArr.get(i));
            }
        }

        // 3. Send new head elements (indices 0..shiftCount-1)
        for (int i = 0; i < shiftCount; i++) {
            Object elem = newArr.get(i);
            if (Flatten.shouldFlatten(elem)) {
                Map<String, Object> elemFlat = Flatten.flatten(key + "." + i, elem);
                for (var e : elemFlat.entrySet()) setLeaf(e.getKey(), e.getValue());
            } else {
                setLeaf(key + "." + i, elem);
            }
        }

        // 4. Update length if changed
        if (newLen != oldLen) {
            setLeaf(key + ".length", newLen);
        }

        // 5. Update flattenedKeys
        Map<String, Object> flattened = Flatten.flatten(key, newArr);
        flattenedKeys.put(key, new HashSet<>(flattened.keySet()));

        // 6. Update previousArrays
        previousArrays.put(key, new ArrayList<>(newArr));
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

    /** Check if all elements in a list are primitive (not Map/List) and of the same type. */
    private static boolean isPrimitiveArray(List<?> list) {
        if (list.isEmpty()) return true;
        Class<?> firstType = list.get(0) != null ? list.get(0).getClass() : null;
        for (Object elem : list) {
            if (elem instanceof Map || elem instanceof List) return false;
            Class<?> elemType = elem != null ? elem.getClass() : null;
            if (!Objects.equals(firstType, elemType)) return false;
        }
        return true;
    }

    private static boolean isArrayIndexKey(String prefix, String path) {
        if (!path.startsWith(prefix + ".")) return false;
        String suffix = path.substring(prefix.length() + 1);
        for (int i = 0; i < suffix.length(); i++) {
            if (suffix.charAt(i) < '0' || suffix.charAt(i) > '9') return false;
        }
        return !suffix.isEmpty();
    }

    private void triggerResync() {
        cachedKeyFrames = null;
        if (onKeysChanged != null) onKeysChanged.run();
    }
}
