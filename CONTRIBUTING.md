# Contributing to dan-websocket (Java)

## Development Setup

```bash
git clone https://github.com/justdancecloud/danws_java.git
cd danws_java
```

Requires **Java 17+** and **Gradle 8+** (wrapper included).

### Run Tests

```bash
# Protocol conformance tests (excludes stress tests)
./gradlew test --no-daemon --rerun-tasks

# Specific test class
./gradlew test --tests "com.danws.api.ModeComprehensiveTest"
```

Stress tests (`StressTest`, `TenKTest`) are excluded from the default test suite via `build.gradle`.

### Build

```bash
./gradlew build
```

## Project Structure

```
src/main/java/com/danws/
  api/          Server, Client, Session, PrincipalTX, TopicHandle, TopicPayload
  protocol/     Frame, Codec, Serializer, StreamParser, DataType, FrameType
  connection/   BulkQueue, HeartbeatManager, ReconnectEngine
  state/        KeyRegistry
src/test/java/com/danws/
  api/          E2E integration tests
  protocol/     Unit tests for codec, serializer, parser
  connection/   Unit tests for bulk-queue
docs/           Architecture and guides
```

## Writing Tests

- Use JUnit 5 (`@Test`, `@AfterEach`)
- Use ports in range `19000-19999` for test servers (check existing tests to avoid conflicts)
- Always close servers in `@AfterEach` to free ports
- Use `Thread.sleep()` for timing (Netty EventLoop is async)
- E2E tests go in `src/test/java/com/danws/api/`

## Pull Request Process

1. Fork the repository
2. Create a feature branch from `main`
3. Write tests for new functionality
4. Ensure all tests pass: `./gradlew test --no-daemon --rerun-tasks`
5. Ensure build succeeds: `./gradlew build`
6. Submit a pull request with a clear description

## Code Style

- Java 17+ features (records, pattern matching, sealed classes where appropriate)
- No generics on public API types
- Package-private visibility for internal methods (no public `_` prefix)
- Netty EventLoop for all async operations — avoid creating extra threads

## Cross-Language Compatibility

dan-websocket has a TypeScript implementation that must remain wire-compatible. When modifying the protocol:

1. Update `dan-protocol.md` specification first
2. Implement in Java
3. Ensure the TypeScript implementation is updated to match
4. Run E2E tests on both to verify wire compatibility

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
