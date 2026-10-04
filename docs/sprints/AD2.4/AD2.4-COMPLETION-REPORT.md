# A.D2.4 — Sprint Completion Report

**Sprint:** A.D2.4 — Diagnostic Runtime Integrity Surgical Remediation  
**Track:** Track A — Product Experience / Android Orchestration  
**Baseline:** `vA.D2.3-T` (`0427471`)  
**Status:** Implementation Complete — Automated Tests 100% Green — Ready for Physical Device Sign-Off  

---

## 1. Executive Summary

Sprint A.D2.4 successfully resolved the three runtime defects proven in the A.D2.3-T forensic audit:
1. **The Missing RX Defect (F1):** `PravahAndroidMessagingManager` now implements multi-listener dispatch via `CopyOnWriteArrayList<ProtocolListener>`, ensuring `DefaultApplicationMessagingService` and `DiagnosticActivity` both receive protocol callbacks without overwriting each other.
2. **Redundant Path Activations (F2):** Removed the two residual `presenceBridge.handlePeerConnected()` calls in `PravahAndroidMessagingManager.kt` that were missed in A.D2.3. The coordinator's `handleInboundMessage(JOIN)` is now the sole authoritative activation trigger.
3. **Dispatch Route Inconsistency (F3):** `DiagnosticModelMapper.kt` now normalizes `resolvedRoute` to strip any leading `/`, ensuring semantic and presentational consistency with path display.

All 44 Core Maven tests pass. All Android unit tests pass. The debug APK compiles cleanly. Zero Core architecture files were modified.

---

## 2. Commit Ledger

| Commit | Type | Description | Files Modified |
|---|---|---|---|
| `05977ea` | `fix(diagnostic)` | Support multiple protocol listeners, safe lifecycle unregistration, and remove residual activations | `PravahAndroidMessagingManager.kt`, `DiagnosticActivity.kt` |
| `088befd` | `fix(diagnostic)` | Normalize dispatch route representation in mapper | `DiagnosticModelMapper.kt` |

---

## 3. Detailed Fix Verification

### F1 — ProtocolListener Multi-Cast
- **Before:** `DiagnosticActivity` overwrote `downstreamListener`, orphaning `DefaultApplicationMessagingService` and preventing it from receiving `onMessageReceived`. Inbound messages reached the socket and were decoded, but the messaging service was never notified, so no `RX` event fired.
- **After:** `PravahAndroidMessagingManager` maintains a `CopyOnWriteArrayList<ProtocolListener>` and dispatches all events to every registered listener. When an inbound message arrives, `DefaultApplicationMessagingService.handleInboundProtocolMessage()` executes and notifies `ApplicationMessageListener`, which posts the `RX` event to LiveWire.
- **Lifecycle Safety:** `DiagnosticActivity.onDestroy()` unregisters its listener via `removeProtocolListener()`, preventing stale listener accumulation across Activity recreation cycles.

### F2 — Residual Peer Activation Removal
- **Before:** Lines 72–73 (in `onPeerJoined`) and line 94 (in `onMessageReceived`) redundantly called `presenceBridge.handlePeerConnected()`, triggering duplicate path state transitions.
- **After:** Both calls deleted. Peer activation occurs strictly when `PeerConnectionCoordinator.handleInboundMessage()` receives and validates a wire-level `JOIN` frame.

### F3 — Dispatch Route Normalization
- **Before:** `DiagnosticModelMapper.kt` rendered `manager.router.resolveConnectionId()` verbatim, displaying `/10.177.67.157:36681` while the path panel displayed `10.177.67.157:36681`.
- **After:** `resolvedRoute` is normalized with slash-stripping, rendering `10.177.67.157:36681` consistently across both panels.

---

## 4. Automated Verification Summary

| Gate | Target | Result |
|---|---|---|
| **Core Maven Tests** | 44 Core JVM tests (transport, coordinator, presence, router) | **44 / 44 PASS (100% Green)** |
| **Android Unit Tests** | `testDebugUnitTest` | **BUILD SUCCESSFUL** |
| **Android Debug APK** | `assembleDebug` | **BUILD SUCCESSFUL (`app-debug.apk` 4.57 MB)** |
| **Core Files Modified** | 0 Core files changed | **CONFIRMED** |

---

## 5. Definition of Done Checklist

- [x] F1: ProtocolListener multi-cast implemented with thread-safe list.
- [x] F1: DiagnosticActivity lifecycle unregistration implemented in `onDestroy()`.
- [x] F1: Inbound message delivery pipeline from coordinator to `DefaultApplicationMessagingService` restored.
- [x] F2: Residual `handlePeerConnected()` calls removed from `PravahAndroidMessagingManager.kt`.
- [x] F2: Coordinator's `handleInboundMessage(JOIN)` remains sole activation authority.
- [x] F3: Dispatch route rendered canonically (`host:port` without leading `/`).
- [x] Core Maven tests green (44/44).
- [x] Android unit tests green.
- [x] Debug APK builds cleanly.
- [x] Atomic git commits created per project convention.
- [x] Zero Core architecture files modified.
- [ ] Physical two-device validation (TCP-01 to TCP-05, Hybrid) per `AD2.4-VALIDATION-PLAN.md`.