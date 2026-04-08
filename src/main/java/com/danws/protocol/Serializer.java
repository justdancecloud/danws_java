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
            case UINT16 -> {
                int val = ((Number) value).intValue();
                yield new byte[] { (byte)(val >>> 8), (byte)val };
            }
            case UINT32 -> {
                int val = ((Number) value).intValue();
                yield new byte[] { (byte)(val >>> 24), (byte)(val >>> 16), (byte)(val >>> 8), (byte)val };
            }
            case UINT64 -> {
                long val = ((Number) value).longValue();
                yield new byte[] { (byte)(val >>> 56), (byte)(val >>> 48), (byte)(val >>> 40), (byte)(val >>> 32),
                                    (byte)(val >>> 24), (byte)(val >>> 16), (byte)(val >>> 8), (byte)val };
            }
            case INT32 -> {
                int val = ((Number) value).intValue();
                yield new byte[] { (byte)(val >>> 24), (byte)(val >>> 16), (byte)(val >>> 8), (byte)val };
            }
            case INT64 -> {
                long val = ((Number) value).longValue();
                yield new byte[] { (byte)(val >>> 56), (byte)(val >>> 48), (byte)(val >>> 40), (byte)(val >>> 32),
                                    (byte)(val >>> 24), (byte)(val >>> 16), (byte)(val >>> 8), (byte)val };
            }
            case FLOAT32 -> {
                int bits = Float.floatToIntBits(((Number) value).floatValue());
                yield new byte[] { (byte)(bits >>> 24), (byte)(bits >>> 16), (byte)(bits >>> 8), (byte)bits };
            }
            case FLOAT64 -> {
                long bits = Double.doubleToLongBits(((Number) value).doubleValue());
                yield new byte[] { (byte)(bits >>> 56), (byte)(bits >>> 48), (byte)(bits >>> 40), (byte)(bits >>> 32),
                                    (byte)(bits >>> 24), (byte)(bits >>> 16), (byte)(bits >>> 8), (byte)bits };
            }
            case STRING -> (value instanceof String s ? s : value.toString()).getBytes(StandardCharsets.UTF_8);
            case BINARY -> (byte[]) value;
            case TIMESTAMP -> {
                long ms;
                if (value instanceof Date d) ms = d.getTime();
                else if (value instanceof Instant i) ms = i.toEpochMilli();
                else if (value instanceof Number n) ms = n.longValue();
                else throw new DanWSException("INVALID_VALUE_TYPE", "Timestamp requires Date, Instant, or long");
                yield new byte[] { (byte)(ms >>> 56), (byte)(ms >>> 48), (byte)(ms >>> 40), (byte)(ms >>> 32),
                                    (byte)(ms >>> 24), (byte)(ms >>> 16), (byte)(ms >>> 8), (byte)ms };
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
