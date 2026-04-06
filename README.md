# dan-websocket (Java)

> Lightweight binary protocol for real-time state synchronization — **Server to Client**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.justdancecloud/dan-websocket)](https://central.sonatype.com/artifact/io.github.justdancecloud/dan-websocket)
[![npm](https://img.shields.io/npm/v/dan-websocket)](https://www.npmjs.com/package/dan-websocket)

Java implementation of [DanProtocol v3.0](./dan-protocol-3.0.md). Wire-compatible with the [TypeScript version](https://github.com/justdancecloud/danws_typescript).

---

## What is this?

**dan-websocket** pushes state from your server to connected clients in real time. Instead of sending JSON over WebSocket, it uses a compact binary protocol that auto-detects types and handles reconnection, heartbeat, and recovery transparently.

You just `set(key, value)` on the server. All connected clients receive it instantly.

---

## Why not just JSON over WebSocket?

| | JSON WebSocket | dan-websocket |
|---|---|---|
| A boolean update | `{"key":"alive","value":true}` = 30+ bytes | 9 bytes |
| Type safety | Parse then cast | Auto-typed on the wire |
| Reconnection | DIY | Built-in with heartbeat |
| Multi-device sync | DIY per-connection | Principal-based (1 state → N sessions) |

---

## Install

### Gradle

```groovy
dependencies {
    implementation 'io.github.justdancecloud:dan-websocket:0.1.0'
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.justdancecloud</groupId>
    <artifactId>dan-websocket</artifactId>
    <version>0.1.0</version>
</dependency>
```

Requires **Java 17+**.

---

## Quick Start

### 1. Broadcast Mode — all clients get the same data

Perfect for dashboards, live feeds, status pages.

**Server:**

```java
import com.danws.api.DanWebSocketServer;
import static com.danws.api.DanWebSocketServer.Mode;

var server = new DanWebSocketServer(8080, Mode.BROADCAST);

// Just set values. Types are auto-detected. No schema needed.
server.set("sensor.temp", 23.5);          // Double → Float64
server.set("sensor.status", "online");    // String → String
server.set("sensor.active", true);        // Boolean → Bool
server.set("sensor.updated", new Date()); // Date → Timestamp

// Update a value — all connected clients get it within 100ms
new Timer().scheduleAtFixedRate(new TimerTask() {
    public void run() {
        server.set("sensor.temp", 20 + Math.random() * 10);
    }
}, 0, 1000);

// Read back, list, delete
server.get("sensor.temp");     // 23.5
server.keys();                 // ["sensor.temp", "sensor.status", ...]
server.clear("sensor.active"); // remove one key
server.clear();                // remove all keys
```

**Client:**

```java
import com.danws.api.DanWebSocketClient;

var client = new DanWebSocketClient("ws://localhost:8080");

// Fires once initial sync is complete
client.onReady(() -> {
    System.out.println("Temperature: " + client.get("sensor.temp"));
    System.out.println("All keys: " + client.keys());
});

// Fires on every value update (including initial sync)
client.onReceive((key, value) -> {
    System.out.println(key + " = " + value);
});

client.connect();
```

### 2. Individual Mode — per-user data via principals

Perfect for games, user dashboards, personalized data.

A **principal** = one authenticated user. All their sessions (PC, mobile, other tabs) share the same state automatically.

**Server:**

```java
var server = new DanWebSocketServer(8080, Mode.INDIVIDUAL);

// Enable authentication
server.enableAuthorization(true);
server.onAuthorize((uuid, token) -> {
    String user = verifyJWT(token);
    server.authorize(uuid, token, user);  // 3rd arg = principal name
});

// Set data per principal — no schema, just set
server.principal("alice").set("score", 100.0);
server.principal("alice").set("name", "Alice");

server.principal("bob").set("score", 50.0);
server.principal("bob").set("name", "Bob");

// Alice opens on PC and mobile → both sessions see score=100
// Update alice → all her sessions get it instantly
server.principal("alice").set("score", 200.0);
```

**Client:**

```java
var client = new DanWebSocketClient("ws://localhost:8080");

client.onConnect(() -> client.authorize(myJWTToken));

client.onReady(() -> {
    System.out.println("My score: " + client.get("score"));  // 100.0
    System.out.println("My name: " + client.get("name"));    // "Alice"
});

client.onReceive((key, value) -> {
    if ("score".equals(key)) updateScoreUI(value);
});

client.connect();
```

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                     SERVER                          │
│                                                     │
│  Broadcast Mode:                                    │
│    server.set("temp", 23.5) ──▶ All clients         │
│                                                     │
│  Individual Mode:                                   │
│    Principal "alice" ─── Shared State (1 copy)      │
│      ├── Session (PC)     ── same data              │
│      ├── Session (mobile) ── same data              │
│      └── Session (tablet) ── same data              │
│                                                     │
│    Principal "bob" ─── Shared State (1 copy)        │
│      └── Session (laptop) ── own data               │
└──────────────────────┬──────────────────────────────┘
                       │ Binary WebSocket (DanProtocol v3.0)
                       ▼
┌─────────────────────────────────────────────────────┐
│                     CLIENT                          │
│                                                     │
│  client.get("temp")  → 23.5                         │
│  client.onReceive((key, val) -> ...)                │
│  client.keys()       → ["temp", "status", ...]      │
└─────────────────────────────────────────────────────┘
```

---

## API Reference

### Server — Broadcast Mode

```java
var server = new DanWebSocketServer(port, Mode.BROADCAST);
```

| Method | Description |
|--------|-------------|
| `server.set(key, value)` | Set value, auto-detect type, sync to all |
| `server.get(key)` | Read current value (`null` if not set) |
| `server.keys()` | All registered key paths |
| `server.clear(key)` | Remove one key |
| `server.clear()` | Remove all keys |

### Server — Individual Mode

```java
var server = new DanWebSocketServer(port, Mode.INDIVIDUAL);
```

| Method | Description |
|--------|-------------|
| `server.principal(name).set(key, value)` | Set for principal |
| `server.principal(name).get(key)` | Read value |
| `server.principal(name).keys()` | List keys |
| `server.principal(name).clear(key)` | Remove one key |
| `server.principal(name).clear()` | Remove all keys |

### Server — Auth & Sessions

| Method | Description |
|--------|-------------|
| `server.enableAuthorization(enabled)` | Enable token auth |
| `server.authorize(uuid, token, principal)` | Accept, bind to principal |
| `server.reject(uuid, reason)` | Reject |
| `server.onConnection(cb)` | New session: `Consumer<Session>` |
| `server.onAuthorize(cb)` | Auth received: `BiConsumer<uuid, token>` |
| `server.getSession(uuid)` | Get session by ID |
| `server.getSessionsByPrincipal(name)` | All sessions for principal |
| `server.isConnected(uuid)` | Connection status |
| `server.close()` | Shutdown |

### Client

```java
var client = new DanWebSocketClient(url);
```

| Method | Description |
|--------|-------------|
| `client.connect()` | Connect |
| `client.disconnect()` | Disconnect |
| `client.authorize(token)` | Send auth token |
| `client.get(key)` | Current value (`null` if not received) |
| `client.keys()` | All received key paths |
| `client.id()` | This client's UUIDv7 |
| `client.state()` | Connection state enum |

| Event | Callback Type |
|-------|--------------|
| `client.onConnect(cb)` | `Runnable` |
| `client.onReady(cb)` | `Runnable` |
| `client.onReceive(cb)` | `BiConsumer<String, Object>` |
| `client.onDisconnect(cb)` | `Runnable` |
| `client.onError(cb)` | `Consumer<DanWSException>` |

---

## Auto-Detected Types

| Java Type | Wire Type | Size |
|-----------|-----------|------|
| `null` | Null | 0 bytes |
| `Boolean` | Bool | 1 byte |
| `Integer` | Int32 | 4 bytes |
| `Long` | Int64 / Uint64 | 8 bytes |
| `Float` | Float32 | 4 bytes |
| `Double` | Float64 | 8 bytes |
| `String` | String | variable |
| `byte[]` | Binary | variable |
| `Date` / `Instant` | Timestamp | 8 bytes |

---

## Cross-Language Support

dan-websocket is available in two languages with identical APIs and wire-compatible binary protocol:

| Language | Package | Install |
|----------|---------|---------|
| **TypeScript** | [`dan-websocket`](https://www.npmjs.com/package/dan-websocket) | `npm install dan-websocket` |
| **Java** | [`io.github.justdancecloud:dan-websocket`](https://central.sonatype.com/artifact/io.github.justdancecloud/dan-websocket) | Gradle / Maven |

A TypeScript server can serve Java clients and vice versa.

---

## Protocol

See [dan-protocol-3.0.md](./dan-protocol-3.0.md) for the full binary protocol specification.

---

## License

MIT
