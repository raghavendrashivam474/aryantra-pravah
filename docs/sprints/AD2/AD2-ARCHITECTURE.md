# A.D2 Architecture — Live Network Cockpit

**Sprint:** A.D2 (Track A — Product Experience)
**Baseline:** vA.D1
**Tag:** vA.D2

---

## 1. Architectural Layer Diagram
┌─────────────────────────────────────────────────────────┐
│ PRAVAAH CORE (Java) │
│ /src/main/java/ │
│ │
│ ┌──────────────┐ ┌──────────────┐ ┌───────────────┐ │
│ │ PeerRouter │ │ Connectivity │ │ Transition │ │
│ │ │ │ Registry │ │ Buffer (B.R2) │ │
│ │ PathSelection│ │ │ │ │ │
│ │ Policy │ │ PathState │ │ totalBuffered │ │
│ │ │ │ Listener │ │ totalFlushed │ │
│ └──────┬───────┘ └──────┬───────┘ └───────┬───────┘ │
│ │ │ │ │
│ ┌──────┴─────────────────┴───────────────────┴───────┐ │
│ │ ProtocolListener / PathStateListener │ │
│ │ (Event Boundaries — READ ONLY from UI) │ │
│ └──────────────────────┬─────────────────────────────┘ │
└─────────────────────────┼───────────────────────────────┘
│
│ UNTOUCHED BY A.D2
│
┌─────────────────────────┼───────────────────────────────┐
│ ANDROID BRIDGE (Kotlin) │
│ │
│ ┌──────────────────────┴─────────────────────────────┐ │
│ │ PravahAndroidMessagingManager │ │
│ │ │ │
│ │ .connectivityRegistry (PeerConnectivityRegistry) │ │
│ │ .router (PeerRouter) │ │
│ │ .coordinator (PeerConnectionCoordinator)│ │
│ │ .tcpTransport (TcpTransport) │ │
│ │ .bluetoothTransport (AndroidBluetoothRfcomm) │ │
│ │ .localPeerId (PeerId) │ │
│ │ .isRunning / .isDiscovering / .boundPort │ │
│ │ .getSessionState() .sendText() │ │
│ └──────────────────────┬─────────────────────────────┘ │
└─────────────────────────┼───────────────────────────────┘
│
│ SINGLE UI BOUNDARY
│
┌─────────────────────────┼───────────────────────────────┐
│ A.D2 UI LAYER (Kotlin) │
│ │
│ ┌──────────────────────┴─────────────────────────────┐ │
│ │ DiagnosticModelMapper (object) │ │
│ │ │ │
│ │ Reads: manager.connectivityRegistry │ │
│ │ manager.router.resolveConnectionId() │ │
│ │ manager.router.transitionBuffer() │ │
│ │ manager.getSessionState() │ │
│ │ path.state().name (Core PathState enum) │ │
│ │ │ │
│ │ Produces: DiagnosticState (immutable data class) │ │
│ └──────────────────────┬─────────────────────────────┘ │
│ │ │
│ ┌──────────────────────┴─────────────────────────────┐ │
│ │ DiagnosticState │ │
│ │ │ │
│ │ nodeStatus NodeStatusState (A.D1) │ │
│ │ snapshot NetworkSnapshot (A.D1) │ │
│ │ peers List<PeerItem> (A.D1) │ │
│ │ paths List<PathItem> (A.D1+A.D2) │ │
│ │ operations OperationsState (A.D1) │ │
│ │ liveWireEvents List<LiveWireEvent>(A.D2 NEW) │ │
│ │ topology TopologyState (A.D2 NEW) │ │
│ │ bufferState TransitionBuffer (A.D2 NEW) │ │
│ └──────────────────────┬─────────────────────────────┘ │
│ │ │
│ ┌──────────────────────┴─────────────────────────────┐ │
│ │ DiagnosticActivity │ │
│ │ │ │
│ │ Subscribes: │ │
│ │ PathStateListener -> postEvent("PATH", ...) │ │
│ │ ProtocolListener -> postEvent("JOIN/RX/LEFT") │ │
│ │ ApplicationMessage -> postEvent("TX", ...) │ │
│ │ DiscoveryListener -> postEvent("SYSTEM", ...) │ │
│ │ │ │
│ │ Threading: │ │
│ │ backgroundExecutor -> network ops │ │
│ │ handler.post{} -> UI updates │ │
│ └──────────────────────┬─────────────────────────────┘ │
│ │ │
│ ┌──────────────────────┴─────────────────────────────┐ │
│ │ Presentation Panels │ │
│ │ │ │
│ │ NodeStatusPanel (A.D1 preserved) │ │
│ │ NetworkSnapshotPanel (A.D2 upgraded: ASCII topo) │ │
│ │ PeerPanel (A.D1 preserved) │ │
│ │ PathPanel (A.D2 upgraded: state enums) │ │
│ │ LiveWirePanel (A.D2 upgraded: categories) │ │
│ │ OperationsPanel (A.D1 preserved) │ │
│ └────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘

