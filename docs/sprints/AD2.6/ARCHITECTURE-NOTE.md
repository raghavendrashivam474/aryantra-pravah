# A.D2.6 — Architecture Note: Dispatch Route Invariant & Transition Buffering Semantics

## 1. Dispatch Route Resolution Invariant
**Context:** When multi-path connectivity registry is active, the dispatch route displayed in diagnostic dashboards and consumed by routers must strictly reflect currently usable paths.

**Invariant Rule:**
> If `peerConnectivity.activePaths()` is empty, the effective dispatch route for that peer is **`NONE`**, regardless of historical records in legacy `PeerRegistry`.

**Track A Implementation:**
In `DiagnosticModelMapper.kt`:
```kotlin
val hasActive = conn.activePaths().isNotEmpty()
val resolvedRoute = if (hasActive) {
    try {
        val rawSelected = manager.router.resolveConnectionId(conn.peerId())
        val selected = if (rawSelected != null && rawSelected.startsWith("/")) rawSelected.substring(1) else rawSelected
        if (selected != null && selected.isNotEmpty()) selected else "NONE"
    } catch (_: Exception) {
        "NONE"
    }
} else {
    "NONE"
}
```

## 2. Protocol JOIN Routing vs Direct Transport Dispatch

**Context**: Logical protocol handshakes (such as JOIN and reciprocal JOIN) establish the identity-to-connection mapping that enables path activation.

**Invariant Rule**:

>Protocol handshakes must be dispatched directly over the newly established transport connection ID (via CompositeTransport.send
>(connectionId, frame)) rather than through PeerRouter.send(peerId, msg), which requires an already-ACTIVE path.