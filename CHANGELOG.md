# Changelog

## [2.4.5] - 2026-04-11
### Changed
- **`Frame` is now generic `Frame<T>`** — payload type parameter replaces raw `Object payload`. Producers use specific types (`Frame<String>` for key-registration, `Frame<byte[]>` for identify, `Frame<Void>` for signals, etc.); pipeline code routes frames as `Frame<?>`. Compile-time safety for known payload shapes while keeping full runtime flexibility. All 12 affected files updated with diamond operator + wildcard parameters; `-Xlint:unchecked` remains clean.
- **CI workflow scope tightened**: no automatic builds on `push` — PRs + manual dispatch only. `versions/*` snapshot branches are never built.
- **Release workflow skips tests** — tests are validated locally before tagging and by the CI workflow on PR, so the tag-push Release pipeline goes straight to build + sign + publish (5-minute end-to-end).

## [2.4.4] - 2026-04-11
### Removed
- **`Mode.INDIVIDUAL`** — deleted. It was already silently aliased to `PRINCIPAL` at construction time; the enum entry was a misleading API surface. Use `Mode.PRINCIPAL` directly.

### Added
- **KeyId overflow guard**: `KeyRegistry.registerNew()` / `nextId()` now throws `KEY_ID_OVERFLOW` instead of silently wrapping past `Integer.MAX_VALUE`.
- **`-Xlint:unchecked`** enabled alongside `-Xlint:deprecation` in `build.gradle` so any new unchecked/raw-type warnings surface during compilation.

## [2.4.3] - 2026-04-11
### Added
- Automated release workflow (`.github/workflows/release.yml`) — on `v*` tag push, the workflow runs tests, builds signed artifacts, uploads to Maven Central via the Portal API, mirrors to GitHub Packages, and creates a GitHub Release with attached JARs + POM + signatures.

## [2.4.2] - 2026-04-11
### Fixed
- **Codec signal frame registry:** `SERVER_KEY_DELETE` (0x22) and `CLIENT_KEY_REQUEST` (0x23) added to `Codec.isSignalFrame()`, restoring parity with `StreamParser` and the DanProtocol v3.5 spec.
- **Client topic-state concurrency:** `subscriptions`, `topicClientHandles`, `topicIndexMap`, `indexToTopic` now use `ConcurrentHashMap`. Prevents state corruption when user threads call `subscribe()/topic()` while the Netty event loop processes frames.
- **Unhandled error escalation:** `emitError` no longer throws from inside the Netty event loop or the shared heartbeat scheduler when no `onError` listener is registered; it logs to debug/stderr instead.
- **ArrayShift bounds:** shift count is now clamped to `[0, currentLength]`, rejecting malformed or hostile `ARRAY_SHIFT_LEFT/RIGHT` frames that previously caused loop under/overflow.
- **Heartbeat resilience:** `scheduleAtFixedRate` tasks are wrapped in `try/catch` so a single transient exception no longer cancels future heartbeat ticks for a client.
- **UUIDv7 entropy:** replaced `new Random()` with a static `SecureRandom` — client IDs are no longer predictable and allocation is eliminated from the hot path.

### Added
- **Server protocol version check:** 18-byte `IDENTIFY` payloads now have their major version validated against `PROTOCOL_MAJOR=3`; mismatched clients are rejected.

### Changed
- Protocol constant bumped to **3.5** (`PROTOCOL_MAJOR=3`, `PROTOCOL_MINOR=5`) in `IDENTIFY` frames.

## [2.4.1] - 2026-04-08
### Added
- ReentrantReadWriteLock on PrincipalTX, DanWebSocketSession, TopicPayload
- Defensive deep copy on all get() methods (immutable return values)
- DeepCopy utility class
- ThreadLocal deferred action pattern for deadlock prevention

## [2.4.0] - 2026-04-08
### Added
- VAR_INTEGER (0x0D): Zigzag + VarInt encoding for integers
- VAR_DOUBLE (0x0E): Scale + VarInt mantissa encoding for doubles
- VAR_FLOAT (0x0F): Scale + VarInt mantissa encoding for floats
- Parallel test execution (maxParallelForks = availableProcessors)
- PrincipalTX auto-eviction with configurable TTL

### Changed
- Test time: 5m23s to 1m36s (70% reduction)
- Numbers auto-detect to VarInteger/VarDouble/VarFloat

## [2.3.1] - 2026-04-08
### Added
- ClientKeyRequest O(1) via reverse keyId index in TopicPayload + KeyRegistry
- Single-pass buildAllFrames() in PrincipalTX, TopicPayload, Session
- ArrayDiffUtil: MAX_SHIFT=50 + quickHash pre-filter

## [2.3.0] - 2026-04-08
### Added
- Direct byte manipulation in Serializer (no ByteBuffer allocation)
- Single-pass Codec encoding (3 allocations reduced to 1)
- Netty PooledByteBufAllocator in sendBytes
- FlatStateHelper: shared set() logic extracted from 3 classes
- Flatten path pre-computation

## [2.2.3] - 2026-04-08
### Added
- PrincipalTX auto-eviction after configurable TTL
- hasActiveSessions() read-only check

## [2.2.2] - 2026-04-08
### Added
- emitError throws when no listeners (EventEmitter pattern)
- freedKeyIds pool capped at 10,000
- wss:// security docs, CQRS docs

## [2.2.1] - 2026-04-07
### Added
- Protocol v3.4: ServerKeyDelete (0x22), ClientKeyRequest (0x23)
- Incremental key deletion, single-key recovery, keyId reuse

