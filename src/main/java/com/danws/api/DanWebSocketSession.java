package com.danws.api;

import com.danws.protocol.*;
import com.danws.state.KeyRegistry;

import io.netty.channel.EventLoop;

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

    // Session-level flat TX store (backward compat)
    private final KeyRegistry sessionRegistry = new KeyRegistry();
    private final Map<Integer, Object> sessionStore = new ConcurrentHashMap<>();
    private int nextKeyId = 1; // global keyId counter — shared by flat session keys and all topic payloads
    private Consumer<Frame> sessionEnqueue;
    private boolean sessionBound;
    private final Map<String, Set<String>> flattenedKeys = new java.util.HashMap<>();
    private final Map<String, List<Object>> previousArrays = new java.util.HashMap<>();

    // Topic handles
    private final Map<String, TopicHandle> topicHandles = new LinkedHashMap<>();
    private int topicIndex = 0;
    private final Map<String, TopicInfo> topics = new LinkedHashMap<>();
    private EventLoop eventLoop;

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

    // ---- Session-level flat data API (backward compat) ----

    public void set(String key, Object value) {
        if (!sessionBound) {
            throw new DanWSException("INVALID_MODE", "session.set() is only available in topic modes.");
        }
        if (Flatten.shouldFlatten(value)) {
            // Array shift detection for List values
            if (value instanceof List<?> newArr) {
                List<Object> oldArr = previousArrays.get(key);
                if (oldArr != null && !oldArr.isEmpty() && !newArr.isEmpty()) {
                    int[] shift = detectSessionArrayShiftBoth(oldArr, newArr);
                    if (shift[0] == 1) { // left
                        applySessionArrayShiftLeft(key, oldArr, newArr, shift[1]);
                        return;
                    }
                    if (shift[0] == 2) { // right
                        applySessionArrayShiftRight(key, oldArr, newArr, shift[1]);
                        return;
                    }
                }
                previousArrays.put(key, new java.util.ArrayList<>(newArr));
            }

            Map<String, Object> flattened = Flatten.flatten(key, value);
            Set<String> newKeys = flattened.keySet();
            Set<String> oldKeys = flattenedKeys.get(key);
            boolean needsResync = false;
            if (oldKeys != null) {
                for (String oldPath : oldKeys) {
                    if (!newKeys.contains(oldPath)) {
                        if (isArrayIndexKey(key, oldPath)) continue; // stale array index — client uses .length
                        KeyRegistry.KeyEntry entry = sessionRegistry.getByPath(oldPath);
                        if (entry != null) { sessionRegistry.remove(oldPath); sessionStore.remove(entry.keyId()); }
                        needsResync = true;
                    }
                }
            }
            flattenedKeys.put(key, new java.util.HashSet<>(newKeys));
            for (var entry : flattened.entrySet()) {
                setLeaf(entry.getKey(), entry.getValue());
            }
            if (needsResync) triggerSessionResync();
            return;
        }
        setLeaf(key, value);
    }

    private int[] detectSessionArrayShiftBoth(List<Object> oldArr, List<?> newArr) {
        int oldLen = oldArr.size();
        int newLen = newArr.size();

        // 1. Left shift: find new[0] in oldArr → gives shift amount k
        Object newFirst = newArr.get(0);
        for (int k = 1; k < oldLen; k++) {
            if (!Objects.equals(oldArr.get(k), newFirst)) continue;
            int matchLen = Math.min(oldLen - k, newLen);
            if (matchLen <= 0) continue;
            boolean match = true;
            for (int i = 1; i < matchLen; i++) {
                if (!Objects.equals(oldArr.get(i + k), newArr.get(i))) { match = false; break; }
            }
            if (match) return new int[]{1, k};
        }

        // 2. Right shift: find old[0] in newArr → gives shift amount k
        Object oldFirst = oldArr.get(0);
        for (int k = 1; k < newLen; k++) {
            if (!Objects.equals(newArr.get(k), oldFirst)) continue;
            int matchLen = Math.min(oldLen, newLen - k);
            if (matchLen <= 0) continue;
            boolean match = true;
            for (int i = 1; i < matchLen; i++) {
                if (!Objects.equals(oldArr.get(i), newArr.get(i + k))) { match = false; break; }
            }
            if (match) return new int[]{2, k};
        }

        return new int[]{0, 0};
    }

    private void applySessionArrayShiftLeft(String key, List<Object> oldArr, List<?> newArr, int shiftCount) {
        int oldLen = oldArr.size();
        int newLen = newArr.size();

        // 1. Send ARRAY_SHIFT_LEFT frame
        KeyRegistry.KeyEntry lengthEntry = sessionRegistry.getByPath(key + ".length");
        if (lengthEntry != null && sessionEnqueue != null) {
            sessionEnqueue.accept(new Frame(FrameType.ARRAY_SHIFT_LEFT, lengthEntry.keyId(), DataType.INT32, shiftCount));
        }

        // 2. Silently update internal store for shifted indices (low to high)
        for (int i = 0; i < newLen && i < oldLen - shiftCount; i++) {
            KeyRegistry.KeyEntry entry = sessionRegistry.getByPath(key + "." + i);
            if (entry != null) {
                sessionStore.put(entry.keyId(), newArr.get(i));
            }
        }

        // 3. Send new tail elements
        int existingAfterShift = oldLen - shiftCount;
        for (int i = existingAfterShift; i < newLen; i++) {
            Object elem = newArr.get(i);
            if (Flatten.shouldFlatten(elem)) {
                Map<String, Object> elemFlat = Flatten.flatten(key + "." + i, elem);
                for (var e : elemFlat.entrySet()) setLeaf(e.getKey(), e.getValue());
            } else {
                setLeaf(key + "." + i, elem);
            }
        }

        // 4. Update length if changed
        if (newLen != oldLen) {
            setLeaf(key + ".length", (double) newLen);
        }

        // 5. Update flattenedKeys
        Map<String, Object> flattened = Flatten.flatten(key, newArr);
        flattenedKeys.put(key, new java.util.HashSet<>(flattened.keySet()));

        // 6. Update previousArrays
        previousArrays.put(key, new java.util.ArrayList<>(newArr));
    }

    private void applySessionArrayShiftRight(String key, List<Object> oldArr, List<?> newArr, int shiftCount) {
        int oldLen = oldArr.size();
        int newLen = newArr.size();

        // 1. Send ARRAY_SHIFT_RIGHT frame
        KeyRegistry.KeyEntry lengthEntry = sessionRegistry.getByPath(key + ".length");
        if (lengthEntry != null && sessionEnqueue != null) {
            sessionEnqueue.accept(new Frame(FrameType.ARRAY_SHIFT_RIGHT, lengthEntry.keyId(), DataType.INT32, shiftCount));
        }

        // 2. Silently update internal store for shifted indices (high to low)
        for (int i = oldLen - 1; i >= 0; i--) {
            KeyRegistry.KeyEntry srcEntry = sessionRegistry.getByPath(key + "." + i);
            KeyRegistry.KeyEntry dstEntry = sessionRegistry.getByPath(key + "." + (i + shiftCount));
            if (srcEntry != null && dstEntry != null) {
                sessionStore.put(dstEntry.keyId(), oldArr.get(i));
            }
        }

        // 3. Send new head elements (indices 0..shiftCount-1)
        for (int i = 0; i < shiftCount; i++) {
            Object elem = newArr.get(i);
            if (Flatten.shouldFlatten(elem)) {
                Map<String, Object> elemFlat = Flatten.flatten(key + "." + i, elem);
                for (var e : elemFlat.entrySet()) setLeaf(e.getKey(), e.getValue());
            } else {
                setLeaf(key + "." + i, elem);
            }
        }

        // 4. Update length if changed
        if (newLen != oldLen) {
            setLeaf(key + ".length", (double) newLen);
        }

        // 5. Update flattenedKeys
        Map<String, Object> flattened = Flatten.flatten(key, newArr);
        flattenedKeys.put(key, new java.util.HashSet<>(flattened.keySet()));

        // 6. Update previousArrays
        previousArrays.put(key, new java.util.ArrayList<>(newArr));
    }

    private void setLeaf(String key, Object value) {
        KeyRegistry.validateKeyPath(key);
        DataType newType = DataType.detect(value);
        Serializer.serialize(newType, value);

        KeyRegistry.KeyEntry existing = sessionRegistry.getByPath(key);

        if (existing == null) {
            int keyId = nextKeyId++;
            sessionRegistry.registerOne(keyId, key, newType);
            sessionStore.put(keyId, value);
            if (sessionEnqueue != null) {
                sessionEnqueue.accept(Frame.keyRegistration(keyId, newType, key));
                sessionEnqueue.accept(Frame.signal(FrameType.SERVER_SYNC));
                sessionEnqueue.accept(Frame.value(keyId, newType, value));
            }
            return;
        }

        if (existing.type() != newType) {
            sessionRegistry.remove(key);
            int keyId = nextKeyId++;
            sessionRegistry.registerOne(keyId, key, newType);
            sessionStore.remove(existing.keyId());
            sessionStore.put(keyId, value);
            triggerSessionResync();
            return;
        }

        if (Objects.equals(sessionStore.get(existing.keyId()), value)) return;

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

    // ---- Topic API (backward compat) ----

    public List<String> topics() { return new ArrayList<>(topics.keySet()); }
    public TopicInfo topic(String name) { return topics.get(name); }

    // ---- Topic Handle API (new) ----

    public TopicHandle getTopicHandle(String name) { return topicHandles.get(name); }
    public Map<String, TopicHandle> topicHandles() { return topicHandles; }

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

    void setEventLoop(EventLoop eventLoop) {
        this.eventLoop = eventLoop;
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

    // Backward compat topic management
    void addTopic(String name, Map<String, Object> params) {
        topics.put(name, new TopicInfo(name, params));
    }

    boolean removeTopic(String name) { return topics.remove(name) != null; }

    void updateTopicParams(String name, Map<String, Object> params) {
        TopicInfo t = topics.get(name);
        if (t != null) topics.put(name, new TopicInfo(name, params));
    }

    // ---- TopicHandle management ----

    int nextTopicIndex() { return topicIndex; }

    TopicHandle createTopicHandle(String name, Map<String, Object> params, int wireIndex) {
        if (wireIndex >= topicIndex) topicIndex = wireIndex + 1;
        TopicPayload payload = new TopicPayload(wireIndex, () -> nextKeyId++);
        if (sessionEnqueue != null) {
            payload.bind(sessionEnqueue, this::triggerSessionResync);
        }
        TopicHandle handle = new TopicHandle(name, params, payload, this, eventLoop);
        topicHandles.put(name, handle);
        topics.put(name, new TopicInfo(name, params));
        return handle;
    }

    void removeTopicHandle(String name) {
        TopicHandle handle = topicHandles.get(name);
        if (handle != null) {
            handle.dispose();
            topicHandles.remove(name);
            topics.remove(name);
            triggerSessionResync();
        }
    }

    void disposeAllTopicHandles() {
        for (TopicHandle h : topicHandles.values()) h.dispose();
        topicHandles.clear();
    }

    // ---- Private ----

    private static boolean isArrayIndexKey(String prefix, String path) {
        if (!path.startsWith(prefix + ".")) return false;
        String suffix = path.substring(prefix.length() + 1);
        for (int i = 0; i < suffix.length(); i++) {
            if (suffix.charAt(i) < '0' || suffix.charAt(i) > '9') return false;
        }
        return !suffix.isEmpty();
    }

    private void triggerSessionResync() {
        if (sessionEnqueue == null) return;

        sessionEnqueue.accept(Frame.signal(FrameType.SERVER_RESET));

        // Flat session keys
        for (KeyRegistry.KeyEntry entry : sessionRegistry.entries()) {
            sessionEnqueue.accept(Frame.keyRegistration(entry.keyId(), entry.type(), entry.path()));
        }
        // Topic payload keys
        for (TopicHandle h : topicHandles.values()) {
            h.payload().buildKeyFrames().forEach(sessionEnqueue);
        }

        sessionEnqueue.accept(Frame.signal(FrameType.SERVER_SYNC));

        // Flat session values
        for (KeyRegistry.KeyEntry entry : sessionRegistry.entries()) {
            Object val = sessionStore.get(entry.keyId());
            if (val != null) {
                sessionEnqueue.accept(Frame.value(entry.keyId(), entry.type(), val));
            }
        }
        // Topic payload values
        for (TopicHandle h : topicHandles.values()) {
            h.payload().buildValueFrames().forEach(sessionEnqueue);
        }
    }
}
