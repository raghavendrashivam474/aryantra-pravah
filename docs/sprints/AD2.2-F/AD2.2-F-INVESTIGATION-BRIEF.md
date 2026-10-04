# A.D2.2-F — Runtime Regression & Causality Audit Brief

## 1. Baseline
- **Branch:** main
- **Commit:** e2c2cc3 / 051fa93
- **Tag:** vA.D2.2
- **Audit Date:** 2026-10-04
- **Sprint Mode:** READ-ONLY Forensic Investigation (0 production code modifications)

## 2. Core Integrity & Safety Check
- **Maven Core Tests:** 22/22 Passing (100% Green)
- **Protected Boundaries Intact:**
  - Transport (TcpTransport, BluetoothRfcommTransport, CompositeTransport)
  - Routing (PathSelectionPolicy, PeerRouter)
  - Protocol (Message, MessageEncoder, FrameEncoder, ProtocolSessionManager)
  - Security & Discovery

## 3. Investigation Findings Summary

### Observation A: TCP Duplicate [TCP] ● ACTIVE Paths
- **Status:** ROOT CAUSE PROVEN
- **Causality:** Dual-call + Connection ID Format Mismatch:
  1. `PravahAndroidMessagingManager.connectToTcp()` calls `presenceBridge.handlePeerConnected(peer, "192.168.1.50:8080")` (no leading slash). This activates candidate Path 1.
  2. Physical `TcpTransport.attachActiveSocket()` registers socket with `socket.getRemoteSocketAddress().toString()` which yields `"/192.168.1.50:8080"` (with leading slash).
  3. Wire JOIN handler in `PeerConnectionCoordinator` calls `presenceBridge.handlePeerConnected(peer, "/192.168.1.50:8080")`.
  4. `PeerPresenceBridge.activatePath()` compares `"192.168.1.50:8080"` with `"/192.168.1.50:8080"`, finds no match, and generates a second active path: `PathId["path:peer:tcp:conn:/192.168.1.50:8080"]`.
  5. `DiagnosticModelMapper.kt` strips the leading slash for display, rendering two identical-looking `[TCP] ● ACTIVE 192.168.1.50:8080` rows.

### Observation B: Bluetooth MAC / Endpoint Discrepancy
- **Status:** ROOT CAUSE PROVEN
- **Causality:** Synthetic PeerId Mismatch in Cleanup Handler:
  1. `DiagnosticActivity.kt` generates synthetic PeerIds formatted as `PeerId.of("remote-bt-XXXXXX")`.
  2. `PravahAndroidMessagingManager.kt` hardcoded synthetic cleanup for `PeerId.of("remote-bt-node")`.
  3. Consequently, stale candidate paths or orphaned nodes retained endpoints from previous connection attempts or initial candidate bindings.

### Observation C: Repeated Bluetooth Activation Events
- **Status:** ROOT CAUSE PROVEN
- **Causality:** Redundant `handlePeerConnected` Invocations:
  1. `PravahAndroidMessagingManager.kt` invokes `presenceBridge.handlePeerConnected()` in `connectToBluetooth()`, inside `sendJoin()`, in `coordinator.onPeerJoined()`, and inside `coordinator.onMessageReceived()`.
  2. Each invocation triggers path state evaluation and listener callbacks.

## 4. Owning Boundaries
- **Observation A:** Track A (Android Messaging Manager Connection ID Normalization & premature invocation)
- **Observation B:** Track A (Synthetic BT PeerId naming mismatch between UI and Manager)
- **Observation C:** Track A (Redundant call sites in Android Messaging Manager wrapper)
