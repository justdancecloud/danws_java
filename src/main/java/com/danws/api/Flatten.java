package com.danws.api;

import com.danws.protocol.DanWSException;

import java.util.*;

/**
 * Auto-flatten utility: recursively expands Maps and Lists into dot-path leaf entries.
 */
public final class Flatten {

    private static final int MAX_DEPTH = 10;

    private Flatten() {}

    /**
     * Check if a value should be auto-flattened (Map or List).
     */
    public static boolean shouldFlatten(Object value) {
        return value instanceof Map || value instanceof List;
    }

    /**
     * Flatten a Map or List into dot-path → leaf value entries.
     * Arrays (Lists) get an additional `.length` key.
     */
    public static Map<String, Object> flatten(String prefix, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        flattenRecursive(prefix, value, 0, seen, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void flattenRecursive(String prefix, Object value, int depth, Set<Object> seen, Map<String, Object> result) {
        if (depth > MAX_DEPTH) {
            throw new DanWSException("FLATTEN_DEPTH_EXCEEDED", "Auto-flatten depth limit exceeded (max " + MAX_DEPTH + ") at path \"" + prefix + "\"");
        }

        if (value instanceof List<?> list) {
            if (seen.contains(list)) {
                throw new DanWSException("CIRCULAR_REFERENCE", "Circular reference detected at path \"" + prefix + "\"");
            }
            seen.add(list);
            result.put(prefix + ".length", list.size());
            for (int i = 0; i < list.size(); i++) {
                String childPath = prefix + "." + i;
                Object child = list.get(i);
                if (child instanceof Map || child instanceof List) {
                    flattenRecursive(childPath, child, depth + 1, seen, result);
                } else {
                    result.put(childPath, child);
                }
            }
            return;
        }

        if (value instanceof Map<?, ?> map) {
            if (seen.contains(map)) {
                throw new DanWSException("CIRCULAR_REFERENCE", "Circular reference detected at path \"" + prefix + "\"");
            }
            seen.add(map);
            for (var entry : ((Map<String, Object>) map).entrySet()) {
                String childPath = prefix + "." + entry.getKey();
                Object child = entry.getValue();
                if (child instanceof Map || child instanceof List) {
                    flattenRecursive(childPath, child, depth + 1, seen, result);
                } else {
                    result.put(childPath, child);
                }
            }
            return;
        }

        // Leaf value
        result.put(prefix, value);
    }
}
