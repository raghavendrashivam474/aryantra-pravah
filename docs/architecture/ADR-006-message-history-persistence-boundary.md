# ADR-006 — Message History Persistence Boundary

## Status
Accepted (S4.4)

## Problem
Application messages in S4.1–S4.3 are ephemeral. They exist only in memory
within `DefaultApplicationMessagingService.messageStates` and are lost on
process shutdown. Phase 4 requires durable conversation history.

## Current Limitation
- `ApplicationMessage` objects are created, routed, and garbage-collected.
- `MessageState` tracking is an in-memory `ConcurrentHashMap`.
- No persistence abstraction exists.
- The project has zero runtime dependencies (Phase 0 policy).

## Why the Existing Design Is Insufficient
Users expect conversation history to survive application restarts.
Without persistence, the messaging layer cannot support real-world usage.

## Alternatives Considered

### A. Inline SQLite in DefaultApplicationMessagingService
Rejected: violates dependency direction. The messaging service would
depend directly on a database API, making testing and Android porting harder.

### B. File-based serialization (ObjectOutputStream / JSON)
Rejected: fragile schema evolution, no query capability, poor ordering guarantees.

### C. MessageHistoryStore interface + pluggable implementations
**Selected.** The messaging layer depends on an abstraction. Concrete
implementations (InMemory for tests, SQLite for production, Android SQLite
for mobile) can be swapped without changing application logic.

### D. Full ORM (JPA / Hibernate)
Rejected: massive dependency footprint, overkill for a single table,
hurts Android portability.

## Selected Design
```text
ApplicationMessagingService
↓
MessageHistoryStore (interface)
↓
┌───────────────────────┬───────────────────────┐
│ InMemoryHistoryStore │ SqliteHistoryStore │
│ (tests / dev) │ (production desktop) │
└───────────────────────┴───────────────────────┘
```

## Why It Was Selected
- Preserves zero-dependency policy for the interface layer.
- SQLite dependency is isolated to a single implementation class.
- Android can provide its own implementation using `android.database.sqlite`.
- Testable without any database setup.
- Matches the brief's directive: "The messaging layer should depend on an abstraction."

## Existing APIs Affected
- `DefaultApplicationMessagingService` constructor will accept an optional
  `MessageHistoryStore` in a future sprint integration step.
- No changes to `ApplicationMessage`, `ConversationId`, `PeerId`, or any
  Phase 3 component.

## Compatibility Impact
- Additive only. No existing API signatures change.
- `MessageState` remains a separate concern from `ApplicationMessage`.
  The store tracks state alongside messages without modifying the domain model.

## Regression Risks
- None for S4.4 abstraction layer. Integration with
  `DefaultApplicationMessagingService` (future block) carries risk of
  altering message lifecycle ordering.

## Required Tests
- Contract tests against `MessageHistoryStore` interface.
- Restart durability test against `SqliteMessageHistoryStore` (Block 4).
- Integration test: send → persist → recreate store → retrieve (Block 4).

## Schema (for SQLite implementation)
```sql
CREATE TABLE messages (
    message_id       TEXT PRIMARY KEY,
    conversation_id  TEXT NOT NULL,
    sender_id        TEXT NOT NULL,
    content          TEXT NOT NULL,
    timestamp_ms     INTEGER NOT NULL,
    state            TEXT NOT NULL,
    sequence         INTEGER NOT NULL
);
CREATE INDEX idx_conv_seq ON messages(conversation_id, sequence);
```

- sequence: auto-increment for deterministic ordering when timestamps collide.
- sender_id: stores PeerId.value(), never connectionId.
- No transport data (no connection_id, socket, port, etc.).
