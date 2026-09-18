# ADR-009 — Bounded Delivery Retry and Abandonment

## Status
Accepted

## Context
Phase 5.1–5.3 introduced reliable delivery with an outbox and retry-on-reconnect model.
Retry was unbounded: a permanently unavailable peer caused its pending entries to be
retried indefinitely on every future reconnection event.

## Decision
Introduce a bounded retry policy with three outbox states: PENDING, COMPLETED, ABANDONED.

- `OutboxState.ABANDONED` signals that automatic retry has been exhausted.
- `OutboxEntry.attemptCount` tracks dispatch attempts per entry.
- `maxAttempts` is configurable (default: 3) via `DeliveryRetryManager` constructor.
- ABANDONED entries are excluded from `findPending()` and `findPendingForPeer()`.
- Attempt count and state survive process restart via SQLite persistence.
- Original `messageId` is never changed across retries (identity invariance).

## State Model

**OutboxState**: PENDING → COMPLETED (on ACK)
PENDING → ABANDONED (on max attempts)

`MessageState` remains unchanged (ADR-008 separation preserved).

## Persistence
`SqliteDeliveryOutbox` schema extended with `attempt_count INTEGER NOT NULL DEFAULT 0`.
Migration-safe via `ALTER TABLE ... ADD COLUMN` with `DEFAULT 0`.

## Compatibility
Backward-compatible. Existing 5-arg `OutboxEntry` constructor defaults `attemptCount` to 0.
Existing `DeliveryRetryManager` 5-arg constructor defaults `maxAttempts` to 3.

## Future
Backoff strategies, per-message retry limits, and manual re-queue of ABANDONED entries
are deferred to future sprints.