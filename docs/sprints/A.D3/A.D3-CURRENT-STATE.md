# A.D3 Current State Audit

## 1. Existing UI Structure
The diagnostic interface (`DiagnosticActivity`) operates as a single screen built with:
*   **Fixed Header:** Displays the brand name "PRAVAAH" and subtitle "HYBRID MULTI-PATH DIAGNOSTIC NODE".
*   **Node Status Card (`tvStatus`):** Formatted monospace block showing current runtime status, Peer ID, active port, and connected peer.
*   **Live Network Topology (`tvMultiPathTopology`):** Renders an ASCII graph representation of local and remote nodes with their connected transport edges.
*   **Operations / Control buttons:** Actionable buttons to control the system (Start, Stop, Discover, Connect TCP, Connect BT, Simulate Drop, Send Payload).
*   **Live Wire Console (`tvLog`):** Monospace chronological scrolling log for events categorized under direction and detail tags.

## 2. State Mapping & Origins

| UI Concept / Component | Data Origin / Authority | Observer / Callback Hook |
| :--- | :--- | :--- |
| **Node Status** | `PravahAndroidMessagingManager` | Polling/Refresh on change via `updateDashboard()` |
| **Network Snapshot** | `PeerConnectivityRegistry` via `allConnectivities()` | Polling/Refresh on change via `updateDashboard()` |
| **Peer Information** | `PeerRegistry` | Polling/Refresh on change via `updateDashboard()` |
| **Path State / Selection**| `PeerConnectivityRegistry` | `PathStateListener` triggered on path changes |
| **Topology View** | Derived from connectivity records | Derived inside `DiagnosticModelMapper` |
| **Live Wire Events** | In-memory `CopyOnWriteArrayList<LiveWireEvent>` | Triggered via `ProtocolListener` (JOIN/LEFT) and `ApplicationMessageListener` (RX) |
| **Buffer Status** | Core `TransitionBuffer` inside `PeerRouter` | Captured via `PathStateListener` & mapped during `DiagnosticModelMapper.map()` |

## 3. Core Capabilities Readily Observable
*   **Active Peer Connections:** Through `registry.allPeers()`.
*   **Transport Availability:** Exact path statuses (`ACTIVE`, `CANDIDATE`, `INACTIVE`) and which path is currently selected as the active route by the router (`isSelected`).
*   **Buffer Metrics:** Message size, byte counts, total buffered, total flushed/expired/evicted.
*   **Physical Events:** Real-time logging of physical TCP connect/disconnect and Bluetooth state transitions.

## 4. Observed Technical Gaps & Limitations
*   **Missing Trust State in Manager:** `PravahAndroidMessagingManager` does not instantiate or wire `PeerTrustManager` in its coordinator or router constructor. It defaults to the fallback constructors (which pass `null` or empty policies).
*   **Missing Human-Readable Identity:** There is no display name contract. Peers are identified purely by random physical strings (`android-b9a8989e`).
*   **Message State Gaps:** The UI does not consume B.R3 outbox delivery semantics. Messages are pushed via fire-and-forget interfaces, and tracking the state sequence (`CREATED` -> `BUFFERED` -> `SENT` -> `DELIVERED`) is not mapped or monitored.