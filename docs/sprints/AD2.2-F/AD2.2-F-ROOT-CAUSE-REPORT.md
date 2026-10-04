# A.D2.2-F — Root-Cause Forensic Report

**Track:** A — Product Experience / Diagnostic  
**Baseline:** vA.D2.2 (Commit: `051fa93` / `e2c2cc3`)  
**Status:** Audit Complete — Ready for A.D2.3 Implementation  
**Golden Rule Adherence:** 100% Read-Only Forensic Analysis — 0 Production Source Files Modified  

---

## Issue 1: TCP Duplicate [TCP] ● ACTIVE Paths

### Observed Symptom
In the Diagnostic Cockpit, a single connected peer displays two simultaneous `[TCP] ● ACTIVE` path entries with identical visual endpoints (e.g., `192.168.1.50:8080`).

### Exact Reproduction
1. Start two Pravaah Android nodes on the same local Wi-Fi network.
2. Tap **DISCOVER** on Node 1; wait for discovery candidate to appear.
3. Tap **CONNECT TCP** on Node 1.
4. Observe the Path Panel: two active TCP rows appear for the same peer.

### Evidence
- In `PravahAndroidMessagingManager.kt:133`, `connectToTcp()` calls:
  `presenceBridge.handlePeerConnected(remotePeerId, "$remoteHost:$remotePort")`
  This uses a connection ID format without a leading slash: `"192.168.1.50:8080"`.
