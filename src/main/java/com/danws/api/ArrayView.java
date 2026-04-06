package com.danws.api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Client-side read-only view of a ring-buffer array.
 * Reads __h, __l, __c, __0..__N keys and presents as ordered array.
 */
public class ArrayView {

    private final String prefix;
    private final Function<String, Object> getter;

    ArrayView(String prefix, Function<String, Object> getter) {
        this.prefix = prefix;
        this.getter = getter;
    }

    /** Current length. */
    public int length() {
        Object v = getter.apply(prefix + ".__l");
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    /** Capacity. */
    public int capacity() {
        Object v = getter.apply(prefix + ".__c");
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    private int head() {
        Object v = getter.apply(prefix + ".__h");
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    /** Get element at logical index. */
    public Object get(int index) {
        int len = length();
        if (index < 0 || index >= len) return null;
        int cap = capacity();
        if (cap == 0) return null;
        int slotIdx = (head() + index) % cap;
        return getter.apply(prefix + ".__" + slotIdx);
    }

    /** Reconstruct as list. */
    public List<Object> toList() {
        int len = length();
        int cap = capacity();
        int h = head();
        List<Object> result = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            int slotIdx = (h + i) % cap;
            result.add(getter.apply(prefix + ".__" + slotIdx));
        }
        return result;
    }
}
