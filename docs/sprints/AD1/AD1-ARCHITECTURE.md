# Pravaah Diagnostic UI Foundation — Architecture & Handover (A.D1)

## 1. Architectural Blueprint
Sprint A.D1 established a modular, contract-driven Diagnostic UI system that separates presentation layout concerns from networking client internals. The core-facing boundary has been locked down to protect physical network validation states.

The data flow runs deterministically downward:

```text
       [ Pravaah Core Engine ]
                  │ (Registries, Routers, Sockets, Transports)
                  ▼
[ PravahAndroidMessagingManager.kt ]
                  │ (Existing Boundary Bridge)
                  ▼
   [ DiagnosticModelMapper.kt ] <─── connectedPeerId Context
                  │ (Translates core states to presentation models)
                  ▼
     [ DiagnosticState.kt ] (Immutable render-ready POCO snapshot)
                  │
    ┌─────────────┼─────────────┐ (Unidirectional Binder Streams)
    ▼             ▼             ▼
[NodeStatus] [Snapshot] [Peers/Paths] [LiveWire] [Operations]
```

---

## 2. Component Responsibility Matrix

| Module / Class | Specific Owner | Explicit Non-Owner |
|---|---|---|
| **`DiagnosticState`** | Immutable structures depicting the network status. | State mutations, business calculations, or network clients. |
| **`DiagnosticModelMapper`** | Unidirectional transformation logic from Core Registry/Router to `DiagnosticState`. | View updates, layout binding, or socket manipulations. |
| **`NodeStatusPanel`** | Formatting identity values and connection statuses for `tvStatus`. | Mutating manager variables or launching connections. |
| **`NetworkSnapshotPanel`** | Aggregating telemetry metric states (Peers, active path calculations). | Managing transport outbox or counting packet streams directly. |
| **`PeerPanel` & `PathPanel`** | Formatting peer-to-path tree structural representation lines. | Resolving dynamic core routing lookup policies. |
| **`LiveWirePanel`** | Output formatting, log timing, and locking view scroll states down. | Transport thread scheduling or buffer maintenance. |
| **`OperationsPanel`** | Binding enabled/disabled parameters to operations buttons. | Triggering raw connect loops or dispatching messages directly. |
| **`DiagnosticActivity`** | UI initialization, click bindings, and system-level lifecycle loops. | Low-level string layout rendering or formatting raw telemetry. |

---

## 3. Preservation of Physical Validation Contracts (§24)
- **TCP Path:** Connection handshakes, JOIN validation sequences, and RECIPROCAL auto-joins are strictly preserved in their validated original executor routines.
- **Bluetooth Path:** Adapter discovery mechanisms and RFCOMM MAC Address bindings are untouched, safeguarding dual-device execution.
- **Simulated Failover:** The simulation routine deactivates the target TCP path within the connectivity registry. This automatically triggers a UI model re-map, rendering the path "INACTIVE" in the panel.

---

## 4. Runway Guide for A.D2, A.D3, and A.D4

### A.D2 — Live Network Cockpit Integration
- To introduce dynamic node topologies and path telemetry metrics, add new properties inside `NetworkSnapshotState` (e.g., `txRate`, `rxRate`).
- Update `DiagnosticModelMapper.kt` to extract these values from the outbox/router, then pass them to `NetworkSnapshotPanel` for visualization.

### A.D3 — Live Wire + Operations Evolution
- Enhance the `LiveWireEvent` inside `DiagnosticState` with precise event categorizations.
- In `LiveWirePanel.kt`, substitute the text append operations with a lightweight recycler view or custom item adapters for styled terminal animations.

### A.D4 — Hardware Verification
- Since components are isolated from Android context dependencies, you can mock `DiagnosticState` patterns to run hardware-less automation tests on multiple virtual screens.

---

## 5. Definition of Done Checklist Status
- [x] Android UI decoupled from Core transport internals.
- [x] Data transformation localized inside `DiagnosticModelMapper`.
- [x] Modular UI elements cleanly separated under `.presentation`.
- [x] 100% of physical networking, discovery, Bluetooth RFCOMM, and failover capabilities preserved.
- [x] Added JVM unit testing with Mockito.
