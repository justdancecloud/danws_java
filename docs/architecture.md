# Architecture

## Overview

dan-websocket is a server-to-client state synchronization library. The server holds state and pushes changes to connected clients in real-time using a custom binary protocol (DanProtocol v3.4).

```
Server                          Wire                         Client
+--------------+          +--------------+          +--------------+
| set("price", |  binary  |  DLE-framed  |  binary  | get("price   |
|  Map.of(...))| -------> |   frames     | -------> |   .btc")     |
|              |          |  (only diff)  |          |   -> 67000   |
+--------------+          +--------------+          +--------------+
```

## Layer Architecture

```
+---------------------------------------------+
|  API Layer                                   |
|  DanWebSocketServer / DanWebSocketClient     |
|  DanWebSocketSession / PrincipalTX           |
|  TopicHandle / TopicPayload                  |
+---------------------------------------------+
|  State Layer                                 |
|  FlatStateHelper (shared flatten+diff+dedup) |
|  Flatten (auto-flatten Maps/Lists)           |
|  ArrayDiffUtil (shift detection)             |
|  KeyRegistry (keyId <-> path mapping)        |
+---------------------------------------------+
|  Connection Layer                            |
|  BulkQueue (batch + dedup on EventLoop)      |
|  HeartbeatManager (10s/15s)                  |
|  ReconnectEngine (backoff + jitter)          |
|  PrincipalEviction (TTL-based cleanup)       |
+---------------------------------------------+
|  Protocol Layer                              |
|  Codec (encode/decode)                       |
|  StreamParser (DLE state machine)            |
|  Serializer (16 types incl. VarNumber)       |
|  Frame / DataType / FrameType                |
+---------------------------------------------+
|  Transport Layer (Netty)                     |
|  NioEventLoopGroup (zero extra threads)      |
|  Pooled buffer allocator                     |
|  WebSocketServerProtocolHandler              |
|  BinaryWebSocketFrame                        |
+---------------------------------------------+
```

## Data Flow: Server -> Client

### 1. `server.set(key, value)`

```
set("user", Map.of("name","Alice", "score",100))
    |
    v
FlatStateHelper.performSet()
    |
    +- shouldFlatten? -> Flatten.flatten()
    |   "user.name" = "Alice"
    |   "user.score" = 100
    |
    +- List? -> ArrayDiffUtil.detectShift()
    |   shift detected -> ARRAY_SHIFT_LEFT/RIGHT frame
    |
    +- Stale keys? -> deleteKey() -> ServerKeyDelete frame
    |
    +- Per leaf -> setLeaf()
    |   +- New key? -> KeyRegistration + ServerSync + Value (3 frames)
    |   +- Type changed? -> ServerKeyDelete(old) + KeyRegistration(new)
    |   +- Value changed? -> ServerValue frame (1 frame)
    |       Value same? -> skip (dedup)
    |
    v
BulkQueue.enqueue(frame)  [on Netty EventLoop]
    |
    +- ServerValue dedup (same keyId in batch -> keep latest)
    +- Batched for 100ms (configurable flushIntervalMs)
    |
    v
flush() -> Codec.encodeBatch(frames) + SERVER_FLUSH_END
    |
    v
channel.writeAndFlush(BinaryWebSocketFrame)
```

### 2. Client receives binary

```
channelRead0(BinaryWebSocketFrame)
    |
    v
StreamParser.feed(bytes)
    | DLE state machine: IDLE -> DLE STX -> IN_FRAME -> DLE ETX
    |
    v
parseFrame(body) -> Frame { frameType, keyId, dataType, payload }
    |
    v
handleFrame(frame)
    |
    +- SERVER_KEY_REGISTRATION -> registry.registerOne(keyId, path, type)
    +- SERVER_KEY_DELETE -> registry.remove(keyId), store.remove(keyId)
    +- SERVER_SYNC -> send CLIENT_READY
    +- SERVER_VALUE -> Serializer.deserialize(dataType, payload)
    |   +- VAR_INTEGER -> decodeVarInt + ZigZag decode
    |   +- VAR_DOUBLE  -> exponent byte + decodeVarInt mantissa
    |   +- VAR_FLOAT   -> exponent byte + decodeVarInt mantissa
    |   +- (13 other types handled normally)
    |   +- store.put(keyId, decoded value)
    |   +- topic wire key (t.0.xxx)? -> topicHandle.notify()
    |   +- global key? -> onReceive callbacks
    +- ARRAY_SHIFT_LEFT/RIGHT -> shift store values in-place
    +- SERVER_FLUSH_END -> onUpdate callback (once per batch)
    +- SERVER_RESET -> clear registry + store
```

## VarNumber Encoding Pipeline

New in v2.3.x, the Serializer uses variable-length encoding for numeric types:

