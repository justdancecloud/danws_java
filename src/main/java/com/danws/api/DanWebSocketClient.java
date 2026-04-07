package com.danws.api;

import com.danws.protocol.*;
import com.danws.state.KeyRegistry;
import com.danws.state.KeyRegistry.KeyEntry;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;

import com.danws.connection.ReconnectEngine;

import java.net.URI;
import java.util.concurrent.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DanWebSocketClient {

    public enum State { DISCONNECTED, CONNECTING, IDENTIFYING, AUTHORIZING, SYNCHRONIZING, READY, RECONNECTING }

    private static final Pattern TOPIC_WIRE_PATTERN = Pattern.compile("^t\\.(\\d+)\\.(.+)$");

    // Shared EventLoopGroup for all clients — avoids 1 thread per client
    private static volatile EventLoopGroup SHARED_GROUP;

    private static EventLoopGroup getSharedGroup() {
        if (SHARED_GROUP == null) {
            synchronized (DanWebSocketClient.class) {
                if (SHARED_GROUP == null) {
                    SHARED_GROUP = new NioEventLoopGroup(
                        Math.max(4, Runtime.getRuntime().availableProcessors()),
                        r -> { Thread t = new Thread(r, "danws-client"); t.setDaemon(true); return t; }
                    );
                }
            }
        }
        return SHARED_GROUP;
    }

    /** Override the shared EventLoopGroup (e.g. for stress tests needing more threads). */
    public static void setSharedGroup(EventLoopGroup group) {
        SHARED_GROUP = group;
    }

    private final String id;
    private final URI uri;
    private State state = State.DISCONNECTED;
    private Channel channel;
    private boolean intentionalDisconnect;

    private final KeyRegistry registry = new KeyRegistry();
    private final Map<Integer, Object> store = new ConcurrentHashMap<>();
    private final StreamParser parser = new StreamParser(); // reuse instance

    // Topic state
    private final Map<String, Map<String, Object>> subscriptions = new LinkedHashMap<>();
    private final Map<String, TopicClientHandle> topicClientHandles = new LinkedHashMap<>();
    private final Map<String, Integer> topicIndexMap = new LinkedHashMap<>();
    private final Map<Integer, String> indexToTopic = new LinkedHashMap<>();

    private final List<Runnable> onConnect = new ArrayList<>();
    private final List<Runnable> onDisconnect = new ArrayList<>();
    private final List<Runnable> onReady = new ArrayList<>();
    private final List<BiConsumer<String, Object>> onReceive = new ArrayList<>();
    private final List<Runnable> onUpdate = new ArrayList<>();
    private final List<Consumer<DanWSException>> onError = new ArrayList<>();
    private final List<BiConsumer<Integer, Long>> onReconnecting = new ArrayList<>();
    private final List<Runnable> onReconnect = new ArrayList<>();
    private final List<Runnable> onReconnectFailed = new ArrayList<>();

    private final ReconnectEngine reconnectEngine = new ReconnectEngine();

    // Heartbeat
    private static final long HB_SEND_INTERVAL = 10_000;
    private static final long HB_TIMEOUT = 15_000;
    private static final ScheduledExecutorService HB_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "danws-client-hb"); t.setDaemon(true); return t; });
    private volatile long lastHeartbeatReceived;
    private ScheduledFuture<?> hbSendTask;
    private ScheduledFuture<?> hbCheckTask;

    public DanWebSocketClient(String url) {
        this.uri = URI.create(url);
        this.id = generateUUIDv7();

        // Setup parser callbacks once (reuse)
        parser.onFrame(this::handleFrame);
        parser.onHeartbeat(() -> lastHeartbeatReceived = System.currentTimeMillis());
        parser.onError(e -> {});

        // Reconnect wiring
        reconnectEngine.onAttempt(this::connect);
        reconnectEngine.onReconnecting((attempt, delay) -> {
            for (var cb : onReconnecting) { try { cb.accept(attempt, delay); } catch (Exception ignored) {} }
        });
        reconnectEngine.onExhausted(() -> {
            state = State.DISCONNECTED;
            for (var cb : onReconnectFailed) { try { cb.run(); } catch (Exception ignored) {} }
        });
    }

    public String id() { return id; }
    public State state() { return state; }

    public Object get(String key) {
        KeyEntry entry = registry.getByPath(key);
        if (entry == null) return null;
        return store.get(entry.keyId());
    }

    public List<String> keys() { return registry.paths(); }

    /** Get a read-only array view backed by ring buffer keys. */
    public ArrayView array(String key) {
        return new ArrayView(key, this::get);
    }

    public void connect() {
        if (state != State.DISCONNECTED && state != State.RECONNECTING) return;
        intentionalDisconnect = false;
        state = State.CONNECTING;

        String scheme = uri.getScheme();
        int port = uri.getPort();
        if (port == -1) port = "wss".equals(scheme) ? 443 : 80;
        String wsPath = uri.getRawPath() != null && !uri.getRawPath().isEmpty() ? uri.getRawPath() : "/";

        WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders());

        int finalPort = port;
        Bootstrap b = new Bootstrap();
        b.group(getSharedGroup())
         .channel(NioSocketChannel.class)
         .option(ChannelOption.TCP_NODELAY, true)
         .handler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 ch.pipeline().addLast(
                     new HttpClientCodec(),
                     new HttpObjectAggregator(65536),
                     new ClientHandler(handshaker)
                 );
             }
         });

        b.connect(uri.getHost(), finalPort).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) handleClose();
        });
    }

    public void disconnect() {
        intentionalDisconnect = true;
        reconnectEngine.stop();
        stopHeartbeat();
        cleanup();
        state = State.DISCONNECTED;
        onDisconnect.forEach(Runnable::run);
    }

    public void authorize(String token) {
        sendFrame(new Frame(FrameType.AUTH, 0, DataType.STRING, token));
        state = State.AUTHORIZING;
    }

    // ---- Topic API ----

    public void subscribe(String topicName, Map<String, Object> params) {
        subscriptions.put(topicName, params != null ? params : Map.of());
        sendTopicSync();
    }

    public void subscribe(String topicName) { subscribe(topicName, Map.of()); }

    public void unsubscribe(String topicName) {
        if (subscriptions.remove(topicName) != null) sendTopicSync();
    }

    public void setParams(String topicName, Map<String, Object> params) {
        if (!subscriptions.containsKey(topicName)) return;
        subscriptions.put(topicName, params);
        sendTopicSync();
    }

    public List<String> topics() { return new ArrayList<>(subscriptions.keySet()); }

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
    /** Called once per server flush batch — use for rendering. */
    public void onUpdate(Runnable cb) { onUpdate.add(cb); }
    public void onReconnecting(BiConsumer<Integer, Long> cb) { onReconnecting.add(cb); }
    public void onReconnect(Runnable cb) { onReconnect.add(cb); }
    public void onReconnectFailed(Runnable cb) { onReconnectFailed.add(cb); }
    public void onError(Consumer<DanWSException> cb) { onError.add(cb); }

    // ──── Netty Client Handler ────

    private class ClientHandler extends SimpleChannelInboundHandler<Object> {
        private final WebSocketClientHandshaker handshaker;

        ClientHandler(WebSocketClientHandshaker handshaker) {
            this.handshaker = handshaker;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            handshaker.handshake(ctx.channel());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (!handshaker.isHandshakeComplete()) {
                try {
                    handshaker.finishHandshake(ctx.channel(), (FullHttpResponse) msg);
                    channel = ctx.channel();
                    handleOpen();
                } catch (WebSocketHandshakeException e) {
                    handleClose();
                }
                return;
            }

            if (msg instanceof BinaryWebSocketFrame binaryFrame) {
                ByteBuf content = binaryFrame.content();
                byte[] bytes = new byte[content.readableBytes()];
                content.readBytes(bytes);
                parser.feed(bytes);
            } else if (msg instanceof CloseWebSocketFrame) {
                ctx.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            handleClose();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    // ──── Internals ────

    /** Protocol version: 3.3 */
    private static final byte PROTOCOL_MAJOR = 3;
    private static final byte PROTOCOL_MINOR = 3;

    private void handleOpen() {
        state = State.IDENTIFYING;
        startHeartbeat();
        // Send 18-byte IDENTIFY: 16-byte UUID + 2-byte protocol version
        byte[] uuid = UuidUtil.uuidToBytes(id);
        byte[] payload = new byte[18];
        System.arraycopy(uuid, 0, payload, 0, 16);
        payload[16] = PROTOCOL_MAJOR;
        payload[17] = PROTOCOL_MINOR;
        sendFrame(new Frame(FrameType.IDENTIFY, 0, DataType.BINARY, payload));
        onConnect.forEach(Runnable::run);
    }

    private void handleClose() {
        stopHeartbeat();
        if (intentionalDisconnect) return;
        onDisconnect.forEach(Runnable::run);

        if (reconnectEngine.isActive()) {
            state = State.RECONNECTING;
            reconnectEngine.retry();
        } else {
            state = State.RECONNECTING;
            reconnectEngine.start();
        }
    }

    private void handleFrame(Frame frame) {
        switch (frame.frameType()) {
            case AUTH_OK -> state = State.SYNCHRONIZING;
            case AUTH_FAIL -> {
                intentionalDisconnect = true;
                var err = new DanWSException("AUTH_REJECTED", String.valueOf(frame.payload()));
                onError.forEach(cb -> cb.accept(err));
                cleanup();
                state = State.DISCONNECTED;
                onDisconnect.forEach(Runnable::run);
            }
            case SERVER_KEY_REGISTRATION -> {
                if (state == State.IDENTIFYING) state = State.SYNCHRONIZING;
                registry.registerOne(frame.keyId(), (String) frame.payload(), frame.dataType());
            }
            case SERVER_SYNC -> {
                if (state == State.IDENTIFYING) state = State.SYNCHRONIZING;
                if (state != State.READY) sendFrame(Frame.signal(FrameType.CLIENT_READY));
                if (registry.size() == 0) {
                    state = State.READY;
                    if (reconnectEngine.isActive()) { reconnectEngine.stop(); onReconnect.forEach(Runnable::run); }
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
                        for (var cb : onReceive) { try { cb.accept(path, frame.payload()); } catch (Exception ignored) {} }
                    }
                }
                if (state == State.SYNCHRONIZING) {
                    state = State.READY;
                    if (reconnectEngine.isActive()) { reconnectEngine.stop(); onReconnect.forEach(Runnable::run); }
                    onReady.forEach(Runnable::run);
                    if (!subscriptions.isEmpty()) sendTopicSync();
                }
            }
            case ARRAY_SHIFT_LEFT -> {
                // keyId refers to {array}.length — shift values LEFT by count
                KeyEntry lengthEntry = registry.getByKeyId(frame.keyId());
                if (lengthEntry != null) {
                    String lengthPath = lengthEntry.path();
                    String prefix;
                    Matcher tm = TOPIC_WIRE_PATTERN.matcher(lengthPath);
                    boolean isTopic = false;
                    int topicIdx = -1;
                    String userPrefix = null;
                    if (tm.matches()) {
                        isTopic = true;
                        topicIdx = Integer.parseInt(tm.group(1));
                        String userKey = tm.group(2);
                        userPrefix = userKey.substring(0, userKey.length() - ".length".length());
                        prefix = lengthPath.substring(0, lengthPath.length() - ".length".length());
                    } else {
                        prefix = lengthPath.substring(0, lengthPath.length() - ".length".length());
                    }

                    int shiftCount = ((Number) frame.payload()).intValue();
                    Object currentLenObj = store.get(frame.keyId());
                    int currentLength = currentLenObj instanceof Number n ? n.intValue() : 0;

                    // Shift values left: prefix.0 <- prefix.{shift}, prefix.1 <- prefix.{shift+1}, etc.
                    for (int i = 0; i < currentLength - shiftCount; i++) {
                        KeyEntry src = registry.getByPath(prefix + "." + (i + shiftCount));
                        KeyEntry dst = registry.getByPath(prefix + "." + i);
                        if (src != null && dst != null) {
                            store.put(dst.keyId(), store.get(src.keyId()));
                        }
                    }

                    // Do NOT update length here — server sends length update separately if needed

                    // Fire callbacks
                    if (isTopic) {
                        String topicName = indexToTopic.get(topicIdx);
                        if (topicName != null) {
                            TopicClientHandle handle = topicClientHandles.get(topicName);
                            if (handle != null) {
                                handle.notify(userPrefix + ".length", store.get(frame.keyId()));
                            }
                        }
                    } else {
                        for (var cb : onReceive) {
                            try { cb.accept(prefix + ".length", store.get(frame.keyId())); } catch (Exception ignored) {}
                        }
                    }
                }
            }
            case ARRAY_SHIFT_RIGHT -> {
                // keyId refers to {array}.length — shift values RIGHT by count
                KeyEntry lengthEntry = registry.getByKeyId(frame.keyId());
                if (lengthEntry != null) {
                    String lengthPath = lengthEntry.path();
                    String prefix;
                    Matcher tm = TOPIC_WIRE_PATTERN.matcher(lengthPath);
                    boolean isTopic = false;
                    int topicIdx = -1;
                    String userPrefix = null;
                    if (tm.matches()) {
                        isTopic = true;
                        topicIdx = Integer.parseInt(tm.group(1));
                        String userKey = tm.group(2);
                        userPrefix = userKey.substring(0, userKey.length() - ".length".length());
                        prefix = lengthPath.substring(0, lengthPath.length() - ".length".length());
                    } else {
                        prefix = lengthPath.substring(0, lengthPath.length() - ".length".length());
                    }

                    int shiftCount = ((Number) frame.payload()).intValue();
                    Object currentLenObj = store.get(frame.keyId());
                    int currentLength = currentLenObj instanceof Number n ? n.intValue() : 0;

                    // Shift values right: iterate from high to low to avoid overwriting
                    for (int i = currentLength - 1; i >= 0; i--) {
                        KeyEntry src = registry.getByPath(prefix + "." + i);
                        KeyEntry dst = registry.getByPath(prefix + "." + (i + shiftCount));
                        if (src != null && dst != null) {
                            store.put(dst.keyId(), store.get(src.keyId()));
                        }
                    }

                    // Do NOT update length here — server sends length update separately if needed

                    // Fire callbacks
                    if (isTopic) {
                        String topicName = indexToTopic.get(topicIdx);
                        if (topicName != null) {
                            TopicClientHandle handle = topicClientHandles.get(topicName);
                            if (handle != null) {
                                handle.notify(userPrefix + ".length", store.get(frame.keyId()));
                            }
                        }
                    } else {
                        for (var cb : onReceive) {
                            try { cb.accept(prefix + ".length", store.get(frame.keyId())); } catch (Exception ignored) {}
                        }
                    }
                }
            }
            case SERVER_FLUSH_END -> {
                for (var cb : onUpdate) { try { cb.run(); } catch (Exception ignored) {} }
                for (var handle : topicClientHandles.values()) { handle.flushUpdate(); }
            }
            case SERVER_READY -> { /* acknowledged */ }
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
        if (channel == null || !channel.isActive()) return;

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
        sendRaw(Codec.encode(frame));
    }

    private void sendRaw(byte[] data) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(data)));
        }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        lastHeartbeatReceived = System.currentTimeMillis();
        hbSendTask = HB_SCHEDULER.scheduleAtFixedRate(
                () -> sendRaw(Codec.encodeHeartbeat()), HB_SEND_INTERVAL, HB_SEND_INTERVAL, TimeUnit.MILLISECONDS);
        hbCheckTask = HB_SCHEDULER.scheduleAtFixedRate(() -> {
            if (System.currentTimeMillis() - lastHeartbeatReceived > HB_TIMEOUT) {
                stopHeartbeat();
                for (var cb : onError) { try { cb.accept(new DanWSException("HEARTBEAT_TIMEOUT", "No heartbeat received")); } catch (Exception ignored) {} }
                cleanup();
                handleClose();
            }
        }, HB_TIMEOUT, 5000, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (hbSendTask != null) { hbSendTask.cancel(false); hbSendTask = null; }
        if (hbCheckTask != null) { hbCheckTask.cancel(false); hbCheckTask = null; }
    }

    private void cleanup() {
        if (channel != null) { try { channel.close(); } catch (Exception ignored) {} channel = null; }
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

}
