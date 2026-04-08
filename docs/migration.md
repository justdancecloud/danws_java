# Migration Guide

## 2.3.x -> 2.4.0

### No Breaking Changes

v2.4.0 is fully backward-compatible with v2.3.x. All existing APIs work without modification.

### New: PrincipalTX Auto-Eviction

When all sessions for a principal disconnect, the server now schedules automatic eviction of that principal's in-memory data after a configurable TTL. This prevents memory leaks on long-running servers with many transient users.

**Default behavior:** Principal data is evicted 5 minutes after the last session disconnects. If a session reconnects before the TTL expires, the eviction is cancelled and the data remains intact.

```java
// Default: 5-minute eviction TTL
new DanWebSocketServer(8080, Mode.PRINCIPAL);

// Custom eviction TTL: 30 seconds
new DanWebSocketServer(8080, "/ws", Mode.PRINCIPAL, 600_000, 100,
    1_048_576, 65_536,
    30_000  // principalEvictionTtl: 30 seconds
);

// Disable eviction entirely (principal data lives forever)
new DanWebSocketServer(8080, "/ws", Mode.PRINCIPAL, 600_000, 100,
    1_048_576, 65_536,
    0  // principalEvictionTtl: 0 = disabled
);
```

**Impact:** If you rely on principal data persisting indefinitely after disconnect (e.g., you call `server.principal("alice").set(...)` before Alice connects), the data will now be evicted if no sessions are connected for 5 minutes. Options:
- Increase the TTL to match your use case
- Set `principalEvictionTtl` to `0` to disable eviction
- Re-set data when needed (the data will be sent on next connection)

### New: Constructor Parameter

The full constructor now accepts an 8th parameter:

```java
// v2.3.x (7 params)
new DanWebSocketServer(port, path, mode, ttlMs, flushIntervalMs, maxMessageSize, maxValueSize);

// v2.4.0 (8 params, optional -- defaults to 300_000)
new DanWebSocketServer(port, path, mode, ttlMs, flushIntervalMs, maxMessageSize, maxValueSize, principalEvictionTtl);
```

The 7-parameter constructor still works and defaults `principalEvictionTtl` to 300,000ms (5 minutes).

### Performance Improvements

- Comprehensive performance optimizations: direct byte manipulation in Serializer, Netty pooled buffer usage
- FlatStateHelper consolidation: shared flatten+diff logic across PrincipalTX, Session, TopicPayload
- Parallel test execution utilizing up to 28 cores

These are internal changes with no API impact.

---

## 2.2.x -> 2.3.0

### No Breaking Changes

v2.3.0 is fully backward-compatible with v2.2.x.

### VarNumber Encoding

All numeric types now use variable-length encoding on the wire. This is transparent to your code -- you still call `set()` with Integer, Long, Double, Float, etc. The protocol handles compression automatically.

**Wire type changes (transparent to application code):**

| Java Type | Old Wire Type | New Wire Type | Size Change |
|-----------|--------------|---------------|-------------|
| `Integer` | Int32 (4 bytes fixed) | VarInteger (1-5 bytes) | 50-75% smaller for small values |
| `Long` | Int64 (8 bytes fixed) | VarInteger (1-9 bytes) | 50-75% smaller for small values |
| `Short` | Int32 (4 bytes fixed) | VarInteger (1-3 bytes) | 25-75% smaller |
| `Byte` | Uint8 (1 byte) | VarInteger (1 byte) | Same |
| `Double` | Float64 (8 bytes fixed) | VarDouble (2-9 bytes) | 50-75% smaller for round numbers |
| `Float` | Float32 (4 bytes fixed) | VarFloat (2-5 bytes) | 25-50% smaller for round numbers |
| `BigDecimal` | Float64 (8 bytes) | VarDouble (2-9 bytes) | 50-75% smaller |
| `BigInteger` | Int64 (8 bytes) | VarInteger (1-9 bytes) | 50-75% smaller |

**Backward compatibility:** Old fixed-width type codes (Int32, Int64, Float32, Float64) are still decoded correctly. A v2.3.0 client can read data from a v2.2.x server, and a v2.2.x client can read VarNumber data if it supports the VarNumber type codes (0x0D, 0x0E, 0x0F). For full benefit, update both server and client to v2.3.0+.

### Protocol Version

Protocol version is now v3.4 (was v3.3). The IDENTIFY handshake includes the version byte, and both versions are accepted for backward compatibility.

---

## 2.1.x -> 2.2.0

### No Breaking Changes

v2.2.0 is a stability release. All v2.1.x APIs remain fully backward-compatible.

### New Defaults

| Option | v2.1.x | v2.2.0 | Impact |
|--------|--------|--------|--------|
| `maxMessageSize` | No limit | 1 MB | Oversized messages rejected. Increase if needed. |
| `maxValueSize` | No limit | 64 KB | `set()` throws if a single serialized value exceeds limit. |

If your application sets large binary values or strings, you may need to increase these limits:

```java
new DanWebSocketServer(8080, "/ws", Mode.BROADCAST, 600_000, 100,
    4_194_304,  // maxMessageSize: 4MB
    1_048_576   // maxValueSize: 1MB
);
```

### New Features

- `server.setDebug(true)` / `client.setDebug(true)` -- debug logging for callback errors
- `maxMessageSize` / `maxValueSize` -- configurable size limits
- `DanWebSocketClient.shutdownSharedGroup()` -- clean up shared thread pool on exit

### New in v2.2.1: ServerKeyDelete + ClientKeyRequest

- `clear(key)` sends incremental ServerKeyDelete per key instead of full ServerReset + resync
- Type change sends ServerKeyDelete(old) + KeyRegistration(new) instead of full resync
- KeyId reuse: deleted keyIds are recycled for new registrations (prevents keyId exhaustion)
- Unknown keyId: client sends ClientKeyRequest instead of CLIENT_RESYNC_REQ (single-key recovery)

### Bug Fixes

- StreamParser buffer bounded (prevents OOM from malformed frames)
- Server.close() no longer deadlocks when called from Netty thread
- Principal index properly cleaned on reconnect
- Session.setLeaf() null-safe for sessionEnqueue
- TTL future cancelled before re-scheduling on rapid disconnect

---

## 1.x -> 2.0.0

### Breaking Changes

1. **Protocol version**: v3.2 -> v3.3. Clients and servers must use the same major protocol version.
2. **SERVER_FLUSH_END (0xFF)**: New frame type added. Old clients will not understand this frame.
3. **`onUpdate` behavior changed**: Now fires once per batch (on SERVER_FLUSH_END), not per frame.

### Migration Steps

1. Update both server and client to v2.0.0 simultaneously
2. Replace per-frame rendering logic with `onUpdate` callback:

```java
// Before (v1.x) -- rendered on every frame
client.onReceive((key, value) -> {
    updateUI(key, value);  // called 100x per batch
});

// After (v2.0) -- render once per batch
client.onUpdate(() -> {
    renderUI();  // called once per ~100ms batch
});
```

3. Array shift optimization is automatic -- no code changes needed.

---

## 0.x -> 1.0.0

### Breaking Changes

1. Complete API redesign. See README for current API.
2. Protocol v3.0 -- not compatible with 0.x wire format.
