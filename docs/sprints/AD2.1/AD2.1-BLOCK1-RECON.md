# A.D2.1 Block 1 & 2 — Baseline Audit & Flow Reconnaissance Report

**Sprint:** A.D2.1 (Track A — Stabilization)
**Date of Execution:** 2025-07-11
**Baseline:** vA.D2 (commit ece3932)
**Objective:** Verify repository state, trace all diagnostic flows, and identify root causes for 10 physical validation issues before writing any fix.

---

## 1. Baseline Verification (Block 1)

### 1.1 Git State
- **Branch:** main
- **HEAD:** ece3932 (tag: vA.D2, origin/main)
- **Working tree:** CLEAN
- **Remote sync:** In sync with origin/main
- **Tags present:** AD1-baseline, vA.D2

### 1.2 Key File Inventory
All 15 mandatory inspection files confirmed present:

| Layer | File | Status |
|-------|------|--------|
| Android Activity | DiagnosticActivity.kt | OK |
| Android State | DiagnosticState.kt | OK |
| Android Mapper | DiagnosticModelMapper.kt | OK |
| Android Bridge | PravahAndroidMessagingManager.kt | OK |
| Android Layout | activity_diagnostic.xml | OK |
| Android Tests | DiagnosticModelMapperTest.kt | OK |
| Core Connectivity | PeerConnectivity.java | OK |
| Core Connectivity | PeerConnectivityRegistry.java | OK |
| Core Connectivity | ConnectivityPath.java | OK |
| Core Connectivity | PathState.java | OK |
| Core Connectivity | PathStateListener.java | OK |
| Core Connectivity | PathSelectionPolicy.java | OK |
| Core Routing | PeerRouter.java | OK |
| Core Lifecycle | PeerConnectionCoordinator.java | OK |
| Core B.R2 | TransitionBuffer.java | OK |

### 1.3 Test Baseline
- **Result:** BUILD SUCCESSFUL
- **Tests:** 33/33 passing
- **APK:** 4.47 MB debug artifact present

### 1.4 Artifact Cleanup
- 1 stale `.bak` file found and flagged: `DiagnosticActivity.kt.bak`
- Purged during Block 3-Fix2 to resolve AAPT2 resource merge failure

---

## 2. Flow Tracing (Block 2)

### 2.1 Bluetooth Flow (Issue A)

**Traced path:**

```text
btnConnectBt.setOnClickListener
-> showBluetoothDeviceChooser()
-> BluetoothAdapter.getDefaultAdapter()
-> adapter.bondedDevices.toList()
-> bondedDevices.firstOrNull { name.contains("android") } ?: bondedDevices.first()
-> manager.connectToBluetooth(device.address, targetPeerId)
-> bluetoothTransport.connect(remoteMac)
-> presenceBridge.handlePeerConnected(remotePeerId, connId)
-> sendJoin(remotePeerId, connId)
```

**Finding:** The fallback `bondedDevices.first()` selects ANY paired device when no device name contains "android". This caused connections to `ZEB-ENVY 2` and `realme Buds T200 Lite`.

**Additionally:** The `targetPeerId` was hardcoded as `PeerId.of("remote-bt-node")` regardless of which device was selected, meaning all BT connections shared the same logical identity.

### 2.2 TCP Connect Flow (Issue D)

**Traced path:**

```text
btnConnectTcp.setOnClickListener
-> manager.discoveredPeers.firstOrNull()
-> connectTcp(host, port, targetPeerId)
-> manager.connectToTcp(host, port, targetPeerId)
-> tcpTransport.connect(remoteHost, remotePort)
-> presenceBridge.handlePeerConnected(remotePeerId, connId)
-> manager.sendJoin(targetPeerId, connId)
-> manager.replyJoin(targetPeerId)
```

**Finding:** No guard checks whether an ACTIVE TCP path already exists for the target peer. The button unconditionally initiates a new socket connection, which fails if the endpoint already has an active connection.

### 2.3 Path Lifecycle (Issue C)

**Traced path:**

```text
PeerConnectivity.addPath(ConnectivityPath path)
-> paths.put(path.pathId(), path) // Map<PathId, ConnectivityPath>
-> if (oldState != path.state()) notifyListeners(path, oldState)

ConnectivityPath.deactivate()
-> returns ConnectivityPath.inactive(same pathId, same peerId, same transport, same endpoint)

PeerConnectivity.removePath(PathId pathId)
-> paths.remove(pathId)
-> notifyListeners(path.deactivate(), path.state())
```

**Finding:** `addPath()` keys by `PathId`. When a path is deactivated via `conn.addPath(path.deactivate())`, it replaces the entry under the SAME PathId. However, a NEW connection creates a NEW PathId, so the old deactivated path persists under its old key alongside the new active path. Result: two TCP entries for the same peer.

`removePath()` exists but is not called during normal disconnect flows — it would need to be invoked by the connection lifecycle coordinator.

