# DanProtocol v3.5 Specification

> April 2026 | Real-Time State Synchronization with Auto-Flatten, Array Operations & Variable-Length Encoding

---

## 1. What is DanProtocol?

DanProtocol is a **lightweight binary protocol** designed for pushing real-time state from a server to connected clients. It runs over WebSocket, TCP, serial, or any byte-stream transport.

### Key Design Decisions

| Decision | Why |
|----------|-----|
| **Binary wire format** | Minimal bandwidth. A boolean update is ~13 bytes total (vs ~30+ bytes for JSON). |
| **DLE-based framing** | Self-synchronizing frames without length prefixes. Robust on unreliable streams. |
| **Auto-typed** | No schema declaration needed. 16 types detected from values. |
| **4-byte KeyID** | Supports 4B+ unique keys for auto-flatten at scale. |
| **Auto-flatten** | Objects/arrays expand into dot-path leaf keys at API layer. Only changed fields go on wire. |
| **Principal-based** | State is per-authenticated-user, not per-connection. Multiple devices share one state. |
| **VarNumber encoding** | Integers and decimals use variable-length encoding for compact wire representation. |

---

## 2. Wire Format

### 2.1 Control Characters

| Hex | Name | Purpose |
|-----|------|---------|
| `0x10` | DLE | Escape prefix. Never appears as raw data. |
| `0x02` | STX | Start of frame (after DLE). |
| `0x03` | ETX | End of frame (after DLE). |
| `0x05` | ENQ | Heartbeat signal (after DLE). |

### 2.2 Frame Layout

```
+---------+---------+-----------+---------+----------+----------+---------+---------+
| DLE     | STX     | FrameType | KeyID   | DataType | Payload  | DLE     | ETX     |
| 0x10    | 0x02    | 1 byte    | 4 bytes | 1 byte   | N bytes  | 0x10    | 0x03    |
+---------+---------+-----------+---------+----------+----------+---------+---------+
                    |<---------- DLE-escaped body ------------>|
```

- **All multi-byte numbers**: Big Endian (network byte order)
- **KeyID**: 4 bytes unsigned (0x00000000 ~ 0xFFFFFFFF)
- **Minimum frame**: 10 bytes (signal frame: 2 framing + 6 body + 2 framing)
- **DLE escaping**: Any `0x10` in the body becomes `0x10 0x10`

Signal frames MUST set DataType to `0x00` (Null). Receivers SHOULD ignore this field for signal frames.

### 2.3 Heartbeat (not a frame)

```
+---------+---------+
| DLE     | ENQ     |
| 0x10    | 0x05    |
+---------+---------+
```

Sent every 10 seconds by both sides. If not received within 15 seconds, the connection is considered dead.

---

## 3. Frame Types

### 3.1 Server to Client -- Data

| Code | Name | Payload |
|------|------|---------|
| `0x00` | ServerKeyRegistration | UTF-8 keyPath |
| `0x01` | ServerValue | Typed value |

### 3.2 Client to Server -- Data (Topic Mode)

| Code | Name | Payload |
|------|------|---------|
| `0x02` | ClientKeyRegistration | UTF-8 keyPath |
| `0x03` | ClientValue | Typed value |

### 3.3 Handshake

| Code | Name | Direction | Payload |
|------|------|-----------|---------|
| `0x04` | ServerSync | S->C | -- |
| `0x05` | ClientReady | C->S | -- |
| `0x06` | ClientSync | C->S | -- |
| `0x07` | ServerReady | S->C | -- |

### 3.4 Control

| Code | Name | Direction | Payload |
|------|------|-----------|---------|
| `0x08` | Error | Both | UTF-8 message |
| `0x09` | ServerReset | S->C | -- |
| `0x0A` | ClientResyncReq | C->S | -- |
| `0x0B` | ClientReset | C->S | -- |
| `0x0C` | ServerResyncReq | S->C | -- |

### 3.5 Authentication

| Code | Name | Direction | Payload |
|------|------|-----------|---------|
| `0x0D` | Identify | C->S | 16-byte UUIDv7 (+ optional 2-byte version) |
| `0x0E` | Auth | C->S | UTF-8 token |
| `0x0F` | AuthOk | S->C | -- |
| `0x11` | AuthFail | S->C | UTF-8 reason |

