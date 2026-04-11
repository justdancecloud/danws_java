package com.danws.protocol;

/**
 * Generic frame container parameterized by payload type {@code T}.
 * <p>
 * Mirrors the TypeScript {@code Frame} interface whose {@code payload} field
 * is {@code any}. Java generics are erased at runtime, so this is purely a
 * compile-time aid — consumers that know the intended payload type (e.g.
 * {@code Frame<String>} for key registration frames) get safer access without
 * explicit casts. Producers and pipeline stages that route arbitrary frames
 * use the wildcard {@code Frame<?>}.
 */
public class Frame<T> {
    private final FrameType frameType;
    private final int keyId;
    private final DataType dataType;
    private final T payload;

    public Frame(FrameType frameType, int keyId, DataType dataType, T payload) {
        this.frameType = frameType;
        this.keyId = keyId;
        this.dataType = dataType;
        this.payload = payload;
    }

    public FrameType frameType() { return frameType; }
    public int keyId() { return keyId; }
    public DataType dataType() { return dataType; }
    public T payload() { return payload; }

    /** Signal frame — no payload. */
    public static Frame<Void> signal(FrameType type) {
        return new Frame<>(type, 0, DataType.NULL, null);
    }

    /** Key-registration frame — UTF-8 key path. */
    public static Frame<String> keyRegistration(int keyId, DataType dataType, String keyPath) {
        return new Frame<>(FrameType.SERVER_KEY_REGISTRATION, keyId, dataType, keyPath);
    }

    /** Value frame — payload type depends on dataType. */
    public static Frame<Object> value(int keyId, DataType dataType, Object value) {
        return new Frame<>(FrameType.SERVER_VALUE, keyId, dataType, value);
    }

    /** Key-delete signal. */
    public static Frame<Void> keyDelete(int keyId) {
        return new Frame<>(FrameType.SERVER_KEY_DELETE, keyId, DataType.NULL, null);
    }
}
