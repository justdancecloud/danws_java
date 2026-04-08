# Contributing to dan-websocket (Java)

## Development Setup

```bash
git clone https://github.com/justdancecloud/danws_java.git
cd danws_java
```

Requires **Java 17+** and **Gradle 8+** (wrapper included).

### Run Tests

```bash
# Protocol conformance tests (excludes stress tests, runs in parallel)
./gradlew test --no-daemon --rerun-tasks

# Specific test class
./gradlew test --tests "com.danws.api.ModeComprehensiveTest"
```

Tests run with `maxParallelForks = Runtime.runtime.availableProcessors()` for parallel execution. Stress tests (`StressTest`, `TenKTest`) are excluded from the default test suite via `build.gradle`.

### Build

```bash
./gradlew build
```

## Project Structure

```
src/main/java/com/danws/
  api/            Server, Client, Session, PrincipalTX, TopicHandle,
                  TopicPayload, FlatStateHelper, ArrayDiffUtil
  protocol/       Frame, Codec, Serializer, StreamParser, DataType,
                  FrameType, DanWSException
  connection/     BulkQueue, HeartbeatManager, ReconnectEngine
  state/          KeyRegistry
src/test/java/com/danws/
  api/            E2E integration tests (mode tests, topic tests, array tests)
  protocol/       Unit tests for codec, serializer, parser, VarNumber encoding
  connection/     Unit tests for bulk-queue
docs/             Architecture diagrams and migration guides
```

### Key Classes

| Class | Purpose |
|-------|---------|
| `DanWebSocketServer` | Main server entry point. Configures mode, TTL, size limits. |
| `DanWebSocketClient` | Main client entry point. Auto-reconnect, heartbeat. |
| `Codec` | Single-pass frame encoding/decoding with DLE byte-stuffing. |
| `Serializer` | Direct byte manipulation for all 16 data types including VarNumber. |
| `StreamParser` | Streaming frame parser with bounded buffer. |
| `PrincipalTX` | Per-principal state with auto-eviction TTL. |
| `TopicPayload` | Per-topic scoped key-value store with reverse keyId index. |
| `ArrayDiffUtil` | Smart array diff with MAX_SHIFT=50 and quickHash pre-filter. |
| `FlatStateHelper` | Shared flatten/set logic extracted from PrincipalTX, Session, TopicPayload. |

## Writing Tests

- Use JUnit 5 (`@Test`, `@AfterEach`)
- Use ports in range `19000-19999` for test servers (check existing tests to avoid conflicts)
- Always close servers in `@AfterEach` to free ports
- Use `Thread.sleep()` for timing (Netty EventLoop is async)
- E2E tests go in `src/test/java/com/danws/api/`
- Protocol unit tests go in `src/test/java/com/danws/protocol/`

## Code Style

- Java 17+ features (records, pattern matching, sealed classes where appropriate)
- **English comments only** -- no comments in other languages
- No generics on public API types
- Package-private visibility for internal methods (no public `_` prefix)
- Netty EventLoop for all async operations -- avoid creating extra threads
- Direct byte manipulation preferred over ByteBuffer allocation
- Use `PooledByteBufAllocator` for Netty buffer operations

## Cross-Language Compatibility

dan-websocket has a TypeScript implementation (`dan-websocket` on npm) that must remain wire-compatible. When modifying the protocol:

1. Update `dan-protocol.md` specification first
2. Implement in Java
3. Ensure the TypeScript implementation is updated to match
4. Run E2E tests on both to verify wire compatibility

All 16 data types, all frame types, DLE byte-stuffing, and VarNumber encoding must produce identical wire bytes across both implementations.

## Pull Request Process

1. Fork the repository
2. Create a feature branch from `main`
3. Write tests for new functionality
4. Ensure all tests pass: `./gradlew test --no-daemon --rerun-tasks`
5. Ensure build succeeds: `./gradlew build`
6. Submit a pull request with a clear description of what changed and why

## Versioning

- Java and TypeScript implementations share the same version number
- Protocol version is included in the IDENTIFY frame (currently v3.5)
- Breaking protocol changes require a major version bump

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
