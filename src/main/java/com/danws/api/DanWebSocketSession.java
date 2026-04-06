package com.danws.api;

import com.danws.protocol.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class DanWebSocketSession {
    public enum State { PENDING, AUTHORIZED, SYNCHRONIZING, READY, DISCONNECTED }

    private final String id;
    private String principal;
    private boolean authorized;
    private boolean connected = true;
    private State state = State.PENDING;

    private Consumer<Frame> enqueueFrame;
    private Supplier<List<Frame>> txKeyFrameProvider;
    private Supplier<List<Frame>> txValueFrameProvider;

    private final List<Runnable> onReady = new ArrayList<>();
    private final List<Runnable> onDisconnect = new ArrayList<>();
    private final List<Consumer<DanWSException>> onError = new ArrayList<>();

    private boolean serverSyncSent;
    private boolean clientReadyReceived;

    public DanWebSocketSession(String clientUuid) {
        this.id = clientUuid;
    }

    public String id() { return id; }
    public String principal() { return principal; }
    public boolean authorized() { return authorized; }
    public boolean connected() { return connected; }
    public State state() { return state; }

    public void onReady(Runnable cb) { onReady.add(cb); }
    public void onDisconnect(Runnable cb) { onDisconnect.add(cb); }
    public void onError(Consumer<DanWSException> cb) { onError.add(cb); }

    public void disconnect() {
        connected = false;
        state = State.DISCONNECTED;
        onDisconnect.forEach(Runnable::run);
    }

    public void close() {
        connected = false;
        state = State.DISCONNECTED;
    }

    // ──── Internal ────

    void setEnqueue(Consumer<Frame> fn) { this.enqueueFrame = fn; }

    void setTxProviders(Supplier<List<Frame>> keyFrames, Supplier<List<Frame>> valueFrames) {
        this.txKeyFrameProvider = keyFrames;
        this.txValueFrameProvider = valueFrames;
    }

    void authorize(String principal) {
        this.principal = principal;
        this.authorized = true;
        this.state = State.AUTHORIZED;
    }

    void startSync() {
        state = State.SYNCHRONIZING;
        serverSyncSent = false;
        clientReadyReceived = false;

        if (txKeyFrameProvider != null && enqueueFrame != null) {
            List<Frame> frames = txKeyFrameProvider.get();
            if (!frames.isEmpty()) {
                frames.forEach(enqueueFrame);
                serverSyncSent = true;
            } else {
                state = State.READY;
                onReady.forEach(Runnable::run);
            }
        } else {
            state = State.READY;
            onReady.forEach(Runnable::run);
        }
    }

    void handleFrame(Frame frame) {
        switch (frame.frameType()) {
            case CLIENT_READY -> {
                clientReadyReceived = true;
                if (txValueFrameProvider != null && enqueueFrame != null) {
                    txValueFrameProvider.get().forEach(enqueueFrame);
                }
                if (serverSyncSent) {
                    state = State.READY;
                    onReady.forEach(Runnable::run);
                }
            }
            case CLIENT_RESYNC_REQ -> {
                if (txKeyFrameProvider != null && enqueueFrame != null) {
                    enqueueFrame.accept(Frame.signal(FrameType.SERVER_RESET));
                    txKeyFrameProvider.get().forEach(enqueueFrame);
                    clientReadyReceived = false;
                }
            }
            case ERROR -> {
                var err = new DanWSException("REMOTE_ERROR", String.valueOf(frame.payload()));
                onError.forEach(cb -> cb.accept(err));
            }
            default -> {}
        }
    }

    void handleDisconnect() {
        connected = false;
        state = State.DISCONNECTED;
        onDisconnect.forEach(Runnable::run);
    }

    void handleReconnect() {
        connected = true;
        state = State.AUTHORIZED;
    }
}
