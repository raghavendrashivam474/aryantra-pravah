# A.D2 Block 2 — Deep File Inspection & Analysis Report

**Sprint:** A.D2 (Track A — Product Experience)
**Date of Execution:** 2025-07-11
**Objective:** Document the code-level analysis of core types, discovered signature gaps, and structural integration strategies used to build the live cockpit.

---

## 1. File-by-File Inspection Audit

### 1.1 `DiagnosticActivity.kt` (Baseline Analysis)
- **Lifecycle Setup:** Uses standard Android Activity lifecycles (`onCreate`, `onDestroy`).
- **Thread Model:** Manages background actions via a single-thread executor (`backgroundExecutor`) and UI dispatches via a main-thread handler (`handler`).
- **State Flow:** Calls `updateDashboard()` synchronously on actions and incoming callbacks. This rebuilds state via `DiagnosticModelMapper.map(manager, connectedPeerId)`.
- **Observation:** Interactive buttons in A.D1 were polling-based or updated only on manual clicks. No real-time network event listeners were registered.

### 1.2 `PravahAndroidMessagingManager.kt` (Integration Bridge)
- **Coordinator Boundary:** Instantiates `PeerConnectionCoordinator` inside the `coordinator` property.
- **Protocol Delegation:** Exposes `setProtocolListener(...)` directly on the `coordinator` member rather than on the top-level manager itself.
- **Transports Exposed:** Has `val tcpTransport: TcpTransport` and `val bluetoothTransport: AndroidBluetoothRfcommTransport`.

### 1.3 `TransitionBuffer.java` (observability Inspection)
- **Metric Verification:** Verified all metrics mentioned in **Section 18** (totalBuffered, totalFlushed, totalExpired, totalEvicted, totalRejected) exist as public getter methods.
- **Type Discovery:** Discovered `currentBytes()` and `size()` return standard Java `int` values, while the cumulative counters (`totalBuffered`, etc.) return `long` values.

---

## 2. Identified Compilation Gaps & Architectural Resolutions

During compilation and test runs, three critical discrepancies were identified between the conceptual rules and the concrete signatures. They were resolved while strictly respecting **Rule 1** (No Core modifications):

### 2.1 Gap 1: `setProtocolListener` Reference
- **Discrepancy:** The initial wiring attempt used `manager.setProtocolListener(...)`.
- **Discovery:** In the Android wrapper, the protocol listener registration is owned by the coordinator instance.
- **Resolution:** Updated `DiagnosticActivity.kt` to bind via `manager.coordinator.setProtocolListener(...)` cleanly.

### 2.2 Gap 2: Simulated TCP Drop Method Location
- **Discrepancy:** The initial implementation tried to call `manager.tcpTransport.closeConnection(connectionId)` or `manager.tcpTransport.dropConnection(...)`.
- **Discovery:** `closeConnection()` is marked `private` inside `TcpTransport.java`, preventing direct platform calls.
- **Resolution:** Re-aligned `simulateTcpDrop()` with the exact A.D1 backup mechanism. It uses the proper logical registry boundary:
  ```kotlin
  conn.addPath(path.deactivate())
  ```

This is architecturally superior because deactivating the path naturally triggers the registered 
`PathStateListener`, propagating deactivation down to the UI.

### 2.3 Gap 3: Buffer Bytes Type Mismatch

- **Discrepancy**: `DiagnosticModelMapper.kt` expected `TransitionBufferState.currentBytes` to be a 
  `Long` value, but `TransitionBuffer.java` returns `int`.
- **Resolution**: Explicitly mapped the signature using Kotlin's type-cast:

```Kotlin
currentBytes = tb.currentBytes().toLong()
```

> This guarantees interface compatibility with zero modifications to the core Java model.

## 3. Structural Integration Map

Based on these discoveries, the cockpit updates flow through these validated channels:

```text
Runtime Event
    │
    ├── Connectivity Path Change  ──► PathStateListener.onPathStateChanged()
    │                                     │
    │                                     ▼
    │                                 DiagnosticActivity.postEvent("PATH", ...)
    │
    └── Protocol State Change     ──► ProtocolListener.onPeerJoined/onPeerLeft()
                                          │
                                          ▼
                                      DiagnosticActivity.postEvent("JOIN/LEFT", ...)
```

No timers, no pollers, and no log-scraping filters are introduced. Every state transition is event-driven.
