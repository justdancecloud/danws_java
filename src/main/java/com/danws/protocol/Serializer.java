package com.danws.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
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
            case VAR_INTEGER -> {
                long n = ((Number) value).longValue();
                long zigzag = (n << 1) ^ (n >> 63);
                yield encodeVarInt(zigzag);
            }
            case VAR_DOUBLE -> {
                double val = ((Number) value).doubleValue();
                // Fallback: NaN, Infinity, -0
                if (!Double.isFinite(val) || (Double.doubleToRawLongBits(val) == Long.MIN_VALUE)) {
                    byte[] result = new byte[9];
                    result[0] = (byte) 0x80;
                    long bits = Double.doubleToLongBits(val);
                    for (int i = 0; i < 8; i++) result[1 + i] = (byte)(bits >>> (56 - i * 8));
                    yield result;
                }
                // Determine scale via string representation (avoids floating-point drift)
                double abs = Math.abs(val);
                int scale = 0;
                long mantissa;
                String str = Double.toString(abs);
                // Handle scientific notation fallback
                if (str.contains("E") || str.contains("e")) {
                    byte[] fb = new byte[9];
                    fb[0] = (byte) 0x80;
                    long fbBits = Double.doubleToLongBits(val);
                    for (int i = 0; i < 8; i++) fb[1 + i] = (byte)(fbBits >>> (56 - i * 8));
                    yield fb;
                }
                int dotIdx = str.indexOf('.');
                if (dotIdx != -1) {
                    // Remove trailing zeros for clean scale
                    String trimmed = str.replaceAll("0+$", "");
                    if (trimmed.endsWith(".")) {
                        // e.g. "100." → integer, scale=0
                        mantissa = Long.parseLong(trimmed.substring(0, trimmed.length() - 1));
                        scale = 0;
                    } else {
                        int tDot = trimmed.indexOf('.');
                        scale = trimmed.length() - tDot - 1;
                        if (scale > 63) {
                            byte[] fb = new byte[9];
                            fb[0] = (byte) 0x80;
                            long fbBits = Double.doubleToLongBits(val);
                            for (int i = 0; i < 8; i++) fb[1 + i] = (byte)(fbBits >>> (56 - i * 8));
                            yield fb;
                        }
                        mantissa = Long.parseLong(trimmed.replace(".", ""));
                    }
                } else {
                    mantissa = Long.parseLong(str);
                }
                // If mantissa too large, fallback
                if (mantissa < 0 || mantissa > 0x1FFFFFFFFFFFFFL) { // > 2^53-1
                    byte[] result = new byte[9];
                    result[0] = (byte) 0x80;
                    long bits = Double.doubleToLongBits(val);
                    for (int i = 0; i < 8; i++) result[1 + i] = (byte)(bits >>> (56 - i * 8));
                    yield result;
                }
                boolean negative = val < 0;
                int firstByte = negative ? (scale + 64) : scale;
                byte[] varint = encodeVarInt(mantissa);
                byte[] result = new byte[1 + varint.length];
                result[0] = (byte) firstByte;
                System.arraycopy(varint, 0, result, 1, varint.length);
                yield result;
            }
            case VAR_FLOAT -> {
                float fval = ((Number) value).floatValue();
                // Fallback: NaN, Infinity, -0
                if (!Float.isFinite(fval) || Float.floatToRawIntBits(fval) == 0x80000000) {
                    byte[] result = new byte[5];
                    result[0] = (byte) 0x80;
                    int bits = Float.floatToIntBits(fval);
                    result[1] = (byte)(bits >>> 24);
                    result[2] = (byte)(bits >>> 16);
                    result[3] = (byte)(bits >>> 8);
                    result[4] = (byte)bits;
                    yield result;
                }
                // Determine scale via string representation
                double dval = (double) fval;
                double abs = Math.abs(dval);
                int fScale = 0;
                long fMantissa;
                String fStr = Double.toString(abs);
                // Handle scientific notation fallback
                if (fStr.contains("E") || fStr.contains("e")) {
                    byte[] fb = new byte[5];
                    fb[0] = (byte) 0x80;
                    int fbBits = Float.floatToIntBits(fval);
                    fb[1] = (byte)(fbBits >>> 24);
                    fb[2] = (byte)(fbBits >>> 16);
                    fb[3] = (byte)(fbBits >>> 8);
                    fb[4] = (byte)fbBits;
                    yield fb;
                }
                int fDotIdx = fStr.indexOf('.');
                if (fDotIdx != -1) {
                    String fTrimmed = fStr.replaceAll("0+$", "");
                    if (fTrimmed.endsWith(".")) {
                        fMantissa = Long.parseLong(fTrimmed.substring(0, fTrimmed.length() - 1));
                        fScale = 0;
                    } else {
                        int fTDot = fTrimmed.indexOf('.');
                        fScale = fTrimmed.length() - fTDot - 1;
                        if (fScale > 63) {
                            byte[] fb = new byte[5];
                            fb[0] = (byte) 0x80;
                            int fbBits = Float.floatToIntBits(fval);
                            fb[1] = (byte)(fbBits >>> 24);
                            fb[2] = (byte)(fbBits >>> 16);
                            fb[3] = (byte)(fbBits >>> 8);
                            fb[4] = (byte)fbBits;
                            yield fb;
                        }
                        fMantissa = Long.parseLong(fTrimmed.replace(".", ""));
                    }
                } else {
                    fMantissa = Long.parseLong(fStr);
                }
                // If mantissa too large, fallback
                if (fMantissa < 0 || fMantissa > 0x1FFFFFFFFFFFFFL) {
                    byte[] result = new byte[5];
                    result[0] = (byte) 0x80;
                    int bits = Float.floatToIntBits(fval);
                    result[1] = (byte)(bits >>> 24);
                    result[2] = (byte)(bits >>> 16);
                    result[3] = (byte)(bits >>> 8);
                    result[4] = (byte)bits;
                    yield result;
                }
                boolean fNegative = fval < 0;
                int fFirstByte = fNegative ? (fScale + 64) : fScale;
                byte[] fVarint = encodeVarInt(fMantissa);
                byte[] fResult = new byte[1 + fVarint.length];
                fResult[0] = (byte) fFirstByte;
                System.arraycopy(fVarint, 0, fResult, 1, fVarint.length);
                yield fResult;
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
            case VAR_INTEGER -> {
                int[] br = {0};
                long zigzag = decodeVarInt(payload, 0, br);
                long n = (zigzag >>> 1) ^ -(zigzag & 1);
                if (n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) yield (int) n;
                yield n;
            }
            case VAR_DOUBLE -> {
                int firstByte = payload[0] & 0xFF;
                if (firstByte == 0x80) {
                    // Fallback Float64
                    long bits = 0;
                    for (int i = 0; i < 8; i++) bits = (bits << 8) | (payload[1 + i] & 0xFF);
                    yield Double.longBitsToDouble(bits);
                }
                boolean negative = firstByte >= 64;
                int scale = negative ? (firstByte - 64) : firstByte;
                int[] bytesRead = {0};
                long mantissa = decodeVarInt(payload, 1, bytesRead);
                double v = mantissa / Math.pow(10, scale);
                if (negative) v = -v;
                yield v;
            }
            case VAR_FLOAT -> {
                int fFirstByte = payload[0] & 0xFF;
                if (fFirstByte == 0x80) {
                    // Fallback Float32
                    int bits = 0;
                    for (int i = 0; i < 4; i++) bits = (bits << 8) | (payload[1 + i] & 0xFF);
                    yield Float.intBitsToFloat(bits);
                }
                boolean fNegative = fFirstByte >= 64;
                int fScale = fNegative ? (fFirstByte - 64) : fFirstByte;
                int[] fBytesRead = {0};
                long fMantissa = decodeVarInt(payload, 1, fBytesRead);
                double fv = fMantissa / Math.pow(10, fScale);
                if (fNegative) fv = -fv;
                yield (float) fv;
            }
        };
    }

    private static final int[] FIXED_SIZES = {
        0, 1, 1, 2, 4, 8, 4, 8, 4, 8, -1, -1, 8, -1, -1, -1
    };

    private static void validateSize(DataType type, byte[] payload) {
        int expected = FIXED_SIZES[type.code()];
        if (expected >= 0 && payload.length != expected) {
            throw new DanWSException("PAYLOAD_SIZE_MISMATCH",
                    type + " expects " + expected + " bytes, got " + payload.length);
        }
    }

    static byte[] encodeVarInt(long value) {
        if (value == 0) return new byte[]{0};
        byte[] buf = new byte[10];
        int pos = 0;
        while (value > 0) {
            int b = (int)(value & 0x7F);
            value >>>= 7;
            if (value > 0) b |= 0x80;
            buf[pos++] = (byte) b;
        }
        return Arrays.copyOf(buf, pos);
    }

    static long decodeVarInt(byte[] data, int offset, int[] bytesRead) {
        long result = 0;
        int shift = 0;
        int pos = offset;
        while (pos < data.length) {
            int b = data[pos] & 0xFF;
            result |= (long)(b & 0x7F) << shift;
            shift += 7;
            pos++;
            if ((b & 0x80) == 0) break;
        }
        bytesRead[0] = pos - offset;
        return result;
    }
}
