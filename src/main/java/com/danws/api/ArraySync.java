package com.danws.api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Ring-buffer backed array synchronization.
 * Minimizes wire traffic for sliding-window patterns (chart data, logs, etc.)
 *
 * Wire keys: {prefix}.__h (head), {prefix}.__l (length), {prefix}.__c (capacity),
 *            {prefix}.__0, {prefix}.__1, ... (ring buffer slots)
 *
 * push():      2 frames (slot value + length update)
 * pushShift(): 2 frames (overwrite slot + head update) — regardless of array size
 * set(i, v):   1 frame (single slot update)
 */
public class ArraySync {

    private final String prefix;
    private final BiConsumer<String, Object> setter;
    private final int capacity;
    private final Object[] slots;
    private int head;
    private int length;

    ArraySync(String prefix, int capacity, BiConsumer<String, Object> setter) {
        this.prefix = prefix;
        this.capacity = capacity;
        this.slots = new Object[capacity];
        this.head = 0;
        this.length = 0;
        this.setter = setter;

        // Initialize metadata
        setter.accept(prefix + ".__h", 0.0);
        setter.accept(prefix + ".__l", 0.0);
        setter.accept(prefix + ".__c", (double) capacity);
    }

    /** Append value. If at capacity, oldest is removed (same as pushShift). */
    public void push(Object value) {
        if (length < capacity) {
            int slotIdx = (head + length) % capacity;
            slots[slotIdx] = value;
            length++;
            setter.accept(prefix + ".__" + slotIdx, value);
            setter.accept(prefix + ".__l", (double) length);
        } else {
            pushShift(value);
        }
    }

    /** Push new value + remove oldest in one operation. Only 2 frames. */
    public void pushShift(Object value) {
        if (length == 0) {
            push(value);
            return;
        }
        int slotIdx = head;
        slots[slotIdx] = value;
        head = (head + 1) % capacity;
        setter.accept(prefix + ".__" + slotIdx, value);
        setter.accept(prefix + ".__h", (double) head);
    }

    /** Remove oldest element. 2 frames (head + length). */
    public void shift() {
        if (length == 0) return;
        slots[head] = null;
        head = (head + 1) % capacity;
        length--;
        setter.accept(prefix + ".__h", (double) head);
        setter.accept(prefix + ".__l", (double) length);
    }

    /** Update element at logical index. 1 frame. */
    public void set(int index, Object value) {
        if (index < 0 || index >= length) throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + length);
        int slotIdx = (head + index) % capacity;
        slots[slotIdx] = value;
        setter.accept(prefix + ".__" + slotIdx, value);
    }

    /** Get element at logical index. */
    public Object get(int index) {
        if (index < 0 || index >= length) return null;
        return slots[(head + index) % capacity];
    }

    /** Current length. */
    public int length() { return length; }

    /** Capacity. */
    public int capacity() { return capacity; }

    /** Reconstruct as list. */
    public List<Object> toList() {
        List<Object> result = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            result.add(slots[(head + i) % capacity]);
        }
        return result;
    }

    /** Push multiple values at once. */
    public void pushAll(List<?> values) {
        for (Object v : values) push(v);
    }

    /** Clear all elements. 2 frames (head + length). */
    public void clear() {
        for (int i = 0; i < capacity; i++) slots[i] = null;
        head = 0;
        length = 0;
        setter.accept(prefix + ".__h", 0.0);
        setter.accept(prefix + ".__l", 0.0);
    }
}
