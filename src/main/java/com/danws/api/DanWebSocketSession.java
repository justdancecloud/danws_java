package com.danws.api;

import com.danws.protocol.*;
import com.danws.state.KeyRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

    // Session-level TX store (topic modes)
    private final KeyRegistry sessionRegistry = new KeyRegistry();
    private final Map<Integer, Object> sessionStore = new ConcurrentHashMap<>();
    private Consumer<Frame> sessionEnqueue;
    private boolean sessionBound;

    // Topic state
    private final Map<String, TopicInfo> topics = new LinkedHashMap<>();

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

    // ---- Session-level data API (topic modes) ----

    public void set(String key, Object value) {
        if (!sessionBound) {
            throw new DanWSException("INVALID_MODE", "session.set() is only available in topic modes.");
        }
        KeyRegistry.validateKeyPath(key);
        DataType newType = DataType.detect(value);
        Serializer.serialize(newType, value);

        KeyRegistry.KeyEntry existing = sessionRegistry.getByPath(key);

        if (existing == null) {
            int keyId = sessionRegistry.registerNew(key, newType);
            sessionStore.put(keyId, value);
            triggerSessionResync();
            return;
        }

        if (existing.type() != newType) {
            sessionRegistry.remove(key);
            int keyId = sessionRegistry.registerNew(key, newType);
            sessionStore.remove(existing.keyId());
            sessionStore.put(keyId, value);
            triggerSessionResync();
            return;
        }

        sessionStore.put(existing.keyId(), value);
        if (sessionEnqueue != null) {
            sessionEnqueue.accept(Frame.value(existing.keyId(), existing.type(), value));
        }
    }

    public Object get(String key) {
        KeyRegistry.KeyEntry entry = sessionRegistry.getByPath(key);
        return entry != null ? sessionStore.get(entry.keyId()) : null;
    }

    public List<String> keys() {
        return sessionRegistry.paths();
    }

    public void clearKey(String key) {
        if (!sessionBound) return;
        KeyRegistry.KeyEntry entry = sessionRegistry.getByPath(key);
        if (entry != null) {
            sessionRegistry.remove(key);
            sessionStore.remove(entry.keyId());
            triggerSessionResync();
        }
    }

    public void clearKey() {
        if (!sessionBound) return;
        if (sessionRegistry.size() > 0) {
            sessionRegistry.clear();
            sessionStore.clear();
            triggerSessionResync();
        }
    }

    // ---- Topic API ----

    public List<String> topics() {
        return new ArrayList<>(topics.keySet());
    }

    public TopicInfo topic(String name) {
        return topics.get(name);
    }

    // ---- Internal methods ----

    void setEnqueue(Consumer<Frame> fn) { this.enqueueFrame = fn; }

    void setTxProviders(Supplier<List<Frame>> keyFrames, Supplier<List<Frame>> valueFrames) {
        this.txKeyFrameProvider = keyFrames;
        this.txValueFrameProvider = valueFrames;
    }

    void bindSessionTX(Consumer<Frame> enqueue) {
        this.sessionEnqueue = enqueue;
        this.sessionBound = true;
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
                enqueueFrame.accept(Frame.signal(FrameType.SERVER_SYNC));
                serverSyncSent = true;
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

    void addTopic(String name, Map<String, Object> params) {
        topics.put(name, new TopicInfo(name, params));
    }

    boolean removeTopic(String name) {
        return topics.remove(name) != null;
    }

    void updateTopicParams(String name, Map<String, Object> params) {
        TopicInfo t = topics.get(name);
        if (t != null) topics.put(name, new TopicInfo(name, params));
    }

    private void triggerSessionResync() {
        if (sessionEnqueue == null) return;

        sessionEnqueue.accept(Frame.signal(FrameType.SERVER_RESET));

        for (KeyRegistry.KeyEntry entry : sessionRegistry.entries()) {
            sessionEnqueue.accept(Frame.keyRegistration(entry.keyId(), entry.type(), entry.path()));
        }

        sessionEnqueue.accept(Frame.signal(FrameType.SERVER_SYNC));

        for (KeyRegistry.KeyEntry entry : sessionRegistry.entries()) {
            Object val = sessionStore.get(entry.keyId());
            if (val != null) {
                sessionEnqueue.accept(Frame.value(entry.keyId(), entry.type(), val));
            }
        }
    }
}
