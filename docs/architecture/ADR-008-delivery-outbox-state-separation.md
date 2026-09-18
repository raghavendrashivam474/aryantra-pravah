# ADR-008 — Delivery Outbox State Separation

## Status
Accepted (S5.1)

## Problem
Phase 4 established `MessageState` (`CREATED`, `SENT`, `DELIVERED`, `FAILED`)
to track the logical lifecycle of application messages. When a peer is
temporarily unavailable, `send()` transitions the message to `FAILED` and
the delivery intent is permanently lost. Phase 5 requires that delivery
intent survive transient routing failures so messages can be retried when
the destination peer reconnects.

## Current Limitation
- `MessageState.FAILED` is a terminal state in the Phase 4 model.
- There is no concept of "pending delivery work" separate from the
  logical message lifecycle.
- Adding `PENDING` to `MessageState` would conflate two distinct concerns:
  the logical message lifecycle and the delivery work queue.
- A message that failed to route is still a valid, persisted message in
  history. Its logical state should not be confused with its delivery
  work status.

## Why the Existing Design Is Insufficient
If `PENDING` were added to `MessageState`, a single enum would represent
two orthogonal dimensions:
1. **What happened to this message logically?** (CREATED -> SENT -> DELIVERED)
2. **Does this message still need delivery work?** (PENDING -> COMPLETED)

Collapsing these creates ambiguity. For example, a message could be
`FAILED` in routing but still `PENDING` for retry. A single enum cannot
express both simultaneously.

## Alternatives Considered

### A. Add PENDING and RETRYING to MessageState
Rejected: conflates logical lifecycle with delivery work. Would require
changing the Phase 4 state machine, risking regression in all existing
lifecycle tests and listeners. Violates the Phase 5 non-negotiable
foundation rule.

### B. Use MessageHistoryStore state field to track delivery work
Rejected: the history store represents "what messages do I know about,"
not "what delivery work remains." A delivered message should remain in
history permanently but should not remain in the delivery queue.

### C. Separate OutboxState enum + DeliveryOutbox abstraction
**Selected.** A dedicated `OutboxState` (`PENDING`, `COMPLETED`) tracks
delivery work independently. The `DeliveryOutbox` interface manages the
queue of pending delivery work. `MessageState` remains untouched.

### D. Retry logic embedded in PeerRouter or Transport
Rejected: violates the architectural layering directive. Reliability
belongs above the peer system, not inside transport or routing.

## Selected Design

```text
MessageState (unchanged from Phase 4)
  CREATED -> SENT -> DELIVERED | FAILED

OutboxState (new in Phase 5)
  PENDING -> COMPLETED
```
A message can be FAILED in MessageState while simultaneously PENDING
in the OutboxState. These are independent dimensions.

```text
send()
  |
  v
persist to MessageHistoryStore (CREATED)
  |
  v
enqueue to DeliveryOutbox (PENDING)
  |
  v
attempt PeerRouter.send()
  |
  +-- success --> MessageState.SENT
  |
  +-- failure --> MessageState.FAILED
                  OutboxState remains PENDING (intent preserved)
```

### Why It Was Selected

- Zero changes to Phase 4 MessageState enum or state machine.
- All 230 existing Phase 4 tests pass without modification.
- Clean separation: history = durable record, outbox = pending work.
- The outbox can be independently persisted (SQLite) without coupling
  to the message history schema.
- Android portability is preserved: the outbox interface can be
  implemented with android.database.sqlite independently.

### Existing APIs Affected

- `DefaultApplicationMessagingService` constructor now accepts an
  optional `DeliveryOutbox` parameter.
- `DefaultApplicationMessagingService.send()` now enqueues a delivery
  intent before attempting routing.
- `DefaultApplicationMessagingService.handleInboundAck()` now calls
  `outbox.markCompleted(messageId)` upon receiving an ACK.
- No changes to `ApplicationMessage`, `MessageState`, `ConversationId`,
  `PeerId`, `PeerRouter`, or any Phase 3/4 contract.

### Compatibility Impact

- Additive only. The default constructor creates an InMemoryDeliveryOutbox,
  preserving backward compatibility for all existing callers.
- `MessageState` semantics are unchanged.
- Wire format is unchanged.
- No changes to protocol framing or transport.

### Regression Risks
- The `send()` method now performs an additional outbox enqueue before
  routing. This is a lightweight ConcurrentHashMap.putIfAbsent() for
  the in-memory implementation and a single INSERT for SQLite.
- The `handleInboundAck()` method now calls outbox.markCompleted().
  If the outbox entry does not exist (e.g., message was sent before
  Phase 5), this is a no-op.

### Required Tests

- **Outbox contract tests**: enqueue, findPending, markCompleted, remove.
- **Deterministic ordering tests** with sequence tie-breaker.
- **Integration test**: send to unavailable peer -> FAILED state but
  PENDING outbox entry.
- **Durability test**: enqueue -> close -> reopen -> pending entries survive.
- **end-to-end**: send offline -> restart -> peer reconnects -> retry ->
  ACK -> DELIVERED -> outbox COMPLETED.

### Schema (for SQLite outbox implementation)

```SQL
CREATE TABLE IF NOT EXISTS outbox (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id TEXT NOT NULL UNIQUE,
    destination_peer_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at_epoch_ms INTEGER NOT NULL
);
CREATE INDEX idx_outbox_status_seq ON outbox(status, seq);
CREATE INDEX idx_outbox_peer_status_seq
    ON outbox(destination_peer_id, status, seq);
```

- **destination_peer_id**: stores PeerId.value(), never connectionId.
- **No transport metadata** (no socket, port, or connection reference).
- **seq**: auto-increment for deterministic FIFO ordering.