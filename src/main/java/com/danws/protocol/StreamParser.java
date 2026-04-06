package com.danws.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static com.danws.protocol.DLE.*;

public class StreamParser {
    private enum State { IDLE, AFTER_DLE, IN_FRAME, IN_FRAME_AFTER_DLE }

    private State state = State.IDLE;
    private final List<Byte> buffer = new ArrayList<>();

    private Consumer<Frame> onFrame;
    private Runnable onHeartbeat;
    private Consumer<Exception> onError;

    public void onFrame(Consumer<Frame> callback) { this.onFrame = callback; }
    public void onHeartbeat(Runnable callback) { this.onHeartbeat = callback; }
    public void onError(Consumer<Exception> callback) { this.onError = callback; }

    public void feed(byte[] chunk) {
        for (byte b : chunk) {
            switch (state) {
                case IDLE -> {
                    if (b == DLE_BYTE) {
                        state = State.AFTER_DLE;
                    } else {
                        emitError(new DanWSException("FRAME_PARSE_ERROR",
                                "Unexpected byte 0x" + String.format("%02x", b) + " outside frame"));
                    }
                }
                case AFTER_DLE -> {
                    if (b == STX) {
                        state = State.IN_FRAME;
                        buffer.clear();
                    } else if (b == ENQ) {
                        if (onHeartbeat != null) onHeartbeat.run();
                        state = State.IDLE;
                    } else {
                        emitError(new DanWSException("INVALID_DLE_SEQUENCE",
                                "Invalid DLE sequence: 0x10 0x" + String.format("%02x", b)));
                        state = State.IDLE;
                    }
                }
                case IN_FRAME -> {
                    if (b == DLE_BYTE) {
                        state = State.IN_FRAME_AFTER_DLE;
                    } else {
                        buffer.add(b);
                    }
                }
                case IN_FRAME_AFTER_DLE -> {
                    if (b == ETX) {
                        try {
                            byte[] body = toByteArray(buffer);
                            Frame frame = parseFrame(body);
                            if (onFrame != null) onFrame.accept(frame);
                        } catch (Exception e) {
                            emitError(e);
                        }
                        buffer.clear();
                        state = State.IDLE;
                    } else if (b == DLE_BYTE) {
                        buffer.add(DLE_BYTE);
                        state = State.IN_FRAME;
                    } else {
                        emitError(new DanWSException("INVALID_DLE_SEQUENCE",
                                "Invalid DLE in frame: 0x10 0x" + String.format("%02x", b)));
                        buffer.clear();
                        state = State.IDLE;
                    }
                }
            }
        }
    }

    public void reset() {
        state = State.IDLE;
        buffer.clear();
    }

    private Frame parseFrame(byte[] body) {
        if (body.length < 4) {
            throw new DanWSException("FRAME_PARSE_ERROR", "Frame body too short: " + body.length);
        }

        FrameType frameType = FrameType.fromCode(body[0] & 0xFF);
        int keyId = ((body[1] & 0xFF) << 8) | (body[2] & 0xFF);
        DataType dataType = DataType.fromCode(body[3] & 0xFF);

        byte[] rawPayload = Arrays.copyOfRange(body, 4, body.length);

        Object payload;
        if (frameType == FrameType.SERVER_KEY_REGISTRATION || frameType == FrameType.CLIENT_KEY_REGISTRATION) {
            payload = Serializer.deserialize(DataType.STRING, rawPayload);
        } else if (isSignalFrame(frameType)) {
            payload = null;
        } else {
            payload = Serializer.deserialize(dataType, rawPayload);
        }

        return new Frame(frameType, keyId, dataType, payload);
    }

    private boolean isSignalFrame(FrameType ft) {
        return ft == FrameType.SERVER_SYNC || ft == FrameType.CLIENT_READY
                || ft == FrameType.CLIENT_SYNC || ft == FrameType.SERVER_READY
                || ft == FrameType.SERVER_RESET || ft == FrameType.CLIENT_RESYNC_REQ
                || ft == FrameType.CLIENT_RESET || ft == FrameType.SERVER_RESYNC_REQ
                || ft == FrameType.AUTH_OK;
    }

    private void emitError(Exception e) {
        if (onError != null) onError.accept(e);
    }

    private static byte[] toByteArray(List<Byte> list) {
        byte[] arr = new byte[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }
}