```
Serialize:
  Integer/Long/Short/Byte -> ZigZag encode -> encodeVarInt() -> 1-9 bytes
  Double/BigDecimal       -> mantissa+exp split -> 1 byte exp + encodeVarInt(mantissa)
  Float                   -> mantissa+exp split -> 1 byte exp + encodeVarInt(mantissa)

Deserialize:
  VAR_INTEGER (0x0D) -> decodeVarInt() -> ZigZag decode -> Integer or Long
  VAR_DOUBLE  (0x0E) -> read exp byte + decodeVarInt(mantissa) -> reconstruct Double
  VAR_FLOAT   (0x0F) -> read exp byte + decodeVarInt(mantissa) -> reconstruct Float
```

ZigZag encoding maps signed integers to unsigned integers so that small negative values also produce small encodings: `0 -> 0, -1 -> 1, 1 -> 2, -2 -> 3, 2 -> 4, ...`

## FlatStateHelper

Shared logic extracted into `FlatStateHelper` used by `PrincipalTX`, `DanWebSocketSession`, and `TopicPayload`. This eliminates code duplication and ensures consistent behavior:

```
FlatStateHelper.performSet(key, value, ...)
    |
    +- Track previous flattened key sets per top-level key
    +- Flatten Map/List to leaf keys
    +- Detect array shifts via ArrayDiffUtil
    +- Identify stale keys (present in old flatten, absent in new)
    +- Delete stale keys via ServerKeyDelete
    +- Set changed leaf values via setLeaf callback
    +- Dedup: skip unchanged values
```

## Netty Threading Model

```
+- bossGroup (1 thread) ----------------------+
|  Accepts new TCP connections                 |
+----------------------------------------------+
         |
         v
+- workerGroup (N threads) -------------------+
|  Each connection bound to one EventLoop      |
|  BulkQueue runs on same EventLoop            |
|  HeartbeatManager runs on same EventLoop     |
|  TopicHandle timers run on same EventLoop    |
|  No synchronization needed per-session       |
+----------------------------------------------+
```

All per-session operations (BulkQueue enqueue/flush, heartbeat, topic callbacks) run on the same Netty EventLoop thread. No locks or synchronized blocks needed.

The client uses a shared `NioEventLoopGroup` across all client instances to avoid 1 thread per client.

**Netty buffer pooling:** The server uses Netty's pooled buffer allocator for frame construction. Encoded frames are written directly to pooled `ByteBuf` instances, reducing GC pressure under high throughput.

## Connection Lifecycle

### Handshake (no auth)

```
Client                              Server
  |                                    |
  |---- IDENTIFY (UUID + v3.4) ------>|
  |                                    | createSession(uuid)
  |                                    | activateSession()
  |<--- ServerKeyRegistration xN -----|
  |<--- ServerSync -------------------|
  |---- ClientReady ----------------->|
  |<--- ServerValue xN ---------------|
  |<--- SERVER_FLUSH_END -------------|
  |         [state: READY]             |
```

### Key Lifecycle (v3.4)

```
New key added:
  Server ----> ServerKeyRegistration(keyId, path, type)
  Server ----> ServerSync
  Server ----> ServerValue(keyId, payload)

Key deleted (clear):
  Server ----> ServerKeyDelete(keyId)
               Client removes keyId from registry + store

Type changed:
  Server ----> ServerKeyDelete(old keyId)
  Server ----> ServerKeyRegistration(new keyId, path, new type)
  Server ----> ServerSync
  Server ----> ServerValue(new keyId, payload)

Unknown keyId received (client recovery):
  Client ----> ClientKeyRequest(keyId)
  Server ----> ServerKeyRegistration(keyId, path, type)
  Server ----> ServerSync
  Server ----> ServerValue(keyId, payload)
```

### Topic Subscription

```
Client                              Server
  |                                    |
  |---- ClientReset ----------------->|
  |---- ClientKeyRegistration xN ---->|  (topic.0.name, topic.0.param.x)
  |---- ClientValue xN -------------->|  (topic names + param values)
  |---- ClientSync ------------------>|
  |                                    | processTopicSync()
  |                                    |   diff old vs new subscriptions
  |                                    |   create/update/remove TopicHandles
  |                                    |   fire onSubscribe callbacks
  |<--- (session resync with topic data)|
```

### Principal Eviction (v2.4.0)

```
All sessions disconnect for principal "alice"
    |
    v
schedulePrincipalEviction("alice", TTL)
    |
    +- TTL expires, no reconnect?
    |   -> Remove PrincipalTX from memory
    |   -> Log: "Evicting principal 'alice' data"
    |
    +- Session reconnects before TTL?
        -> Cancel eviction timer
        -> Principal data intact
```

## Thread Safety Model

