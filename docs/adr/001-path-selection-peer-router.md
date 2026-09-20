# ADR 001: Path Selection in PeerRouter

## Status
Proposed & Accepted (S6.5 Sprints)

## Context
Aryntra Pravah introduced a rich connectivity model in S6.1–S6.3 (`PeerConnectivityRegistry` and `ConnectivityPath`). However, the messaging system (`PeerRouter`) remains bound to the Phase 3 design, routing messages solely based on `PeerRegistry` and a single static `connectionId` associated with a `PeerRecord`.

To fulfill S6.5, we must enable the messaging layer to dynamically select between multiple active connectivity paths for a given `PeerId` without forcing the application layer to specify a `PathId` or a transport name.

## Proposed Alternatives
1. **Option A (No Change to Router):** Update `PeerRegistry` internally to look up paths. However, `PeerRegistry` deals with logical peer presence and identity, not active networking pathways, resulting in high coupling and violating single-responsibility rules.
2. **Option B (Additive PeerRouter Extension):** Extend `PeerRouter` with an additive constructor that accepts an optional `PeerConnectivityRegistry`. When present, routing resolves the optimal connection via the connectivity registry. If absent, or if no connectivity record is found, it falls back seamlessly to the legacy `PeerRecord` route.
3. **Option C (Separate Path Resolver Middleware):** Introduce an intermediate interface `PathResolver`. While highly decoupled, this increases architectural complexity and boilerplate for a system where `PeerRouter` is already the designated routing coordinator.

## Decision
We choose **Option B (Additive PeerRouter Extension)**. This minimizes code churn, ensures 100% backward compatibility for existing application code and tests, and cleanly centralizes multi-path resolution inside the routing coordinator.

### Path Selection Policy
- **Deterministic Sort:** If a peer has multiple active paths, they are sorted alphabetically by their unique `PathId` string representation. The first path is selected. This guarantees fully deterministic, cross-platform behavior.
- **Exclusion of Candidates:** Paths in the `CANDIDATE` or `INACTIVE` state are ignored for message sending.
- **Legacy Fallback:** If the connectivity registry is not present, or if it does not contain any active paths for the destination peer, we query the original `PeerRegistry` for `PeerRecord.connectionId()`.

## Compatibility and Migration Impact
- **Callers:** No modification to application code is needed; existing code targets `PeerId` and calls `PeerRouter.send(PeerId, Message)` exactly as before.
- **Constructors:** The existing `PeerRouter(PeerRegistry, Transport)` constructor is retained, internally delegating to the new constructor with a `null` registry parameter.
- **Tests:** Existing routing integration tests will remain green as they fall back to the legacy resolution path.

## Verification Strategy
We will implement dedicated tests to prove:
1. Deterministic selection with a single active path.
2. Deterministic selection with multiple active paths.
3. Complete exclusion of candidates and inactive paths.
4. Correct fallback to the legacy connection ID when no connectivity record exists.