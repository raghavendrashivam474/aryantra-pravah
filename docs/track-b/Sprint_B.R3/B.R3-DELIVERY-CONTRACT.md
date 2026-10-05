# Pravaah — Message Delivery Semantics & Lifecycle Contract
**Sprint:** B.R3 | **Status:** Active Standard | **Baseline:** v-A.D2.7 / B.R3

---

## 1. Executive Summary

This document formalizes the message delivery contract in Pravaah. It defines:
- The exact meaning and invariant of every message lifecycle state
- The wire-level framing format for application payloads
- The precise guarantee provided by an Application ACK
- Deduplication semantics across retry sweeps and transition flushes
- Application-level ordering guarantees across transport migrations

---

## 2. Message Lifecycle State Machine
```text

                  +-------------------+
                  |      CREATED      |
                  +---------+---------+
                            |
               PeerRouter.send(dest, msg)
                            |
           +----------------+----------------+
           |                                 |
     [Path ACTIVE]                   [Path CANDIDATE]
   transport.send()              transitionBuffer.offer()
           |                                 |
           v                                 v
    +--------------+                  +--------------+
    |     SENT     |                  |   BUFFERED   |
    +-------+------+                  +-------+------+
            |                                 |
    Remote ACK received                 Path activates
    (handleInboundAck)               (TransitionBuffer flush)
            |                                 |
            v                                 v
    +--------------+                  +--------------+
    |  DELIVERED   |                  |     SENT     |
    +--------------+                  +-------+------+
                                              |
                                      Remote ACK received
                                              |
                                              v
                                      +--------------+
                                      |  DELIVERED   |
                                      +--------------+
```

[Failure Paths]

- All paths down & no candidates --> FAILED (immediate)
- DeliveryRetryManager attempts >= 3 --> FAILED (OutboxState.ABANDONED)
- TransitionBuffer TTL expires (>10s) --> Buffer expired (dropped from window)


---

## 3. Formal State Definitions & Invariants

| State | Exact Architectural Meaning | What it Guarantees | What it Does NOT Guarantee |
| :--- | :--- | :--- | :--- |
| **`CREATED`** | The application service has accepted the message, generated or validated `messageId`, registered delivery intent in the `DeliveryOutbox`, and stored the record in `MessageHistoryStore`. | Message is tracked locally; intent is durable (if SQLite outbox enabled). | Does NOT mean the message has left the local device. |
| **`BUFFERED`** | `PeerRouter` attempted path resolution, found no active transport path, but discovered reachable candidate path(s). Captured in in-memory `TransitionBuffer`. | Message will be flushed automatically if candidate path transitions to ACTIVE within TTL window (10s). | Does NOT guarantee path will succeed or that message won't expire if peer stays offline. |
| **`SENT`** | `PeerRouter` selected an active path via `PathSelectionPolicy` and the underlying `Transport.send()` wrote the framed byte stream to OS/socket/RFCOMM buffer. | Bytes successfully handed off to local transport subsystem without throwing `IOException` or socket error. | Does NOT mean remote device received or processed bytes. |
| **`DELIVERED`** | Remote peer received the protocol envelope, parsed payload, saved into local `MessageHistoryStore`, and returned an explicit application ACK (`0x02`). | Remote peer application layer has accepted and verified the message. | Does NOT guarantee the user on the remote screen has read the message. |
| **`FAILED`** | Message could not be routed (no active/candidate paths), transport write threw fatal error without fallback, or max retry attempts were exceeded. | System has ceased automatic transmission attempts for this specific transaction. | May still be retried manually if retained in history. |

---

## 4. Application Payload Wire Framing

Application messages travel encapsulated within protocol `Message(type=MESSAGE)` envelopes. Byte 0 specifies the application framing sub-type:

```text
+-------------------+-------------------------------------------------------------+
| Sub-Type Byte | Payload Structure |
+-------------------+-------------------------------------------------------------+
| 0x01 (CHAT) | [0x01:1 byte] [UTF-8 Plaintext: N bytes] |
| 0x02 (ACK) | [0x02:1 byte] [Target MessageId UTF-8: N bytes] |
| 0x03 (GROUP_CHAT) | [0x03:1 byte] [GroupIdLen:2 bytes] [GroupId] [Text:N bytes] |
| 0x04 (SEQUENCED) | [0x04:1 byte] [Sequence:8 bytes (Big-Endian)] [Text:N bytes]|
+-------------------+-------------------------------------------------------------+
```

### Invariants:
1. **Backward Compatibility**: Inbound handler accepts both `0x01` (legacy unsequenced) and `0x04` (sequenced). Legacy receivers receiving unknown sub-types log a warning without throwing fatal crashes.
2. **Endianness**: 64-bit integer sequences are serialized in network byte order (Big-Endian) using `ByteBuffer.putLong()`.

---

## 5. Application ACK Contract

### Guarantee
When Peer A receives `APP_MSG_ACK (0x02)` for `messageId`:
1. Peer B's coordinator received and authenticated the protocol frame.
2. Peer B's application messaging layer decoded the frame and passed deduplication check.
3. Peer B recorded the message in its `MessageHistoryStore`.
4. Peer B notified all registered `ApplicationMessageListener` callbacks.

### Execution Invariant
- ACKs are dispatched **asynchronously and immediately** from the inbound message thread upon completing listener notification.
- ACK delivery does not generate recursive ACKs (no ACK storms).

---

## 6. Deduplication Contract

During transport transitions and retry sweeps, the following race condition is possible:

```text
Peer A (Retry Sweep) ───────> Dispatches M1 via Bluetooth
Peer A (Transition Flush) ──> Dispatches M1 via TCP
Peer B receives M1 twice
```

### Resolution Invariant:
1. **Receiver Cache**: `DefaultApplicationMessagingService` maintains a thread-safe, bounded LRU cache (`MAX_DEDUP_CACHE_SIZE = 1024`) of processed `messageId` strings.
2. **Duplicate Handling**:
   - If `messageId` exists in cache: **Do not invoke `ApplicationMessageListener`**.
   - **Always send ACK back to sender**: This clears sender's `DeliveryOutbox` and prevents infinite retry loops.

---

## 7. Ordering Guarantees Across Transport Migration

| Scenario | Transport Route | Ordering Guarantee | Enforcement Mechanism |
| :--- | :--- | :--- | :--- |
| **Single Path Stable** | TCP only | Strict FIFO | TCP connection byte stream |
| **Single Path Stable** | Bluetooth only | Strict FIFO | RFCOMM stream order |
| **Path Migration** | BT ➔ Transition ➔ TCP | Monotonic per-peer | `SequenceGenerator` 64-bit sequence in `0x04` frame |
| **Multi-Path Interleaved** | TCP + BT concurrent | Monotonic per-peer | Destination sequence counter |

### Sequence Number Properties:
- **Scope**: Independent per `(localPeer, destinationPeer)` tuple.
- **Progression**: Strictly monotonic ($seq_{n+1} = seq_n + 1$), starting at 1.
- **Session Scope**: In-memory atomic counters reset on application restart.

---

## 8. TransitionBuffer vs. DeliveryRetryManager Separation

```text
+------------------------------------+------------------------------------+
| TransitionBuffer | DeliveryRetryManager |
+------------------------------------+------------------------------------+
| Scope: Transport/Path transition | Scope: Application offline queue |
| Lifetime: Short-lived (TTL: 10s) | Lifetime: Persistent (SQLite) |
| Owned by: PeerRouter | Owned by: Messaging Service |
| Trigger: Path CANDIDATE available | Trigger: Peer joined / reconnected |
| Strategy: In-memory FIFO drop | Strategy: Bounded retries (max: 3) |
+------------------------------------+------------------------------------+
```

Neither subsystem bypasses the other:
- `DeliveryRetryManager` retries pending outbox entries by invoking `PeerRouter.send()`.
- If a retry attempt coincides with a path transition window, `PeerRouter` captures the retry in `TransitionBuffer` safely.