**IDENTIFY (0x0D) Payload Format:**

Payload: 16-byte UUIDv7 + optional 2-byte protocol version (major, minor). Servers accepting 18-byte payload extract version; 16-byte payload is treated as version 0.0.

> **Note**: `0x10` is reserved (DLE control character). AuthFail uses `0x11` to avoid collision.

### 3.6 Array Operations

| Code | Name | Direction | Payload |
|------|------|-----------|---------|
| `0x20` | ArrayShiftLeft | S->C | Int32 shift count |
| `0x21` | ArrayShiftRight | S->C | Int32 shift count |

**ARRAY_SHIFT_LEFT (0x20):**

Used to optimize array left-shift patterns (e.g., sliding window: `[1,2,3,4,5]` -> `[2,3,4,5,6]`). Instead of re-sending all shifted element values, the server sends a single ARRAY_SHIFT_LEFT frame.

- **KeyID**: keyId of `{arrayKey}.length` (identifies which array)
- **DataType**: Int32 (0x06)
- **Payload**: shift count as int32 (4 bytes) -- how many elements shifted off the front

**Client action on receiving ARRAY_SHIFT_LEFT(keyId=lengthKeyId, payload=k):**

1. Look up the path for `lengthKeyId` (e.g., `data.length`)
2. Derive the array prefix (e.g., `data`)
3. Read current length from store
4. For `i` from `0` to `length - k - 1`: copy value at `{prefix}.{i+k}` to `{prefix}.{i}`
5. Update length to `length - k`
6. Fire callbacks for `{prefix}.length`

**ARRAY_SHIFT_RIGHT (0x21):**

Used to optimize array right-shift patterns (e.g., prepend: `[1,2,3,4,5]` -> `[0,1,2,3,4,5]`). Instead of re-sending all shifted element values, the server sends a single ARRAY_SHIFT_RIGHT frame.

- **KeyID**: keyId of `{arrayKey}.length` (identifies which array)
- **DataType**: Int32 (0x06)
- **Payload**: shift count as int32 (4 bytes) -- how many positions to shift right

**Client action on receiving ARRAY_SHIFT_RIGHT(keyId=lengthKeyId, payload=k):**

1. Look up the path for `lengthKeyId` (e.g., `data.length`)
2. Derive the array prefix (e.g., `data`)
3. Read current length from store
4. For `i` from `length - 1` down to `0`: copy value at `{prefix}.{i}` to `{prefix}.{i+k}`
5. Do NOT update length (server sends new head elements + length update separately)
6. Fire callbacks for `{prefix}.length`

**Server-side array diff detection (Smart Detection Algorithm):**

When `set(key, array)` is called and a previous array exists for that key, the server compares old and new arrays to detect shift patterns. The algorithm supports **any shift amount** (MAX_SHIFT=50, with quickHash pre-filter for performance).

1. **Left shift**: Compare `old[k:]` against `new[0:matchLen]` for any valid `k`
   - If a contiguous match is found: send ARRAY_SHIFT_LEFT(k) + new tail elements + length update if changed
   - Common patterns: `shift() + push()`, `splice(0, k) + append`, sliding windows
2. **Right shift**: Compare `old[0:matchLen]` against `new[k:k+matchLen]` for any valid `k`
   - If a contiguous match is found: send ARRAY_SHIFT_RIGHT(k) + new head elements + length update if changed
   - Common patterns: `unshift()`, prepend operations
3. **Append only**: If `new.length > old.length` and `old` is a prefix of `new`, only new tail elements are sent
4. **Pop only**: If `new.length < old.length` and `new` is a prefix of `old`, only the length update is sent
5. If no shift pattern detected: fall through to normal flatten (field-level dedup handles unchanged elements)

**Frame count comparison:**

| Scenario | Without ARRAY_SHIFT | With ARRAY_SHIFT |
|----------|-------------------|-----------------|
| 100-element array, shift left by 1 | 101 frames | 3 frames |
| 1000-element array, shift left by 1 | 1001 frames | 3 frames |
| 50-element array, shift left by 5 | 51 frames | 7 frames |
| Append 1 element | 2 frames | 2 frames |
| Pop 1 element | 1 frame | 1 frame |

