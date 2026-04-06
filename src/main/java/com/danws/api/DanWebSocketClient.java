package com.danws.api;

import com.danws.protocol.*;
import com.danws.state.KeyRegistry;
import com.danws.state.KeyRegistry.KeyEntry;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DanWebSocketClient {

    public enum State { DISCONNECTED, CONNECTING, IDENTIFYING, AUTHORIZING, SYNCHRONIZING, READY, RECONNECTING }

    private static final Pattern TOPIC_WIRE_PATTERN = Pattern.compile("^t\\.(\\d+)\\.(.+)$");

    private final String id;
    private final URI url;
    private State state = State.DISCONNECTED;
    private WebSocketClient ws;
    private boolean intentionalDisconnect;

    private final KeyRegistry registry = new KeyRegistry();
    private final Map<Integer, Object> store = new ConcurrentHashMap<>();

    // Topic state
    private final Map<String, Map<String, Object>> subscriptions = new LinkedHashMap<>();
    private final Map<String, TopicClientHandle> topicClientHandles = new LinkedHashMap<>();
    private final Map<String, Integer> topicIndexMap = new LinkedHashMap<>();
    private final Map<Integer, String> indexToTopic = new LinkedHashMap<>();

    private final List<Runnable> onConnect = new ArrayList<>();
    private final List<Runnable> onDisconnect = new ArrayList<>();
    private final List<Runnable> onReady = new ArrayList<>();
    private final List<BiConsumer<String, Object>> onReceive = new ArrayList<>();
    private final List<Consumer<TopicClientPayloadView>> onUpdate = new ArrayList<>();
    private final List<Runnable> onReconnect = new ArrayList<>();
    private final List<Runnable> onReconnectFailed = new ArrayList<>();
    private final List<Consumer<DanWSException>> onError = new ArrayList<>();

    public DanWebSocketClient(String url) {
        this.url = URI.create(url);
        this.id = generateUUIDv7();
    }

    public String id() { return id; }
    public State state() { return state; }

    public Object get(String key) {
        KeyEntry entry = registry.getByPath(key);
        if (entry == null) return null;
        return store.get(entry.keyId());
    }

    public List<String> keys() { return registry.paths(); }

    public void connect() {
        if (state != State.DISCONNECTED && state != State.RECONNECTING) return;
        intentionalDisconnect = false;
        state = State.CONNECTING;

        ws = new WebSocketClient(url) {
            @Override public void onOpen(ServerHandshake handshake) { handleOpen(); }
            @Override public void onClose(int code, String reason, boolean remote) { handleClose(); }
            @Override public void onMessage(String message) {}
            @Override public void onMessage(ByteBuffer bytes) { handleMessage(bytes); }
            @Override public void onError(Exception ex) {}
        };
        ws.connect();
    }

    public void disconnect() {
        intentionalDisconnect = true;
        state = State.DISCONNECTED;
        if (ws != null) { try { ws.close(); } catch (Exception ignored) {} ws = null; }
        onDisconnect.forEach(Runnable::run);
    }

    public void authorize(String token) {
        if (ws == null || !ws.isOpen()) return;
        sendFrame(new Frame(FrameType.AUTH, 0, DataType.STRING, token));
        state = State.AUTHORIZING;
    }

    // ---- Topic API ----

    public void subscribe(String topicName, Map<String, Object> params) {
        subscriptions.put(topicName, params != null ? params : Map.of());
        sendTopicSync();
    }

    public void subscribe(String topicName) {
        subscribe(topicName, Map.of());
    }

    public void unsubscribe(String topicName) {
        if (subscriptions.remove(topicName) != null) sendTopicSync();
    }

    public void setParams(String topicName, Map<String, Object> params) {
        if (!subscriptions.containsKey(topicName)) return;
        subscriptions.put(topicName, params);
        sendTopicSync();
    }

    public List<String> topics() {
        return new ArrayList<>(subscriptions.keySet());
    }

    public TopicClientHandle topic(String name) {
        return topicClientHandles.computeIfAbsent(name, n -> {
            TopicClientHandle h = new TopicClientHandle(n, this);
            Integer idx = topicIndexMap.get(n);
            if (idx != null) h.setIndex(idx);
            return h;
        });
    }

    // ---- Event registration ----

    public void onConnect(Runnable cb) { onConnect.add(cb); }
    public void onDisconnect(Runnable cb) { onDisconnect.add(cb); }
    public void onReady(Runnable cb) { onReady.add(cb); }
    public void onReceive(BiConsumer<String, Object> cb) { onReceive.add(cb); }
    public void onUpdate(Consumer<TopicClientPayloadView> cb) { onUpdate.add(cb); }
    public void onReconnect(Runnable cb) { onReconnect.add(cb); }
    public void onReconnectFailed(Runnable cb) { onReconnectFailed.add(cb); }
    public void onError(Consumer<DanWSException> cb) { onError.add(cb); }

    // ---- Internals ----

    private void handleOpen() {
        state = State.IDENTIFYING;
        sendFrame(new Frame(FrameType.IDENTIFY, 0, DataType.BINARY, uuidToBytes(id)));
        onConnect.forEach(Runnable::run);
    }

    private void handleClose() {
        if (intentionalDisconnect) return;
        state = State.DISCONNECTED;
        onDisconnect.forEach(Runnable::run);
    }

    private void handleMessage(ByteBuffer message) {
        byte[] bytes = new byte[message.remaining()];
        message.get(bytes);

        StreamParser parser = new StreamParser();
        parser.onFrame(this::handleFrame);
        parser.onHeartbeat(() -> {
            if (ws != null && ws.isOpen()) ws.send(ByteBuffer.wrap(Codec.encodeHeartbeat()));
        });
        parser.onError(e -> {
            if (e instanceof DanWSException de) onError.forEach(cb -> cb.accept(de));
        });
        parser.feed(bytes);
    }

    private void handleFrame(Frame frame) {
        switch (frame.frameType()) {
            case AUTH_OK -> state = State.SYNCHRONIZING;
            case AUTH_FAIL -> {
                intentionalDisconnect = true;
                var err = new DanWSException("AUTH_REJECTED", String.valueOf(frame.payload()));
                onError.forEach(cb -> cb.accept(err));
                if (ws != null) ws.close();
                state = State.DISCONNECTED;
                onDisconnect.forEach(Runnable::run);
            }
            case SERVER_KEY_REGISTRATION -> {
                if (state == State.IDENTIFYING) state = State.SYNCHRONIZING;
                registry.registerOne(frame.keyId(), (String) frame.payload(), frame.dataType());
            }
            case SERVER_SYNC -> {
                if (state == State.IDENTIFYING) state = State.SYNCHRONIZING;
                sendFrame(Frame.signal(FrameType.CLIENT_READY));

                if (registry.size() == 0) {
                    state = State.READY;
                    onReady.forEach(Runnable::run);
                    if (!subscriptions.isEmpty()) sendTopicSync();
                }
            }
            case SERVER_VALUE -> {
                if (!registry.hasKeyId(frame.keyId())) {
                    onError.forEach(cb -> cb.accept(new DanWSException("UNKNOWN_KEY_ID", "Unknown key ID: " + frame.keyId())));
                    sendFrame(Frame.signal(FrameType.CLIENT_RESYNC_REQ));
                    return;
                }
                store.put(frame.keyId(), frame.payload());
                KeyEntry entry = registry.getByKeyId(frame.keyId());
                if (entry != null) {
                    String path = entry.path();
                    Matcher m = TOPIC_WIRE_PATTERN.matcher(path);
                    if (m.matches()) {
                        int idx = Integer.parseInt(m.group(1));
                        String key = m.group(2);
                        String topicName = indexToTopic.get(idx);
                        if (topicName != null) {
                            TopicClientHandle handle = topicClientHandles.get(topicName);
                            if (handle != null) handle.notify(key, frame.payload());
                        }
                    } else {
                        // Global key
                        for (var cb : onReceive) { try { cb.accept(path, frame.payload()); } catch (Exception ignored) {} }
                    }
                }
                if (state == State.SYNCHRONIZING) {
                    state = State.READY;
                    onReady.forEach(Runnable::run);
                    if (!subscriptions.isEmpty()) sendTopicSync();
                }
            }
            case SERVER_READY -> { /* Server acknowledged topic sync */ }
            case SERVER_RESET -> {
                registry.clear();
                store.clear();
                state = State.SYNCHRONIZING;
            }
            case ERROR -> {
                var err = new DanWSException("REMOTE_ERROR", String.valueOf(frame.payload()));
                onError.forEach(cb -> cb.accept(err));
            }
            default -> {}
        }
    }

    private void sendTopicSync() {
        if (ws == null || !ws.isOpen()) return;

        List<String> paths = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        List<DataType> types = new ArrayList<>();

        topicIndexMap.clear();
        indexToTopic.clear();

        int idx = 0;
        for (var entry : subscriptions.entrySet()) {
            String topicName = entry.getKey();
            topicIndexMap.put(topicName, idx);
            indexToTopic.put(idx, topicName);

            // Update existing handle's index
            TopicClientHandle handle = topicClientHandles.get(topicName);
            if (handle != null) handle.setIndex(idx);

            paths.add("topic." + idx + ".name");
            values.add(topicName);
            types.add(DataType.STRING);

            for (var param : entry.getValue().entrySet()) {
                paths.add("topic." + idx + ".param." + param.getKey());
                values.add(param.getValue());
                types.add(DataType.detect(param.getValue()));
            }
            idx++;
        }

        sendFrame(Frame.signal(FrameType.CLIENT_RESET));

        for (int i = 0; i < paths.size(); i++) {
            sendFrame(new Frame(FrameType.CLIENT_KEY_REGISTRATION, i + 1, types.get(i), paths.get(i)));
        }

        for (int i = 0; i < values.size(); i++) {
            sendFrame(new Frame(FrameType.CLIENT_VALUE, i + 1, types.get(i), values.get(i)));
        }

        sendFrame(Frame.signal(FrameType.CLIENT_SYNC));
    }

    private void sendFrame(Frame frame) {
        if (ws != null && ws.isOpen()) {
            ws.send(ByteBuffer.wrap(Codec.encode(frame)));
        }
    }

    private static String generateUUIDv7() {
        long now = System.currentTimeMillis();
        byte[] bytes = new byte[16];
        new Random().nextBytes(bytes);
        bytes[0] = (byte) (now >> 40); bytes[1] = (byte) (now >> 32);
        bytes[2] = (byte) (now >> 24); bytes[3] = (byte) (now >> 16);
        bytes[4] = (byte) (now >> 8); bytes[5] = (byte) now;
        bytes[6] = (byte) ((bytes[6] & 0x0F) | 0x70);
        bytes[8] = (byte) ((bytes[8] & 0x3F) | 0x80);

        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format("%02x", b & 0xFF));
        String h = hex.toString();
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16)
                + "-" + h.substring(16, 20) + "-" + h.substring(20, 32);
    }

    private static byte[] uuidToBytes(String uuid) {
        String hex = uuid.replace("-", "");
        byte[] bytes = new byte[16];
        for (int i = 0; i < 16; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