### 2.4 Dispatch Route Source (Issue E)

**Traced path:**

```text
DiagnosticModelMapper.map()
-> manager.router.resolveConnectionId(conn.peerId())
-> PeerRouter.resolveConnectionId(destination)
-> connectivityRegistry.lookup(destination)
-> activePaths()
-> selectionPolicy.selectPath(destination, activePaths)
-> returns selected.get().connectionId()
-> OR returns null if no active paths
-> mapper: if (selected != null && selected.isNotEmpty()) selected else "NONE"
```

**Finding:** `resolveConnectionId()` correctly returns `null` when no active paths exist. The mapper converts this to "NONE". However, `PathPanel.formatDispatchRoute()` displayed "NONE" without clarifying that it means "no active path available", causing operator confusion.

### 2.5 Scroll/Layout Structure (Issue B)

**Traced structure:**

```text
LinearLayout (root, vertical, no scroll)
├── LinearLayout (header)
├── TextView (tvStatus)
├── TextView (topology header)
├── TextView (tvMultiPathTopology)
├── LinearLayout (buttons row 1)
├── LinearLayout (buttons row 2)
├── LinearLayout (message input)
├── TextView (event stream header)
└── ScrollView (scrollLog) <-- ONLY scrollable region
└── TextView (tvLog)
```

**Finding:** The root container is a plain `LinearLayout` with no outer `ScrollView`. On small portrait screens, the topology, paths, buffer, and operations sections overflow the viewport. Only the LiveWire log area scrolls.

### 2.6 LiveWire Event Coverage (Issue H)

**Traced event sources:**
| Event Type | Source | Detail Format |
|-----------|--------|---------------|
| PATH | PathStateListener | `transportName transitioned from prev to new` |
| BUFFER | PathStateListener (conditional) | `Holding N messages (M bytes)` |
| JOIN | ProtocolListener.onPeerJoined | `Peer X joined` |
| RX | ProtocolListener.onMessageReceived | `RECV [X]: msgId` |
| LEFT | ProtocolListener.onPeerLeft | `Peer X left` |
| SYSTEM | Various Activity methods | Free-form |
| ERROR | Various Activity methods | Free-form |
| TX | sendPayloadMessage | `SENT [X]: content` |

**Finding:** PATH events showed `transitioned from X to Y` which is verbose. Missing: explicit ROUTE CHANGE event when selected path switches.

### 2.7 STOP/START Lifecycle (Issue J)

**Traced path:**

```text
stopRuntime()
-> backgroundExecutor.execute { manager.stop() }
-> handler.post { addSystemEvent("STOPPED"); updateDashboard() }

onDestroy()
-> backgroundExecutor.shutdown()
-> manager.close()
```

**Finding:** Lifecycle appears correct. `manager.close()` tears down coordinator and transports. Dashboard update after stop shows clean state. No fix needed.

### 2.8 Peer Identity (Issue F)

**Traced path:**

```text
DiagnosticModelMapper
-> TopologyNode label = peerIdVal.take(12) + ".."
-> "android-1fa286bc" (16 chars) -> "android-1fa2.." (12+2 chars)

PeerPanel.formatPeerHeader()
-> "Peer: state.peerId[{state.sessionState}]"
-> Full PeerId preserved in path connections section
```

**Finding:** Topology box truncation at 12 chars loses distinguishing information. The path connections section shows the full PeerId, creating inconsistency.

---

## 3. Suspected Root Cause Summary

| Issue | Category | Root Cause | Owner Layer | Fix Complexity |
|-------|----------|-----------|-------------|---------------|
| A | BT Selection | Auto-fallback to first bonded device | Android Activity | Low |
| B | Scrolling | No outer ScrollView in layout | Android XML | Low |
| C | Duplicate Paths | New PathId on reconnect, old not pruned | Core (deferred) / UI sort | Medium |
| D | TCP Reconnect | No active-path guard before connect | Android Activity | Low |
| E | Route Semantics | "NONE" displayed without explanation | Android Panel | Low |
| F | Identity | 12-char truncation too aggressive | Android Mapper | Trivial |
| G | Label | "ACTIVE" header with inactive paths | Android XML | Trivial |
| H | LiveWire | Verbose transition format | Android Activity | Low |
| I | Stale State | Paths not removed on disconnect | Core (deferred) | High |
| J | Lifecycle | No defect found | N/A | None |

---

## 4. Resolution Priority

1. **CRITICAL (Block 3):** B (scroll), A (BT chooser) — highest user impact
2. **HIGH (Block 3):** D (TCP guard), F (identity), G (label) — low risk, high clarity
3. **MEDIUM (Block 4):** C (path sorting), E (route semantics), H (LiveWire) — mapper refinements
4. **DEFERRED:** I (stale state) — requires Core lifecycle changes, out of stabilization scope
5. **NO ACTION:** J (lifecycle) — verified correct
