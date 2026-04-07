# Architecture

## Overview

dan-websocket is a server-to-client state synchronization library. The server holds state and pushes changes to connected clients in real-time using a custom binary protocol (DanProtocol v3.3).

```
Server                          Wire                         Client
┌──────────────┐          ┌──────────────┐          ┌──────────────┐
│ set("price", │  binary  │  DLE-framed  │  binary  │ get("price   │
│  Map.of(...))│ ──────→  │   frames     │ ──────→  │   .btc")     │
│              │          │  (only diff)  │          │   → 67000    │
└──────────────┘          └──────────────┘          └──────────────┘
```

## Layer Architecture

```
┌─────────────────────────────────────────────┐
│  API Layer                                   │
│  DanWebSocketServer / DanWebSocketClient     │
│  DanWebSocketSession / PrincipalTX           │
│  TopicHandle / TopicPayload                  │
├─────────────────────────────────────────────┤
│  State Layer                                 │
│  Flatten (auto-flatten Maps/Lists)           │
│  ArrayDiffUtil (shift detection)             │
│  KeyRegistry (keyId ↔ path mapping)          │
├─────────────────────────────────────────────┤
│  Connection Layer                            │
│  BulkQueue (batch + dedup on EventLoop)      │
│  HeartbeatManager (10s/15s)                  │
│  ReconnectEngine (backoff + jitter)          │
├─────────────────────────────────────────────┤
│  Protocol Layer                              │
│  Codec (encode/decode)                       │
│  StreamParser (DLE state machine)            │
│  Serializer (13 types)                       │
│  Frame / DataType / FrameType                │
├─────────────────────────────────────────────┤
│  Transport Layer (Netty)                     │
│  NioEventLoopGroup (zero extra threads)      │
│  WebSocketServerProtocolHandler              │
│  BinaryWebSocketFrame                        │
└─────────────────────────────────────────────┘
```

## Data Flow: Server → Client

### 1. `server.set(key, value)`

```
set("user", Map.of("name","Alice", "score",100))
    │
    ▼
Flatten.flatten() + ArrayDiffUtil
    │
    ├─ shouldFlatten? → flatten()
    │   "user.name" = "Alice"
    │   "user.score" = 100
    │
    ├─ List? → detectShift()
    │   shift detected → ARRAY_SHIFT_LEFT/RIGHT frame
    │
    ├─ Per leaf → setLeaf()
    │   ├─ New key? → KeyRegistration + ServerSync + Value (3 frames)
    │   ├─ Type changed? → trigger full resync
    │   └─ Value changed? → ServerValue frame (1 frame)
    │       Value same? → skip (dedup)
    │
    ▼
BulkQueue.enqueue(frame)  [on Netty EventLoop]
    │
    ├─ ServerValue dedup (same keyId in batch → keep latest)
    ├─ Batched for 100ms (configurable flushIntervalMs)
    │
    ▼
flush() → Codec.encodeBatch(frames) + SERVER_FLUSH_END
    │
    ▼
channel.writeAndFlush(BinaryWebSocketFrame)
```

### 2. Client receives binary

```
channelRead0(BinaryWebSocketFrame)
    │
    ▼
StreamParser.feed(bytes)
    │ DLE state machine: IDLE → DLE STX → IN_FRAME → DLE ETX
    │
    ▼
parseFrame(body) → Frame { frameType, keyId, dataType, payload }
    │
    ▼
handleFrame(frame)
    │
    ├─ SERVER_KEY_REGISTRATION → registry.registerOne(keyId, path, type)
    ├─ SERVER_SYNC → send CLIENT_READY
    ├─ SERVER_VALUE → store.put(keyId, payload)
    │   ├─ topic wire key (t.0.xxx)? → topicHandle.notify()
    │   └─ global key? → onReceive callbacks
    ├─ ARRAY_SHIFT_LEFT/RIGHT → shift store values in-place
    ├─ SERVER_FLUSH_END → onUpdate callback (once per batch)
    └─ SERVER_RESET → clear registry + store
```

## Netty Threading Model

```
┌─ bossGroup (1 thread) ──────────────────┐
│  Accepts new TCP connections              │
└──────────────────────────────────────────┘
         │
         ▼
┌─ workerGroup (N threads) ───────────────┐
│  Each connection bound to one EventLoop  │
│  BulkQueue runs on same EventLoop        │
│  HeartbeatManager runs on same EventLoop │
│  No synchronization needed per-session   │
└──────────────────────────────────────────┘
```

All per-session operations (BulkQueue enqueue/flush, heartbeat, topic callbacks) run on the same Netty EventLoop thread. No locks or synchronized blocks needed.

The client uses a shared `NioEventLoopGroup` across all client instances to avoid 1 thread per client.

## Connection Lifecycle

### Handshake (no auth)

```
Client                              Server
  │                                    │
  │──── IDENTIFY (UUID + v3.3) ───────→│
  │                                    │ createSession(uuid)
  │                                    │ activateSession()
  │←── ServerKeyRegistration ×N ──────│
  │←── ServerSync ────────────────────│
  │──── ClientReady ──────────────────→│
  │←── ServerValue ×N ────────────────│
  │←── SERVER_FLUSH_END ──────────────│
  │         [state: READY]             │
```

### Topic Subscription

```
Client                              Server
  │                                    │
  │──── ClientReset ──────────────────→│
  │──── ClientKeyRegistration ×N ─────→│  (topic.0.name, topic.0.param.x)
  │──── ClientValue ×N ──────────────→│  (topic names + param values)
  │──── ClientSync ───────────────────→│
  │                                    │ processTopicSync()
  │                                    │   diff old vs new subscriptions
  │                                    │   create/update/remove TopicHandles
  │                                    │   fire onSubscribe callbacks
  │←── (session resync with topic data)│
```

## Key Classes

### PrincipalTX / Session

Hold server-side state. Use `Flatten` + `ArrayDiffUtil` for object flattening and array shift detection. `Session` is per-connection; `PrincipalTX` is shared across all sessions of the same principal.

### BulkQueue

Runs on Netty EventLoop. Batches frames every N ms.
- `doEnqueue()` / `doFlush()` — always on EventLoop (no locks)
- ServerValue dedup by keyId
- Appends SERVER_FLUSH_END at end of each flush

### TopicHandle

Per-session, per-topic state container.
- `payload()` (TopicPayload) — scoped key-value store with auto-flatten
- `setCallback(fn)` — runs immediately + on events (SUBSCRIBE, CHANGED_PARAMS, DELAYED_TASK)
- `setDelayedTask(ms)` — periodic polling via Netty `scheduleAtFixedRate`
- Auto-disposed on unsubscribe or disconnect

## Wire Key Format

```
Flat keys:         <userKey>              e.g. "price.btc"
Topic keys:        t.<index>.<userKey>    e.g. "t.0.items.length"
Array length:      <prefix>.length        e.g. "scores.length"
Array elements:    <prefix>.<n>           e.g. "scores.0", "scores.1"
Ring buffer:       <prefix>.__h/l/c/N     e.g. "chart.__h", "chart.__0"
```

## Size Limits

```
maxMessageSize (default 1MB)
  ├─ Netty: HttpObjectAggregator + WebSocketServerProtocolHandler maxFrameSize
  └─ StreamParser: maxBufferSize (FRAME_TOO_LARGE error)

maxValueSize (default 64KB)
  └─ PrincipalTX / Session / TopicPayload: checked after Serializer.serialize()
     → DanWSException("VALUE_TOO_LARGE")
```
