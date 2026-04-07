package com.danws.protocol;

import java.util.Arrays;
import java.util.function.Consumer;

import static com.danws.protocol.DLE.*;

public class StreamParser {
    private enum State { IDLE, AFTER_DLE, IN_FRAME, IN_FRAME_AFTER_DLE }

    private static final int INITIAL_CAPACITY = 256;

    private State state = State.IDLE;
    private byte[] buffer = new byte[INITIAL_CAPACITY];
    private int bufferLen = 0;

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
                        bufferLen = 0;
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
                        appendByte(b);
                    }
                }
                case IN_FRAME_AFTER_DLE -> {
                    if (b == ETX) {
                        try {
                            byte[] body = Arrays.copyOf(buffer, bufferLen);
                            Frame frame = parseFrame(body);
                            if (onFrame != null) onFrame.accept(frame);
                        } catch (Exception e) {
                            emitError(e);
                        }
                        bufferLen = 0;
                        state = State.IDLE;
                    } else if (b == DLE_BYTE) {
                        appendByte(DLE_BYTE);
                        state = State.IN_FRAME;
                    } else {
                        emitError(new DanWSException("INVALID_DLE_SEQUENCE",
                                "Invalid DLE in frame: 0x10 0x" + String.format("%02x", b)));
                        bufferLen = 0;
                        state = State.IDLE;
                    }
                }
            }
        }
    }

    public void reset() {
        state = State.IDLE;
        bufferLen = 0;
    }

    private Frame parseFrame(byte[] body) {
        if (body.length < 6) {
            throw new DanWSException("FRAME_PARSE_ERROR", "Frame body too short: " + body.length);
        }

        FrameType frameType = FrameType.fromCode(body[0] & 0xFF);
        int keyId = ((body[1] & 0xFF) << 24) | ((body[2] & 0xFF) << 16) | ((body[3] & 0xFF) << 8) | (body[4] & 0xFF);
        DataType dataType = DataType.fromCode(body[5] & 0xFF);

        byte[] rawPayload = Arrays.copyOfRange(body, 6, body.length);

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
                || ft == FrameType.AUTH_OK || ft == FrameType.SERVER_FLUSH_END;
    }

    private void appendByte(byte b) {
        if (bufferLen == buffer.length) {
            buffer = Arrays.copyOf(buffer, buffer.length * 2);
        }
        buffer[bufferLen++] = b;
    }

    private void emitError(Exception e) {
        if (onError != null) onError.accept(e);
    }
}
