package com.danws.protocol;

public enum FrameType {
    SERVER_KEY_REGISTRATION(0x00),
    SERVER_VALUE(0x01),
    CLIENT_KEY_REGISTRATION(0x02),
    CLIENT_VALUE(0x03),
    SERVER_SYNC(0x04),
    CLIENT_READY(0x05),
    CLIENT_SYNC(0x06),
    SERVER_READY(0x07),
    ERROR(0x08),
    SERVER_RESET(0x09),
    CLIENT_RESYNC_REQ(0x0A),
    CLIENT_RESET(0x0B),
    SERVER_RESYNC_REQ(0x0C),
    IDENTIFY(0x0D),
    AUTH(0x0E),
    AUTH_OK(0x0F),
    AUTH_FAIL(0x11),
    ARRAY_SHIFT_LEFT(0x20),
    ARRAY_SHIFT_RIGHT(0x21),
    SERVER_FLUSH_END(0xFF);

    private final int code;

    FrameType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static FrameType fromCode(int code) {
        for (FrameType ft : values()) {
            if (ft.code == code) return ft;
        }
        throw new DanWSException("UNKNOWN_FRAME_TYPE", "Unknown frame type: 0x" + Integer.toHexString(code));
    }
}
