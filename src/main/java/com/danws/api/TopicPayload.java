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
            boolean needsResync = false;
            if (oldKeys != null) {
                for (String oldPath : oldKeys) {
                    if (!newKeys.contains(oldPath)) {
                        if (isArrayIndexKey(key, oldPath)) continue; // stale array index — client uses .length
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

    private int[] detectArrayShiftBoth(List<Object> oldArr, List<?> newArr) {
        int oldLen = oldArr.size();
        int newLen = newArr.size();

        // 1. Left shift: find new[0] in oldArr → gives shift amount k
        Object newFirst = newArr.get(0);
        for (int k = 1; k < oldLen; k++) {
            if (!java.util.Objects.equals(oldArr.get(k), newFirst)) continue;
            int matchLen = Math.min(oldLen - k, newLen);
            if (matchLen <= 0) continue;
            boolean match = true;
            for (int i = 1; i < matchLen; i++) {
                if (!java.util.Objects.equals(oldArr.get(i + k), newArr.get(i))) { match = false; break; }
            }
            if (match) return new int[]{1, k};
        }

        // 2. Right shift: find old[0] in newArr → gives shift amount k
        Object oldFirst = oldArr.get(0);
        for (int k = 1; k < newLen; k++) {
            if (!java.util.Objects.equals(newArr.get(k), oldFirst)) continue;
            int matchLen = Math.min(oldLen, newLen - k);
            if (matchLen <= 0) continue;
            boolean match = true;
            for (int i = 1; i < matchLen; i++) {
                if (!java.util.Objects.equals(oldArr.get(i), newArr.get(i + k))) { match = false; break; }
            }
            if (match) return new int[]{2, k};
        }

        return new int[]{0, 0};
    }

    private void applyArrayShiftLeft(String key, List<Object> oldArr, List<?> newArr, int shiftCount) {
        int oldLen = oldArr.size();
        int newLen = newArr.size();

        // 1. Send ARRAY_SHIFT_LEFT frame
        Entry lengthEntry = entries.get(key + ".length");
        if (lengthEntry != null && enqueue != null) {
            enqueue.accept(new Frame(FrameType.ARRAY_SHIFT_LEFT, lengthEntry.keyId(), DataType.INT32, shiftCount));
        }

        // 2. Silently update internal store for shifted indices (low to high)
        for (int i = 0; i < newLen && i < oldLen - shiftCount; i++) {
            Entry entry = entries.get(key + "." + i);
            if (entry != null) {
                DataType dt = DataType.detect(newArr.get(i));
                entries.put(key + "." + i, new Entry(entry.keyId(), dt, newArr.get(i)));
            }
        }

        // 3. Send new tail elements
        int existingAfterShift = oldLen - shiftCount;
        for (int i = existingAfterShift; i < newLen; i++) {
            Object elem = newArr.get(i);
            if (Flatten.shouldFlatten(elem)) {
                Map<String, Object> elemFlat = Flatten.flatten(key + "." + i, elem);
                for (var e : elemFlat.entrySet()) setLeafInternal(e.getKey(), e.getValue());
            } else {
                setLeafDirect(key + "." + i, elem);
            }
        }

        // 4. Update length if changed
        if (newLen != oldLen) {
            setLeafDirect(key + ".length", newLen);
        }

        // 5. Update flattenedKeys
        Map<String, Object> flattened = Flatten.flatten(key, newArr);
        flattenedKeys.put(key, new HashSet<>(flattened.keySet()));

        // 6. Update previousArrays
        previousArrays.put(key, new ArrayList<>(newArr));
    }

    private void applyArrayShiftRight(String key, List<Object> oldArr, List<?> newArr, int shiftCount) {
        int oldLen = oldArr.size();
        int newLen = newArr.size();

        // 1. Send ARRAY_SHIFT_RIGHT frame
        Entry lengthEntry = entries.get(key + ".length");
        if (lengthEntry != null && enqueue != null) {
            enqueue.accept(new Frame(FrameType.ARRAY_SHIFT_RIGHT, lengthEntry.keyId(), DataType.INT32, shiftCount));
        }

        // 2. Update internal store for shifted indices (high to low)
        for (int i = oldLen - 1; i >= 0; i--) {
            Entry dstEntry = entries.get(key + "." + (i + shiftCount));
            if (dstEntry != null) {
                DataType dt = DataType.detect(oldArr.get(i));
                entries.put(key + "." + (i + shiftCount), new Entry(dstEntry.keyId(), dt, oldArr.get(i)));
            } else {
                // New index — register and send
                setLeafDirect(key + "." + (i + shiftCount), oldArr.get(i));
            }
        }

        // 3. Send new head elements (indices 0..shiftCount-1)
        for (int i = 0; i < shiftCount; i++) {
            Object elem = newArr.get(i);
            if (Flatten.shouldFlatten(elem)) {
                Map<String, Object> elemFlat = Flatten.flatten(key + "." + i, elem);
                for (var e : elemFlat.entrySet()) setLeafInternal(e.getKey(), e.getValue());
            } else {
                setLeafDirect(key + "." + i, elem);
            }
        }

        // 4. Update length if changed
        if (newLen != oldLen) {
            setLeafDirect(key + ".length", newLen);
        }

        // 5. Update flattenedKeys
        Map<String, Object> flattened = Flatten.flatten(key, newArr);
        flattenedKeys.put(key, new HashSet<>(flattened.keySet()));

        // 6. Update previousArrays
        previousArrays.put(key, new ArrayList<>(newArr));
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
            if (onResync != null) onResync.run();
        } else if (entries.remove(key) != null) {
            wirePathCache.remove(key);
            if (onResync != null) onResync.run();
        }
    }

    public void clear() {
        if (!entries.isEmpty()) {
            entries.clear();
            flattenedKeys.clear();
            wirePathCache.clear();
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
