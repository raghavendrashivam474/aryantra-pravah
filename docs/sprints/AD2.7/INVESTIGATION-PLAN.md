# A.D2.7 — Investigation Plan & Execution Summary

## 1. Baseline Verification
- **Baseline Tag:** vA.D2.6
- **Branch:** feature/sprint-ad2.7
- **Date:** 2026-10-05 02:28
- **Initial State:** Working tree verified clean; baseline tag preserved.

## 2. Minimal Files Inspection
- [x] **Group A (Android Orchestration):**
  - DiagnosticActivity.kt: Connect actions, Drop simulator, message dispatch handlers.
  - PravahAndroidMessagingManager.kt: Connection triggers, sendJoin, 
eplyJoin, protocol listeners.
  - DiagnosticModelMapper.kt: State & path mapping invariants.
- [x] **Group B (Core Lifecycle Authority):**
  - PeerConnectionCoordinator.java: Transport listener callbacks, authoritative handleInboundMessage for JOIN.
  - PeerPresenceBridge.java: handlePeerConnected, ActivatePath, duplicate inactive pruning.
  - PeerConnectivity.java: addPath, 
emovePath, active & candidate path collections.
- [x] **Group C (Routing):**
  - PathSelectionPolicy.java: Scheme prioritization (	cp > Bluetooth > bt).
  - PeerRouter.java: Path selection, fallback route resolution, TransitionBuffer flush/discard hooks.

## 3. Investigation & Root Cause Proofs
- [x] **Single-Sided Lifecycle:** Traced inbound JOIN -> PeerConnectionCoordinator -> presenceBridge.handlePeerConnected() -> PeerConnectivity.activatePath().
- [x] **Simultaneous Initiation:** Proved that simultaneous initiation opens two physical sockets. Idempotent multi-path attachment tracks both paths without registry corruption, with PathSelectionPolicy resolving the active route deterministically.
- [x] **BT -> TCP Transition:** Proved that when TCP activates, PathSelectionPolicy immediately shifts active dispatch route from BT to TCP.
- [x] **DROP Transport & Asymmetric Messaging:** Proved that previously simulateTransportDrop only mutated local path state in memory without closing the underlying socket/stream, leaving the remote device transmitting into a zombie socket.

## 4. Remediation Executed
- Added TcpTransport.disconnect(connectionId) to close active TCP connections explicitly and trigger onConnectionClosed.
- Added PravahAndroidMessagingManager.dropTransport(peerId, scheme) to close physical transport links authoritatively.
- Wired DiagnosticActivity.simulateTransportDrop to use manager.dropTransport().
- Added comprehensive Core test suite ConnectionLifecycleStabilizationTest.java.