The three core stateful classes -- `PrincipalTX`, `DanWebSocketSession`, and `TopicPayload` -- are protected by `ReentrantReadWriteLock` to allow safe concurrent access from arbitrary threads (e.g., REST controller threads, scheduled executors) alongside the Netty EventLoop.

### Read-Write Lock

```
ReadLock  (shared)      WriteLock (exclusive)
  get()                   set()
  keys()                  clear()
  multiple readers        single writer
  concurrent OK           blocks all others
```

Read operations (`get()`, `keys()`) acquire the read lock. Multiple threads can hold the read lock simultaneously. Write operations (`set()`, `clear()`) acquire the write lock, which is exclusive -- it waits for all readers to finish and blocks new readers until the write completes.

### Defensive Deep Copy

All `get()` methods return values through `DeepCopy.copy()`:

```
DeepCopy.copy(val)
    |
    +- null / String / Number / Boolean  -> return as-is (immutable)
    +- List   -> recursive copy -> Collections.unmodifiableList
    +- Map    -> recursive copy -> Collections.unmodifiableMap
    +- byte[] -> clone
```

This guarantees that user code cannot mutate the internal store through a returned reference. The returned collections are wrapped in unmodifiable wrappers, so any attempt to modify them throws `UnsupportedOperationException`.

### Deferred Callback Pattern

A critical design constraint: **callbacks must never run while holding a lock**. If a callback (e.g., `BulkQueue.enqueue()`) tried to acquire another lock, it could deadlock.

The solution uses `ThreadLocal<List<Runnable>>`:

```
writeLock.lock()
try {
    // 1. Mutate state
    store.put(keyId, value);
    
    // 2. Collect callbacks (do NOT execute yet)
    deferred.add(() -> bulkQueue.enqueue(frame));
    deferred.add(() -> session.notify());
} finally {
    writeLock.unlock();
}

// 3. Execute callbacks OUTSIDE the lock
for (Runnable action : deferred) action.run();
deferred.clear();
```

This pattern is applied consistently across `PrincipalTX`, `DanWebSocketSession`, and `TopicPayload`. The `ThreadLocal` ensures that each calling thread has its own deferred list with no contention.

## Key Classes

### FlatStateHelper

Shared flatten + diff + shift logic used by PrincipalTX, Session, and TopicPayload. Accepts callback interfaces (`LeafSetter`, `KeyDeleter`) so each caller provides its own frame-sending behavior while sharing the core algorithm.

### PrincipalTX / Session

Hold server-side state. Delegate to `FlatStateHelper` for object flattening, array shift detection, and stale key cleanup. `Session` is per-connection; `PrincipalTX` is shared across all sessions of the same principal.

PrincipalTX supports auto-eviction: when all sessions disconnect, a timer is scheduled. If no session reconnects within the TTL, the PrincipalTX is removed from the server's principal map.

### BulkQueue

Runs on Netty EventLoop. Batches frames every N ms.
- `doEnqueue()` / `doFlush()` -- always on EventLoop (no locks)
- ServerValue dedup by keyId
- Appends SERVER_FLUSH_END at end of each flush

### TopicHandle

Per-session, per-topic state container.
- `payload()` (TopicPayload) -- scoped key-value store with auto-flatten via FlatStateHelper
- `setCallback(fn)` -- runs immediately + on events (SUBSCRIBE, CHANGED_PARAMS, DELAYED_TASK)
- `setDelayedTask(ms)` -- periodic polling via Netty `scheduleAtFixedRate`
- Auto-disposed on unsubscribe or disconnect

### Serializer

Handles 16 data types including the 3 VarNumber types (VarInteger, VarDouble, VarFloat). Uses direct byte manipulation -- no intermediate ByteBuffer or object allocation during encode/decode.

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
  +- Netty: HttpObjectAggregator + WebSocketServerProtocolHandler maxFrameSize
  +- StreamParser: maxBufferSize (FRAME_TOO_LARGE error)

maxValueSize (default 64KB)
  +- PrincipalTX / Session / TopicPayload: checked after Serializer.serialize()
     -> DanWSException("VALUE_TOO_LARGE")
```

## Performance Characteristics

| Operation | Cost |
|-----------|------|
| `set()` with unchanged value | 0 frames (dedup) |
| `set()` with changed leaf | 1 frame |
| New key registration | 3 frames (reg + sync + value) |
| Key deletion (`clear(key)`) | 1 frame per sub-key (ServerKeyDelete) |
| Array shift + push (size N) | 2 frames (shift + new element) |
| Ring buffer push | 2 frames (always) |
| Flush batch | 1 frame (SERVER_FLUSH_END) |
| VarInteger (small value) | 1 byte (vs 4 bytes fixed Int32) |
| VarDouble (round number) | 2-3 bytes (vs 8 bytes fixed Float64) |
