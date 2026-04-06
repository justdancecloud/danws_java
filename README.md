# dan-websocket (Java)

Lightweight binary protocol library for **real-time state synchronization** from server to client.

Java implementation of [DanProtocol v3.0](https://github.com/justdancecloud/danws_typescript). Wire-compatible with the TypeScript version.

## Installation

### Gradle

```groovy
implementation 'io.github.justdancecloud:dan-websocket:0.1.0'
```

### Maven

```xml
<dependency>
    <groupId>io.github.justdancecloud</groupId>
    <artifactId>dan-websocket</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Two Modes

### Broadcast Mode — all clients get the same data

```java
var server = new DanWebSocketServer(8080, DanWebSocketServer.Mode.BROADCAST);

server.set("sensor.temp", 23.5);
server.set("status", "online");

server.get("sensor.temp");    // 23.5
server.keys();                // ["sensor.temp", "status"]
server.clear("status");       // remove one
server.clear();               // remove all
```

### Individual Mode — per-user (principal) data

```java
var server = new DanWebSocketServer(8080, DanWebSocketServer.Mode.INDIVIDUAL);

server.principal("alice").set("score", 100.0);
server.principal("bob").set("score", 200.0);

// Authentication
server.enableAuthorization(true);
server.onAuthorize((uuid, token) -> {
    String user = verifyToken(token);
    server.authorize(uuid, token, user); // 3rd arg = principal
});
```

### Client

```java
var client = new DanWebSocketClient("ws://localhost:8080");

client.onConnect(() -> client.authorize(getToken()));

client.onReady(() -> {
    System.out.println("Score: " + client.get("score"));
});

client.onReceive((key, value) -> {
    System.out.println(key + " = " + value);
});

client.connect();
```

## Auto-detected Data Types

| Java Type | Wire Type |
|-----------|-----------|
| `null` | Null |
| `Boolean` | Bool |
| `Integer` | Int32 |
| `Long` | Int64 |
| `Float` | Float32 |
| `Double` | Float64 |
| `String` | String |
| `byte[]` | Binary |
| `Date` / `Instant` | Timestamp |

## Requirements

- Java 17+
- [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket) (transitive dependency)

## License

MIT