## [2.2.0] - 2026-04-07
### Added
- maxMessageSize / maxValueSize configurable
- Debug logging system
- Comprehensive mode tests
- SECURITY.md, CONTRIBUTING.md, docs/

## [2.1.9] - 2026-04-07
### Fixed
- StreamParser buffer limit now driven by maxMessageSize setting (was hardcoded 1MB)

## [2.1.8] - 2026-04-07
### Fixed
- StreamParser buffer bounded to 1MB (prevents OOM from malformed frames)
- Server.close() no longer calls .sync() (prevents deadlock from Netty thread)
- Session.setLeaf() null-safe for sessionEnqueue (prevents NPE before bindSessionTX)
- Principal index properly cleaned on reconnect (prevents duplicate entries)
- TTL future cancelled before re-scheduling on rapid disconnect

### Added
- DanWebSocketClient.shutdownSharedGroup() for clean application exit

## [2.1.7] - 2026-04-07
### Added
- Comprehensive mode tests (27 tests) covering all 4 modes, mode guards, maxValueSize

### Changed
- Version sync: TypeScript and Java now share the same version number

## [2.1.6] - 2026-04-07
### Changed
- README: detailed mode documentation with complete server + client examples for all 4 modes

## [2.1.5] - 2026-04-07
### Added
- maxMessageSize (default 1MB): limits incoming WebSocket frame size via Netty maxFrameSize
- maxValueSize (default 64KB): throws VALUE_TOO_LARGE if serialized value exceeds limit

## [2.1.4] - 2026-04-07
### Added
- Debug logger system: server.setDebug(true) / client.setDebug(true) or custom BiConsumer

### Fixed
- All silent catch (Exception ignored) blocks now route through debug logger

## [2.1.3] - 2026-04-07
### Fixed
- Client unsubscribe cleans topicClientHandles (memory leak)

## [2.1.2] - 2026-04-07
### Added
- Client HeartbeatManager (10s send, 15s timeout detection)

### Fixed
- previousArrays memory leak in PrincipalTX, Session, TopicPayload clear()
- Client.unsubscribe cleans topicClientHandles (memory leak)
- Session.clearKey properly removes flattened sub-keys

### Changed
- Session.sessionStore ConcurrentHashMap replaced with HashMap

## [2.1.1] - 2026-04-07
### Added
- ReconnectEngine with exponential backoff + jitter (client auto-reconnects)
- IDENTIFY frame includes protocol version (v3.3), backward-compatible

### Changed
- Extract shared ArrayDiffUtil (~289 lines removed)
- TopicClientHandle.onUpdate now fires per-flush batch (not per-frame)
- StreamParser: replace List<Byte> with byte[] buffer (eliminates autoboxing GC)
- PrincipalTX.store: ConcurrentHashMap replaced with HashMap (EventLoop-only access)

### Fixed
- applyShiftLeft: always send length after shift (client length restore)
- Session: remove (double) cast on length, use int consistently

## [2.1.0] - 2026-04-07
### Added
- Protocol v3.3: SERVER_FLUSH_END (0xFF) batch boundary frame
- Batch-level onUpdate: fires once per BulkQueue flush (~100ms) instead of per-frame
- Client onUpdate(Runnable) callback

## [2.0.1] - 2026-04-07
### Fixed
- Redundant full sync: server ignores CLIENT_READY when already in READY state
- Client only sends CLIENT_READY when state is not READY (prevents periodic full transmission)
- Heartbeat double-send removed: server no longer echoes heartbeat on receive

### Added
- FrameCountTest.java (8 tests) verifying array optimization efficiency

## [2.0.0] - 2026-04-06
### Added
- Protocol v3.2: ARRAY_SHIFT_LEFT (0x20) and ARRAY_SHIFT_RIGHT (0x21) frame types
- Auto array diff detection: shift, append, pop patterns detected automatically
- Smart shift detection algorithm: no k=1..5 limit, any shift amount supported
- Array shrink without full resync: stale index keys cleaned via .length
- Incremental key registration for Session and TopicPayload
- Value change detection in PrincipalTX and Session setLeaf
- Principal session index: O(1) lookup instead of O(N) scan
- Key frame caching in PrincipalTX
- Wire path caching in TopicPayload
- Configurable BulkQueue flush interval (flushIntervalMs server option, default 100ms)
- ArraySync ring buffer API + ArrayView client-side read-only view

## [1.0.3] - 2026-04-07
### Added
- Incremental key registration for Session and TopicPayload
- BigDecimal to Float64, BigInteger to Int64/String auto-detection
- Short to Int32, Byte to Uint8 auto-detection

## [1.0.2] - 2026-04-07
### Changed
- Principal session index: O(1) lookup
- PrincipalTX key frame caching
- TopicPayload wire path caching
- Configurable BulkQueue flush interval

## [1.0.1] - 2026-04-06
### Fixed
- Flatten value change detection: unchanged leaf values no longer re-sent

## [1.0.0] - 2026-04-06
### Added
- Stable release: production-ready
- Netty EventLoop architecture (zero extra threads)
- 4 modes: Broadcast, Principal, Session Topic, Session Principal Topic
- Auto-flatten objects/arrays to binary leaf keys (depth 10, circular ref detection)
- 4-byte keyId (supports 4B+ keys)
- Topic API: setCallback + setDelayedTask pattern with EventType
- TopicPayload scoped per-topic key-value store
- BulkQueue: 100ms batch flush with ServerValue dedup
- Heartbeat with auto-reconnection
- Wire-compatible with TypeScript (dan-websocket for npm)
