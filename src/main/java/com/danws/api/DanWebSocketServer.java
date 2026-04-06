package com.danws.api;

import com.danws.protocol.*;
import com.danws.state.KeyRegistry;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DanWebSocketServer {

    public enum Mode { BROADCAST, PRINCIPAL, INDIVIDUAL, SESSION_TOPIC, SESSION_PRINCIPAL_TOPIC }

    private static final String BROADCAST_PRINCIPAL = "__broadcast__";
    private static final Pattern TOPIC_NAME_PATTERN = Pattern.compile("^topic\\.(\\d+)\\.name$");
    private static final Pattern TOPIC_PARAM_PATTERN = Pattern.compile("^topic\\.(\\d+)\\.param\\.(.+)$");

    private final Mode mode;
    private final WebSocketServer wss;
    private final long ttl;
    private boolean authEnabled;
    private long authTimeout = 5000;

    private final Map<String, PrincipalTX> principals = new ConcurrentHashMap<>();
    private final Map<String, InternalSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, InternalSession> tmpSessions = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> wsToUuid = new ConcurrentHashMap<>();

    private final List<Consumer<DanWebSocketSession>> onConnection = new ArrayList<>();
    private final List<BiConsumer<String, String>> onAuthorize = new ArrayList<>();
    private final List<Consumer<DanWebSocketSession>> onSessionExpired = new ArrayList<>();
    private final List<BiConsumer<DanWebSocketSession, TopicInfo>> onTopicSubscribe = new ArrayList<>();
    private final List<BiConsumer<DanWebSocketSession, String>> onTopicUnsubscribe = new ArrayList<>();
    private final List<BiConsumer<DanWebSocketSession, TopicInfo>> onTopicParamsChange = new ArrayList<>();

    public DanWebSocketServer(int port, String path, Mode mode, long ttlMs) {
        // INDIVIDUAL is alias for PRINCIPAL
        this.mode = (mode == Mode.INDIVIDUAL) ? Mode.PRINCIPAL : mode;
        this.ttl = ttlMs;

        wss = new WebSocketServer(new InetSocketAddress(port)) {
            @Override public void onOpen(WebSocket conn, ClientHandshake handshake) {}
            @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                String uuid = wsToUuid.remove(conn);
                if (uuid != null) handleSessionDisconnect(uuid);
            }
            @Override public void onMessage(WebSocket conn, String message) {}
            @Override public void onMessage(WebSocket conn, ByteBuffer message) {
                handleMessage(conn, message);
            }
            @Override public void onError(WebSocket conn, Exception ex) {}
            @Override public void onStart() {}
        };
        wss.setReuseAddr(true);
        wss.start();
    }

    public DanWebSocketServer(int port, Mode mode) {
        this(port, "/", mode, 600_000);
    }

    public DanWebSocketServer(int port) {
        this(port, "/", Mode.PRINCIPAL, 600_000);
    }

    public Mode mode() { return mode; }

    private boolean isTopicMode() {
        return mode == Mode.SESSION_TOPIC || mode == Mode.SESSION_PRINCIPAL_TOPIC;
    }

    // ---- Broadcast mode API ----

    public void set(String key, Object value) {
        assertMode(Mode.BROADCAST, "set");
        getPrincipal(BROADCAST_PRINCIPAL).set(key, value);
    }

    public Object get(String key) {
        assertMode(Mode.BROADCAST, "get");
        return getPrincipal(BROADCAST_PRINCIPAL).get(key);
    }

    public List<String> keys() {
        if (mode != Mode.BROADCAST) return List.of();
        return getPrincipal(BROADCAST_PRINCIPAL).keys();
    }

    public void clear(String key) {
        assertMode(Mode.BROADCAST, "clear");
        getPrincipal(BROADCAST_PRINCIPAL).clear(key);
    }

    public void clear() {
        assertMode(Mode.BROADCAST, "clear");
        getPrincipal(BROADCAST_PRINCIPAL).clear();
    }

    // ---- Principal mode API ----

    public PrincipalTX principal(String name) {
        if (mode != Mode.PRINCIPAL && mode != Mode.SESSION_PRINCIPAL_TOPIC) {
            throw new DanWSException("INVALID_MODE", "server.principal() is only available in PRINCIPAL/SESSION_PRINCIPAL_TOPIC mode.");
        }
        return getPrincipal(name);
    }

    // ---- Common API ----

    public void enableAuthorization(boolean enabled) { this.authEnabled = enabled; }
    public void enableAuthorization(boolean enabled, long timeoutMs) { this.authEnabled = enabled; this.authTimeout = timeoutMs; }

    public void authorize(String clientUuid, String token, String principal) {
        InternalSession internal = tmpSessions.remove(clientUuid);
        if (internal == null) return;

        internal.session.authorize(principal);
        sendFrame(internal, Frame.signal(FrameType.AUTH_OK));
        sessions.put(clientUuid, internal);
        activateSession(internal, principal);
    }

    public void reject(String clientUuid, String reason) {
        InternalSession internal = tmpSessions.remove(clientUuid);
        if (internal == null) return;
        sendFrame(internal, new Frame(FrameType.AUTH_FAIL, 0, DataType.STRING, reason != null ? reason : "Rejected"));
        if (internal.ws != null) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            internal.ws.close();
        }
    }

    public DanWebSocketSession getSession(String uuid) {
        InternalSession i = sessions.get(uuid);
        return i != null ? i.session : null;
    }

    public List<DanWebSocketSession> getSessionsByPrincipal(String principal) {
        List<DanWebSocketSession> result = new ArrayList<>();
        for (InternalSession i : sessions.values()) {
            if (Objects.equals(i.session.principal(), principal)) result.add(i.session);
        }
        return result;
    }

    public boolean isConnected(String uuid) {
        InternalSession i = sessions.get(uuid);
        return i != null && i.session.connected();
    }

    public void close() {
        for (InternalSession i : sessions.values()) {
            i.session.handleDisconnect();
            if (i.ttlFuture != null) i.ttlFuture.cancel(false);
        }
        sessions.clear();
        tmpSessions.clear();
        try { wss.stop(500); } catch (InterruptedException ignored) {}
    }

    // ---- Event registration ----

    public void onConnection(Consumer<DanWebSocketSession> cb) { onConnection.add(cb); }
    public void onAuthorize(BiConsumer<String, String> cb) { onAuthorize.add(cb); }
    public void onSessionExpired(Consumer<DanWebSocketSession> cb) { onSessionExpired.add(cb); }
    public void onTopicSubscribe(BiConsumer<DanWebSocketSession, TopicInfo> cb) { onTopicSubscribe.add(cb); }
    public void onTopicUnsubscribe(BiConsumer<DanWebSocketSession, String> cb) { onTopicUnsubscribe.add(cb); }
    public void onTopicParamsChange(BiConsumer<DanWebSocketSession, TopicInfo> cb) { onTopicParamsChange.add(cb); }

    // ---- Internal ----

    private PrincipalTX getPrincipal(String name) {
        return principals.computeIfAbsent(name, n -> {
            PrincipalTX ptx = new PrincipalTX(n);
            if (!isTopicMode()) bindPrincipalTX(ptx);
            return ptx;
        });
    }

    private void bindPrincipalTX(PrincipalTX ptx) {
        ptx.setOnValue(frame -> {
            for (InternalSession i : sessions.values()) {
                if (matchesPrincipal(i, ptx.name()) && i.session.state() == DanWebSocketSession.State.READY
                        && i.ws != null && i.ws.isOpen()) {
                    sendFrame(i, frame);
                }
            }
        });
        ptx.setOnResync(() -> {
            for (InternalSession i : sessions.values()) {
                if (matchesPrincipal(i, ptx.name()) && i.session.connected() && i.ws != null && i.ws.isOpen()) {
                    sendFrame(i, Frame.signal(FrameType.SERVER_RESET));
                    ptx.buildKeyFrames().forEach(f -> sendFrame(i, f));
                }
            }
        });
    }

    private boolean matchesPrincipal(InternalSession i, String principalName) {
        if (mode == Mode.BROADCAST) return BROADCAST_PRINCIPAL.equals(principalName);
        return Objects.equals(i.session.principal(), principalName);
    }

    private void handleMessage(WebSocket conn, ByteBuffer message) {
        byte[] bytes = new byte[message.remaining()];
        message.get(bytes);

        StreamParser parser = new StreamParser();
        parser.onHeartbeat(() -> {
            if (conn.isOpen()) conn.send(ByteBuffer.wrap(Codec.encodeHeartbeat()));
        });

        parser.onFrame(frame -> {
            String uuid = wsToUuid.get(conn);

            if (uuid == null) {
                if (frame.frameType() != FrameType.IDENTIFY) { conn.close(); return; }
                if (!(frame.payload() instanceof byte[] payload) || payload.length != 16) { conn.close(); return; }

                uuid = bytesToUuid(payload);
                wsToUuid.put(conn, uuid);
                handleIdentified(conn, uuid);
                return;
            }

            // AUTH frame
            if (frame.frameType() == FrameType.AUTH) {
                InternalSession tmp = tmpSessions.get(uuid);
                if (tmp != null && authEnabled) {
                    String token = (String) frame.payload();
                    for (var cb : onAuthorize) { try { cb.accept(uuid, token); } catch (Exception ignored) {} }
                }
                return;
            }

            // Client topic frames (topic modes)
            if (isTopicMode()) {
                InternalSession internal = sessions.get(uuid);
                if (internal != null) {
                    FrameType ft = frame.frameType();
                    if (ft == FrameType.CLIENT_RESET || ft == FrameType.CLIENT_KEY_REGISTRATION
                            || ft == FrameType.CLIENT_VALUE || ft == FrameType.CLIENT_SYNC) {
                        handleClientTopicFrame(internal, frame);
                        return;
                    }
                }
            }

            // Route to session
            InternalSession internal = sessions.get(uuid);
            if (internal != null) internal.session.handleFrame(frame);
        });

        parser.onError(e -> {});
        parser.feed(bytes);
    }

    private void handleIdentified(WebSocket conn, String uuid) {
        InternalSession existing = sessions.get(uuid);
        if (existing != null) {
            if (existing.ws != null && existing.ws.isOpen()) existing.ws.close();
            if (existing.ttlFuture != null) { existing.ttlFuture.cancel(false); existing.ttlFuture = null; }
            existing.ws = conn;
            existing.session.handleReconnect();

            if (authEnabled) {
                tmpSessions.put(uuid, existing);
                sessions.remove(uuid);
            } else {
                String p = existing.session.principal() != null ? existing.session.principal()
                        : (mode == Mode.BROADCAST ? BROADCAST_PRINCIPAL : "default");
                existing.session.authorize(p);
                activateSession(existing, p);
            }
            return;
        }

        DanWebSocketSession session = new DanWebSocketSession(uuid);
        InternalSession internal = new InternalSession(session, conn);
        session.setEnqueue(f -> sendFrame(internal, f));

        if (authEnabled) {
            tmpSessions.put(uuid, internal);
        } else {
            String defaultPrincipal = mode == Mode.BROADCAST ? BROADCAST_PRINCIPAL : "default";
            session.authorize(defaultPrincipal);
            sessions.put(uuid, internal);
            activateSession(internal, defaultPrincipal);
        }
    }

    private void activateSession(InternalSession internal, String principal) {
        if (isTopicMode()) {
            internal.session.bindSessionTX(f -> sendFrame(internal, f));
            for (var cb : onConnection) { try { cb.accept(internal.session); } catch (Exception ignored) {} }
            // Send empty ServerSync so client transitions to ready
            sendFrame(internal, Frame.signal(FrameType.SERVER_SYNC));
        } else {
            String effective = mode == Mode.BROADCAST ? BROADCAST_PRINCIPAL : principal;
            PrincipalTX ptx = getPrincipal(effective);
            internal.session.setTxProviders(ptx::buildKeyFrames, ptx::buildValueFrames);
            for (var cb : onConnection) { try { cb.accept(internal.session); } catch (Exception ignored) {} }
            internal.session.startSync();
        }
    }

    // ---- Client topic frame handling ----

    private void handleClientTopicFrame(InternalSession internal, Frame frame) {
        switch (frame.frameType()) {
            case CLIENT_RESET -> {
                if (internal.clientRegistry != null) internal.clientRegistry.clear();
                else internal.clientRegistry = new KeyRegistry();
                if (internal.clientValues != null) internal.clientValues.clear();
                else internal.clientValues = new HashMap<>();
            }
            case CLIENT_KEY_REGISTRATION -> {
                if (internal.clientRegistry == null) internal.clientRegistry = new KeyRegistry();
                internal.clientRegistry.registerOne(frame.keyId(), (String) frame.payload(), frame.dataType());
            }
            case CLIENT_VALUE -> {
                if (internal.clientValues == null) internal.clientValues = new HashMap<>();
                internal.clientValues.put(frame.keyId(), frame.payload());
            }
            case CLIENT_SYNC -> processTopicSync(internal);
            default -> {}
        }
    }

    private void processTopicSync(InternalSession internal) {
        DanWebSocketSession session = internal.session;

        Map<String, Map<String, Object>> newTopics = new LinkedHashMap<>();

        if (internal.clientRegistry != null && internal.clientValues != null) {
            Map<String, String> indexToName = new HashMap<>();

            for (String path : internal.clientRegistry.paths()) {
                Matcher m = TOPIC_NAME_PATTERN.matcher(path);
                if (m.matches()) {
                    KeyRegistry.KeyEntry entry = internal.clientRegistry.getByPath(path);
                    if (entry != null) {
                        String topicName = (String) internal.clientValues.get(entry.keyId());
                        if (topicName != null) {
                            indexToName.put(m.group(1), topicName);
                            newTopics.putIfAbsent(topicName, new HashMap<>());
                        }
                    }
                }
            }

            for (String path : internal.clientRegistry.paths()) {
                Matcher m = TOPIC_PARAM_PATTERN.matcher(path);
                if (m.matches()) {
                    String topicName = indexToName.get(m.group(1));
                    if (topicName != null) {
                        KeyRegistry.KeyEntry entry = internal.clientRegistry.getByPath(path);
                        if (entry != null) {
                            Object value = internal.clientValues.get(entry.keyId());
                            if (value != null) newTopics.get(topicName).put(m.group(2), value);
                        }
                    }
                }
            }
        }

        // Diff: unsubscribed
        Set<String> oldTopics = new HashSet<>(session.topics());
        for (String oldName : oldTopics) {
            if (!newTopics.containsKey(oldName)) {
                session.removeTopic(oldName);
                for (var cb : onTopicUnsubscribe) { try { cb.accept(session, oldName); } catch (Exception ignored) {} }
            }
        }

        // Diff: new / changed
        for (var entry : newTopics.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> params = entry.getValue();
            TopicInfo existing = session.topic(name);

            if (existing == null) {
                session.addTopic(name, params);
                TopicInfo info = new TopicInfo(name, params);
                for (var cb : onTopicSubscribe) { try { cb.accept(session, info); } catch (Exception ignored) {} }
            } else {
                if (!existing.params().equals(params)) {
                    session.updateTopicParams(name, params);
                    TopicInfo info = new TopicInfo(name, params);
                    for (var cb : onTopicParamsChange) { try { cb.accept(session, info); } catch (Exception ignored) {} }
                }
            }
        }
    }

    private void handleSessionDisconnect(String uuid) {
        InternalSession internal = sessions.get(uuid);
        if (internal == null) { tmpSessions.remove(uuid); return; }
        if (!internal.session.connected()) return;

        internal.session.handleDisconnect();
        internal.ws = null;

        internal.ttlFuture = scheduler.schedule(() -> {
            sessions.remove(uuid);
            for (var cb : onSessionExpired) { try { cb.accept(internal.session); } catch (Exception ignored) {} }
        }, ttl, TimeUnit.MILLISECONDS);
    }

    private void sendFrame(InternalSession internal, Frame frame) {
        if (internal.ws != null && internal.ws.isOpen()) {
            internal.ws.send(ByteBuffer.wrap(Codec.encode(frame)));
        }
    }

    private void assertMode(Mode expected, String method) {
        if (mode != expected)
            throw new DanWSException("INVALID_MODE", "server." + method + "() is only available in " + expected + " mode.");
    }

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "danws-ttl");
        t.setDaemon(true);
        return t;
    });

    private static String bytesToUuid(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format("%02x", b & 0xFF));
        String h = hex.toString();
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16)
                + "-" + h.substring(16, 20) + "-" + h.substring(20, 32);
    }

    static class InternalSession {
        DanWebSocketSession session;
        WebSocket ws;
        ScheduledFuture<?> ttlFuture;
        KeyRegistry clientRegistry;
        Map<Integer, Object> clientValues;

        InternalSession(DanWebSocketSession session, WebSocket ws) {
            this.session = session;
            this.ws = ws;
        }
    }
}
