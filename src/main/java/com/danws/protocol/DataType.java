package com.danws.protocol;

public enum DataType {
    NULL(0x00),
    BOOL(0x01),
    UINT8(0x02),
    UINT16(0x03),
    UINT32(0x04),
    UINT64(0x05),
    INT32(0x06),
    INT64(0x07),
    FLOAT32(0x08),
    FLOAT64(0x09),
    STRING(0x0A),
    BINARY(0x0B),
    TIMESTAMP(0x0C);

    private final int code;

    DataType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static DataType fromCode(int code) {
        for (DataType dt : values()) {
            if (dt.code == code) return dt;
        }
        throw new DanWSException("UNKNOWN_DATA_TYPE", "Unknown data type: 0x" + Integer.toHexString(code));
    }

    public static DataType detect(Object value) {
        if (value == null) return NULL;
        if (value instanceof Boolean) return BOOL;
        if (value instanceof Integer) return INT32;
        if (value instanceof Long) return INT64;
        if (value instanceof Float) return FLOAT32;
        if (value instanceof Double) return FLOAT64;
        if (value instanceof String) return STRING;
        if (value instanceof byte[]) return BINARY;
        if (value instanceof java.util.Date) return TIMESTAMP;
        if (value instanceof java.time.Instant) return TIMESTAMP;
        if (value instanceof java.math.BigDecimal) return FLOAT64;
        if (value instanceof java.math.BigInteger) {
            java.math.BigInteger bi = (java.math.BigInteger) value;
            int bitLen = bi.bitLength();
            if (bitLen < 64) return INT64;
            return STRING;
        }
        if (value instanceof Short) return INT32;
        if (value instanceof Byte) return UINT8;
        throw new DanWSException("INVALID_VALUE_TYPE", "Cannot detect type for: " + value.getClass().getName());
    }
}
