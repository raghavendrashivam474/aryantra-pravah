# A.D2.2 Architecture Note — Core Path Lifecycle Stabilization

**Sprint:** A.D2.2 (Runtime Integrity & Message Path Surgical Fixes)
**Parent:** A.D2 — Live Network Cockpit
**Date:** 2025-07-11
**Status:** Approved & Implemented

---

## 1. Existing Architecture & Limitation

In the original Phase 6 connectivity design (`S6.1`), communication paths were modeled as long-lived, transport-independent candidate records.
When a connection was opened, the corresponding candidate path was activated via `PeerPresenceBridge.activatePath()`.
When closed, it was deactivated via `PeerPresenceBridge.handleConnectionClosed()` and transitioned to `INACTIVE`.

### The Core Defect (Issue 1)
The path registry (`PeerConnectivity`) stores paths in a `Map<PathId, ConnectivityPath>`.
When `PeerConnectionCoordinator` opens a new connection, `PeerPresenceBridge.activatePath()` is invoked.
If the connection is a reconnect, a new connection ID is generated.
The `activatePath()` method searched for non-active paths and activated them.
However, because of duplicate connection signaling (e.g., Discovery + Join race), `activatePath()` was often called multiple times with slightly different connection parameters.
This bypassed the `alreadyActive` guard and generated a **second** active path with a new synthetic `PathId`, leaving the previous path in an orphaned `INACTIVE` state.

Furthermore, these `INACTIVE` paths were never pruned because `removePath()` was never invoked during normal disconnect sequences.
This resulted in **stale path accumulation** over repeated connect/disconnect cycles.

---

## 2. Proposed & Implemented Surgical Correction

Rather than patching the symptom at the UI mapper layer, we corrected the lifecycle directly inside the Core coordination layer (`PeerPresenceBridge.java`).

### 2.1 Solution A: Redundant Path Pruning on Activation

We updated `activatePath()` to clean up duplicate inactive paths matching the target transport scheme before attempting activation:

```java
// Keep at most one candidate/inactive path template
connectivity.allPaths().stream()
        .filter(p -> !p.isActive() && p.transportName().equalsIgnoreCase(targetScheme))
        .skip(1)
        .forEach(p -> {
            connectivity.removePath(p.pathId());
            logger.fine("Connectivity: Pruned redundant inactive path " + p.pathId());
        });

```

### 2.2 Solution B: Stale Path Pruning on Connection Closed

We updated `handleConnectionClosed()` to prune other inactive paths of the same scheme after the target path is deactivated:

```java
connectivity.allPaths().stream()
        .filter(p -> !p.isActive() && p.transportName().equalsIgnoreCase(targetScheme))
        .forEach(p -> connectivity.removePath(p.pathId()));

```

---

## 3. Impact Assessment

* **Contract Impact:** None. `PeerConnectivity` public methods are unchanged. `removePath()` is an existing public API.
* **Regression Impact:** Verified. All existing Core tests pass under path pruning.
* **Security Impact:** Zero. No cryptography or authentication boundaries are touched.
* **B.R2 Impact:** None. `TransitionBuffer` relies on `activePaths()` and `candidatePaths()` count. Pruning inactive paths ensures `candidatePaths().isEmpty()` checks behave predictably, preventing accidental transition-window buffering on dead routes.

---

## 4. Alternative Considered: UI-Only Filter

We considered ignoring the Core leak and simply filtering out `INACTIVE` paths in `DiagnosticModelMapper` if an `ACTIVE` path existed for the same transport.

* **Rejected:** This would violate the Golden Rule (Section 31). Hiding a Core memory leak in the UI mapper leaves the underlying runtime state corrupted.
* **Conclusion:** Surgical correction at the coordinate boundary is the only clean resolution.

```