# Changelog

## 2.1.2 (2026-04-07)
- Add: client HeartbeatManager (10s send, 15s timeout detection)
- Fix: previousArrays memory leak in PrincipalTX, Session, TopicPayload clear()
- Fix: client.unsubscribe cleans topicClientHandles (memory leak)
- Fix: Session.clearKey properly removes flattened sub-keys
- Optimize: Session.sessionStore ConcurrentHashMap → HashMap
- Refactor: extract shared UuidUtil (bytesToUuid/uuidToBytes)

## 2.1.1 (2026-04-07)
- Refactor: extract shared ArrayDiffUtil (~289 lines removed)
- Add ReconnectEngine with exponential backoff + jitter (client auto-reconnects)
- TopicClientHandle.onUpdate now fires per-flush batch (not per-frame)
- StreamParser: replace List\<Byte> with byte[] buffer (eliminates autoboxing GC)
- PrincipalTX.store: ConcurrentHashMap → HashMap (EventLoop-only access)
- Fix applyShiftLeft: always send length after shift (client length restore)
- Fix Session: remove (double) cast on length — int consistently
- IDENTIFY frame includes protocol version (v3.3), backward-compatible
- Remove unused: arrayShiftCounters, matchesPrincipal, misleading reconnect callbacks

## 2.1.0 (2026-04-07)
- **Protocol v3.3**: SERVER_FLUSH_END (0xFF) batch boundary frame
- **Batch-level `onUpdate`**: fires once per BulkQueue flush (~100ms) instead of per-frame — prevents render storms
- `onReceive` remains per-frame for fine-grained key-level listeners
- BulkQueue automatically appends SERVER_FLUSH_END at the end of every flush
- Client `onUpdate(Runnable)` callback added

## 2.0.1 (2026-04-07)
- Fix: Redundant full sync — server ignores CLIENT_READY when already in READY state
- Fix: Client only sends CLIENT_READY when state !== READY (prevents periodic full transmission)
- Fix: Heartbeat double-send removed — server no longer echoes heartbeat on receive
- Add: FrameCountTest.java (8 tests) verifying array optimization efficiency
- Add: Frame count verification in README with actual E2E test results

## 2.0.0 (2026-04-06)
- **Protocol v3.2**: ARRAY_SHIFT_LEFT (0x20) and ARRAY_SHIFT_RIGHT (0x21) frame types
- **Auto array diff detection**: shift, append, pop patterns detected automatically
- **Smart shift detection algorithm**: no k=1..5 limit, any shift amount supported
- **Array shrink without full resync**: stale index keys cleaned via .length
- **Incremental key registration** for Session and TopicPayload (3 frames instead of full resync)
- **Value change detection** in PrincipalTX and Session setLeaf — unchanged values no longer re-sent
- **Principal session index**: O(1) lookup instead of O(N) scan on every value set
- **Key frame caching**: PrincipalTX avoids rebuilding key frames on every resync
- **Wire path caching**: TopicPayload avoids string allocation on every buildKeyFrames
- **Configurable BulkQueue flush interval** (`flushIntervalMs` server option, default 100ms)
- ArraySync ring buffer API + ArrayView client-side read-only view
- ArraySyncE2ETest (24 tests), FrameCountTest (8 tests)

## 1.0.3 (2026-04-07)
- Perf: Incremental key registration for Session and TopicPayload
- Add: BigDecimal → Float64, BigInteger → Int64/String auto-detection
- Add: Short → Int32, Byte → Uint8 auto-detection

## 1.0.2 (2026-04-07)
- Perf: Principal session index — O(1) lookup
- Perf: PrincipalTX key frame caching
- Perf: TopicPayload wire path caching
- Add: Configurable BulkQueue flush interval

## 1.0.1 (2026-04-06)
- Fix: Flatten value change detection — unchanged leaf values no longer re-sent

## 1.0.0 (2026-04-06)
- **Stable release** — production-ready
- Netty EventLoop architecture (zero extra threads)
- 4 modes: Broadcast, Principal, Session Topic, Session Principal Topic
- Auto-flatten objects/arrays to binary leaf keys (depth 10, circular ref detection)
- 4-byte keyId (supports 4B+ keys)
- Topic API: `setCallback` + `setDelayedTask` pattern with EventType
- TopicPayload scoped per-topic key-value store
- BulkQueue: 100ms batch flush with ServerValue dedup
- Heartbeat with auto-reconnection
- Wire-compatible with TypeScript (dan-websocket for npm)
