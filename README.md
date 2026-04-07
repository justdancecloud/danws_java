# dan-websocket

> Lightweight binary protocol for real-time state synchronization -- **Server to Client**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.justdancecloud/dan-websocket)](https://central.sonatype.com/artifact/io.github.justdancecloud/dan-websocket)
[![npm](https://img.shields.io/npm/v/dan-websocket)](https://www.npmjs.com/package/dan-websocket)

Java implementation of [DanProtocol v3.3](./dan-protocol.md). Wire-compatible with the [TypeScript version](https://github.com/justdancecloud/danws_typescript).

---

## What is this?

**dan-websocket** pushes state from your server to connected clients in real time using a compact binary protocol. You call `set(key, value)` on the server and every connected client receives the update instantly -- no serialization boilerplate, no JSON parsing, no manual diffing.

The library handles:

- **Auto type detection** -- Booleans, integers, doubles, strings, byte arrays, timestamps, BigDecimal, BigInteger, Short, and Byte are all detected and serialized automatically.
- **Auto-flatten** -- Pass a Map or List and it expands into dot-path binary keys. Only changed leaf values are transmitted.
- **Array shift optimization (NEW in v2.0)** -- Sliding-window arrays (chart data, logs) transmit 2 frames instead of N.
- **Reconnection and heartbeat** -- Built in. Clients recover state transparently.
- **Principal-based multi-device sync** -- One user, many devices, one state.
- **Topic-scoped data** -- Subscribe/unsubscribe to data channels with parameters and periodic polling.

---

## Why not just JSON over WebSocket?

| | JSON WebSocket | dan-websocket |
|---|---|---|
| A boolean update | `{"key":"alive","value":true}` = 30+ bytes | 9 bytes |
| Type safety | Parse then cast | Auto-typed on the wire |
| Reconnection | Build it yourself | Built-in with heartbeat |
| Multi-device sync | DIY per-connection | Principal-based (1 state -> N sessions) |
| Array of 100 elements, shift+push | 100 value updates | 2 frames (shift + new value) |
| Nested object change | Re-serialize entire object | Only changed leaf values sent |

---

## Install

### Gradle

```groovy
dependencies {
    implementation 'io.github.justdancecloud:dan-websocket:2.1.7'
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.justdancecloud</groupId>
    <artifactId>dan-websocket</artifactId>
    <version>2.1.7</version>
</dependency>
```

Requires **Java 17+**.

### Quick Start

```java
// Server — just set objects. That's it.
var server = new DanWebSocketServer(8080, DanWebSocketServer.Mode.BROADCAST);

server.set("price", Map.of("btc", 67000, "eth", 3200));
// Internally: "price" is split into price.btc (Float64, 8 bytes) and price.eth (Float64, 8 bytes).
// Each client receives only these two binary frames — not a JSON blob.
```

```java
// Client — just read values. No parsing, no schema, no boilerplate.
var client = new DanWebSocketClient("ws://localhost:8080");

client.onUpdate(() -> {
    System.out.println(client.get("price.btc"));  // 67000
    System.out.println(client.get("price.eth"));   // 3200
    // This callback fires once per server flush (~100ms batch),
    // not once per field. Safe for rendering — no render storms.
});

client.connect();
```

Now update just one field:

```java
server.set("price", Map.of("btc", 67100, "eth", 3200));
// Only price.btc changed → only 1 frame (8 bytes) goes over the wire.
// price.eth is identical → not sent. Zero waste.
```

**What just happened?**
- Server: you wrote a plain Java Map.
- Wire: only the changed leaf field (`btc`) traveled as a binary-encoded 8-byte Float64.
- Client: you read it back with `client.get("price.btc")`.
- No JSON serialization. No manual diffing. No field-by-field subscriptions.

This is the core idea: **objects in, objects out, binary in between**. Only changed fields are sent — up to **99% less traffic** than re-sending full JSON. Drop in the library, cut your network costs.

---

## Modes

| Mode | Auth | Data scope | Use case |
|------|:---:|-----------|----------|
| `BROADCAST` | No | All clients see the same state | Dashboards, tickers, live feeds |
| `PRINCIPAL` | Yes | Per-user state, shared across all user's devices | Games, portfolios, user profiles |
| `SESSION_TOPIC` | No | Each client subscribes to topics, gets its own data per topic | Public charts, anonymous boards |
| `SESSION_PRINCIPAL_TOPIC` | Yes | Topics + user identity (session knows who you are) | Authenticated dashboards, personalized feeds |

---

### 1. Broadcast

The simplest mode. The server holds one global state. Every connected client gets the same data. No auth required.

**When to use:** Live dashboards, crypto tickers, server monitoring -- anything where all users see the same thing.

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
        // If only btc.price changed → 1 frame (8 bytes). eth stays the same → 0 bytes.
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

**What's on the wire:**
1. First connect → server sends key registrations + all current values (full sync)
2. After that → only changed leaf fields as binary frames
3. Client disconnects and reconnects → full sync again (automatic)

---

### 2. Principal

Per-user state. Each user is identified by a "principal" name (e.g., username). If one user has multiple devices (PC + mobile), all devices share the same state and stay in sync automatically.

**When to use:** Online games (per-player state), user dashboards, portfolio trackers -- anything where each user has their own data.

**Auth flow:**
1. Client connects → server fires `onAuthorize` with the client's UUID and token
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
    "score", 101,  // only this 8-byte Float64 goes to alice's PC + mobile
    "inventory", List.of("sword", "shield", "potion")  // unchanged → not sent
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

---

### 3. Session Topic

Topic-based subscriptions without auth. Each client subscribes to "topics" with optional parameters, and the server provides data per-topic per-session. Different clients can subscribe to different topics or the same topic with different params.

**When to use:** Public data feeds where each client picks what to watch -- stock charts (different symbols), paginated boards, real-time search results.

**Topic lifecycle:**
1. Client calls `client.subscribe("topic.name", params)`
2. Server's `topic().onSubscribe` fires → you register a callback
3. Callback runs immediately (`SUBSCRIBE`) and on every `setDelayedTask` tick (`DELAYED_TASK`)
4. Client calls `client.setParams(...)` → callback re-fires with `CHANGED_PARAMS`
5. Client calls `client.unsubscribe(...)` → server's `topic().onUnsubscribe` fires, timers stop

**Server:**

```java
import com.danws.api.*;

var server = new DanWebSocketServer(8080, Mode.SESSION_TOPIC);

server.topic().onSubscribe((session, topic) -> {

    // Each topic.name gets its own handler
    if ("stock.chart".equals(topic.name())) {
        topic.setCallback((event, t, s) -> {
            // event tells you WHY this callback fired:
            //   SUBSCRIBE       → client just subscribed (first call)
            //   CHANGED_PARAMS  → client changed params (e.g., different symbol)
            //   DELAYED_TASK    → timer tick (periodic refresh)

            if (event == EventType.CHANGED_PARAMS) {
                t.payload().clear();  // params changed → clear old data
            }

            // t.params() contains the client's subscription parameters
            String symbol = (String) t.params().get("symbol");    // "AAPL"

            List<Map<String, Object>> candles = fetchCandles(symbol);
            t.payload().set("candles", candles);  // array → auto-flattened, shift-detected
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
                "items", data.items(),         // array of maps → each field auto-flattened
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

// Change params → server callback re-fires with CHANGED_PARAMS
client.setParams("board.posts", Map.of("page", 2.0, "size", 20.0));

// Switch symbol → server clears old data, fetches new
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

**When to use:** Authenticated apps where each user sees different data based on their identity -- personal dashboards, per-user notifications, role-based data feeds.

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
// Wire: user.name = "Alice", user.age = 30

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
- **Unchanged leaf values are NOT re-transmitted** (field-level dedup)

---

## Array Sync Optimization (NEW in v2.0)

### The Problem

Imagine a real-time stock chart with 100 data points displayed as a sliding window. Every second, you push a new price and shift the oldest one out:

```java
// Before v2.0: this sends 101 value frames every update!
// scores.0=old[1], scores.1=old[2], ..., scores.99=newPrice, scores.length=100
List<Double> chartData = getLatest100Prices();
server.set("chart", chartData);
```

Even though only 1 value actually changed (the new tail element), every index shifts by one, so every element looks "changed" to the flatten logic.

### The Solution: ARRAY_SHIFT_LEFT / ARRAY_SHIFT_RIGHT

v2.0 introduces two new protocol frames that tell the client to shift its local array in-place:

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

**Stock/crypto chart -- sliding window (shift + push):**

```java
// Every second, new price arrives
List<Double> prices = new ArrayList<>(recentPrices); // [10.1, 10.2, ..., 10.5]
prices.remove(0);          // shift oldest
prices.add(latestPrice);   // push newest
server.set("chart", prices);
// Auto-detected as left shift by 1
// Frames: ARRAY_SHIFT_LEFT(1) + chart.99 = latestPrice = 2 frames
```

**Historical data loading -- prepend (right shift):**

```java
// User scrolls up to load older messages
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
// Auto-detected as left shift by 0? No -- this is a simple append
// The auto-flatten dedup handles it: only logs.{N} and logs.length are new
```

### Frame Count Comparison (Verified by automated tests)

| Scenario | Array Size | Before v2.0 | After v2.0 | Reduction |
|----------|-----------|-------------|------------|-----------|
| Shift+push (sliding window) | 100 | ~100 frames | **2 frames** | **98%** |
| Shift by 10 + push 10 | 100 | ~100 frames | **10 frames** | **90%** |
| Prepend (right shift) | 50 | ~50 frames | **3 frames** | **94%** |
| Append 1 element | 10 | 2 frames | **2 frames** | Same (already optimal) |
| Pop (shrink from end) | 10→7 | full resync | **1 frame** | **99%+** |
| Unchanged (same data) | 100 | ~100 frames | **0 frames** | **100%** |
| 10× repeated shift+push | 50 | ~500 total | **20 total** (2 each) | **96%** |

> These numbers are from actual E2E tests: server sets the array, client counts `onReceive` callbacks. See `FrameCountTest.java` for the full benchmark.

### ArraySync -- Ring Buffer API for Explicit Control

For maximum efficiency in sliding-window scenarios, use `ArraySync` which uses a ring buffer internally. Every operation sends a fixed number of frames regardless of array size:

```java
// Server-side: create a ring buffer with capacity 100
ArraySync chart = server.array("chart", 100);   // Broadcast mode
// OR
ArraySync chart = server.principal("alice").array("chart", 100);

// Push values -- 2 frames each
chart.push(10.5);
chart.push(11.2);
chart.push(9.8);

// Push + shift (sliding window) -- 2 frames, always
chart.pushShift(12.0);   // removes oldest, adds newest

// Update specific index -- 1 frame
chart.set(0, 99.9);

// Read back
chart.get(0);        // 99.9
chart.length();      // current count
chart.capacity();    // 100
chart.toList();      // ordered list

// Bulk push
chart.pushAll(List.of(1.0, 2.0, 3.0));

// Clear -- 2 frames
chart.clear();
```

**Wire keys for ring buffer:**

| Key | Purpose |
|-----|---------|
| `{prefix}.__h` | Head index (ring buffer offset) |
| `{prefix}.__l` | Current length |
| `{prefix}.__c` | Capacity |
| `{prefix}.__0`, `.__1`, ... | Ring buffer slots |

### ArrayView -- Client-Side Reading

On the client side, use `ArrayView` to read ring-buffer arrays as ordered lists:

```java
// Client-side
ArrayView chart = client.array("chart");

chart.length();    // current count
chart.capacity();  // buffer capacity
chart.get(0);      // first element (logical index)
chart.get(99);     // last element
chart.toList();    // reconstructed ordered list
```

---

## Topic API -- `setCallback` + `setDelayedTask` Pattern

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

## API Reference

### Server -- Broadcast Mode

| Method | Description |
|--------|-------------|
| `server.set(key, value)` | Set value, auto-detect type, sync to all clients |
| `server.get(key)` | Read current value (`null` if not set) |
| `server.keys()` | All registered key paths |
| `server.clear(key)` | Remove one key |
| `server.clear()` | Remove all keys |
| `server.array(key, capacity)` | Create ring-buffer `ArraySync` |

### Server -- Principal Mode

| Method | Description |
|--------|-------------|
| `server.principal(name)` | Get `PrincipalTX` for named principal |
| `principal.set(key, value)` | Set value for this principal |
| `principal.get(key)` | Read current value |
| `principal.keys()` | List all keys |
| `principal.clear(key)` | Remove one key |
| `principal.clear()` | Remove all keys |
| `principal.array(key, capacity)` | Create ring-buffer `ArraySync` |

### Server -- Topic Modes

| Method | Description |
|--------|-------------|
| `server.topic().onSubscribe(cb)` | `BiConsumer<DanWebSocketSession, TopicHandle>` |
| `server.topic().onUnsubscribe(cb)` | `BiConsumer<DanWebSocketSession, TopicHandle>` |

### TopicHandle (received in callbacks)

| Method | Description |
|--------|-------------|
| `topic.name()` | Topic name string |
| `topic.params()` | Client-provided params `Map<String, Object>` |
| `topic.payload()` | `TopicPayload` -- scoped data store |
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
| `session.set(key, value)` | Push data to this session |
| `session.get(key)` | Read current value |
| `session.keys()` | List keys |
| `session.clearKey(key)` | Remove one key |
| `session.clearKey()` | Remove all keys |
| `session.principal()` | Principal name (`SESSION_PRINCIPAL_TOPIC` only) |

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

### Client

| Method | Description |
|--------|-------------|
| `client.connect()` | Connect to server |
| `client.disconnect()` | Disconnect |
| `client.authorize(token)` | Send auth token |
| `client.get(key)` | Current value (`null` if not received) |
| `client.keys()` | All received key paths |
| `client.id()` | This client's UUIDv7 |
| `client.state()` | Connection state enum |
| `client.array(key)` | Get `ArrayView` for ring-buffer array |

### Client -- Topic Methods

| Method | Description |
|--------|-------------|
| `client.subscribe(name, params)` | Subscribe with params |
| `client.subscribe(name)` | Subscribe without params |
| `client.unsubscribe(name)` | Unsubscribe |
| `client.setParams(name, params)` | Update params (triggers CHANGED_PARAMS) |
| `client.topics()` | List subscribed topic names |
| `client.topic(name)` | Get `TopicClientHandle` for scoped access |

### TopicClientHandle

| Method | Description |
|--------|-------------|
| `handle.get(key)` | Get scoped value |
| `handle.keys()` | List keys in this topic |
| `handle.onReceive(cb)` | `BiConsumer<String, Object>` -- per-key callback |
| `handle.onUpdate(cb)` | `Consumer<TopicClientPayloadView>` -- full payload callback |

### ArrayView (Client-side reader)

| Method | Description |
|--------|-------------|
| `view.get(index)` | Element at logical index |
| `view.length()` | Current count |
| `view.capacity()` | Buffer capacity |
| `view.toList()` | Reconstructed ordered list |

### Client Events

| Event | Callback Type |
|-------|--------------|
| `client.onConnect(cb)` | `Runnable` |
| `client.onReady(cb)` | `Runnable` |
| `client.onReceive(cb)` | `BiConsumer<String, Object>` -- per frame (fine-grained) |
| `client.onUpdate(cb)` | `Runnable` -- per flush batch (use for rendering, fires once per ~100ms batch) |
| `client.onDisconnect(cb)` | `Runnable` |
| `client.onError(cb)` | `Consumer<DanWSException>` |

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

// Full options: port, path, mode, ttl, flushInterval, maxMessageSize, maxValueSize
new DanWebSocketServer(8080, "/ws", Mode.BROADCAST, 600_000, 100,
    1_048_576,  // maxMessageSize: 1MB (default)
    65_536      // maxValueSize: 64KB (default)
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

### Authorization

```java
server.enableAuthorization(true);                // default timeout 5s
server.enableAuthorization(true, 10_000);        // custom timeout 10s
```

---

## Auto-Detected Types

| Java Type | Wire Type | Size |
|-----------|-----------|------|
| `null` | Null | 0 bytes |
| `Boolean` | Bool | 1 byte |
| `Integer` | Int32 | 4 bytes |
| `Long` | Int64 | 8 bytes |
| `Float` | Float32 | 4 bytes |
| `Double` | Float64 | 8 bytes |
| `String` | String | variable |
| `byte[]` | Binary | variable |
| `Date` / `Instant` | Timestamp | 8 bytes |
| `BigDecimal` | Float64 | 8 bytes |
| `BigInteger` | Int64 (or String if > 63 bits) | 8 bytes |
| `Short` | Int32 | 4 bytes |
| `Byte` | Uint8 | 1 byte |

---

## Performance

dan-websocket is engineered for high throughput and low latency:

- **Netty EventLoop architecture** -- Zero extra threads. All I/O runs on Netty's event loops. No thread pool overhead, no context switching.
- **Principal session index** -- O(1) lookup to find all sessions for a principal. Broadcasting to a user's devices is instant regardless of total connection count.
- **Key frame caching** -- Key registration frames are cached and reused across connections. A new client joining a server with 1000 keys does not trigger 1000 frame allocations.
- **Incremental key registration** -- When a new key is added, only that key's registration frame is sent. No full resync needed.
- **Value change detection** -- If `set()` is called with the same value, nothing is sent. Field-level dedup at the leaf level.
- **Array shift optimization (v2.0)** -- Sliding-window patterns send 2 frames instead of N. A 1000-element chart update goes from 1001 frames to 2.
- **Ring buffer ArraySync** -- Fixed 2-frame cost for push/shift regardless of array size.
- **Bulk queue batching** -- Frames are batched every 100ms (configurable) and deduplicated. Multiple `set()` calls for the same key within one batch window send only the latest value.
- **DLE-escaped binary framing** -- Minimal overhead, self-synchronizing. No JSON parsing cost.
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
| `UNKNOWN_KEY_ID` | Client | Received value for unregistered key |
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

## Cross-Language Support

dan-websocket is available in two languages with identical APIs and wire-compatible binary protocol:

| Language | Package | Install |
|----------|---------|---------|
| **TypeScript** | [`dan-websocket`](https://www.npmjs.com/package/dan-websocket) | `npm install dan-websocket` |
| **Java** | [`io.github.justdancecloud:dan-websocket`](https://central.sonatype.com/artifact/io.github.justdancecloud/dan-websocket) | Gradle / Maven |

A TypeScript server can serve Java clients and vice versa. Mix and match freely.

---

## Protocol

See [dan-protocol.md](./dan-protocol.md) for the full binary protocol specification (DanProtocol v3.3).

---

## License

MIT
