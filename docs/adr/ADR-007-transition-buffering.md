# ADR-007: Transition-Window In-Flight Buffering

## Status
Accepted — B.R2 Sprint

## Context
B.R1-TWV experimentally established a 100% message loss rate during the
connectivity transition window (ACTIVE -> CANDIDATE -> ACTIVE). The gap
occurs in PeerRouter.send() when peerConn.activePaths() returns empty
while an alternate path is in CANDIDATE state transitioning to ACTIVE.

## Decision
Introduce a bounded, TTL-aware TransitionBuffer owned by PeerRouter that
captures messages during the no-ACTIVE-path transition window and flushes
them when a viable path becomes ACTIVE via a new PathStateListener callback.

### Ownership
- **TransitionBuffer**: lives in `com.aryntra.pravah.peer` alongside PeerRouter
- **PathStateListener**: lives in `com.aryntra.pravah.connectivity` as a minimal
  additive callback interface on PeerConnectivityRegistry
- **Flush trigger**: PeerConnectivityRegistry fires PathStateListener when a
  path transitions to ACTIVE; PeerRouter subscribes and flushes

### Why NOT DeliveryRetryManager
- DRM operates at ApplicationMessage level (reconstructs from history store)
- DRM is peer-reconnection-driven, not path-state-driven
- DRM + buffer would cause duplicate delivery
- Transition window is a routing-layer concern, not application-layer

### Why NOT CompositeTransport
- Transport operates on connectionIds, not PeerIds
- No concept of path state or connectivity transitions
- Would violate layer isolation (ADR-004)

## Constraints
- Buffer is bounded: max 64 messages, max 256KB, 10s TTL
- Buffer is NOT an offline queue — only active during transition window
- PathSelectionPolicy remains authoritative for flush routing
- Existing active-to-active failover is untouched
- No protocol, transport, or security contract changes

## Consequences
- PeerRouter gains a TransitionBuffer field (nullable for backward compat)
- PeerConnectivityRegistry gains addPathStateListener() (additive, no break)
- PeerConnectivity.addPath() fires listeners on state change (additive)
- New test suite: TransitionBufferTest, RouterTransitionIntegrationTest