**Wire example -- left shift by 1 on array "scores" (length keyId=0x00000005):**

```
10 02 20 00 00 00 05 06 00 00 00 01 10 03
|  |  |  |--------|  |  |--------| |  |
|  |  |    KeyID   |  |  payload   DLE ETX
|  |  FrameType   DataType=Int32
DLE STX  =0x20     =0x06         shiftCount=1
```

**Wire example -- right shift by 1 on array "scores" (length keyId=0x00000005):**

```
10 02 21 00 00 00 05 06 00 00 00 01 10 03
|  |  |  |--------|  |  |--------| |  |
|  |  |    KeyID   |  |  payload   DLE ETX
|  |  FrameType   DataType=Int32
DLE STX  =0x21     =0x06         shiftCount=1
```

### 3.7 Key Lifecycle

| Code | Name | Direction | Payload |
|------|------|-----------|---------|
| `0x22` | ServerKeyDelete | S->C | Signal (no payload) |
| `0x23` | ClientKeyRequest | C->S | Signal (no payload) |

**ServerKeyDelete (0x22):**

Incremental key deletion. The server sends this instead of a full ServerReset+resync when individual keys are removed.

- **KeyID**: the keyId being deleted
- **DataType**: Null (0x00) -- signal frame
- **Payload**: none

**Client action on receiving ServerKeyDelete(keyId):**
1. Remove keyId from key registry
2. Remove keyId from value store
3. Fire onReceive(path, undefined) to notify listeners of deletion

**Use cases:**
- `server.clear("user")` -- sends ServerKeyDelete for each flattened sub-key
- Type change (e.g., number->string) -- sends ServerKeyDelete(old keyId) + ServerKeyRegistration(new keyId) + ServerSync + ServerValue

**ClientKeyRequest (0x23):**

Single-key recovery. The client sends this when it receives a ServerValue for an unknown keyId, instead of requesting a full state resync.

- **KeyID**: the keyId the client needs information about
- **DataType**: Null (0x00) -- signal frame
- **Payload**: none

**Server action on receiving ClientKeyRequest(keyId):**
1. Find the keyId in current state (principal TX, session flat state, or topic payloads)
2. Send: ServerKeyRegistration(keyId, path, type) + ServerSync + ServerValue(keyId, value)
3. If keyId not found, no response (client will timeout and may request full resync)

O(1) lookup is achieved via a reverse keyId index maintained in TopicPayload and KeyRegistry.

**Client behavior for unknown keyId:**
1. Receive ServerValue(keyId=X) but keyId X is not in registry
2. Send ClientKeyRequest(keyId=X)
3. Buffer the value in pendingValues map
4. When ServerKeyRegistration arrives for keyId X, apply the buffered value immediately

### 3.8 Batch Boundary

| Code | Name | Direction | Payload |
|------|------|-----------|---------|
| `0xFF` | ServerFlushEnd | S->C | -- |

**SERVER_FLUSH_END (0xFF):**

Sent automatically at the end of every BulkQueue flush batch. This signal tells the client that all frames in this batch have been delivered, and the client's state is now consistent with the server at this point in time.

- **Purpose**: Prevents render storms. Without this, `onReceive` fires per-frame causing N re-renders per batch. With `ServerFlushEnd`, the client fires `onUpdate` exactly once per batch.
- **Client behavior**:
  - `onReceive(key, value)` -- fires per individual `ServerValue` frame (fine-grained, per-key)
  - `onUpdate(state)` -- fires once when `ServerFlushEnd` is received (batch-level, for rendering)
- **Timing**: Appended to every BulkQueue flush (default every 100ms). Initial sync values also go through BulkQueue, so the first `onUpdate` fires after the initial data is fully loaded.

---

## 4. Data Types

Types are auto-detected from application values. No explicit declaration needed.

