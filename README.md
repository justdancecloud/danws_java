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
    implementation 'io.github.justdancecloud:dan-websocket:0.3.2'
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.justdancecloud</groupId>
    <artifactId>dan-websocket</artifactId>
    <version>0.3.2</version>
</dependency>
```

Requires **Java 17+**.

---

## 4 Modes

| Mode | Auth | Data Scope | Topics | Use Case |
|------|------|-----------|--------|----------|
| `BROADCAST` | No | Shared (all clients) | No | Dashboards, live feeds |
| `PRINCIPAL` | Yes | Per-principal (shared across devices) | No | Games, per-user data |
| `SESSION_TOPIC` | No | Per-session + topic scoped | Yes | Public charts, anonymous boards |
| `SESSION_PRINCIPAL_TOPIC` | Yes | Per-session + topic scoped + auth | Yes | Authenticated boards, personalized charts |

---

## Quick Start

### 1. Broadcast Mode — all clients get the same data

**Server:**

```java
import com.danws.api.DanWebSocketServer;
import static com.danws.api.DanWebSocketServer.Mode;

var server = new DanWebSocketServer(8080, Mode.BROADCAST);

server.set("sensor.temp", 23.5);          // Double → Float64
server.set("sensor.status", "online");    // String → String
server.set("sensor.active", true);        // Boolean → Bool
server.set("sensor.updated", new Date()); // Date → Timestamp

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

client.onReady(() -> {
    System.out.println("Temperature: " + client.get("sensor.temp"));
    System.out.println("All keys: " + client.keys());
});

client.onReceive((key, value) -> {
    System.out.println(key + " = " + value);
});

client.connect();
```

### 2. Principal Mode — per-user data via principals

**Server:**

```java
var server = new DanWebSocketServer(8080, Mode.PRINCIPAL);

server.enableAuthorization(true);
server.onAuthorize((uuid, token) -> {
    String user = verifyJWT(token);
    server.authorize(uuid, token, user);
});

server.principal("alice").set("score", 100.0);
server.principal("alice").set("name", "Alice");

server.principal("bob").set("score", 50.0);

// Alice opens on PC and mobile → both sessions see score=100
server.principal("alice").set("score", 200.0);
```

**Client:**

```java
var client = new DanWebSocketClient("ws://localhost:8080");

client.onConnect(() -> client.authorize("alice-jwt-token"));

client.onReady(() -> {
    System.out.println("Score: " + client.get("score"));
});

client.onReceive((key, value) -> {
    System.out.println(key + " = " + value);
});

client.connect();
```

### 3. Session Topic Mode — per-session scoped data with topics

**Server:**

```java
var server = new DanWebSocketServer(8080, Mode.SESSION_TOPIC);

server.topic().onSubscribe((session, topic) -> {
    if ("board.posts".equals(topic.name())) {
        topic.setCallback((event, t, s) -> {
            var data = db.getPosts(t.params());
            t.payload().set("items", data.items());
            t.payload().set("totalCount", data.total());
        });
    }

    if ("chart.cpu".equals(topic.name())) {
        topic.setCallback((event, t, s) -> {
            var stats = getCpuStats();
            t.payload().set("value", stats.usage());
            t.payload().set("timestamp", new Date());
        });
        topic.setDelayedTask(1000); // poll every 1s
    }
});

server.topic().onUnsubscribe((session, topic) -> {
    System.out.println("Unsubscribed: " + topic.name());
});
```

**Client:**

```java
var client = new DanWebSocketClient("ws://localhost:8080");

client.onReady(() -> {
    client.subscribe("board.posts", Map.of("page", 1.0, "size", 20.0));
    client.subscribe("chart.cpu");
});

// Per-topic scoped data
client.topic("board.posts").onReceive((key, value) -> {
    if ("items".equals(key)) renderTable(value);
});

client.topic("board.posts").onUpdate(payload -> {
    System.out.println("Posts keys: " + payload.keys());
    System.out.println("Total: " + payload.get("totalCount"));
});

client.topic("chart.cpu").onReceive((key, value) -> {
    if ("value".equals(key)) updateChart(value);
});

// Change page → triggers CHANGED_PARAMS event
client.setParams("board.posts", Map.of("page", 2.0, "size", 20.0));

// Stop watching CPU → timer auto-cleanup
client.unsubscribe("chart.cpu");

client.connect();
```

### 4. Session Principal Topic Mode — topics with authentication

```java
var server = new DanWebSocketServer(8080, Mode.SESSION_PRINCIPAL_TOPIC);

server.enableAuthorization(true);
server.onAuthorize((uuid, token) -> {
    String user = verifyJWT(token);
    server.authorize(uuid, token, user);
});

server.topic().onSubscribe((session, topic) -> {
    if ("my.dashboard".equals(topic.name())) {
        topic.setCallback((event, t, s) -> {
            // s.principal() = authenticated user
            var data = db.getUserDashboard(s.principal(), t.params());
            t.payload().set("widgets", data.widgets());
            t.payload().set("notifications", data.count());
        });
        topic.setDelayedTask(5000); // refresh every 5s
    }
});
```

**Client:**

```java
var client = new DanWebSocketClient("ws://localhost:8080");

client.onConnect(() -> client.authorize("alice-jwt-token"));

client.onReady(() -> {
    client.subscribe("my.dashboard", Map.of("layout", "compact"));
});

client.topic("my.dashboard").onUpdate(payload -> {
    System.out.println("Widgets: " + payload.get("widgets"));
    System.out.println("Notifications: " + payload.get("notifications"));
});

