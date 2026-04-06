package com.danws.protocol;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CodecTest {

    private static String hex(byte[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", arr[i] & 0xFF));
        }
        return sb.toString();
    }

    @Test
    void serverKeyRegistration() {
        Frame frame = Frame.keyRegistration(0x0001, DataType.BOOL, "root.status.alive");
        byte[] bytes = Codec.encode(frame);
        String expected = "10 02 00 00 01 01 72 6f 6f 74 2e 73 74 61 74 75 73 2e 61 6c 69 76 65 10 03";
        assertEquals(expected, hex(bytes));
    }

    @Test
    void serverValueBool() {
        Frame frame = Frame.value(0x0001, DataType.BOOL, true);
        byte[] bytes = Codec.encode(frame);
        assertEquals("10 02 01 00 01 01 01 10 03", hex(bytes));
    }

    @Test
    void serverValueString() {
        Frame frame = Frame.value(0x0002, DataType.STRING, "Alice");
        byte[] bytes = Codec.encode(frame);
        assertEquals("10 02 01 00 02 0a 41 6c 69 63 65 10 03", hex(bytes));
    }

    @Test
    void serverValueFloat64() {
        Frame frame = Frame.value(0x0001, DataType.FLOAT64, 23.5);
        byte[] bytes = Codec.encode(frame);
        List<Frame> decoded = Codec.decode(bytes);
        assertEquals(1, decoded.size());
        assertEquals(23.5, (double) decoded.get(0).payload(), 0.001);
    }

    @Test
    void signalFrames() {
        assertEquals("10 02 04 00 00 00 10 03", hex(Codec.encode(Frame.signal(FrameType.SERVER_SYNC))));
        assertEquals("10 02 05 00 00 00 10 03", hex(Codec.encode(Frame.signal(FrameType.CLIENT_READY))));
        assertEquals("10 02 09 00 00 00 10 03", hex(Codec.encode(Frame.signal(FrameType.SERVER_RESET))));
        assertEquals("10 02 0a 00 00 00 10 03", hex(Codec.encode(Frame.signal(FrameType.CLIENT_RESYNC_REQ))));
        assertEquals("10 02 0f 00 00 00 10 03", hex(Codec.encode(Frame.signal(FrameType.AUTH_OK))));
    }

    @Test
    void heartbeat() {
        assertArrayEquals(new byte[]{0x10, 0x05}, Codec.encodeHeartbeat());
    }

    @Test
    void dleEscapingInPayload() {
        // String containing 0x10
        Frame frame = Frame.value(0x0001, DataType.STRING, "A\u0010B");
        byte[] encoded = Codec.encode(frame);
        List<Frame> decoded = Codec.decode(encoded);
        assertEquals("A\u0010B", decoded.get(0).payload());
    }

    @Test
    void dleEscapingInKeyId() {
        // KeyID 0x0010 — the low byte is 0x10
        Frame frame = Frame.value(0x0010, DataType.BOOL, true);
        byte[] encoded = Codec.encode(frame);
        List<Frame> decoded = Codec.decode(encoded);
        assertEquals(0x0010, decoded.get(0).keyId());
        assertEquals(true, decoded.get(0).payload());
    }

    @Test
    void batchEncodeDecode() {
        List<Frame> frames = List.of(
                Frame.keyRegistration(0x0001, DataType.BOOL, "root.alive"),
                Frame.keyRegistration(0x0002, DataType.STRING, "root.name"),
                Frame.signal(FrameType.SERVER_SYNC)
        );
        byte[] batch = Codec.encodeBatch(frames);
        List<Frame> decoded = Codec.decode(batch);
        assertEquals(3, decoded.size());
        assertEquals("root.alive", decoded.get(0).payload());
        assertEquals("root.name", decoded.get(1).payload());
        assertEquals(FrameType.SERVER_SYNC, decoded.get(2).frameType());
    }

    @Test
    void roundtripAllTypes() {
        Object[][] cases = {
                {DataType.NULL, null},
                {DataType.BOOL, true},
                {DataType.BOOL, false},
                {DataType.UINT8, 42},
                {DataType.INT32, -12345},
                {DataType.INT64, 9876543210L},
                {DataType.FLOAT32, 23.5f},
                {DataType.FLOAT64, 3.141592653589793},
                {DataType.STRING, "Hello, World!"},
                {DataType.BINARY, new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF}},
        };

        for (Object[] c : cases) {
            DataType dt = (DataType) c[0];
            Object val = c[1];
            Frame frame = Frame.value(0x0001, dt, val);
            byte[] encoded = Codec.encode(frame);
            List<Frame> decoded = Codec.decode(encoded);
            assertEquals(1, decoded.size());
            if (val instanceof byte[]) {
                assertArrayEquals((byte[]) val, (byte[]) decoded.get(0).payload());
            } else if (val instanceof Float) {
                assertEquals((float) val, ((Number) decoded.get(0).payload()).floatValue(), 0.001);
            } else {
                assertEquals(val, decoded.get(0).payload());
            }
        }
    }
}