| Code | Type | Size | JS / Java Type |
|------|------|------|----------------|
| `0x00` | Null | 0 | `null` |
| `0x01` | Bool | 1 | `boolean` / `Boolean` |
| `0x02` | Uint8 | 1 | -- |
| `0x03` | Uint16 | 2 | -- |
| `0x04` | Uint32 | 4 | -- / `Integer` |
| `0x05` | Uint64 | 8 | `bigint` / `Long` |
| `0x06` | Int32 | 4 | -- / `Integer` |
| `0x07` | Int64 | 8 | `bigint` / `Long` |
| `0x08` | Float32 | 4 | -- / `Float` |
| `0x09` | Float64 | 8 | `number` / `Double` |
| `0x0A` | String | var | `string` / `String` |
| `0x0B` | Binary | var | `Uint8Array` / `byte[]` |
| `0x0C` | Timestamp | 8 | `Date` / `Date` |
| `0x0D` | VarInteger | var | `number` / `Integer`, `Long` |
| `0x0E` | VarDouble | var | `number` / `Double` |
| `0x0F` | VarFloat | var | `number` / `Float` |

### Auto-Detection Rules

| Value | Wire Type |
|-------|-----------|
| `null` | Null |
| `true` / `false` | Bool |
| Java `Integer`, `Long`, `Short`, `Byte` | VarInteger |
| Java `Double`, `BigDecimal` | VarDouble |
| Java `Float` | VarFloat |
| `string` / `String` | String |
| `Uint8Array` / `byte[]` | Binary |
| `Date` / `Instant` | Timestamp |
| `BigInteger` (< 64 bits) | VarInteger |
| `BigInteger` (>= 64 bits) | String (fallback) |
| `{ ... }` / `[...]` (object/array) | **Auto-flatten** (API layer, not a wire type) |

### VarInteger (0x0D) -- Compact Variable-Length Integer Encoding

VarInteger encodes all integer types (Integer, Long, Short, Byte) using zigzag + unsigned VarInt.

**Zigzag encoding:** Maps signed long to unsigned long: `(n << 1) ^ (n >> 63)`
- 0 -> 0, -1 -> 1, 1 -> 2, -2 -> 3, 2 -> 4, ...

**Payload:** The zigzag-encoded value as unsigned VarInt (protobuf-style: 7 bits per byte, MSB = continuation).

**Deserialization type mapping:**
- Fits in int32 range -> `Integer` (Java) / `number` (JS)
- Exceeds int32 range -> `Long` (Java) / `number` (JS)

**Wire examples:**

| Value | Bytes | Explanation |
|-------|-------|-------------|
| `0` | `00` | zigzag=0, VarInt=0 |
| `1` | `02` | zigzag=2, VarInt=2 |
| `-1` | `01` | zigzag=1, VarInt=1 |
| `42` | `54` | zigzag=84, VarInt=84 (0x54) |
| `-42` | `53` | zigzag=83, VarInt=83 (0x53) |
| `300` | `D8 04` | zigzag=600, VarInt: 0xD8 0x04 (2 bytes) |
| `2147483647` (INT_MAX) | `FE FF FF FF 0F` | zigzag=4294967294, VarInt: 5 bytes |

### VarDouble (0x0E) -- Compact Variable-Length Double Encoding

VarDouble encodes Double values using scale + mantissa encoding with Float64 fallback.

**First byte layout: `[FSSS SSSS]`**

| Bit | Name | Meaning |
|-----|------|---------|
| F (bit 7) | Fallback flag | 1 = Float64 raw 8 bytes follow |
| SSSSSSS (bits 0-6) | Sign + Scale | 0-63: positive, scale = value; 64-127: negative, scale = value - 64 |

**When F=0 (normal encoding):**
- First byte encodes sign and decimal scale
- Followed by an unsigned VarInt mantissa (protobuf-style: 7 bits per byte, MSB = continuation)
- Value = mantissa / 10^scale (negated if sign bit set)

**When F=1 (byte = 0x80):**
- Next 8 bytes = raw IEEE 754 Float64
- Used for: NaN, Infinity, -Infinity, -0, scientific notation, scale > 63, mantissa overflow

**Deserialization:** Always returns `Double` (Java) / `number` (JS).

**Wire examples:**

| Value | Bytes | Explanation |
|-------|-------|-------------|
| `42.0` | `00 2A` | scale=0, positive, mantissa=42 |
| `-7.0` | `40 07` | scale=0, negative, mantissa=7 |
| `3.14` | `02 BA 02` | scale=2, positive, mantissa=314 (VarInt: 0xBA 0x02) |
| `0.0` | `00 00` | scale=0, positive, mantissa=0 |
| `100.5` | `01 E9 07` | scale=1, positive, mantissa=1005 (VarInt: 0xE9 0x07) |
| `Math.PI` | `80 [8 bytes]` | fallback Float64 |
| `NaN` | `80 [8 bytes]` | fallback Float64 |
| `Infinity` | `80 [8 bytes]` | fallback Float64 |

