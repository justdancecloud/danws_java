package com.danws.protocol;

public class Frame {
    private final FrameType frameType;
    private final int keyId;
    private final DataType dataType;
    private final Object payload;

    public Frame(FrameType frameType, int keyId, DataType dataType, Object payload) {
        this.frameType = frameType;
        this.keyId = keyId;
        this.dataType = dataType;
        this.payload = payload;
    }

    public FrameType frameType() { return frameType; }
    public int keyId() { return keyId; }
    public DataType dataType() { return dataType; }
    public Object payload() { return payload; }

    public static Frame signal(FrameType type) {
        return new Frame(type, 0, DataType.NULL, null);
    }

    public static Frame keyRegistration(int keyId, DataType dataType, String keyPath) {
        return new Frame(FrameType.SERVER_KEY_REGISTRATION, keyId, dataType, keyPath);
    }

    public static Frame value(int keyId, DataType dataType, Object value) {
        return new Frame(FrameType.SERVER_VALUE, keyId, dataType, value);
    }

    public static Frame keyDelete(int keyId) {
        return new Frame(FrameType.SERVER_KEY_DELETE, keyId, DataType.NULL, null);
    }
}
