# ADR-010 — Per-Recipient Reliable Group Delivery

## Status
Accepted

## Context
Phase 4 introduced group messaging with application-layer fan-out.
Phase 5.1–5.4 provided reliable delivery for single-recipient messages only.
Group messages had no per-recipient delivery tracking: one global state could not
represent partial delivery (e.g., Bob received, Charlie did not).

## Decision
Introduce `GroupDeliveryOutbox` as a parallel abstraction to `DeliveryOutbox`.

- Each group message generates independent delivery intents per recipient `PeerId`.
- Composite key: `(messageId, recipientPeerId)`.
- Per-recipient states: PENDING, COMPLETED, ABANDONED (reuses `OutboxState`).
- Per-recipient attempt counts for bounded retry.
- `DeliveryRetryManager.retryPendingForPeer()` sweeps both direct and group outboxes.
- On reconnect, only the reconnected peer's pending group entries are retried.
- Completed recipients are never resent.
- ACK correlation uses existing `messageId` + sender identity from the protocol frame.

## Architecture

```text
Logical Group Message M1
↓
GroupDeliveryOutbox
├── M1 → Bob COMPLETED
├── M1 → Charlie PENDING
└── M1 → David ABANDONED
```

The existing single-recipient `DeliveryOutbox` is unchanged.

## Persistence
`SqliteGroupDeliveryOutbox` uses a `group_outbox` table with
`UNIQUE(message_id, recipient_peer_id)`.

## Compatibility
Additive only. `DefaultApplicationMessagingService` gains an optional
`GroupDeliveryOutbox` constructor parameter (defaults to `InMemoryGroupDeliveryOutbox`).

## Future
Group-level aggregate state derivation, per-recipient read receipts,
and multi-device group synchronization are deferred.