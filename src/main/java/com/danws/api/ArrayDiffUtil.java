package com.danws.api;

import com.danws.protocol.*;

import java.util.*;
import java.util.function.Consumer;

/**
 * Shared utility for array shift detection and application.
 * Used by PrincipalTX, DanWebSocketSession, and TopicPayload.
 */
final class ArrayDiffUtil {

    private ArrayDiffUtil() {}

    /** Size limit: skip shift detection for very large arrays. */
    private static final int SHIFT_DETECTION_SIZE_LIMIT = 1000;

    /** Max positions to search for shift — caps worst case to O(MAX_SHIFT_SEARCH * n). */
    private static final int MAX_SHIFT_SEARCH = 50;

    /** Number of leading elements sampled for the rolling hash pre-filter. */
    private static final int HASH_SAMPLE = 8;

    /**
     * Quick hash of the first {@code len} elements starting at {@code start}.
     * Used as a cheap pre-filter so full element-by-element comparison is only
     * performed when the hash matches the target window.
     */
    private static int quickHash(List<?> arr, int start, int len) {
        int h = 0;
        int end = Math.min(start + len, arr.size());
        for (int i = start; i < end; i++) {
            h = h * 31 + Objects.hashCode(arr.get(i));
        }
        return h;
    }

    /**
     * Detect left or right shift between old and new arrays.
     * Returns int[2]: [direction, count] where direction: 0=none, 1=left, 2=right.
     *
     * Search is bounded to {@link #MAX_SHIFT_SEARCH} positions, giving O(n) worst case.
     * A rolling hash pre-filter on the first {@link #HASH_SAMPLE} elements avoids
     * full comparisons at non-matching positions.
     */
    static int[] detectShift(List<Object> oldArr, List<?> newArr) {
        int oldLen = oldArr.size();
        int newLen = newArr.size();

        // Early exit: if first elements match, no shift occurred
        if (Objects.equals(oldArr.get(0), newArr.get(0))) {
            return new int[]{0, 0};
        }

        // Skip shift detection for large arrays
        if (oldLen > SHIFT_DETECTION_SIZE_LIMIT || newLen > SHIFT_DETECTION_SIZE_LIMIT) {
            return new int[]{0, 0};
        }

        // 1. Left shift: find new[0] in oldArr → gives shift amount k
        //    Pre-compute hash of newArr[0..HASH_SAMPLE) as the target to match.
        int sampleLen = Math.min(HASH_SAMPLE, newLen);
        int targetHashLeft = quickHash(newArr, 0, sampleLen);
        Object newFirst = newArr.get(0);
        for (int k = 1; k < Math.min(oldLen, MAX_SHIFT_SEARCH + 1); k++) {
            if (!Objects.equals(oldArr.get(k), newFirst)) continue;
            // Quick hash pre-filter: compare hash of oldArr[k..k+SAMPLE) with target
            if (quickHash(oldArr, k, sampleLen) != targetHashLeft) continue;
            int matchLen = Math.min(oldLen - k, newLen);
            if (matchLen <= 0) continue;
            boolean match = true;
            for (int i = 1; i < matchLen; i++) {
                if (!Objects.equals(oldArr.get(i + k), newArr.get(i))) { match = false; break; }
            }
            if (match) return new int[]{1, k};
        }

        // 2. Right shift: find old[0] in newArr → gives shift amount k
        //    Pre-compute hash of oldArr[0..HASH_SAMPLE) as the target to match.
        int sampleLenRight = Math.min(HASH_SAMPLE, oldLen);
        int targetHashRight = quickHash(oldArr, 0, sampleLenRight);
        Object oldFirst = oldArr.get(0);
        for (int k = 1; k < Math.min(newLen, MAX_SHIFT_SEARCH + 1); k++) {
            if (!Objects.equals(newArr.get(k), oldFirst)) continue;
            // Quick hash pre-filter: compare hash of newArr[k..k+SAMPLE) with target
            if (quickHash(newArr, k, sampleLenRight) != targetHashRight) continue;
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

    /** Check if all elements in a list are primitive (not Map/List) and of the same type. */
    static boolean isPrimitiveArray(List<?> list) {
        if (list.isEmpty()) return true;
        Class<?> firstType = list.get(0) != null ? list.get(0).getClass() : null;
        for (Object elem : list) {
            if (elem instanceof Map || elem instanceof List) return false;
            Class<?> elemType = elem != null ? elem.getClass() : null;
            if (!Objects.equals(firstType, elemType)) return false;
        }
        return true;
    }

    /** Check if a path is a numeric array index under a given prefix. */
    static boolean isArrayIndexKey(String prefix, String path) {
        if (!path.startsWith(prefix + ".")) return false;
        String suffix = path.substring(prefix.length() + 1);
        for (int i = 0; i < suffix.length(); i++) {
            if (suffix.charAt(i) < '0' || suffix.charAt(i) > '9') return false;
        }
        return !suffix.isEmpty();
    }

    /** Context for applying array shift operations. */
    interface ShiftContext {
        /** Get the keyId for a key path, or -1 if not found. */
        int getKeyId(String key);
        /** Get the DataType for a key path, or null if not found. */
        DataType getType(String key);
        /** Silently update internal store value (no frame). */
        void setStoreValue(String key, Object value);
        /** Set a leaf key-value (sends frame if changed, creates if new). */
        void setLeaf(String key, Object value);
        /** Enqueue a frame for sending. */
        void enqueue(Frame<?> frame);
        /** Update the flattenedKeys set for a prefix. */
        void setFlattenedKeys(String key, Set<String> keys);
        /** Update the previousArrays cache. */
        void setPreviousArray(String key, List<Object> arr);
    }

    static void applyShiftLeft(ShiftContext ctx, String key, List<Object> oldArr, List<?> newArr, int shiftCount) {
        int oldLen = oldArr.size();
        int newLen = newArr.size();

        // 1. Send ARRAY_SHIFT_LEFT frame
        int lengthKeyId = ctx.getKeyId(key + ".length");
        if (lengthKeyId >= 0) {
            ctx.enqueue(new Frame<>(FrameType.ARRAY_SHIFT_LEFT, lengthKeyId, DataType.INT32, shiftCount));
        }

        // 2. Silently update internal store for shifted indices (low to high)
        for (int i = 0; i < newLen && i < oldLen - shiftCount; i++) {
            ctx.setStoreValue(key + "." + i, newArr.get(i));
        }

        // 3. Send new tail elements
        int existingAfterShift = oldLen - shiftCount;
        for (int i = existingAfterShift; i < newLen; i++) {
            Object elem = newArr.get(i);
            if (Flatten.shouldFlatten(elem)) {
                for (var e : Flatten.flatten(key + "." + i, elem).entrySet()) ctx.setLeaf(e.getKey(), e.getValue());
            } else {
                ctx.setLeaf(key + "." + i, elem);
            }
        }

        // 4. Always send length — client decrements on ArrayShiftLeft,
        //    so we must always send correct final length to restore it.
        if (lengthKeyId >= 0) {
            DataType lenType = ctx.getType(key + ".length");
            ctx.setStoreValue(key + ".length", newLen);
            ctx.enqueue(new Frame<>(FrameType.SERVER_VALUE, lengthKeyId, lenType != null ? lenType : DataType.INT32, newLen));
        }

        // 5. Update flattenedKeys + previousArrays
        ctx.setFlattenedKeys(key, new HashSet<>(Flatten.flatten(key, newArr).keySet()));
        ctx.setPreviousArray(key, new ArrayList<>(newArr));
    }

    static void applyShiftRight(ShiftContext ctx, String key, List<Object> oldArr, List<?> newArr, int shiftCount) {
        int oldLen = oldArr.size();
        int newLen = newArr.size();

        // 1. Send ARRAY_SHIFT_RIGHT frame
        int lengthKeyId = ctx.getKeyId(key + ".length");
        if (lengthKeyId >= 0) {
            ctx.enqueue(new Frame<>(FrameType.ARRAY_SHIFT_RIGHT, lengthKeyId, DataType.INT32, shiftCount));
        }

        // 2. Update internal store for shifted indices (high to low)
        for (int i = oldLen - 1; i >= 0; i--) {
            String dstKey = key + "." + (i + shiftCount);
            if (ctx.getKeyId(dstKey) >= 0) {
                ctx.setStoreValue(dstKey, oldArr.get(i));
            } else {
                ctx.setLeaf(dstKey, oldArr.get(i));
            }
        }

        // 3. Send new head elements (indices 0..shiftCount-1)
        for (int i = 0; i < shiftCount; i++) {
            Object elem = newArr.get(i);
            if (Flatten.shouldFlatten(elem)) {
                for (var e : Flatten.flatten(key + "." + i, elem).entrySet()) ctx.setLeaf(e.getKey(), e.getValue());
            } else {
                ctx.setLeaf(key + "." + i, elem);
            }
        }

        // 3b. Send overflow tail elements
        for (int i = oldLen; i < newLen; i++) {
            Object elem = newArr.get(i);
            if (Flatten.shouldFlatten(elem)) {
                for (var e : Flatten.flatten(key + "." + i, elem).entrySet()) ctx.setLeaf(e.getKey(), e.getValue());
            } else {
                ctx.setLeaf(key + "." + i, elem);
            }
        }

        // 4. Update length if changed
        if (newLen != oldLen) {
            ctx.setLeaf(key + ".length", newLen);
        }

        // 5. Update flattenedKeys + previousArrays
        ctx.setFlattenedKeys(key, new HashSet<>(Flatten.flatten(key, newArr).keySet()));
        ctx.setPreviousArray(key, new ArrayList<>(newArr));
    }
}
