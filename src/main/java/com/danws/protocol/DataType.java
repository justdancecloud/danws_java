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
    TIMESTAMP(0x0C),
    VAR_INTEGER(0x0D),
    VAR_DOUBLE(0x0E),
    VAR_FLOAT(0x0F);

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
        if (value instanceof Integer) return VAR_INTEGER;
        if (value instanceof Long) return VAR_INTEGER;
        if (value instanceof Short) return VAR_INTEGER;
        if (value instanceof Byte) return VAR_INTEGER;
        if (value instanceof Double) return VAR_DOUBLE;
        if (value instanceof Float) return VAR_FLOAT;
        if (value instanceof String) return STRING;
        if (value instanceof byte[]) return BINARY;
        if (value instanceof java.util.Date) return TIMESTAMP;
        if (value instanceof java.time.Instant) return TIMESTAMP;
        if (value instanceof java.math.BigDecimal) return VAR_DOUBLE;
        if (value instanceof java.math.BigInteger) {
            java.math.BigInteger bi = (java.math.BigInteger) value;
            int bitLen = bi.bitLength();
            if (bitLen < 64) return VAR_INTEGER;
            return STRING;
        }
        throw new DanWSException("INVALID_VALUE_TYPE", "Cannot detect type for: " + value.getClass().getName());
    }
}