### VarFloat (0x0F) -- Compact Variable-Length Float Encoding

VarFloat encodes Float values using the same scale + mantissa encoding as VarDouble, but with Float32 fallback instead of Float64.

**First byte layout:** Same as VarDouble: `[FSSS SSSS]`

**When F=0 (normal encoding):**
- Same as VarDouble -- first byte encodes sign and scale, followed by unsigned VarInt mantissa.
- Value = mantissa / 10^scale (negated if sign bit set)

**When F=1 (byte = 0x80):**
- Next 4 bytes = raw IEEE 754 Float32 (instead of 8 bytes for VarDouble)
- Used for: NaN, Infinity, -Infinity, -0, and values that cannot be represented compactly

**Deserialization:** Returns `Float` (Java) / `number` (JS).

### VarInt Encoding (unsigned, protobuf-style)

Used by VarInteger, VarDouble, and VarFloat for the mantissa/value component:
- 7 bits per byte, MSB = continuation bit
- `0-127`: 1 byte `[0XXXXXXX]`
- `128-16383`: 2 bytes `[1XXXXXXX] [0XXXXXXX]`
- Up to 10 bytes for 64-bit values

**Examples:**

| Unsigned Value | Encoded Bytes | Explanation |
|----------------|---------------|-------------|
| 0 | `00` | Single byte, no continuation |
| 127 | `7F` | Single byte, maximum 7-bit value |
| 128 | `80 01` | Continuation bit set, then 1 |
| 300 | `AC 02` | 300 = 0b100101100 -> split into 7-bit groups |
| 16384 | `80 80 01` | 3 bytes |

---

## 5. DLE Byte-Stuffing

### Rules

| Wire Bytes | Meaning |
|------------|---------|
| `0x10 0x02` | Frame start |
| `0x10 0x03` | Frame end |
| `0x10 0x05` | Heartbeat |
| `0x10 0x10` | Literal `0x10` in data |
| `0x10 [other]` | Protocol error |

The entire frame body (FrameType + KeyID + DataType + Payload) is DLE-escaped. This means any byte with value `0x10` within the body is doubled to `0x10 0x10` on the wire.

**Encoding (sender):**
1. Build raw body: FrameType(1) + KeyID(4) + DataType(1) + Payload(N)
2. Scan body for `0x10` bytes and replace each with `0x10 0x10`
3. Wrap with `0x10 0x02` (start) and `0x10 0x03` (end)

**Decoding (receiver):**
1. Detect frame boundaries via `0x10 0x02` (start) and `0x10 0x03` (end)
2. Within the frame body, replace each `0x10 0x10` back to a single `0x10`
3. Parse: FrameType(1) + KeyID(4) + DataType(1) + Payload(remaining)

---

## 6. Auto-Flatten (API Layer)

Objects and arrays are expanded into dot-path leaf keys before going on the wire. This is handled at the API layer, not the protocol layer -- the wire only carries primitive leaf values.

### Expansion Rules

| Input | Expanded Keys |
|-------|---------------|
| `set("user", { name: "Alice", age: 30 })` | `user.name` = "Alice", `user.age` = 30 |
| `set("scores", [10, 20, 30])` | `scores.0` = 10, `scores.1` = 20, `scores.2` = 30, `scores.length` = 3 |
| `set("data", { items: [{ id: 1 }] })` | `data.items.length` = 1, `data.items.0.id` = 1 |

- Arrays get an automatic `.length` key
- Nested objects flatten recursively (max depth: 10)
- Circular references are detected and rejected
- When an array shrinks, leftover keys are automatically removed
- Unchanged leaf values are not re-transmitted (field-level dedup)
- Flatten paths are pre-computed for performance

### Topic Mode Wire Prefix

In topic modes, each topic's payload keys are prefixed with `t.<index>.`:

```
topic "board" (index=0): t.0.items.length, t.0.items.0.title, ...
topic "chart" (index=1): t.1.value, t.1.timestamp, ...
```

