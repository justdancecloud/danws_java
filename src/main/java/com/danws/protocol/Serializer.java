package com.danws.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

public final class Serializer {

    private Serializer() {}

    public static byte[] serialize(DataType type, Object value) {
        return switch (type) {
            case NULL -> new byte[0];
            case BOOL -> new byte[]{(Boolean) value ? (byte) 0x01 : (byte) 0x00};
            case UINT8 -> new byte[]{((Number) value).byteValue()};
            case UINT16 -> ByteBuffer.allocate(2).putShort(((Number) value).shortValue()).array();
            case UINT32 -> ByteBuffer.allocate(4).putInt(((Number) value).intValue()).array();
            case UINT64 -> ByteBuffer.allocate(8).putLong(((Number) value).longValue()).array();
            case INT32 -> ByteBuffer.allocate(4).putInt(((Number) value).intValue()).array();
            case INT64 -> ByteBuffer.allocate(8).putLong(((Number) value).longValue()).array();
            case FLOAT32 -> ByteBuffer.allocate(4).putFloat(((Number) value).floatValue()).array();
            case FLOAT64 -> ByteBuffer.allocate(8).putDouble(((Number) value).doubleValue()).array();
            case STRING -> ((String) value).getBytes(StandardCharsets.UTF_8);
            case BINARY -> (byte[]) value;
            case TIMESTAMP -> {
                long ms;
                if (value instanceof Date d) ms = d.getTime();
                else if (value instanceof Instant i) ms = i.toEpochMilli();
                else if (value instanceof Number n) ms = n.longValue();
                else throw new DanWSException("INVALID_VALUE_TYPE", "Timestamp requires Date, Instant, or long");
                yield ByteBuffer.allocate(8).putLong(ms).array();
            }
        };
    }

    public static Object deserialize(DataType type, byte[] payload) {
        validateSize(type, payload);
        ByteBuffer buf = ByteBuffer.wrap(payload);
        return switch (type) {
            case NULL -> null;
            case BOOL -> {
                if (payload[0] == 0x01) yield Boolean.TRUE;
                if (payload[0] == 0x00) yield Boolean.FALSE;
                throw new DanWSException("INVALID_VALUE_TYPE", "Bool must be 0x00 or 0x01");
            }
            case UINT8 -> (int) (payload[0] & 0xFF);
            case UINT16 -> (int) (buf.getShort() & 0xFFFF);
            case UINT32 -> buf.getInt() & 0xFFFFFFFFL;
            case UINT64 -> buf.getLong();
            case INT32 -> buf.getInt();
            case INT64 -> buf.getLong();
            case FLOAT32 -> buf.getFloat();
            case FLOAT64 -> buf.getDouble();
            case STRING -> new String(payload, StandardCharsets.UTF_8);
            case BINARY -> payload.clone();
            case TIMESTAMP -> new Date(buf.getLong());
        };
    }

    private static final int[] FIXED_SIZES = {
        0, 1, 1, 2, 4, 8, 4, 8, 4, 8, -1, -1, 8
    };

    private static void validateSize(DataType type, byte[] payload) {
        int expected = FIXED_SIZES[type.code()];
        if (expected >= 0 && payload.length != expected) {
            throw new DanWSException("PAYLOAD_SIZE_MISMATCH",
                    type + " expects " + expected + " bytes, got " + payload.length);
        }
    }
}
