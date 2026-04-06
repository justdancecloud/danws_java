package com.danws.api;

import com.danws.protocol.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class DanWebSocketServer {

    public enum Mode { BROADCAST, INDIVIDUAL }

    private static final String BROADCAST_PRINCIPAL = "__broadcast__";

    private final Mode mode;
    private final WebSocketServer wss;
    private final long ttl;
    private boolean authEnabled;
    private long authTimeout = 5000;

    private final Map<String, PrincipalTX> principals = new ConcurrentHashMap<>();
    private final Map<String, InternalSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, InternalSession> tmpSessions = new ConcurrentHashMap<>();

    private final List<Consumer<DanWebSocketSession>> onConnection = new ArrayList<>();
    private final List<BiConsumer<String, String>> onAuthorize = new ArrayList<>();
    private final List<Consumer<DanWebSocketSession>> onSessionExpired = new ArrayList<>();

    // WebSocket → clientUuid mapping
    private final Map<WebSocket, String> wsToUuid = new ConcurrentHashMap<>();

    public DanWebSocketServer(int port, String path, Mode mode, long ttlMs) {
        this.mode = mode;
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
        this(port, "/", Mode.INDIVIDUAL, 600_000);
    }

    public Mode mode() { return mode; }

    

    public void set(String key, Object value) {
        assertBroadcast("set");
        getPrincipal(BROADCAST_PRINCIPAL).set(key, value);
    }

    public Object get(String key) {
        assertBroadcast("get");
        return getPrincipal(BROADCAST_PRINCIPAL).get(key);
    }

    public List<String> keys() {
        if (mode != Mode.BROADCAST) return List.of();
        return getPrincipal(BROADCAST_PRINCIPAL).keys();
    }

    public void clear(String key) {
        assertBroadcast("clear");
        getPrincipal(BROADCAST_PRINCIPAL).clear(key);
    }

    public void clear() {
        assertBroadcast("clear");
        getPrincipal(BROADCAST_PRINCIPAL).clear();
    }

    

    public PrincipalTX principal(String name) {
        assertIndividual("principal");
        return getPrincipal(name);
    }

    

    public void enableAuthorization(boolean enabled) {
        this.authEnabled = enabled;
    }

    public void enableAuthorization(boolean enabled, long timeoutMs) {
        this.authEnabled = enabled;
        this.authTimeout = timeoutMs;
    }

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

    

    public void onConnection(Consumer<DanWebSocketSession> cb) { onConnection.add(cb); }
    public void onAuthorize(BiConsumer<String, String> cb) { onAuthorize.add(cb); }
    public void onSessionExpired(Consumer<DanWebSocketSession> cb) { onSessionExpired.add(cb); }

    

    private PrincipalTX getPrincipal(String name) {
        return principals.computeIfAbsent(name, n -> {
            PrincipalTX ptx = new PrincipalTX(n);
            bindPrincipalTX(ptx);
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
                // First frame must be IDENTIFY
                if (frame.frameType() != FrameType.IDENTIFY) { conn.close(); return; }
                if (!(frame.payload() instanceof byte[] payload) || payload.length != 16) { conn.close(); return; }

                uuid = bytesToUuid(payload);
                wsToUuid.put(conn, uuid);

                // Reconnect check
                InternalSession existing = sessions.get(uuid);
                if (existing != null) {
                    if (existing.ws != null && existing.ws.isOpen()) existing.ws.close();
                    if (existing.ttlFuture != null) { existing.ttlFuture.cancel(false); existing.ttlFuture = null; }
                    existing.ws = conn;
                    existing.session.handleReconnect();
                }

                if (existing != null && !authEnabled) {
                    String p = existing.session.principal() != null ? existing.session.principal()
                            : (mode == Mode.BROADCAST ? BROADCAST_PRINCIPAL : "default");
                    existing.session.authorize(p);
                    activateSession(existing, p);
                    return;
                }

                if (existing != null && authEnabled) {
                    tmpSessions.put(uuid, existing);
                    sessions.remove(uuid);
                    return;
                }

                // New session
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

            // Route to session
            InternalSession internal = sessions.get(uuid);
            if (internal != null) internal.session.handleFrame(frame);
        });

        parser.onError(e -> {});
        parser.feed(bytes);
    }

    private void activateSession(InternalSession internal, String principal) {
        String effective = mode == Mode.BROADCAST ? BROADCAST_PRINCIPAL : principal;
        PrincipalTX ptx = getPrincipal(effective);

        internal.session.setTxProviders(ptx::buildKeyFrames, ptx::buildValueFrames);
        for (var cb : onConnection) { try { cb.accept(internal.session); } catch (Exception ignored) {} }
        internal.session.startSync();
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

    private void assertBroadcast(String method) {
        if (mode != Mode.BROADCAST)
            throw new DanWSException("INVALID_MODE", "server." + method + "() is only available in broadcast mode");
    }

    private void assertIndividual(String method) {
        if (mode != Mode.INDIVIDUAL)
            throw new DanWSException("INVALID_MODE", "server." + method + "() is only available in individual mode");
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

        InternalSession(DanWebSocketSession session, WebSocket ws) {
            this.session = session;
            this.ws = ws;
        }
    }
}