Client-to-Server topic subscriptions use `topic.<index>.name` and `topic.<index>.param.<key>` encoding.

---

## 7. Key Lifecycle

### Key Registration

Keys are registered before values are sent. Each key gets a unique 4-byte keyId.

1. Server calls `set("sensor.temp", 23.5)`
2. If `sensor.temp` is not yet registered, server sends `ServerKeyRegistration(keyId=N, path="sensor.temp")`
3. Server sends `ServerSync` to mark the end of the registration batch
4. Client sends `ClientReady` to acknowledge
5. Server sends `ServerValue(keyId=N, value=23.5)`

### Key Deletion

When a key is removed (via `clear()` or type change), the server sends `ServerKeyDelete(keyId)` for each affected key. The keyId is then recycled into a `freedKeyIds` pool (capped at 10,000 entries) for reuse by future key registrations.

### Key Recovery

If a client receives a `ServerValue` for an unknown keyId (e.g., due to packet reordering or missed registration), it sends `ClientKeyRequest(keyId)` for single-key recovery instead of requesting a full state resync.

---

## 8. Connection Lifecycle

### 8.1 Connection (No Auth)

```
Client                         Server
  |                               |
  |-- IDENTIFY (UUIDv7) -------->|  Create session
  |                               |
  |<-- Key Reg (key1, float64) --|  Register keys
  |<-- Key Reg (key2, string)  --|
  |<-- Server SYNC --------------|
  |                               |
  |-- Client READY ------------->|
  |                               |
  |<-- Value (key1 = 23.5) ------|  Full state sync
  |<-- Value (key2 = "hello") ---|
  |<-- SERVER_FLUSH_END ---------|  Batch boundary
  |                               |
  |<-- Value (key1 = 24.1) ------|  Live updates...
  |<-- SERVER_FLUSH_END ---------|
```

### 8.2 Connection (With Auth)

```
Client                         Server
  |                               |
  |-- IDENTIFY (UUIDv7) -------->|
  |-- AUTH (token) ------------->|  verify + determine principal
  |<-- AUTH_OK ------------------|  bind to principal
  |                               |
  |<-- Key Reg ... --------------|
  |<-- Server SYNC --------------|
  |-- Client READY ------------->|
  |<-- Values ... ---------------|
  |<-- SERVER_FLUSH_END ---------|
```

### 8.3 Topic Subscription (Topic Modes)

```
Client                         Server
  |                               |
  |-- ClientReset -------------->|  Clear previous topic state
  |-- ClientKeyReg (topic.0.name, ...) ->|
  |-- ClientValue (topic.0.name = "board") ->|
  |-- ClientSync --------------->|  Process topic diff
  |                               |
  |<-- ServerReset --------------|  Full state rebuild
  |<-- Key Reg (t.0.items.0.title, ...) -|
  |<-- Server SYNC --------------|
  |-- Client READY ------------->|
  |<-- Values ... ---------------|
  |<-- SERVER_FLUSH_END ---------|
```

### 8.4 Recovery

If client receives a value for an unknown key:

**Single-key recovery (preferred):**
```
Client                         Server
  |-- ClientKeyRequest(keyId) ->|
  |<-- KeyRegistration(keyId) --|
  |<-- ServerSync --------------|
  |-- ClientReady -------------->|
  |<-- Value(keyId) ------------|
```

**Full resync (fallback):**
```
Client                         Server
  |-- Client RESYNC_REQ ------->|
  |<-- Server RESET ------------|
  |<-- Key Reg (all) ----------|
  |<-- Server SYNC -------------|
  |-- Client READY ------------->|
  |<-- Values (all) ------------|
  |<-- SERVER_FLUSH_END ---------|
```

---

## 9. Heartbeat

```
+---------+---------+
| DLE     | ENQ     |
| 0x10    | 0x05    |
+---------+---------+
```

- Sent every **10 seconds** by both client and server
- If no heartbeat is received within **15 seconds**, the connection is considered dead
- Client uses `ReconnectEngine` with exponential backoff + jitter for automatic reconnection
- Heartbeat is a 2-byte sequence, not a framed message

---

## 10. Batch Framing

Multiple frames can be concatenated in one transport message:

