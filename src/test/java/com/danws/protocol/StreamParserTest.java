package com.danws.protocol;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StreamParserTest {

    @Test
    void parseSingleFrame() {
        StreamParser parser = new StreamParser();
        List<Frame<?>> frames = new ArrayList<>();
        parser.onFrame(frames::add);

        byte[] data = Codec.encode(Frame.value(0x0001, DataType.BOOL, true));
        parser.feed(data);

        assertEquals(1, frames.size());
        assertEquals(true, frames.get(0).payload());
    }

    @Test
    void parseBatch() {
        StreamParser parser = new StreamParser();
        List<Frame<?>> frames = new ArrayList<>();
        parser.onFrame(frames::add);

        byte[] batch = Codec.encodeBatch(List.of(
                Frame.value(0x0001, DataType.BOOL, true),
                Frame.value(0x0002, DataType.STRING, "hello"),
                Frame.signal(FrameType.SERVER_SYNC)
        ));
        parser.feed(batch);

        assertEquals(3, frames.size());
        assertEquals(true, frames.get(0).payload());
        assertEquals("hello", frames.get(1).payload());
        assertEquals(FrameType.SERVER_SYNC, frames.get(2).frameType());
    }

    @Test
    void byteByByte() {
        StreamParser parser = new StreamParser();
        List<Frame<?>> frames = new ArrayList<>();
        parser.onFrame(frames::add);

        byte[] data = Codec.encode(Frame.value(0x0001, DataType.INT32, -42));
        for (byte b : data) {
            parser.feed(new byte[]{b});
        }

        assertEquals(1, frames.size());
        assertEquals(-42, frames.get(0).payload());
    }

    @Test
    void heartbeat() {
        StreamParser parser = new StreamParser();
        int[] count = {0};
        parser.onHeartbeat(() -> count[0]++);

        parser.feed(Codec.encodeHeartbeat());
        assertEquals(1, count[0]);

        // Split across chunks
        parser.feed(new byte[]{0x10});
        parser.feed(new byte[]{0x05});
        assertEquals(2, count[0]);
    }

    @Test
    void interleaveHeartbeatAndFrame() {
        StreamParser parser = new StreamParser();
        List<Frame<?>> frames = new ArrayList<>();
        int[] hb = {0};
        parser.onFrame(frames::add);
        parser.onHeartbeat(() -> hb[0]++);

        byte[] f1 = Codec.encode(Frame.value(0x0001, DataType.BOOL, true));
        byte[] h = Codec.encodeHeartbeat();
        byte[] f2 = Codec.encode(Frame.value(0x0002, DataType.STRING, "x"));

        byte[] combined = new byte[f1.length + h.length + f2.length];
        System.arraycopy(f1, 0, combined, 0, f1.length);
        System.arraycopy(h, 0, combined, f1.length, h.length);
        System.arraycopy(f2, 0, combined, f1.length + h.length, f2.length);

        parser.feed(combined);
        assertEquals(2, frames.size());
        assertEquals(1, hb[0]);
    }

    @Test
    void dleEscapedPayload() {
        StreamParser parser = new StreamParser();
        List<Frame<?>> frames = new ArrayList<>();
        parser.onFrame(frames::add);

        byte[] data = Codec.encode(Frame.value(0x0001, DataType.STRING, "A\u0010B"));
        parser.feed(data);

        assertEquals(1, frames.size());
        assertEquals("A\u0010B", frames.get(0).payload());
    }

    @Test
    void errorRecovery() {
        StreamParser parser = new StreamParser();
        List<Frame<?>> frames = new ArrayList<>();
        List<Exception> errors = new ArrayList<>();
        parser.onFrame(frames::add);
        parser.onError(errors::add);

        byte[] valid = Codec.encode(Frame.value(0x0001, DataType.BOOL, false));
        byte[] combined = new byte[1 + valid.length];
        combined[0] = 0x42; // unexpected
        System.arraycopy(valid, 0, combined, 1, valid.length);

        parser.feed(combined);
        assertEquals(1, errors.size());
        assertEquals(1, frames.size());
        assertEquals(false, frames.get(0).payload());
    }

    @Test
    void keyIdWith0x10() {
        StreamParser parser = new StreamParser();
        List<Frame<?>> frames = new ArrayList<>();
        parser.onFrame(frames::add);

        byte[] data = Codec.encode(Frame.value(0x0010, DataType.BOOL, true));
        parser.feed(data);

        assertEquals(1, frames.size());
        assertEquals(0x0010, frames.get(0).keyId());
    }
}