text


---

## 2. Key Design Decisions

### 2.1 Event-Driven Over Polling (Section 15)
A.D2 uses real listener callbacks instead of timer-based polling:
- `PathStateListener.onPathStateChanged()` fires from Core connectivity layer
- `ProtocolListener.onPeerJoined/onMessageReceived/onPeerLeft` fires from protocol layer
- All callbacks post to `Handler(Looper.getMainLooper())` for thread safety

### 2.2 PathState as String in UI Model (Section 9)
`PathItemState.pathState` is a `String`, not the Core `PathState` enum.
**Rationale:** Prevents the Android UI module from coupling directly to the Core Java enum. The mapper translates `path.state().name` into the string.

### 2.3 Selected Route Computed in Mapper (Section 10)
`isSelected` is determined by comparing `router.resolveConnectionId(peer)` against each path's `connectionId`.
**Rationale:** No second path-selection algorithm in the UI. The authoritative selection remains `PathSelectionPolicy` in Core.

### 2.4 TransitionBuffer Read-Only (Section 18)
A.D2 reads `TransitionBuffer` counters through `manager.router.transitionBuffer()`.
**Rationale:** Observation only. No mutation. B.R2 behavior remains untouched.

### 2.5 simulateTcpDrop Uses Core API (Section 14)
`conn.addPath(path.deactivate())` replaces the path in `PeerConnectivity`, triggering `PathStateListener` naturally.
**Rationale:** Uses the same boundary as real path failures. No transport-level hacks.

---

## 3. Hard Rules (Section 32)

| # | Rule | Rationale |
|---|------|-----------|
| 1 | No Core networking changes for UI | Core is shared; UI is platform-specific |
| 2 | No DiagnosticModelMapper bypass | Mapper is the architectural firewall |
| 3 | No fabricated telemetry | "Pravaah does not hide the network" |
| 4 | No second path-selection mechanism | PathSelectionPolicy is authoritative |
| 5 | No log scraping for state | Fragile; use typed event boundaries |
| 6 | No polling if events exist | Event-driven is cheaper and more accurate |
| 7 | No SX.3 authentication changes | Separate sprint priority |
| 8 | No B.R2 redesign | B.R2 is already implemented |
| 9 | No transition-hold mechanism | Reserved for future validation sprint |
| 10 | Document before changing boundaries | ADR process for architectural changes |

---

## 4. Thread Model
Transport I/O Thread (Core)
│
├── PathStateListener callback
│ │
│ ▼
│ postEvent() ──► liveEventsList.add() [CopyOnWriteArrayList, thread-safe]
│ │
│ ▼
│ handler.post { liveWirePanel.appendEvent(); updateDashboard() } [Main Thread]
│
├── ProtocolListener callback
│ │
│ ▼
│ postEvent() ──► same flow
│
Background Executor (Activity)
│
├── startRuntime() / stopRuntime()
├── connectTcp() / connectToBluetooth()
├── simulateTcpDrop()
├── sendPayloadMessage()
│ │
│ ▼
│ handler.post { updateDashboard() } [Main Thread]
│
Main/UI Thread
│
├── updateDashboard()
│ │
│ ▼
│ DiagnosticModelMapper.map() [reads Core state synchronously]
│ │
│ ▼
│ panel.render() [updates TextViews]

text


---

## 5. Lifecycle Management

| Lifecycle Event | Action |
|-----------------|--------|
| `onCreate` | Initialize manager, register all listeners, bind UI |
| `onDestroy` | `backgroundExecutor.shutdown()`, `manager.close()` |
| Listener cleanup | Handled by `manager.close()` which tears down coordinator and transports |

**Known limitation:** `PathStateListener` and `ProtocolListener` are registered but not explicitly removed in `onDestroy`. This is safe because `manager.close()` destroys the underlying objects. If the Activity is recreated (rotation), a new manager is created.

---

## 6. Compatibility Notes

- Core engine: Java 17+ (records, sealed classes)
- Android UI: Kotlin, minSdk 26, targetSdk 34
- Test framework: JUnit 5 (Jupiter)
- Build: Gradle 8.4, AGP 8.x
- All Kotlin files written UTF-8 without BOM (PowerShell 5.1 compatible)
