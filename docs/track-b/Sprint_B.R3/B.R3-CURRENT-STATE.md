# B.R3-CURRENT-STATE.md
## Message Delivery Semantics & Path-Migration Efficiency
### Baseline: v-A.D2.7 | Date: 2026-10-05

---

## 1. Current Message Model

### Protocol Layer: Message (record)
`
Message(
    MessageType type,    // JOIN | MESSAGE | LEAVE
    String senderId,     // logical peer identity
    String messageId,    // unique identifier (UUID)
    byte[] payload       // transport-independent content
)
`
- Immutable, value-based equality
- Defensive payload copy
- **Has identity**: messageId is stable across transport changes
- **Has sender**: senderId present
- **No sequence number**: ordering is NOT encoded
- **No recipient field**: routing is external via PeerRouter

### Application Layer: ApplicationMessage
`
ApplicationMessage(
    String messageId,          // UUID
    PeerId sender,             // typed peer identity
    String content,            // text content
    Instant timestamp,         // creation time
    ConversationId conversationId  // direct or group
)
`
- Factory: ApplicationMessage.text() generates UUID messageId
- **Has identity**: UUID messageId
- **Has sender**: PeerId
- **No sequence number**: ordering relies on timestamp (insufficient for migration)
- **No recipient field**: destination is a routing parameter, not a message field

### Wire Framing (Application Payload)
`
Byte 0: sub-type (0x01=CHAT, 0x02=ACK, 0x03=GROUP_CHAT)
Byte 1..N: content bytes
`
- ACK payload: [0x02][messageId bytes]
- No sequence field in wire format

---

## 2. Current Send Flow

`
Application
    |
    v
DefaultApplicationMessagingService.send(destination, ApplicationMessage)
    |
    |-- updateState(messageId, CREATED)
    |-- outbox.enqueue(OutboxEntry.pending(...))
    |-- Frame: [0x01][content bytes]
    |-- Create protocol Message(MESSAGE, localPeerId, messageId, framedPayload)
    |
    v
PeerRouter.send(destination, Message)
    |
    |-- MessageEncoder.encode(message)
    |-- FrameEncoder.encode(encoded)
    |
    |-- [Multi-path path]
    |   connectivityRegistry.lookup(destination)
    |       |
    |       v
    |   PathSelectionPolicy.selectPath(destination, activePaths)
    |       |
    |       v
    |   transport.send(connectionId, framed)
    |       |
    |       |-- SUCCESS -> return (message dispatched)
    |       |-- FAIL -> deactivate path, try next
    |       |
    |       v (all paths failed)
    |   candidatePaths exist?
    |       |-- YES -> transitionBuffer.offer(destination, message, framed)
    |       |          updateState -> (currently no BUFFERED state!)
    |       |-- NO -> fall through
    |
    |-- [Legacy fallback]
    |   registry.lookup(destination)
    |   transport.send(connectionId, framed)
    |
    v
updateState(messageId, SENT)  [in messaging service after send returns]
`

---

## 3. Current Buffer Flow (TransitionBuffer)

`
Trigger: PeerRouter.send() — all active paths failed, candidates exist
    |
    v
TransitionBuffer.offer(destination, message, framedPayload)
    |-- Check: size < maxMessages (64)
    |-- Check: currentBytes < maxBytes (256KB)
    |-- Check: payload not oversized
    |-- Create BufferedMessage(destination, message, framed, expiry=now+TTL)
    |-- Add to FIFO list
    |-- Increment totalBuffered counter
    |
    v (later, when path becomes active)
TransitionBuffer.flushForPeer(peerId, sendAction)
    |-- Acquire lock
    |-- Purge expired entries (TTL > 10s)
    |-- Collect non-expired entries for peer (FIFO order)
    |-- Release lock
    |-- For each entry: sendAction.accept(peerId, framedPayload)
    |-- Increment totalFlushed counter
`

**Key properties:**
- Bounded: 64 messages, 256KB, 10s TTL
- FIFO ordering preserved within buffer
- Flush happens OUTSIDE the lock (good concurrency)
- Counters: buffered/flushed/expired/evicted/rejected
- Owned by PeerRouter, NOT by DeliveryRetryManager

---

## 4. Current Retry Flow (DeliveryRetryManager)

`
Trigger: Peer reconnects -> retryPendingForPeer(destination)
    |
    v
outbox.findPendingForPeer(destination)
    |
    v
For each OutboxEntry:
    |-- Increment attemptCount
    |-- historyStore.find(messageId) -> ApplicationMessage
    |-- Re-frame: [0x01][content]
    |-- Create new protocol Message (same messageId!)
    |-- peerRouter.send(destination, protocolMessage)
    |       |-- SUCCESS -> notify DISPATCHED
    |       |-- FAIL -> if attempts >= 3 -> ABANDONED
    |                  else -> notify FAILED
    |
    v (when ACK received)
handleInboundAck() -> outbox.markCompleted() -> updateState(DELIVERED)
`

