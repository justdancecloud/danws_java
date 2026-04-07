# Migration Guide

## 2.1.x → 2.2.0

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

- `server.setDebug(true)` / `client.setDebug(true)` — debug logging for callback errors
- `maxMessageSize` / `maxValueSize` — configurable size limits
- `DanWebSocketClient.shutdownSharedGroup()` — clean up shared thread pool on exit

### Bug Fixes

- StreamParser buffer bounded (prevents OOM from malformed frames)
- Server.close() no longer deadlocks when called from Netty thread
- Principal index properly cleaned on reconnect
- Session.setLeaf() null-safe for sessionEnqueue
- TTL future cancelled before re-scheduling on rapid disconnect

## 1.x → 2.0.0

### Breaking Changes

1. **Protocol version**: v3.2 → v3.3. Clients and servers must use the same major protocol version.
2. **SERVER_FLUSH_END (0xFF)**: New frame type added. Old clients will not understand this frame.
3. **`onUpdate` behavior changed**: Now fires once per batch (on SERVER_FLUSH_END), not per frame.

### Migration Steps

1. Update both server and client to v2.0.0 simultaneously
2. Replace per-frame rendering logic with `onUpdate` callback:

```java
// Before (v1.x) — rendered on every frame
client.onReceive((key, value) -> {
    updateUI(key, value);  // called 100x per batch
});

// After (v2.0) — render once per batch
client.onUpdate(() -> {
    renderUI();  // called once per ~100ms batch
});
```

3. Array shift optimization is automatic — no code changes needed.

## 0.x → 1.0.0

### Breaking Changes

1. Complete API redesign. See README for current API.
2. Protocol v3.0 — not compatible with 0.x wire format.