- `PeerPresenceBridge.activatePath()` executes Branch A: it finds the discovered candidate path (`PathId["path:peer:tcp:192.168.1.50:8080"]`) and activates it with connection ID `"192.168.1.50:8080"`.
- Meanwhile, `TcpTransport.attachActiveSocket()` in `TcpTransport.java:228` formats the socket connection ID using `socket.getRemoteSocketAddress().toString()`, which produces `"/192.168.1.50:8080"` (with a leading slash from Java's `InetSocketAddress`).
- The remote peer receives the socket connection and sends a wire `JOIN` message.
- `PeerConnectionCoordinator.handleInboundMessage()` receives the `JOIN` on connection `"/192.168.1.50:8080"` and calls:
  `presenceBridge.handlePeerConnected(peerId, "/192.168.1.50:8080")`.
- `PeerPresenceBridge.activatePath()` searches for inactive candidate paths for scheme `"tcp"`. Since Candidate Path 1 is already ACTIVE with connection ID `"192.168.1.50:8080"`, no inactive path matches.
- It then checks if `"/192.168.1.50:8080"` matches any existing active path's connection ID. Because `"192.168.1.50:8080" != "/192.168.1.50:8080"`, it fails the check and executes Branch B, creating a **second active path**:
  `PathId["path:peer:tcp:conn:/192.168.1.50:8080"]`.
- `DiagnosticModelMapper.kt:88` strips the leading slash when preparing the UI display string (`if (rawConn.startsWith("/")) rawConn.substring(1)`), causing both distinct Core paths to render identically as `192.168.1.50:8080` in the cockpit.

### Runtime Lifecycle

```text
[User Connect Click]
│
▼
PravahAndroidMessagingManager.connectToTcp("192.168.1.50", 8080)
│
├─► presenceBridge.handlePeerConnected(peer, "192.168.1.50:8080")
│ └─► Path 1 activated: PathId["path:peer:tcp:192.168.1.50:8080"]
│
└─► TcpTransport.connect() ──► Socket created
│
└─► attachActiveSocket() ──► id = "/192.168.1.50:8080"
│
▼ (Wire JOIN handshake received)
PeerConnectionCoordinator.handleInboundMessage(JOIN)
│
└─► presenceBridge.handlePeerConnected(peer, "/192.168.1.50:8080")
└─► String mismatch ("192.168.1.50:8080" != "/192.168.1.50:8080")
└─► Path 2 created: PathId["path:peer:tcp:conn:/192.168.1.50:8080"]
```

### Relevant Identifiers
- Path 1: `PathId["path:peer:tcp:192.168.1.50:8080"]` | connId: `"192.168.1.50:8080"`
- Path 2: `PathId["path:peer:tcp:conn:/192.168.1.50:8080"]` | connId: `"/192.168.1.50:8080"`

### Historical Comparison
- **vB.R2:** Core tests used uniform mock/synthetic connection strings (e.g., `"conn-1"`), so the mismatch never occurred in JVM tests.
- **vA.D2.1:** Added `cleanConnId()` in partial UI paths, but missed the origin in `connectToTcp()` and `TcpTransport`.

### Root Cause
1. **Connection ID String Asymmetry:** `TcpTransport` outputs raw `InetSocketAddress.toString()` (with `/`), while caller methods passed host-port without `/`.
2. **Premature Path Activation:** `PravahAndroidMessagingManager.connectToTcp()` called `presenceBridge.handlePeerConnected()` before the socket handshake and JOIN exchange completed, preempting the `PeerConnectionCoordinator` authoritative binding.

### Owning Boundary
- **Track A (Android Messaging Manager & Connection Orchestration)**

### Is it a Regression?
Yes. Introduced during the addition of manual Connect buttons in Track A (`A.D2` / `A.D2.1`).

### Proposed Correction for A.D2.3
1. Normalize connection IDs at the transport boundary (`TcpTransport` strips leading `/` when generating connection ID) OR ensure `PravahAndroidMessagingManager` normalizes connection strings uniformly via `cleanConnId()`.
2. Let `PeerConnectionCoordinator` remain the single authoritative caller of `presenceBridge.handlePeerConnected()` upon successful JOIN authentication, eliminating redundant premature calls in `connectToTcp()`.

### Why this is the smallest correction
It touches only connection ID normalization and removes 1 redundant call site, without altering any routing, protocol, or state-machine contracts.

### Required Tests
- JVM test: Connect TCP with `"/192.168.1.50:8080"` vs `"192.168.1.50:8080"` produces exactly 1 active path.
- Integration test: Discovered candidate -> manual connect -> JOIN produces exactly 1 active path in registry.

### Physical Validation Requirement
Run Android app, connect via TCP, verify exactly one `[TCP] ● ACTIVE` row appears.

---

## Issue 2: Bluetooth MAC / Endpoint Discrepancy & Stale Identity

### Observed Symptom
A successful physical Bluetooth connection to a peer displays an endpoint or synthetic peer record that does not reflect the authenticated remote peer's actual MAC or identity.

### Exact Reproduction
1. Open Bluetooth Chooser on Device 1.
2. Connect to a Bluetooth device (or attempt a connection that fails, then connect to the valid device).
3. Authenticate peer via JOIN exchange.
4. Observe the Path Panel: Path endpoint or synthetic peer record remains un-migrated or retains stale candidate endpoint.

### Evidence
- In `DiagnosticActivity.kt:180`, synthetic Bluetooth PeerIds are constructed dynamically:
  `val tempPeerId = PeerId.of("remote-bt-${selectedDevice.address.replace(":", "").takeLast(6)}")`
  (e.g., `remote-bt-5566A1`).
- In `PravahAndroidMessagingManager.kt:173`, the cleanup function `cleanOrphanedBtNode` hardcodes:
  `val tempBtPeer = PeerId.of("remote-bt-node")`
- Because `remote-bt-5566A1 != remote-bt-node`, `cleanOrphanedBtNode()` never found or pruned the temporary synthetic BT peer in the registry.
- Consequently, the synthetic peer and its paths remained orphaned in `PeerConnectivityRegistry`, continuing to appear in the Diagnostic UI.

### Relevant Identifiers
- Synthetic Peer ID generated: `remote-bt-XXXXXX`
- Cleanup target in Manager: `remote-bt-node` (MISMATCH)

### Owning Boundary
- **Track A (DiagnosticActivity & PravahAndroidMessagingManager synthetic identity protocol)**

### Is it a Regression?
Yes. Introduced in `A.D2.1` when dynamic MAC-derived synthetic IDs were added to `DiagnosticActivity` without updating the manager's cleanup method.

### Proposed Correction for A.D2.3
1. Align the synthetic BT peer naming between `DiagnosticActivity` and `PravahAndroidMessagingManager` (or pass the synthetic ID cleanly into the cleanup handler).
2. Ensure `DiagnosticActivity.onPeerJoined` explicitly purges any registered synthetic candidate paths from `connectivityRegistry` before binding the real `PeerId`.

### Why this is the smallest correction
Two-line alignment of synthetic ID string conventions; zero changes to Core routing or transport.

### Required Tests
- JVM / Android unit test: Create synthetic BT peer `remote-bt-XXXXXX`, trigger `onPeerJoined` with real `PeerId`, verify synthetic peer is 100% purged from `PeerConnectivityRegistry`.

### Physical Validation Requirement
Connect over Bluetooth between two Android devices and verify no residual `remote-bt-*` nodes appear in the Peer or Path panels.

---

## Issue 3: Repeated Bluetooth Activation Events

### Observed Symptom
LiveWire event log displays 2 to 3 consecutive `bluetooth null → ACTIVE` or `JOIN` events during a single physical Bluetooth connection establishment.

### Exact Reproduction
1. Initiate Bluetooth connection from Diagnostic Cockpit.
2. Monitor LiveWire events during link handshake.
3. Multiple identical activation entries appear in rapid succession.

### Evidence
- In `PravahAndroidMessagingManager.kt`, `presenceBridge.handlePeerConnected()` is called across 4 separate places:
  1. `connectToBluetooth()` (line 144)
  2. `sendJoin()` (line 156)
  3. `coordinator.setProtocolListener.onPeerJoined()` (line 52)
  4. `coordinator.setProtocolListener.onMessageReceived()` (line 61)
- Each call into `presenceBridge.handlePeerConnected()` evaluates `activatePath()` and re-notifies `PathStateListener` instances, generating duplicate LiveWire events for the same logical transition.

### Owning Boundary
- **Track A (PravahAndroidMessagingManager event wiring)**

### Is it a Regression?
Yes. Introduced during Track A event-driven diagnostic logging additions.

### Proposed Correction for A.D2.3
1. Deduplicate/guard `handlePeerConnected` calls in `PravahAndroidMessagingManager.kt` so only authoritative lifecycle events (`PeerConnectionCoordinator.handleInboundMessage` upon JOIN receipt) invoke the presence bridge.
2. In `PeerConnectivity.addPath()`, if `oldState == path.state() && oldConnectionId == path.connectionId()`, skip redundant listener notifications.

### Required Tests
- PathStateListener verification test: Calling `activatePath` twice with the same connection ID fires the listener exactly once.
