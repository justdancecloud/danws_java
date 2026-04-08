# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 2.4.x   | Yes (current) |
| 2.x     | Yes       |
| < 2.0   | No        |

## Reporting Vulnerabilities

If you discover a security vulnerability, please report it privately:

- **Email**: Open a private security advisory at https://github.com/justdancecloud/danws_java/security/advisories
- **GitHub Issues**: For non-sensitive issues, open an issue at https://github.com/justdancecloud/danws_java/issues

Please include:
- Description of the vulnerability
- Steps to reproduce
- Potential impact

We will respond within 48 hours and aim to release a fix within 7 days for critical issues.

## Security Design

### Transport Security

dan-websocket does not provide transport-layer encryption on its own. In production environments, always use **`wss://`** (WebSocket over TLS) rather than plain `ws://`. This ensures that all data on the wire -- including auth tokens, state updates, and subscription parameters -- is encrypted in transit.

**Recommendations:**

- Terminate TLS at a reverse proxy (e.g., nginx, Caddy, or a cloud load balancer) and forward to your dan-websocket server over localhost.
- If exposing the WebSocket server directly, configure Netty with an `SslContext` or place the server behind an HTTPS-enabled gateway.
- Never transmit auth tokens over unencrypted `ws://` connections in production. The `AUTH` frame payload is sent as a plain string and is visible to any network observer without TLS.

**Netty SSL Configuration:**

For direct SSL termination, Netty supports both JDK SSL and OpenSSL (via `netty-tcnative`). Configure an `SslContext` and add it to the Netty pipeline before the WebSocket handler.

### Message Size Limits

dan-websocket enforces size limits at two levels to prevent memory exhaustion:

| Level | Option | Default | Protection |
|-------|--------|---------|------------|
| WebSocket message | `maxMessageSize` | 1 MB | Netty maxFrameSize rejects oversized messages |
| Individual value | `maxValueSize` | 64 KB | Throws `VALUE_TOO_LARGE` on `set()` if serialized value exceeds limit |
| StreamParser buffer | `maxMessageSize` | 1 MB | Aborts frame parsing if buffer grows beyond limit |

```java
new DanWebSocketServer(8080, "/ws", Mode.BROADCAST, 600_000, 100,
    4_194_304,  // maxMessageSize: 4MB
    1_048_576   // maxValueSize: 1MB
);
```

### Authentication Flow

- Auth tokens are transmitted as `String` type in `AUTH` frames -- they are **not encrypted** by the protocol itself
- Use `wss://` (WebSocket over TLS) in production to protect tokens in transit
- Auth timeout (default 5s) closes connections that do not authenticate in time
- `server.reject(uuid, reason)` immediately closes the connection
- The server determines the principal identity from the auth token; clients cannot claim arbitrary principals

**Auth frame sequence:**
1. Client sends `IDENTIFY` (UUIDv7)
2. Client sends `AUTH` (token string)
3. Server verifies token and calls the auth callback
4. Server sends `AUTH_OK` or `AUTH_FAIL`

### Data Direction

dan-websocket is **server-to-client only** for state data. Clients cannot write arbitrary data to the server state. The only client-to-server data is:
- `IDENTIFY` frame (UUID)
- `AUTH` frame (token string)
- Topic subscription frames (topic names + params)
- `ClientKeyRequest` (single-key recovery)
- Control signals (`ClientReady`, `ClientSync`, `ClientReset`, `ClientResyncReq`)

### VarNumber Encoding

The VarInteger, VarDouble, and VarFloat data types (added in v2.4.0) use variable-length encoding for compact wire representation. These encodings have no security implications:

- **VarInteger**: Zigzag + unsigned VarInt. Bounded to 10 bytes maximum (64-bit values).
- **VarDouble**: Scale byte + VarInt mantissa, with Float64 fallback. Maximum 9 bytes (1 scale + 8 raw).
- **VarFloat**: Scale byte + VarInt mantissa, with Float32 fallback. Maximum 5 bytes (1 scale + 4 raw).

All VarNumber types have deterministic maximum sizes and cannot cause unbounded allocation.

### Threading Safety (Java)

- All per-session operations run on the same Netty EventLoop -- no synchronization needed
- `BulkQueue` and `HeartbeatManager` delegate to EventLoop for thread safety
- Static `SHARED_GROUP` in client uses daemon threads -- call `shutdownSharedGroup()` on application exit
- PrincipalTX auto-eviction runs on the EventLoop with configurable TTL

### Protocol Safety

- **DLE-encoded framing** -- self-synchronizing on stream corruption
- **Type validation** -- `Serializer.serialize()` validates value matches declared DataType
- **Key path validation** -- max 200 bytes, restricted character set
- **Flatten depth limit** -- max 10 levels, circular reference detection
- **freedKeyIds pool** -- capped at 10,000 entries to bound memory usage
