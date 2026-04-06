package com.danws.protocol;

import java.io.ByteArrayOutputStream;

public final class DLE {
    public static final byte DLE_BYTE = 0x10;
    public static final byte STX = 0x02;
    public static final byte ETX = 0x03;
    public static final byte ENQ = 0x05;

    private DLE() {}

    public static byte[] encode(byte[] data) {
        int dleCount = 0;
        for (byte b : data) {
            if (b == DLE_BYTE) dleCount++;
        }
        if (dleCount == 0) return data;

        byte[] out = new byte[data.length + dleCount];
        int j = 0;
        for (byte b : data) {
            out[j++] = b;
            if (b == DLE_BYTE) out[j++] = DLE_BYTE;
        }
        return out;
    }

    public static byte[] decode(byte[] data) {
        int dleCount = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == DLE_BYTE) { i++; dleCount++; }
        }
        if (dleCount == 0) return data;

        byte[] out = new byte[data.length - dleCount];
        int j = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == DLE_BYTE) i++;
            out[j++] = data[i];
        }
        return out;
    }
}
