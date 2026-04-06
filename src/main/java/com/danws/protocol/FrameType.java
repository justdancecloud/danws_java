package com.danws.protocol;

public enum FrameType {
    SERVER_KEY_REGISTRATION(0x00),
    SERVER_VALUE(0x01),
    SERVER_SYNC(0x04),
    CLIENT_READY(0x05),
    ERROR(0x08),
    SERVER_RESET(0x09),
    CLIENT_RESYNC_REQ(0x0A),
    IDENTIFY(0x0D),
    AUTH(0x0E),
    AUTH_OK(0x0F),
    AUTH_FAIL(0x10);

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
