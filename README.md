# dan-websocket

> Lightweight binary protocol for real-time state synchronization -- **Server to Client**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.justdancecloud/dan-websocket?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.justdancecloud/dan-websocket)
[![GitHub Packages](https://img.shields.io/github/v/release/justdancecloud/danws_java?label=GitHub%20Packages&color=blue)](https://github.com/justdancecloud/danws_java/packages)
[![npm](https://img.shields.io/npm/v/dan-websocket?label=npm%20(TypeScript))](https://www.npmjs.com/package/dan-websocket)
[![CI](https://github.com/justdancecloud/danws_java/actions/workflows/ci.yml/badge.svg)](https://github.com/justdancecloud/danws_java/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange)](https://adoptium.net/)

Java implementation of [DanProtocol v3.5](./dan-protocol.md). Wire-compatible with the [TypeScript version](https://github.com/justdancecloud/danws_typescript).

---

## Why dan-websocket?

Building real-time features with raw WebSocket or libraries like Socket.IO means writing serialization, diffing, reconnection, and batching from scratch. dan-websocket does all of that for you with a single method call.

You call `set(key, value)` on the server. Every connected client receives the update instantly as a compact binary frame. No JSON parsing, no manual diffing, no serialization boilerplate.

| | JSON WebSocket | Socket.IO | **dan-websocket** |
|---|---|---|---|
| A boolean update | `{"key":"alive","value":true}` = 30+ bytes | Similar + event overhead | **9 bytes** |
| Number encoding | Always string or 8 bytes | Same | **VarNumber: 1-5 bytes** (50-75% smaller) |
| Type safety | Parse then cast | Parse then cast | **Auto-typed on the wire** |
| Reconnection | Build it yourself | Built-in (JSON re-sync) | **Built-in with binary state recovery** |
| Multi-device sync | DIY per-connection | DIY per-connection | **Principal-based (1 state -> N sessions)** |
| Array of 100 elements, shift+push | 100 value updates | 100 value updates | **2 frames (shift + new value)** |
| Nested object change | Re-serialize entire object | Re-emit entire object | **Only changed leaf values sent** |
| Batching | None | None | **100ms auto-batch with dedup** |

**Core idea: objects in, objects out, binary in between.** Only changed fields are sent -- up to 99% less traffic than re-sending full JSON.

---

## Quick Start

**Server** -- 5 lines to push real-time data:

```java
import com.danws.api.DanWebSocketServer;
import static com.danws.api.DanWebSocketServer.Mode;

var server = new DanWebSocketServer(8080, Mode.BROADCAST);

server.set("price", Map.of("btc", 67000, "eth", 3200));
// Internally flattened to: price.btc (VarInteger, 3 bytes) and price.eth (VarInteger, 2 bytes)
// Every connected client receives these two binary frames -- not a JSON blob.
```

**Client** -- 5 lines to receive real-time data:

```java
import com.danws.api.DanWebSocketClient;

var client = new DanWebSocketClient("ws://localhost:8080");

client.onUpdate(() -> {
    System.out.println(client.get("price.btc"));  // 67000
    System.out.println(client.get("price.eth"));   // 3200
});

client.connect();
```

Now update just one field:

```java
server.set("price", Map.of("btc", 67100, "eth", 3200));
// Only price.btc changed -> only 1 frame goes over the wire.
// price.eth is identical -> not sent. Zero waste.
```

**What just happened?**
- Server: you wrote a plain Java Map.
- Wire: only the changed leaf field (`btc`) traveled as a binary-encoded VarInteger.
- Client: you read it back with `client.get("price.btc")`.
- No JSON serialization. No manual diffing. No field-by-field subscriptions.

---

## Installation

Requires **Java 17+**.

### Gradle

```groovy
dependencies {
    implementation 'io.github.justdancecloud:dan-websocket:2.4.0'
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.justdancecloud</groupId>
    <artifactId>dan-websocket</artifactId>
    <version>2.4.0</version>
</dependency>
```

---

## Modes

dan-websocket supports 4 modes that cover every common real-time use case:

| Mode | Auth | Data scope | Use case |
|------|:---:|-----------|----------|
| `BROADCAST` | No | All clients see the same state | Dashboards, tickers, live feeds |
| `PRINCIPAL` | Yes | Per-user state, shared across all user's devices | Games, portfolios, user profiles |
| `SESSION_TOPIC` | No | Each client subscribes to topics, gets its own data per topic | Public charts, anonymous boards |
| `SESSION_PRINCIPAL_TOPIC` | Yes | Topics + user identity (session knows who you are) | Authenticated dashboards, personalized feeds |

---

### 1. Broadcast

The simplest mode. The server holds one global state. Every connected client gets the same data. No auth required.

**Use cases:** Live dashboards, crypto tickers, server monitoring, sports scores -- anything where all users see the same thing.

**Server:**

```java
import com.danws.api.DanWebSocketServer;
import static com.danws.api.DanWebSocketServer.Mode;

var server = new DanWebSocketServer(8080, Mode.BROADCAST);

// Set any object -- it auto-flattens to binary leaf keys
server.set("market", Map.of(
    "btc", Map.of("price", 67000.0, "volume", 1200.0),
    "eth", Map.of("price", 3200.0, "volume", 800.0)
));

// Update periodically -- only changed fields go over the wire
var timer = new Timer();
timer.scheduleAtFixedRate(new TimerTask() {
    public void run() {
        server.set("market", Map.of(
            "btc", Map.of("price", 67000 + Math.random() * 100, "volume", 1200.0),
            "eth", Map.of("price", 3200 + Math.random() * 10, "volume", 800.0)
        ));
        // If only btc.price changed -> 1 frame. eth stays the same -> 0 bytes.
    }
}, 0, 1000);
```

**Client:**

```java
import com.danws.api.DanWebSocketClient;

var client = new DanWebSocketClient("ws://localhost:8080");

// onUpdate fires once per server flush batch (~100ms), not per field.
// Safe for rendering -- no render storms.
client.onUpdate(() -> {
    System.out.println("BTC: " + client.get("market.btc.price"));
    System.out.println("ETH volume: " + client.get("market.eth.volume"));
});

// You can also listen per-field:
client.onReceive((key, value) -> {
    // key = "market.btc.price", value = 67042.3
    // Called for every individual field change
});

client.connect();
```

**What happens on the wire:**
1. First connect -> server sends key registrations + all current values (full sync)
2. After that -> only changed leaf fields as binary frames
3. Client disconnects and reconnects -> full sync again (automatic)

---

### 2. Principal

Per-user state. Each user is identified by a "principal" name (e.g., username). If one user has multiple devices (PC + mobile), all devices share the same state and stay in sync automatically.

**Use cases:** Online games (per-player state), user dashboards, portfolio trackers -- anything where each user has their own data.

**Auth flow:**
1. Client connects -> server fires `onAuthorize` with the client's UUID and token
2. Your code validates the token (JWT, session lookup, etc.)
3. You call `server.authorize(uuid, token, principalName)` to bind the client to a principal
4. All clients with the same principal share the same state

**Server:**

```java
var server = new DanWebSocketServer(8080, Mode.PRINCIPAL);

// Step 1: Enable auth -- clients must send a token before receiving data
server.enableAuthorization(true);

// Step 2: Handle auth -- validate token, then authorize or reject
server.onAuthorize((uuid, token) -> {
    try {
        String username = verifyJWT(token);           // your auth logic
        server.authorize(uuid, token, username);      // bind to principal "alice"
    } catch (Exception e) {
        server.reject(uuid, "Invalid token");         // close connection
    }
});

// Step 3: Set data per principal -- all of alice's devices get this
server.principal("alice").set("profile", Map.of(
    "name", "Alice",
    "score", 100,
    "inventory", List.of("sword", "shield", "potion")
));

// Later: alice scores a point
server.principal("alice").set("profile", Map.of(
    "name", "Alice",
    "score", 101,  // only this changed value goes to alice's PC + mobile
    "inventory", List.of("sword", "shield", "potion")  // unchanged -> not sent
));

// Each principal is independent -- bob's data is separate
server.principal("bob").set("profile", Map.of(
    "name", "Bob",
    "score", 50,
    "inventory", List.of("axe")
));
```

**Client:**

```java
var client = new DanWebSocketClient("ws://localhost:8080");

// After connecting, send auth token
client.onConnect(() -> {
    client.authorize("eyJhbGciOiJIUzI1NiJ9...");  // your JWT or session token
});

// onReady fires after auth succeeds + initial data sync completes
client.onReady(() -> {
    System.out.println("Authenticated and synced!");
    System.out.println("Score: " + client.get("profile.score"));
});

// Receive state updates -- you only see YOUR principal's data
client.onUpdate(() -> {
    System.out.println("Score: " + client.get("profile.score"));
    System.out.println("Name: " + client.get("profile.name"));
});

client.onError(err -> {
    if ("AUTH_REJECTED".equals(err.code())) {
        System.out.println("Login failed: " + err.getMessage());
    }
});

client.connect();
```

**Multi-device sync:** If Alice opens the app on her phone while already connected on PC, both devices see the same data. When the server updates Alice's principal, both devices get the update instantly.

**PrincipalTX auto-eviction (new in v2.4.0):** When all sessions for a principal disconnect, the server schedules automatic eviction of that principal's data after a configurable TTL (default: 5 minutes). If the user reconnects before the TTL expires, eviction is cancelled. This prevents memory leaks for long-running servers with many transient users. See the [Configuration](#configuration) section for details.

---

### 3. Session Topic

Topic-based subscriptions without auth. Each client subscribes to "topics" with optional parameters, and the server provides data per-topic per-session. Different clients can subscribe to different topics or the same topic with different params.

**Use cases:** Public data feeds where each client picks what to watch -- stock charts (different symbols), paginated boards, real-time search results.

**Topic lifecycle:**
1. Client calls `client.subscribe("topic.name", params)`
2. Server's `topic().onSubscribe` fires -> you register a callback
3. Callback runs immediately (`SUBSCRIBE`) and on every `setDelayedTask` tick (`DELAYED_TASK`)
4. Client calls `client.setParams(...)` -> callback re-fires with `CHANGED_PARAMS`
5. Client calls `client.unsubscribe(...)` -> server's `topic().onUnsubscribe` fires, timers stop

**Server:**

```java
import com.danws.api.*;

var server = new DanWebSocketServer(8080, Mode.SESSION_TOPIC);

server.topic().onSubscribe((session, topic) -> {

    // Each topic.name gets its own handler
    if ("stock.chart".equals(topic.name())) {
        topic.setCallback((event, t, s) -> {
            // event tells you WHY this callback fired:
            //   SUBSCRIBE       -> client just subscribed (first call)
            //   CHANGED_PARAMS  -> client changed params (e.g., different symbol)
            //   DELAYED_TASK    -> timer tick (periodic refresh)

            if (event == EventType.CHANGED_PARAMS) {
                t.payload().clear();  // params changed -> clear old data
            }

            // t.params() contains the client's subscription parameters
            String symbol = (String) t.params().get("symbol");    // "AAPL"

            List<Map<String, Object>> candles = fetchCandles(symbol);
            t.payload().set("candles", candles);  // array -> auto-flattened, shift-detected
            t.payload().set("meta", Map.of(
                "symbol", symbol,
                "lastUpdate", new Date(),
                "count", candles.size()
            ));
        });

        // Re-run the callback every 5 seconds (polling)
        topic.setDelayedTask(5000);
    }

    if ("board.posts".equals(topic.name())) {
        topic.setCallback((event, t, s) -> {
            if (event == EventType.CHANGED_PARAMS) t.payload().clear();

            int page = ((Number) t.params().getOrDefault("page", 1)).intValue();
            int size = ((Number) t.params().getOrDefault("size", 20)).intValue();
            var data = db.getPosts(page, size);

            t.payload().set("result", Map.of(
                "items", data.items(),         // array of maps -> each field auto-flattened
                "totalCount", data.total(),
                "page", page
            ));
        });

        topic.setDelayedTask(3000);  // refresh every 3s
    }
});

// Optional: clean up when client unsubscribes
server.topic().onUnsubscribe((session, topic) -> {
    System.out.println(session.id() + " unsubscribed from " + topic.name());
    // Timers are automatically stopped -- no manual cleanup needed
});
```

**Client:**

```java
var client = new DanWebSocketClient("ws://localhost:8080");

client.onReady(() -> {
    // Subscribe to topics with parameters
    client.subscribe("stock.chart", Map.of("symbol", "AAPL", "interval", "1m"));
    client.subscribe("board.posts", Map.of("page", 1.0, "size", 20.0));
});

// Each topic has its own onUpdate -- fires once per batch
client.topic("stock.chart").onUpdate(payload -> {
    System.out.println("Symbol: " + payload.get("meta.symbol"));
    System.out.println("Candles: " + payload.get("candles.length"));
});

client.topic("board.posts").onUpdate(payload -> {
    System.out.println("Total: " + payload.get("result.totalCount"));
    System.out.println("Page: " + payload.get("result.page"));
});

// Per-field callback (optional -- for fine-grained updates)
client.topic("stock.chart").onReceive((key, value) -> {
    // key = "candles.0.close", value = 189.50
    // Called for every individual field change within this topic
});

// Change params -> server callback re-fires with CHANGED_PARAMS
client.setParams("board.posts", Map.of("page", 2.0, "size", 20.0));

// Switch symbol -> server clears old data, fetches new
client.setParams("stock.chart", Map.of("symbol", "GOOG", "interval", "1m"));

// Unsubscribe when done
client.unsubscribe("stock.chart");

client.connect();
```

**Key points:**
- Each topic's data is scoped -- `topic("stock.chart")` keys are isolated from `topic("board.posts")`
- `topic.payload().set()` works exactly like `server.set()` -- auto-flattens, dedup, array shift detection
- `setDelayedTask(ms)` creates a polling loop -- the callback re-runs every N ms
- When client disconnects, all timers are automatically cleaned up

---

### 4. Session Principal Topic

Combines topics with auth. The session knows who the user is (`session.principal()`), so the server can provide personalized data per-topic.

**Use cases:** Authenticated apps where each user sees different data based on their identity -- personal dashboards, per-user notifications, role-based data feeds.

**Server:**

```java
var server = new DanWebSocketServer(8080, Mode.SESSION_PRINCIPAL_TOPIC);

// Auth setup -- same as principal mode
server.enableAuthorization(true);
server.onAuthorize((uuid, token) -> {
    try {
        String username = verifyJWT(token);
        server.authorize(uuid, token, username);
    } catch (Exception e) {
        server.reject(uuid, "Invalid token");
    }
});

server.topic().onSubscribe((session, topic) -> {

    if ("my.dashboard".equals(topic.name())) {
        topic.setCallback((event, t, s) -> {
            // s.principal() is the authenticated user name (e.g., "alice")
            String user = s.principal();

            if (event == EventType.CHANGED_PARAMS) t.payload().clear();

            var dashboard = db.getUserDashboard(user, t.params());
            t.payload().set("widgets", dashboard.widgets());
            t.payload().set("notifications", Map.of(
                "unread", dashboard.unreadCount(),
                "items", dashboard.latestNotifications()
            ));
        });

        topic.setDelayedTask(5000);  // refresh every 5s
    }

    if ("my.orders".equals(topic.name())) {
        topic.setCallback((event, t, s) -> {
            String user = s.principal();
            String status = (String) t.params().getOrDefault("status", "open");
            var orders = db.getOrders(user, status);

            t.payload().set("orders", Map.of(
                "items", orders,
                "count", orders.size()
            ));
        });

        topic.setDelayedTask(2000);
    }
});
```

**Client:**

```java
var client = new DanWebSocketClient("ws://localhost:8080");

// Auth first, then subscribe
client.onConnect(() -> {
    client.authorize(getStoredToken());
});

client.onReady(() -> {
    // Now authenticated -- subscribe to personalized topics
    client.subscribe("my.dashboard", Map.of("view", "compact"));
    client.subscribe("my.orders", Map.of("status", "open"));
});

client.topic("my.dashboard").onUpdate(payload -> {
    System.out.println("Unread: " + payload.get("notifications.unread"));
    System.out.println("Widgets: " + payload.keys());
});

client.topic("my.orders").onUpdate(payload -> {
    System.out.println("Orders: " + payload.get("orders.count"));
});

// Switch to closed orders
client.setParams("my.orders", Map.of("status", "closed"));

client.onError(err -> {
    if ("AUTH_REJECTED".equals(err.code())) {
        // redirect to login
    }
});

client.connect();
```

**Difference from `SESSION_TOPIC`:** The server callback receives `session` which has `session.principal()` -- the authenticated user name. This lets you query per-user data (orders, notifications, dashboards) without the client sending user identity in topic params.

---

## Auto-Flatten

Objects and arrays are automatically expanded into dot-path binary keys. Only changed leaf values are transmitted on the wire.

### How it works

```java
// Objects -> dot-path keys
server.set("user", Map.of("name", "Alice", "age", 30));
// Wire: user.name = "Alice" (String), user.age = 30 (VarInteger, 1 byte)

// Arrays -> indexed keys + .length
server.set("scores", List.of(10.0, 20.0, 30.0));
// Wire: scores.0 = 10.0, scores.1 = 20.0, scores.2 = 30.0, scores.length = 3

// Nested structures
server.set("data", Map.of(
    "items", List.of(
        Map.of("id", 1, "title", "Hello"),
        Map.of("id", 2, "title", "World")
    )
));
// Wire: data.items.length = 2
//       data.items.0.id = 1, data.items.0.title = "Hello"
//       data.items.1.id = 2, data.items.1.title = "World"
```

**Key behaviors:**

- Arrays get an automatic `.length` key
- Nested objects flatten recursively (max depth: 10)
- Circular references are detected and rejected
- When an array shrinks, stale index keys are left in place -- the client uses `.length` as the source of truth
- **Unchanged leaf values are NOT re-transmitted** (field-level dedup via FlatStateHelper)
- Stale keys from previous flattens are automatically cleaned up via ServerKeyDelete

---

## Array Shift Optimization

### The Problem

Imagine a real-time stock chart with 100 data points displayed as a sliding window. Every second, you push a new price and shift the oldest one out:

```java
// Without shift detection: this sends 101 value frames every update!
// scores.0=old[1], scores.1=old[2], ..., scores.99=newPrice, scores.length=100
List<Double> chartData = getLatest100Prices();
server.set("chart", chartData);
```

Even though only 1 value actually changed (the new tail element), every index shifts by one, so every element looks "changed" to the flatten logic.

### The Solution: ARRAY_SHIFT_LEFT / ARRAY_SHIFT_RIGHT

Two protocol frames tell the client to shift its local array in-place:

- **ARRAY_SHIFT_LEFT** -- "Remove k elements from the front, shift everything left"
- **ARRAY_SHIFT_RIGHT** -- "Shift everything right by k positions, making room at the front"

After the shift frame, only the genuinely new elements are sent.

### Auto-Detection: Just call `set()` as before

The optimization is fully automatic. The server compares old and new arrays and detects shift patterns:

```java
// The server detects: "old[1:] == new[0:99], and new[99] is new"
// Sends: ARRAY_SHIFT_LEFT(1) + scores.99 = newPrice
// Total: 2 frames instead of 101!
List<Double> prices = getLatest100Prices();
server.set("chart", prices);
```

### Practical Examples

**Sliding window (shift + push):**

```java
List<Double> prices = new ArrayList<>(recentPrices); // [10.1, 10.2, ..., 10.5]
prices.remove(0);          // shift oldest
prices.add(latestPrice);   // push newest
server.set("chart", prices);
// Auto-detected as left shift by 1
// Frames: ARRAY_SHIFT_LEFT(1) + chart.99 = latestPrice = 2 frames
```

**Prepend (right shift):**

```java
List<Message> messages = new ArrayList<>();
messages.addAll(olderMessages);     // prepend 5 older messages
messages.addAll(currentMessages);
server.set("messages", messages);
// Auto-detected as right shift by 5
// Frames: ARRAY_SHIFT_RIGHT(5) + 5 new head elements + length = 7 frames
```

**Simple append:**

```java
List<String> logs = new ArrayList<>(currentLogs);
logs.add("New log entry");
server.set("logs", logs);
// The auto-flatten dedup handles it: only logs.{N} and logs.length are new
```

### Frame Count Comparison

| Scenario | Array Size | Without shift | With shift | Reduction |
|----------|-----------|-------------|------------|-----------|
| Shift+push (sliding window) | 100 | ~100 frames | **2 frames** | **98%** |
| Shift by 10 + push 10 | 100 | ~100 frames | **10 frames** | **90%** |
| Prepend (right shift) | 50 | ~50 frames | **3 frames** | **94%** |
| Append 1 element | 10 | 2 frames | **2 frames** | Same (already optimal) |
| Pop (shrink from end) | 10->7 | full resync | **1 frame** | **99%+** |
| Unchanged (same data) | 100 | ~100 frames | **0 frames** | **100%** |

> These numbers are from actual E2E tests. See `FrameCountTest.java`.

### ArraySync -- Ring Buffer API

For maximum efficiency in sliding-window scenarios, use `ArraySync` which uses a ring buffer internally. Every operation sends a fixed number of frames regardless of array size:

```java
// Server-side: create a ring buffer with capacity 100
ArraySync chart = server.array("chart", 100);   // Broadcast mode
// OR
ArraySync chart = server.principal("alice").array("chart", 100);

// Push values -- 2 frames each
chart.push(10.5);
chart.push(11.2);

// Push + shift (sliding window) -- 2 frames, always
chart.pushShift(12.0);   // removes oldest, adds newest

// Update specific index -- 1 frame
chart.set(0, 99.9);

// Bulk push
chart.pushAll(List.of(1.0, 2.0, 3.0));

// Read back
chart.get(0);        // 99.9
chart.length();      // current count
chart.capacity();    // 100
chart.toList();      // ordered list

// Clear -- 2 frames
chart.clear();
```

### ArrayView -- Client-Side Reading

```java
ArrayView chart = client.array("chart");

chart.length();    // current count
chart.capacity();  // buffer capacity
chart.get(0);      // first element (logical index)
chart.get(99);     // last element
chart.toList();    // reconstructed ordered list
```

---

## VarNumber Encoding

New in v2.3.0, dan-websocket automatically uses compressed number encoding for all numeric types. Small numbers that appear frequently in real-world applications (counts, IDs, flags, coordinates) use significantly fewer bytes on the wire.

### How It Works

**VarInteger** -- Variable-length integer encoding with ZigZag for signed values:

| Value range | Bytes on wire | vs. fixed Int32 (4 bytes) |
|-------------|:---:|:---:|
| -64 to 63 | **1 byte** | 75% smaller |
| -8,192 to 8,191 | **2 bytes** | 50% smaller |
| -1,048,576 to 1,048,575 | **3 bytes** | 25% smaller |
| Larger values | 4-9 bytes | Same or slightly larger |

**VarDouble** -- Mantissa + exponent encoding for doubles:

| Value | Bytes on wire | vs. fixed Float64 (8 bytes) |
|-------|:---:|:---:|
| `3200.0` | **3 bytes** (mantissa=32, exp=-1) | 63% smaller |
| `0.5` | **2 bytes** | 75% smaller |
| `100.0` | **2 bytes** | 75% smaller |
| Complex decimals | 5-9 bytes | Same or slightly larger |

**VarFloat** -- Same approach for 32-bit floats with proportional savings.

### Automatic -- No Code Changes

VarNumber encoding is applied automatically for all numeric types. Integer, Long, Short, Byte, BigInteger all map to VarInteger. Double, BigDecimal map to VarDouble. Float maps to VarFloat. You just call `set()` as before:

```java
server.set("player", Map.of(
    "score", 42,          // VarInteger: 1 byte (was 4 bytes with Int32)
    "level", 7,           // VarInteger: 1 byte
    "health", 100.0,      // VarDouble:  2 bytes (was 8 bytes with Float64)
    "x", 1234.5,          // VarDouble:  3 bytes (was 8 bytes)
    "name", "Alice"       // String: unchanged
));
// Total numeric payload: ~7 bytes instead of ~24 bytes = 71% reduction
```

### Wire Type Mapping

| Java Type | Wire Type | Encoding |
|-----------|-----------|----------|
| `Integer`, `Long`, `Short`, `Byte` | `VAR_INTEGER` (0x0D) | ZigZag + variable-length |
| `Double`, `BigDecimal` | `VAR_DOUBLE` (0x0E) | Mantissa + exponent, variable-length |
| `Float` | `VAR_FLOAT` (0x0F) | Mantissa + exponent, variable-length |

Previous fixed-width types (Int32, Int64, Float32, Float64) are still supported for decoding -- the protocol is backward-compatible. New servers write VarNumber; old and new clients read both formats.

---

## CQRS Architecture

dan-websocket naturally enables a **CQRS (Command Query Responsibility Segregation)** pattern. Writes (commands) flow through your existing REST/gRPC API, while reads (queries) are delivered as real-time state via WebSocket.

```
                        +-----------+
   REST/gRPC  --------> | Your API  |  (Commands: create, update, delete)
                        +-----+-----+
                              |
                              | server.set() / principal.set()
                              v
                      +---------------+
                      | dan-websocket |  (Read-side: push state to clients)
                      +-------+-------+
                              |
                     Binary WebSocket frames
                              |
              +---------------+---------------+
              v               v               v
           Client A        Client B        Client C
```

The server calls `set()` whenever state changes -- whether triggered by an API request, a database event, or a background job -- and every connected client receives the update instantly.

**Example: REST API + real-time updates:**

```java
// Your REST controller
@PostMapping("/api/orders/{id}/status")
public void updateOrderStatus(@PathVariable String id, @RequestBody String newStatus) {
    orderService.updateStatus(id, newStatus);  // write to database

    // Push the update to the user watching this order
    String userId = orderService.getOrderOwner(id);
    danServer.principal(userId).set("orders", orderService.getOrders(userId));
    // All of userId's connected devices see the change instantly
}
```

Your clients never poll for data. They submit actions through your API, and the results appear automatically through the WebSocket channel.

---

## Topic API

The topic API uses a single callback pattern with `EventType`:

```java
server.topic().onSubscribe((session, topic) -> {
    // One callback handles all lifecycle events
    topic.setCallback((event, t, s) -> {
        switch (event) {
            case SUBSCRIBE -> {
                // First call -- load initial data
                t.payload().set("items", db.getItems(t.params()));
            }
            case CHANGED_PARAMS -> {
                // Client changed params (e.g., page change)
                t.payload().clear();
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
| `SUBSCRIBE` | `setCallback()` called | Immediate -- load initial data |
| `CHANGED_PARAMS` | Client calls `setParams()` | Params changed -- reload with new params |
| `DELAYED_TASK` | Timer fires | Periodic polling -- refresh data |

**TopicPayload -- scoped per-topic key-value store:**

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

## Configuration

### Server Constructor Variants

```java
// Simple -- defaults to path="/", ttl=600s, flushInterval=100ms
new DanWebSocketServer(8080, Mode.BROADCAST);

// Default mode (PRINCIPAL)
new DanWebSocketServer(8080);

// Custom path and TTL
new DanWebSocketServer(8080, "/ws", Mode.PRINCIPAL, 300_000);

// Custom flush interval (ms)
new DanWebSocketServer(8080, "/ws", Mode.BROADCAST, 600_000, 50);

// With size limits
new DanWebSocketServer(8080, "/ws", Mode.BROADCAST, 600_000, 100,
    1_048_576,  // maxMessageSize: 1MB (default)
    65_536      // maxValueSize: 64KB (default)
);

// Full options: port, path, mode, ttl, flushInterval, maxMessageSize, maxValueSize, principalEvictionTtl
new DanWebSocketServer(8080, "/ws", Mode.PRINCIPAL, 600_000, 100,
    1_048_576,  // maxMessageSize: 1MB
    65_536,     // maxValueSize: 64KB
    300_000     // principalEvictionTtl: 5 minutes (default)
);
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `port` | -- | WebSocket listen port |
| `path` | `"/"` | WebSocket endpoint path |
| `mode` | `PRINCIPAL` | One of the 4 modes |
| `ttlMs` | `600000` (10 min) | Session time-to-live after disconnect |
| `flushIntervalMs` | `100` | Bulk queue flush interval in ms |
| `maxMessageSize` | `1048576` (1MB) | Max incoming WebSocket message size in bytes |
| `maxValueSize` | `65536` (64KB) | Max single serialized value size. Throws `VALUE_TOO_LARGE` if exceeded |
| `principalEvictionTtl` | `300000` (5 min) | Time after last session disconnects before principal data is evicted from memory. Set to `0` to disable. |

### Authorization

```java
server.enableAuthorization(true);                // default timeout 5s
server.enableAuthorization(true, 10_000);        // custom timeout 10s
```

### Debug Logging

By default, all callback errors are silently caught. Enable debug logging to see them:

```java
// Simple -- prints to stderr
server.setDebug(true);

// Custom logger
server.setDebug((msg, err) -> {
    logger.warn("[DanWS] " + msg, err);
});

// Client too
client.setDebug(true);
```

Debug propagates automatically: Server -> Session -> TopicHandle, Client -> TopicClientHandle.

---

## Server API Reference

### Broadcast Mode

| Method | Description |
|--------|-------------|
| `server.set(key, value)` | Set value, auto-detect type, sync to all clients. Accepts Map, List, or any leaf type. |
| `server.get(key)` | Read current value (`null` if not set) |
| `server.keys()` | All registered key paths |
| `server.clear(key)` | Remove one key (sends incremental ServerKeyDelete) |
| `server.clear()` | Remove all keys |
| `server.array(key, capacity)` | Create ring-buffer `ArraySync` |
| `server.close()` | Shut down the server and release Netty resources |

### Principal Mode

| Method | Description |
|--------|-------------|
| `server.principal(name)` | Get `PrincipalTX` for named principal |
| `principal.set(key, value)` | Set value for this principal (syncs to all of this user's sessions) |
| `principal.get(key)` | Read current value |
| `principal.keys()` | List all keys |
| `principal.clear(key)` | Remove one key (incremental delete) |
| `principal.clear()` | Remove all keys |
| `principal.array(key, capacity)` | Create ring-buffer `ArraySync` |

### Topic Modes

| Method | Description |
|--------|-------------|
| `server.topic().onSubscribe(cb)` | `BiConsumer<DanWebSocketSession, TopicHandle>` -- called when a client subscribes |
| `server.topic().onUnsubscribe(cb)` | `BiConsumer<DanWebSocketSession, TopicHandle>` -- called when a client unsubscribes |

### TopicHandle (received in callbacks)

| Method | Description |
|--------|-------------|
| `topic.name()` | Topic name string |
| `topic.params()` | Client-provided params `Map<String, Object>` |
| `topic.payload()` | `TopicPayload` -- scoped data store for this topic |
| `topic.setCallback(fn)` | Set callback for all events (fires SUBSCRIBE immediately) |
| `topic.setDelayedTask(ms)` | Start periodic polling timer |
| `topic.clearDelayedTask()` | Stop timer |

### TopicPayload

| Method | Description |
|--------|-------------|
| `payload.set(key, value)` | Set scoped value (auto-syncs to subscribed client) |
| `payload.get(key)` | Read value |
| `payload.keys()` | All keys in this payload |
| `payload.clear(key)` | Remove one key |
| `payload.clear()` | Remove all keys |

### Session

| Method | Description |
|--------|-------------|
| `session.set(key, value)` | Push data to this session only |
| `session.get(key)` | Read current value |
| `session.keys()` | List keys |
| `session.clearKey(key)` | Remove one key |
| `session.clearKey()` | Remove all keys |
| `session.principal()` | Principal name (`SESSION_PRINCIPAL_TOPIC` mode only) |
| `session.id()` | Session UUID |

### ArraySync (Server-side ring buffer)

| Method | Description |
|--------|-------------|
| `array.push(value)` | Append value (2 frames). Auto-shifts when at capacity. |
| `array.pushShift(value)` | Push new + remove oldest (2 frames, always) |
| `array.shift()` | Remove oldest element (2 frames) |
| `array.set(index, value)` | Update element at logical index (1 frame) |
| `array.get(index)` | Read element at logical index |
| `array.length()` | Current element count |
| `array.capacity()` | Buffer capacity |
| `array.toList()` | Reconstructed ordered list |
| `array.pushAll(list)` | Push multiple values |
| `array.clear()` | Clear all elements (2 frames) |

---

## Client API Reference

### Connection and Data

| Method | Description |
|--------|-------------|
| `client.connect()` | Connect to server |
| `client.disconnect()` | Disconnect |
| `client.authorize(token)` | Send auth token (required for PRINCIPAL and SESSION_PRINCIPAL_TOPIC modes) |
| `client.get(key)` | Current value (`null` if not received) |
| `client.keys()` | All received key paths |
| `client.id()` | This client's UUIDv7 |
| `client.state()` | Connection state enum |
| `client.array(key)` | Get `ArrayView` for ring-buffer array |

### Topic Methods

| Method | Description |
|--------|-------------|
| `client.subscribe(name, params)` | Subscribe with params |
| `client.subscribe(name)` | Subscribe without params |
| `client.unsubscribe(name)` | Unsubscribe |
| `client.setParams(name, params)` | Update params (triggers CHANGED_PARAMS on server) |
| `client.topics()` | List subscribed topic names |
| `client.topic(name)` | Get `TopicClientHandle` for scoped access |

### TopicClientHandle

| Method | Description |
|--------|-------------|
| `handle.get(key)` | Get scoped value within this topic |
| `handle.keys()` | List keys in this topic |
| `handle.onReceive(cb)` | `BiConsumer<String, Object>` -- per-key callback |
| `handle.onUpdate(cb)` | `Consumer<TopicClientPayloadView>` -- full payload callback (fires once per batch) |

### ArrayView (Client-side reader)

| Method | Description |
|--------|-------------|
| `view.get(index)` | Element at logical index |
| `view.length()` | Current count |
| `view.capacity()` | Buffer capacity |
| `view.toList()` | Reconstructed ordered list |

### Client Events

| Event | Callback Type | When |
|-------|--------------|------|
| `client.onConnect(cb)` | `Runnable` | WebSocket connection established |
| `client.onReady(cb)` | `Runnable` | Auth succeeded + initial state synced |
| `client.onReceive(cb)` | `BiConsumer<String, Object>` | Per frame -- fine-grained key-level updates |
| `client.onUpdate(cb)` | `Runnable` | Per flush batch (~100ms) -- use for rendering |
| `client.onDisconnect(cb)` | `Runnable` | Connection lost |
| `client.onError(cb)` | `Consumer<DanWSException>` | Error occurred |

### Shutdown

```java
// When your application exits, clean up shared resources:
DanWebSocketClient.shutdownSharedGroup();
```

---

## Auto-Detected Types

| Java Type | Wire Type | Typical Size |
|-----------|-----------|------|
| `null` | Null | 0 bytes |
| `Boolean` | Bool | 1 byte |
| `Integer` | VarInteger | 1-5 bytes |
| `Long` | VarInteger | 1-9 bytes |
| `Short` | VarInteger | 1-3 bytes |
| `Byte` | VarInteger | 1 byte |
| `Double` | VarDouble | 2-9 bytes |
| `Float` | VarFloat | 2-5 bytes |
| `String` | String | variable |
| `byte[]` | Binary | variable |
| `Date` / `Instant` | Timestamp | 8 bytes |
| `BigDecimal` | VarDouble | 2-9 bytes |
| `BigInteger` | VarInteger (or String if > 63 bits) | 1-9 bytes |

---

## ServerKeyDelete and ClientKeyRequest

New in v2.2.1, the protocol supports incremental key lifecycle management:

**ServerKeyDelete (0x22):** When you call `clear(key)`, the server sends a compact `ServerKeyDelete` frame for each affected key instead of performing a full reset + resync. This is much faster for clients with many keys.

**ClientKeyRequest (0x23):** If a client receives a value for an unknown keyId (e.g., due to a dropped frame), it sends a `ClientKeyRequest` to recover just that one key's registration, value, and sync -- instead of requesting a full resync.

**KeyId reuse:** When keys are deleted, their keyIds are recycled for future registrations. This prevents keyId exhaustion on long-running servers that create and delete keys frequently.

```java
// Server: incremental delete instead of full resync
server.set("temp.data", Map.of("a", 1, "b", 2, "c", 3));
server.clear("temp.data");
// Wire: ServerKeyDelete for temp.data.a, temp.data.b, temp.data.c
// Client removes these keys instantly -- no full resync needed
```

---

## Thread Safety

dan-websocket uses `ReentrantReadWriteLock` on the three core stateful classes -- `PrincipalTX`, `DanWebSocketSession`, and `TopicPayload` -- to guarantee safe concurrent access from multiple threads.

### Read-Write Lock Semantics

- **Multiple concurrent readers** -- Any number of threads can call `get()`, `keys()`, etc. simultaneously without blocking each other.
- **Exclusive writer** -- Mutation methods (`set()`, `clear()`) acquire the write lock, blocking all readers and other writers until complete.
- This means reads never block reads, and writes only block for the duration of the state mutation itself.

### Defensive Deep Copy

All `get()` methods return **deep-copied, immutable data**:

- `List` values are returned wrapped in `Collections.unmodifiableList`
- `Map` values are returned wrapped in `Collections.unmodifiableMap`
- `byte[]` values are cloned
- Immutable types (`String`, `Number`, `Boolean`, `null`) are returned as-is

This ensures that the caller cannot accidentally mutate the server's internal state. The `DeepCopy` utility class handles recursive copying of nested structures.

### Deferred Callbacks (Deadlock Prevention)

Callbacks (e.g., BulkQueue enqueue, session notifications) are **never invoked while holding a lock**. Instead, they are collected into a `ThreadLocal<List<Runnable>>` during the write-locked section and executed after the lock is released. This prevents deadlocks that would otherwise occur if a callback tried to acquire another lock.

### Example: Safe Concurrent Access

```java
var server = new DanWebSocketServer(8080, Mode.PRINCIPAL);

// Thread A: update state (acquires write lock)
executor.submit(() -> {
    server.principal("alice").set("score", 100);
});

// Thread B: read state concurrently (acquires read lock -- does not block other readers)
executor.submit(() -> {
    Object score = server.principal("alice").get("score");
    // score is a deep copy -- safe to use without synchronization
});

// Thread C: also reading (runs concurrently with Thread B)
executor.submit(() -> {
    List<String> keys = server.principal("alice").keys();
    // keys is an unmodifiable list -- safe to iterate
});
```

No external synchronization is needed when calling dan-websocket APIs from multiple threads.

---

## Performance

dan-websocket is engineered for high throughput and low latency:

- **Netty EventLoop architecture** -- Zero extra threads. All I/O runs on Netty's event loops. No thread pool overhead, no context switching.
- **Direct byte manipulation** -- Encoding and decoding operate directly on byte arrays with no intermediate object allocation (new in v2.3.x).
- **Netty buffer pooling** -- Uses Netty's pooled buffer allocator for zero-copy frame construction where possible.
- **VarNumber encoding** -- 50-75% smaller payloads for typical numeric values (new in v2.3.x).
- **FlatStateHelper dedup** -- Shared flatten + diff logic across PrincipalTX, Session, and TopicPayload eliminates code duplication and ensures consistent dedup behavior.
- **Principal session index** -- O(1) lookup to find all sessions for a principal.
- **PrincipalTX auto-eviction** -- Configurable TTL cleans up disconnected principal data automatically.
- **Key frame caching** -- Key registration frames are cached and reused across connections.
- **Incremental key operations** -- ServerKeyDelete + ClientKeyRequest for efficient key lifecycle.
- **Value change detection** -- If `set()` is called with the same value, nothing is sent. Field-level dedup at the leaf level.
- **Array shift optimization** -- Sliding-window patterns send 2 frames instead of N.
- **Ring buffer ArraySync** -- Fixed 2-frame cost for push/shift regardless of array size.
- **Bulk queue batching** -- Frames are batched every 100ms (configurable) and deduplicated.
- **DLE-escaped binary framing** -- Minimal overhead, self-synchronizing. No JSON parsing cost.
- **Parallel test execution** -- Test suite utilizes up to 28 cores for fast CI feedback.
- **Tested at scale** -- 60K+ concurrent WebSocket connections on a single server instance.

---

## Error Codes

All errors are instances of `DanWSException` with a `code()` method:

```java
client.onError(err -> {
    System.out.println(err.code());      // "AUTH_REJECTED"
    System.out.println(err.getMessage()); // "Invalid token"
});
```

| Code | Where | Description |
|------|-------|-------------|
| `HEARTBEAT_TIMEOUT` | Client | No heartbeat from server within 15s |
| `AUTH_REJECTED` | Client | Server rejected the auth token |
| `UNKNOWN_KEY_ID` | Client | Received value for unregistered key (triggers ClientKeyRequest) |
| `REMOTE_ERROR` | Client/Session | Error frame from remote peer |
| `INVALID_MODE` | Server/Session | API not available in current mode |
| `VALUE_TOO_LARGE` | Server | Serialized value exceeds `maxValueSize` |
| `INVALID_VALUE_TYPE` | Internal | Value type mismatch for DataType |
| `UNKNOWN_DATA_TYPE` | Internal | Unrecognized DataType byte |
| `FRAME_PARSE_ERROR` | Internal | Malformed binary frame |
| `INVALID_DLE_SEQUENCE` | Internal | Invalid DLE escape in wire data |
| `INVALID_KEY_PATH` | Internal | Key path empty, invalid, or exceeds 200 bytes |
| `FLATTEN_DEPTH_EXCEEDED` | Internal | Auto-flatten depth > 10 |
| `CIRCULAR_REFERENCE` | Internal | Circular reference in object |

---

## Cross-Language Compatibility

dan-websocket is available in two languages with identical wire protocol:

| Language | Package | Install |
|----------|---------|---------|
| **Java** | [`io.github.justdancecloud:dan-websocket`](https://central.sonatype.com/artifact/io.github.justdancecloud/dan-websocket) | Gradle / Maven (see above) |
| **TypeScript** | [`dan-websocket`](https://www.npmjs.com/package/dan-websocket) | `npm install dan-websocket` |

A TypeScript server can serve Java clients and vice versa. The binary protocol (DanProtocol v3.5) is identical on both platforms -- including VarNumber encoding, array shift frames, and ServerKeyDelete/ClientKeyRequest.

```java
// Java server
var server = new DanWebSocketServer(8080, Mode.BROADCAST);
server.set("data", Map.of("count", 42, "label", "hello"));
```

```typescript
// TypeScript client -- receives the same binary frames
const client = new DanWebSocketClient("ws://localhost:8080");
client.onUpdate(() => {
    console.log(client.get("data.count"));  // 42
    console.log(client.get("data.label"));  // "hello"
});
client.connect();
```

---

## Protocol

See [dan-protocol.md](./dan-protocol.md) for the full binary protocol specification (DanProtocol v3.5).

---

## License

MIT