```
[DLE STX ... DLE ETX][DLE STX ... DLE ETX][DLE STX ... DLE ETX]
```

The bulk queue batches frames every **100ms** (configurable via `flushIntervalMs`) and sends them as one message. Value frames for the same key are **deduplicated** within the window (only the latest value is sent). A `SERVER_FLUSH_END` frame is appended at the end of every flush.

---

## 11. KeyPath Convention

```
sensor.temperature       -- dot-separated segments
root.users.0.name        -- numeric segments = array indices
t.0.items.3.title        -- topic wire prefix
```

Rules:
- Segments: `[a-zA-Z0-9_]+`
- Separator: `.`
- Max length: 200 bytes (UTF-8)
- No leading/trailing/consecutive dots

---

## 12. Topic Sync

In topic modes (Session Topic, Session Principal Topic), clients subscribe to named topics with optional parameters.

### Subscription Flow

1. Client sends `ClientReset` to clear previous subscriptions
2. Client registers topic keys: `topic.0.name`, `topic.0.param.key1`, etc.
3. Client sends values for each topic key
4. Client sends `ClientSync` to trigger server-side topic diff

### Topic Diff

The server compares the new subscription set against the previous one:
- **Added topics**: server calls the topic callback to generate payload
- **Removed topics**: server cleans up topic payload and keys
- **Changed params**: server re-invokes the callback with new parameters

### Topic Payload

Each topic has a scoped `TopicPayload` key-value store. Keys are prefixed with `t.<index>.` on the wire. The server can use `setCallback` and `setDelayedTask` patterns with `EventType` to manage topic lifecycle.

---

## 13. Wire Examples (4-byte KeyID)

### Bool value `true` for KeyID 0x00000001

```
10 02 01 00 00 00 01 01 01 10 03
|  |  |  |--------|  |  |  |  |
|  |  |    KeyID   |  |  DLE ETX
|  |  FrameType   DataType
DLE STX  =0x01    =0x01(bool)  payload: 0x01=true
```

### Signal frame (Server SYNC, KeyID=0)

```
10 02 04 00 00 00 00 00 10 03     (10 bytes total)
```

### String "Alice" for KeyID 0x00000002

```
10 02 01 00 00 00 02 0A 41 6C 69 63 65 10 03
```

### VarInteger value 42 for KeyID 0x00000003

```
10 02 01 00 00 00 03 0D 54 10 03
|  |  |  |--------|  |  |  |  |
|  |  |    KeyID   |  |  DLE ETX
|  |  FrameType   DataType
DLE STX  =0x01    =0x0D(VarInteger)  payload: 0x54 (zigzag=84, value=42)
```

### VarDouble value 3.14 for KeyID 0x00000004

```
10 02 01 00 00 00 04 0E 02 BA 02 10 03
|  |  |  |--------|  |  |  |---| |  |
|  |  |    KeyID   |  |  | mant  DLE ETX
|  |  FrameType   DataType scale
DLE STX  =0x01    =0x0E    =2    mantissa=314
```

### KeyID 0x00000010 (contains DLE byte, escaped)

```
10 02 01 00 00 00 10 10 0A ... 10 03
              ^^^^^^^^^ 0x10 escaped to 0x10 0x10
```

### ServerKeyDelete for KeyID 0x00000007

```
10 02 22 00 00 00 07 00 10 03
|  |  |  |--------|  |  |  |
|  |  |    KeyID   |  DLE ETX
|  |  FrameType   DataType=Null (signal)
DLE STX  =0x22
```

### SERVER_FLUSH_END

```
10 02 FF 00 00 00 00 00 10 03
|  |  |  |--------|  |  |  |
|  |  |    KeyID=0 |  DLE ETX
|  |  FrameType   DataType=Null
DLE STX  =0xFF
```

---

## 14. Implementations

| Language | Package | Install |
|----------|---------|---------|
| TypeScript | [`dan-websocket`](https://www.npmjs.com/package/dan-websocket) | `npm install dan-websocket` |
| Java | [`io.github.justdancecloud:dan-websocket`](https://central.sonatype.com/artifact/io.github.justdancecloud/dan-websocket) | Gradle / Maven |

Both implementations are **wire-compatible**: a TypeScript server can serve Java clients and vice versa. Protocol changes must be applied to both implementations simultaneously.
