package com.danws.api;

import com.danws.connection.BulkQueue;
import com.danws.connection.HeartbeatManager;
import com.danws.protocol.*;
import com.danws.state.KeyRegistry;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;

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
    private final long ttl;
    private final long flushIntervalMs;
    private final String path;
    private boolean authEnabled;
    private long authTimeout = 5000;

    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private Channel serverChannel;

    private final Map<String, PrincipalTX> principals = new ConcurrentHashMap<>();
    private final Map<String, Set<InternalSession>> principalIndex = new ConcurrentHashMap<>();
    private final Map<String, InternalSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, InternalSession> tmpSessions = new ConcurrentHashMap<>();
    private final Map<ChannelId, String> chToUuid = new ConcurrentHashMap<>();

    private final List<Consumer<DanWebSocketSession>> onConnection = new ArrayList<>();
    private final List<BiConsumer<String, String>> onAuthorize = new ArrayList<>();
    private final List<Consumer<DanWebSocketSession>> onSessionExpired = new ArrayList<>();
    private final List<BiConsumer<DanWebSocketSession, TopicInfo>> onTopicSubscribe = new ArrayList<>();
    private final List<BiConsumer<DanWebSocketSession, String>> onTopicUnsubscribe = new ArrayList<>();
    private final List<BiConsumer<DanWebSocketSession, TopicInfo>> onTopicParamsChange = new ArrayList<>();

    private BiConsumer<String, Exception> debug;

    private final TopicNamespace topicNamespace = new TopicNamespace();

    public DanWebSocketServer(int port, String path, Mode mode, long ttlMs) {
        this(port, path, mode, ttlMs, 100);
    }

    public DanWebSocketServer(int port, String path, Mode mode, long ttlMs, long flushIntervalMs) {
        this.mode = (mode == Mode.INDIVIDUAL) ? Mode.PRINCIPAL : mode;
        this.ttl = ttlMs;
        this.flushIntervalMs = flushIntervalMs;
        this.path = path;

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .option(ChannelOption.SO_BACKLOG, 10000)
             .childOption(ChannelOption.SO_KEEPALIVE, true)
             .childOption(ChannelOption.TCP_NODELAY, true)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ch.pipeline().addLast(
                         new HttpServerCodec(),
                         new HttpObjectAggregator(65536),
                         new WebSocketServerProtocolHandler(DanWebSocketServer.this.path, null, true, 65536),
                         new ServerFrameHandler()
                     );
                 }
             });
            serverChannel = b.bind(port).sync().channel();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public DanWebSocketServer(int port, Mode mode) {
        this(port, "/", mode, 600_000);
    }

    public DanWebSocketServer(int port) {
        this(port, "/", Mode.PRINCIPAL, 600_000);
    }

    public Mode mode() { return mode; }

    public void setDebug(boolean enabled) {
        this.debug = enabled ? (msg, err) -> {
            System.err.println("[DanWS] " + msg);
            if (err != null) err.printStackTrace(System.err);
        } : null;
    }

    public void setDebug(BiConsumer<String, Exception> logger) {
        this.debug = logger;
    }

    private void log(String msg, Exception err) {
        if (debug != null) debug.accept(msg, err);
    }

    private boolean isTopicMode() {
        return mode == Mode.SESSION_TOPIC || mode == Mode.SESSION_PRINCIPAL_TOPIC;
    }

    // ---- Topic namespace ----

    public TopicNamespace topic() { return topicNamespace; }

    public static class TopicNamespace {
        private final List<BiConsumer<DanWebSocketSession, TopicHandle>> onSubscribeCbs = new ArrayList<>();
        private final List<BiConsumer<DanWebSocketSession, TopicHandle>> onUnsubscribeCbs = new ArrayList<>();

        public void onSubscribe(BiConsumer<DanWebSocketSession, TopicHandle> cb) { onSubscribeCbs.add(cb); }
        public void onUnsubscribe(BiConsumer<DanWebSocketSession, TopicHandle> cb) { onUnsubscribeCbs.add(cb); }
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

    /** Create a ring-buffer backed array for efficient sliding-window sync (broadcast mode). */
    public ArraySync array(String key, int capacity) {
        assertMode(Mode.BROADCAST, "array");
        PrincipalTX ptx = getPrincipal(BROADCAST_PRINCIPAL);
        return new ArraySync(key, capacity, ptx::set);
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
        internal.bulkQueue.enqueue(Frame.signal(FrameType.AUTH_OK));
        sessions.put(clientUuid, internal);
        activateSession(internal, principal);
    }

    public void reject(String clientUuid, String reason) {
        InternalSession internal = tmpSessions.remove(clientUuid);
        if (internal == null) return;
        // Send AUTH_FAIL directly (bypass BulkQueue to ensure delivery before close)
        byte[] encoded = Codec.encode(new Frame(FrameType.AUTH_FAIL, 0, DataType.STRING, reason != null ? reason : "Rejected"));
        sendBytes(internal.ch, encoded);
        if (internal.ch != null) {
            internal.ch.eventLoop().schedule(() -> internal.ch.close(), 100, TimeUnit.MILLISECONDS);
        }
    }

    public DanWebSocketSession getSession(String uuid) {
        InternalSession i = sessions.get(uuid);
        return i != null ? i.session : null;
    }

    public List<DanWebSocketSession> getSessionsByPrincipal(String principal) {
        List<DanWebSocketSession> result = new ArrayList<>();
        for (InternalSession i : getPrincipalSessions(principal)) {
            result.add(i.session);
        }
        return result;
    }

    public boolean isConnected(String uuid) {
        InternalSession i = sessions.get(uuid);
        return i != null && i.session.connected();
    }

    public void close() {
        for (InternalSession i : sessions.values()) {
            i.session.disposeAllTopicHandles();
            i.session.handleDisconnect();
            i.heartbeat.stop();
            i.bulkQueue.dispose();
            if (i.ttlFuture != null) i.ttlFuture.cancel(false);
            if (i.ch != null && i.ch.isActive()) {
                try { i.ch.close(); } catch (Exception e) { log("Error closing channel", e); }
            }
        }
        sessions.clear();
        tmpSessions.clear();
        principalIndex.clear();
        try {
            if (serverChannel != null) serverChannel.close().sync();
            bossGroup.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).sync();
            workerGroup.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).sync();
        } catch (InterruptedException e) { log("Error during server shutdown", e); }
    }

    // ---- Event registration ----

    public void onConnection(Consumer<DanWebSocketSession> cb) { onConnection.add(cb); }
    public void onAuthorize(BiConsumer<String, String> cb) { onAuthorize.add(cb); }
    public void onSessionExpired(Consumer<DanWebSocketSession> cb) { onSessionExpired.add(cb); }
    public void onTopicSubscribe(BiConsumer<DanWebSocketSession, TopicInfo> cb) { onTopicSubscribe.add(cb); }
    public void onTopicUnsubscribe(BiConsumer<DanWebSocketSession, String> cb) { onTopicUnsubscribe.add(cb); }
    public void onTopicParamsChange(BiConsumer<DanWebSocketSession, TopicInfo> cb) { onTopicParamsChange.add(cb); }

    // ──── Netty WebSocket Handler ────

    private class ServerFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

        private final StreamParser parser = new StreamParser();
        private boolean identified = false;
        private String clientUuid = "";

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            parser.onHeartbeat(() -> {
                InternalSession internal = getInternal(clientUuid);
                if (internal != null) {
                    internal.heartbeat.received();
                    // No echo — server sends its own heartbeat on a timer via HeartbeatManager
                }
            });

            parser.onFrame(frame -> {
                if (!identified) {
                    if (frame.frameType() != FrameType.IDENTIFY) { ctx.close(); return; }
                    if (!(frame.payload() instanceof byte[] payload) || (payload.length != 16 && payload.length != 18)) { ctx.close(); return; }
                    clientUuid = UuidUtil.bytesToUuid(java.util.Arrays.copyOf(payload, 16));
                    chToUuid.put(ctx.channel().id(), clientUuid);
                    identified = true;
                    handleIdentified(ctx.channel(), clientUuid);
                    return;
                }

                if (frame.frameType() == FrameType.AUTH) {
                    InternalSession tmp = tmpSessions.get(clientUuid);
                    if (tmp != null && authEnabled) {
                        String token = (String) frame.payload();
                        for (var cb : onAuthorize) { try { cb.accept(clientUuid, token); } catch (Exception e) { log("onAuthorize callback error", e); } }
                    }
                    return;
                }

                if (isTopicMode()) {
                    InternalSession internal = sessions.get(clientUuid);
                    if (internal != null) {
                        FrameType ft = frame.frameType();
                        if (ft == FrameType.CLIENT_RESET || ft == FrameType.CLIENT_KEY_REGISTRATION
                                || ft == FrameType.CLIENT_VALUE || ft == FrameType.CLIENT_SYNC) {
                            handleClientTopicFrame(internal, frame);
                            return;
                        }
                    }
                }

                InternalSession internal = sessions.get(clientUuid);
                if (internal != null) internal.session.handleFrame(frame);
            });

            parser.onError(e -> log("Stream parser error", e));
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame msg) {
            if (msg instanceof BinaryWebSocketFrame binaryFrame) {
                ByteBuf content = binaryFrame.content();
                byte[] bytes = new byte[content.readableBytes()];
                content.readBytes(bytes);
                parser.feed(bytes);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            String uuid = chToUuid.remove(ctx.channel().id());
            if (uuid != null) handleSessionDisconnect(uuid);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    // ──── Internal ────

    private InternalSession getInternal(String uuid) {
        InternalSession i = sessions.get(uuid);
        if (i != null) return i;
        return tmpSessions.get(uuid);
    }

    private void addToPrincipalIndex(String principal, InternalSession s) {
        principalIndex.computeIfAbsent(principal, k -> ConcurrentHashMap.newKeySet()).add(s);
    }

    private void removeFromPrincipalIndex(String principal, InternalSession s) {
        Set<InternalSession> set = principalIndex.get(principal);
        if (set != null) {
            set.remove(s);
            if (set.isEmpty()) principalIndex.remove(principal);
        }
    }

    private Set<InternalSession> getPrincipalSessions(String name) {
        Set<InternalSession> set = principalIndex.get(name);
        return set != null ? set : Collections.emptySet();
    }

    private PrincipalTX getPrincipal(String name) {
        return principals.computeIfAbsent(name, n -> {
            PrincipalTX ptx = new PrincipalTX(n);
            if (!isTopicMode()) bindPrincipalTX(ptx);
            return ptx;
        });
    }

    private void bindPrincipalTX(PrincipalTX ptx) {
        ptx.setOnValue(frame -> {
            for (InternalSession i : getPrincipalSessions(ptx.name())) {
                if (i.session.state() == DanWebSocketSession.State.READY
                        && i.ch != null && i.ch.isActive()) {
                    i.bulkQueue.enqueue(frame);
                }
            }
        });
        ptx.setOnIncremental((keyFrame, syncFrame, valueFrame) -> {
            for (InternalSession i : getPrincipalSessions(ptx.name())) {
                if (i.session.state() == DanWebSocketSession.State.READY
                        && i.ch != null && i.ch.isActive()) {
                    i.bulkQueue.enqueue(keyFrame);
                    i.bulkQueue.enqueue(syncFrame);
                    i.bulkQueue.enqueue(valueFrame);
                }
            }
        });
        ptx.setOnResync(() -> {
            for (InternalSession i : getPrincipalSessions(ptx.name())) {
                if (i.session.connected() && i.ch != null && i.ch.isActive()) {
                    i.bulkQueue.enqueue(Frame.signal(FrameType.SERVER_RESET));
                    ptx.buildKeyFrames().forEach(f -> i.bulkQueue.enqueue(f));
                }
            }
        });
    }

    private void handleIdentified(Channel ch, String uuid) {
        InternalSession existing = sessions.get(uuid);
        if (existing != null) {
            if (existing.ch != null && existing.ch.isActive()) existing.ch.close();
            if (existing.ttlFuture != null) { existing.ttlFuture.cancel(false); existing.ttlFuture = null; }
            existing.ch = ch;
            existing.session.handleReconnect();
            existing.session.setEventLoop(ch.eventLoop());
            // Recreate BulkQueue and HeartbeatManager on new EventLoop
            existing.bulkQueue.dispose();
            existing.bulkQueue = new BulkQueue(ch.eventLoop(), flushIntervalMs, this.debug);
            existing.session.setEnqueue(f -> existing.bulkQueue.enqueue(f));
            existing.heartbeat.stop();
            existing.heartbeat = new HeartbeatManager(ch.eventLoop());
            existing.heartbeat.onSend(data -> sendBytes(ch, data));
            existing.heartbeat.onTimeout(() -> handleSessionDisconnect(uuid));
            existing.heartbeat.start();
            existing.bulkQueue.onFlush(data -> sendBytes(ch, data));

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
        session.setDebug(this.debug);
        session.setEventLoop(ch.eventLoop());
        BulkQueue bulkQueue = new BulkQueue(ch.eventLoop(), flushIntervalMs, this.debug);
        HeartbeatManager heartbeat = new HeartbeatManager(ch.eventLoop());

        InternalSession internal = new InternalSession(session, ch, bulkQueue, heartbeat);

        session.setEnqueue(f -> bulkQueue.enqueue(f));
        bulkQueue.onFlush(data -> sendBytes(ch, data));
        heartbeat.onSend(data -> sendBytes(ch, data));
        heartbeat.onTimeout(() -> handleSessionDisconnect(uuid));
        heartbeat.start();

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
        String effective = mode == Mode.BROADCAST ? BROADCAST_PRINCIPAL : principal;
        addToPrincipalIndex(effective, internal);
        if (isTopicMode()) {
            internal.session.bindSessionTX(f -> internal.bulkQueue.enqueue(f));
            for (var cb : onConnection) { try { cb.accept(internal.session); } catch (Exception e) { log("onConnection callback error", e); } }
            internal.bulkQueue.enqueue(Frame.signal(FrameType.SERVER_SYNC));
        } else {
            PrincipalTX ptx = getPrincipal(effective);
            internal.session.setTxProviders(ptx::buildKeyFrames, ptx::buildValueFrames);
            for (var cb : onConnection) { try { cb.accept(internal.session); } catch (Exception e) { log("onConnection callback error", e); } }
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
        Map<String, Integer> nameToIndex = new LinkedHashMap<>();
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
                            String wireIdx = m.group(1);
                            indexToName.put(wireIdx, topicName);
                            nameToIndex.put(topicName, Integer.parseInt(wireIdx));
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

        Set<String> oldTopics = new HashSet<>(session.topics());
        for (String oldName : oldTopics) {
            if (!newTopics.containsKey(oldName)) {
                TopicHandle handle = session.getTopicHandle(oldName);
                if (handle != null) {
                    for (var cb : topicNamespace.onUnsubscribeCbs) { try { cb.accept(session, handle); } catch (Exception e) { log("topic.onUnsubscribe callback error", e); } }
                }
                session.removeTopicHandle(oldName);
                session.removeTopic(oldName);
                for (var cb : onTopicUnsubscribe) { try { cb.accept(session, oldName); } catch (Exception e) { log("onTopicUnsubscribe callback error", e); } }
            }
        }

        for (var entry : newTopics.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> params = entry.getValue();
            TopicHandle existingHandle = session.getTopicHandle(name);

            if (existingHandle == null) {
                int wireIndex = nameToIndex.getOrDefault(name, session.nextTopicIndex());
                TopicHandle handle = session.createTopicHandle(name, params, wireIndex);
                for (var cb : topicNamespace.onSubscribeCbs) { try { cb.accept(session, handle); } catch (Exception e) { log("topic.onSubscribe callback error", e); } }
                TopicInfo info = new TopicInfo(name, params);
                for (var cb : onTopicSubscribe) { try { cb.accept(session, info); } catch (Exception e) { log("onTopicSubscribe callback error", e); } }
            } else {
                if (!existingHandle.params().equals(params)) {
                    existingHandle.updateParams(params);
                    session.updateTopicParams(name, params);
                    TopicInfo info = new TopicInfo(name, params);
                    for (var cb : onTopicParamsChange) { try { cb.accept(session, info); } catch (Exception e) { log("onTopicParamsChange callback error", e); } }
                }
            }
        }
    }

    private void handleSessionDisconnect(String uuid) {
        InternalSession internal = sessions.get(uuid);
        if (internal == null) { tmpSessions.remove(uuid); return; }
        if (!internal.session.connected()) return;

        internal.session.disposeAllTopicHandles();
        internal.session.handleDisconnect();
        internal.heartbeat.stop();
        internal.bulkQueue.clear();
        internal.ch = null;

        String p = internal.session.principal();
        String effective = mode == Mode.BROADCAST ? BROADCAST_PRINCIPAL : p;
        if (effective != null) removeFromPrincipalIndex(effective, internal);

        internal.ttlFuture = workerGroup.next().schedule(() -> {
            sessions.remove(uuid);
            for (var cb : onSessionExpired) { try { cb.accept(internal.session); } catch (Exception e) { log("onSessionExpired callback error", e); } }
        }, ttl, TimeUnit.MILLISECONDS);
    }

    private static void sendBytes(Channel ch, byte[] data) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(data)));
        }
    }

    private void assertMode(Mode expected, String method) {
        if (mode != expected)
            throw new DanWSException("INVALID_MODE", "server." + method + "() is only available in " + expected + " mode.");
    }


    static class InternalSession {
        DanWebSocketSession session;
        Channel ch;
        BulkQueue bulkQueue;
        HeartbeatManager heartbeat;
        ScheduledFuture<?> ttlFuture;
        KeyRegistry clientRegistry;
        Map<Integer, Object> clientValues;

        InternalSession(DanWebSocketSession session, Channel ch, BulkQueue bulkQueue, HeartbeatManager heartbeat) {
            this.session = session;
            this.ch = ch;
            this.bulkQueue = bulkQueue;
            this.heartbeat = heartbeat;
        }
    }
}
