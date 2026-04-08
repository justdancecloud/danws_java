package com.danws.api;

import java.util.*;

/**
 * Shared flatten + diff + shift logic for set() operations.
 * Used by PrincipalTX, DanWebSocketSession, and TopicPayload.
 */
class FlatStateHelper {

    @FunctionalInterface
    interface LeafSetter {
        void setLeaf(String key, Object value);
    }

    @FunctionalInterface
    interface KeyDeleter {
        void deleteKey(String path);
    }

    /**
     * Perform a set() with auto-flatten, array shift detection, and stale key cleanup.
     *
     * @param key              the top-level key being set
     * @param value            the value (may be Map, List, or leaf)
     * @param flattenedKeys    shared map tracking flattened key sets per top-level key
     * @param previousArrays   shared map tracking previous array values for shift detection
     * @param shiftContextSupplier supplies a ShiftContext for array shift operations
     * @param setter           callback to set a leaf key-value pair
     * @param deleter          callback to delete a stale key path
     * @param checkPrimitiveArray whether to gate shift detection on isPrimitiveArray
     */
    static void set(String key, Object value,
                    Map<String, Set<String>> flattenedKeys,
                    Map<String, List<Object>> previousArrays,
                    java.util.function.Supplier<ArrayDiffUtil.ShiftContext> shiftContextSupplier,
                    LeafSetter setter,
                    KeyDeleter deleter,
                    boolean checkPrimitiveArray) {
        if (Flatten.shouldFlatten(value)) {
            // Array shift detection for List values
            if (value instanceof List<?> newArr) {
                List<Object> oldArr = previousArrays.get(key);
                if (oldArr != null && !oldArr.isEmpty() && !newArr.isEmpty()
                        && (!checkPrimitiveArray || ArrayDiffUtil.isPrimitiveArray(newArr))) {
                    int[] shift = ArrayDiffUtil.detectShift(oldArr, newArr);
                    if (shift[0] != 0) {
                        var ctx = shiftContextSupplier.get();
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
                        if (!ArrayDiffUtil.isArrayIndexKey(key, oldPath)) {
                            deleter.deleteKey(oldPath);
                        }
                    }
                }
            }
            flattenedKeys.put(key, new HashSet<>(newKeys));
            for (var entry : flattened.entrySet()) {
                setter.setLeaf(entry.getKey(), entry.getValue());
            }
            return;
        }
        setter.setLeaf(key, value);
    }
}
