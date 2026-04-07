package com.danws.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.danws.protocol.DLE.*;

public final class Codec {

    private Codec() {}

    public static byte[] encode(Frame frame) {
        byte[] rawPayload;
        if (isKeyRegistrationFrame(frame.frameType())) {
            rawPayload = Serializer.serialize(DataType.STRING, frame.payload());
        } else if (isSignalFrame(frame.frameType())) {
            rawPayload = new byte[0];
        } else {
            rawPayload = Serializer.serialize(frame.dataType(), frame.payload());
        }

        byte[] rawBody = new byte[6 + rawPayload.length];
        rawBody[0] = (byte) frame.frameType().code();
        rawBody[1] = (byte) ((frame.keyId() >>> 24) & 0xFF);
        rawBody[2] = (byte) ((frame.keyId() >>> 16) & 0xFF);
        rawBody[3] = (byte) ((frame.keyId() >>> 8) & 0xFF);
        rawBody[4] = (byte) (frame.keyId() & 0xFF);
        rawBody[5] = (byte) frame.dataType().code();
        System.arraycopy(rawPayload, 0, rawBody, 6, rawPayload.length);

        byte[] escapedBody = DLE.encode(rawBody);

        byte[] result = new byte[2 + escapedBody.length + 2];
        result[0] = DLE_BYTE;
        result[1] = STX;
        System.arraycopy(escapedBody, 0, result, 2, escapedBody.length);
        result[result.length - 2] = DLE_BYTE;
        result[result.length - 1] = ETX;

        return result;
    }

    public static byte[] encodeBatch(List<Frame> frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Frame f : frames) {
            byte[] encoded = encode(f);
            out.write(encoded, 0, encoded.length);
        }
        return out.toByteArray();
    }

    public static byte[] encodeHeartbeat() {
        return new byte[]{DLE_BYTE, ENQ};
    }

    public static List<Frame> decode(byte[] bytes) {
        List<Frame> frames = new ArrayList<>();
        int i = 0;

        while (i < bytes.length) {
            if (i + 1 >= bytes.length || bytes[i] != DLE_BYTE || bytes[i + 1] != STX) {
                throw new DanWSException("FRAME_PARSE_ERROR", "Expected DLE STX at offset " + i);
            }
            i += 2;

            int bodyStart = i;
            int bodyEnd = -1;

            while (i < bytes.length) {
                if (bytes[i] == DLE_BYTE) {
                    if (i + 1 >= bytes.length) {
                        throw new DanWSException("FRAME_PARSE_ERROR", "Unexpected end after DLE");
                    }
                    if (bytes[i + 1] == ETX) { bodyEnd = i; i += 2; break; }
                    else if (bytes[i + 1] == DLE_BYTE) { i += 2; }
                    else { throw new DanWSException("INVALID_DLE_SEQUENCE", "Invalid DLE sequence"); }
                } else { i++; }
            }

            if (bodyEnd == -1) throw new DanWSException("FRAME_PARSE_ERROR", "Missing DLE ETX");

            byte[] body = DLE.decode(Arrays.copyOfRange(bytes, bodyStart, bodyEnd));
            if (body.length < 6) throw new DanWSException("FRAME_PARSE_ERROR", "Frame body too short: " + body.length);

            FrameType frameType = FrameType.fromCode(body[0] & 0xFF);
            int keyId = ((body[1] & 0xFF) << 24) | ((body[2] & 0xFF) << 16) | ((body[3] & 0xFF) << 8) | (body[4] & 0xFF);
            DataType dataType = DataType.fromCode(body[5] & 0xFF);
            byte[] rawPayload = Arrays.copyOfRange(body, 6, body.length);

            Object payload;
            if (isKeyRegistrationFrame(frameType)) {
                payload = Serializer.deserialize(DataType.STRING, rawPayload);
            } else if (isSignalFrame(frameType)) {
                payload = null;
            } else {
                payload = Serializer.deserialize(dataType, rawPayload);
            }

            frames.add(new Frame(frameType, keyId, dataType, payload));
        }

        return frames;
    }

    private static boolean isKeyRegistrationFrame(FrameType ft) {
        return ft == FrameType.SERVER_KEY_REGISTRATION || ft == FrameType.CLIENT_KEY_REGISTRATION;
    }

    private static boolean isSignalFrame(FrameType ft) {
        return ft == FrameType.SERVER_SYNC
                || ft == FrameType.CLIENT_READY
                || ft == FrameType.CLIENT_SYNC
                || ft == FrameType.SERVER_READY
                || ft == FrameType.SERVER_RESET
                || ft == FrameType.CLIENT_RESYNC_REQ
                || ft == FrameType.CLIENT_RESET
                || ft == FrameType.SERVER_RESYNC_REQ
                || ft == FrameType.AUTH_OK
                || ft == FrameType.SERVER_FLUSH_END;
    }
}