**Key properties:**
- Max 3 attempts, then ABANDONED
- Uses SAME messageId on retry (idempotent identity)
- Reconstructs from history store (not from buffer)
- Separate from TransitionBuffer (different concern)
- SQLite-backed outbox survives app restart

---

## 5. Current Ordering Behavior

### What works:
- TCP: in-connection ordering guaranteed by protocol
- Bluetooth: in-connection ordering guaranteed by RFCOMM
- TransitionBuffer: FIFO within buffer
- Outbox: ORDER BY seq ASC (insertion order)
- History store: monotonic sequence counter for storage

### What does NOT work:
- **Cross-transport ordering**: BT->TCP->BT migration has no sequence
- **No wire-level sequence**: Message record has no sequenceNumber
- **No application-level sequence**: ApplicationMessage has no sequenceNumber
- **Timestamp is insufficient**: multiple messages can share same Instant
- **Reorder scenario**:
  `
  Send M1 via BT (seq implicit: 1)
  Send M2 via BT (seq implicit: 2)
  BT drops, TCP activates
  Send M3 via TCP (seq implicit: 1 on new connection)
  M2 buffered, flushes to TCP after M3
  Receiver sees: M1, M3, M2  <-- REORDERED
  `

---

## 6. Current ACK Behavior

### ACK Contract (as implemented):
- **When**: Receiver's handleInboundChat() calls sendApplicationAck() immediately
- **What**: ACK contains the original messageId
- **Means**: "Application layer received and parsed this message"
- **Does NOT mean**: "Message was displayed to user" or "Message was persisted"
- **Effect on sender**: outbox.markCompleted() + updateState(DELIVERED)

### ACK Flow:
`
Sender                          Receiver
  |                                |
  |-- Message(MESSAGE, msgId) ---->|
  |                                |-- handleInboundChat()
  |                                |-- sendApplicationAck(msgId)
  |<-- Message(MESSAGE, ackId) ----|
  |     payload: [0x02][msgId]     |
  |-- handleInboundAck()           |
  |-- outbox.markCompleted(msgId)  |
  |-- updateState(msgId, DELIVERED)|
`

---

## 7. Current Transport Boundary

- Transport interface: send(connectionId, bytes), egisterListener()
- CompositeTransport: aggregates TCP + Bluetooth transports
- PathSelectionPolicy: selects path, authoritative routing decision
- PeerConnectivity: holds active + candidate paths per peer
- ConnectivityPath: typed path with connectionId, transport type, state

**PathSelectionPolicy remains the single routing authority.**

---

## 8. Known Gaps (B.R3 Targets)

### Gap 1: No cross-transport sequence numbers [PRIMARY]
- **Impact**: Messages can reorder during BT<->TCP migration
- **Severity**: Medium — affects message display order
- **Fix**: Add monotonic sequenceNumber per (sender, recipient) pair

### Gap 2: No BUFFERED state in MessageState
- **Current**: CREATED -> SENT -> DELIVERED -> FAILED
- **Missing**: CREATED -> BUFFERED -> SENT -> DELIVERED -> FAILED
- **Impact**: Cannot distinguish "waiting in TransitionBuffer" from "dispatched"
- **Fix**: Add BUFFERED enum value

### Gap 3: ACK contract not formally documented
- **Impact**: Ambiguity about what "DELIVERED" means
- **Fix**: ADR documenting ACK = "application received and parsed"

### Gap 4: No deduplication on receiver side
- **Impact**: Retry + transition flush could deliver same message twice
- **Mitigation**: messageId is stable, so receiver CAN deduplicate
- **Reality**: No explicit dedup check in handleInboundChat()
- **Fix**: Track recently-seen messageIds per peer

---

## 9. Proposed Minimum Changes

### Change 1: Add sequenceNumber to ApplicationMessage
- Per-peer monotonic counter (AtomicLong per destination)
- Included in wire framing: [sub-type][seq-4bytes][content]
- Receiver uses sequence to reorder if needed
- **Does NOT modify protocol Message record** (stays transport-independent)
- **Does NOT add new MessageType** (stays within MESSAGE)

### Change 2: Add BUFFERED to MessageState
- Single enum addition
- PeerRouter/TransitionBuffer integration updates state
- Lifecycle listeners notified

### Change 3: Receiver-side deduplication
- Track last N messageIds per peer (bounded set)
- Skip duplicate in handleInboundChat()
- Prevents double-delivery from retry + flush overlap

### Change 4: ADR documenting delivery contract
- Formal definitions of CREATED/BUFFERED/SENT/DELIVERED/FAILED
- ACK semantics
- Ordering guarantees and limitations

### NOT changing:
- Protocol Message record (no new fields)
- MessageType enum (no new types)
- PathSelectionPolicy (remains authoritative)
- TransitionBuffer constraints (64/256KB/10s)
- DeliveryRetryManager (already works)
- SQLite outbox (already exists and works)
- Transport layer (no modifications)