client.connect();
```

---

## Topic API — `setCallback` + `setDelayedTask` Pattern

The topic API uses a single callback pattern with `EventType`:

```java
server.topic().onSubscribe((session, topic) -> {
    // One callback handles all lifecycle events
    topic.setCallback((event, t, s) -> {
        switch (event) {
            case SUBSCRIBE -> {
                // First call — load initial data
                t.payload().set("items", db.getItems(t.params()));
            }
            case CHANGED_PARAMS -> {
                // Client changed params (e.g., page change)
                t.payload().set("items", db.getItems(t.params()));
            }
            case DELAYED_TASK -> {
                // Periodic polling
                t.payload().set("items", db.getItems(t.params()));
            }
        }
    });

    // Optional: enable periodic polling
    topic.setDelayedTask(2000); // every 2 seconds

    // Can stop later:
    // topic.clearDelayedTask();
});
```

**EventType values:**

| Event | When | Description |
|-------|------|-------------|
| `SUBSCRIBE` | `setCallback()` called | Immediate — load initial data |
| `CHANGED_PARAMS` | Client calls `setParams()` | Params changed — reload with new params |
| `DELAYED_TASK` | Timer fires | Periodic polling — refresh data |

**TopicPayload — scoped per-topic key-value store:**

```java
topic.payload().set("items", data);    // scoped to this topic
topic.payload().set("count", 42.0);
topic.payload().get("items");          // read back
topic.payload().keys();                // ["items", "count"]
topic.payload().clear("items");        // remove one key
topic.payload().clear();               // remove all keys
```

Each topic gets its own isolated payload. No key collisions between topics.

---

## API Reference

### Server — Broadcast Mode

| Method | Description |
|--------|-------------|
| `server.set(key, value)` | Set value, auto-detect type, sync to all |
| `server.get(key)` | Read current value (`null` if not set) |
| `server.keys()` | All registered key paths |
| `server.clear(key)` | Remove one key |
| `server.clear()` | Remove all keys |

### Server — Principal Mode

| Method | Description |
|--------|-------------|
| `server.principal(name).set(key, value)` | Set for principal |
| `server.principal(name).get(key)` | Read value |
| `server.principal(name).keys()` | List keys |
| `server.principal(name).clear(key)` | Remove one key |
| `server.principal(name).clear()` | Remove all keys |

### Server — Topic Modes (new API)

| Method | Description |
|--------|-------------|
| `server.topic().onSubscribe(cb)` | `BiConsumer<DanWebSocketSession, TopicHandle>` |
| `server.topic().onUnsubscribe(cb)` | `BiConsumer<DanWebSocketSession, TopicHandle>` |

**TopicHandle (received in callbacks):**

| Method | Description |
|--------|-------------|
| `topic.name()` | Topic name |
| `topic.params()` | Client-provided params `Map<String, Object>` |
| `topic.payload()` | `TopicPayload` — scoped data store |
| `topic.setCallback(fn)` | Set callback for all events (fires SUBSCRIBE immediately) |
| `topic.setDelayedTask(ms)` | Start periodic polling timer |
| `topic.clearDelayedTask()` | Stop timer |

**TopicPayload (topic.payload()):**

| Method | Description |
|--------|-------------|
| `payload.set(key, value)` | Set scoped value (auto-syncs to client) |
| `payload.get(key)` | Read value |
| `payload.keys()` | All keys in this payload |
| `payload.clear(key)` | Remove one key |
| `payload.clear()` | Remove all keys |

**Backward-compatible callbacks (still work):**

| Method | Description |
|--------|-------------|
| `server.onTopicSubscribe(cb)` | `BiConsumer<DanWebSocketSession, TopicInfo>` |
| `server.onTopicUnsubscribe(cb)` | `BiConsumer<DanWebSocketSession, String>` |
| `server.onTopicParamsChange(cb)` | `BiConsumer<DanWebSocketSession, TopicInfo>` |

**Session (flat data, backward-compatible):**

| Method | Description |
|--------|-------------|
| `session.set(key, value)` | Push flat data to this session |
| `session.get(key)` | Read current value |
| `session.keys()` | List keys |
| `session.clearKey(key)` | Remove one key |
| `session.clearKey()` | Remove all keys |
| `session.principal()` | Principal name (SESSION_PRINCIPAL_TOPIC only) |

### Client

| Method | Description |
|--------|-------------|
| `client.connect()` | Connect |
| `client.disconnect()` | Disconnect |
| `client.authorize(token)` | Send auth token |
| `client.get(key)` | Current value (`null` if not received) |
| `client.keys()` | All received key paths |
| `client.id()` | This client's UUIDv7 |
| `client.state()` | Connection state enum |

**Topic methods:**

| Method | Description |
|--------|-------------|
| `client.subscribe(name, params)` | Subscribe with params |
| `client.subscribe(name)` | Subscribe without params |
| `client.unsubscribe(name)` | Unsubscribe |
| `client.setParams(name, params)` | Update params |
| `client.topics()` | List subscribed topics |
| `client.topic(name)` | Get `TopicClientHandle` for scoped access |

**TopicClientHandle (client.topic(name)):**

| Method | Description |
|--------|-------------|
| `handle.get(key)` | Get scoped value |
| `handle.keys()` | List keys in this topic |
| `handle.onReceive(cb)` | `BiConsumer<String, Object>` — per-key callback |
| `handle.onUpdate(cb)` | `Consumer<TopicClientPayloadView>` — full payload view |

| Event | Callback Type |
|-------|--------------|
| `client.onConnect(cb)` | `Runnable` |
| `client.onReady(cb)` | `Runnable` |
| `client.onReceive(cb)` | `BiConsumer<String, Object>` — global (non-topic keys) |
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
