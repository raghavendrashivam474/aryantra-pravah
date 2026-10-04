# Sprint A.D2.5 Implementation Plan
## Diagnostic Transport Drop Control & Bluetooth Lifecycle Hardening

**Sprint:** A.D2.5  
**Track:** Track A — Product Experience  
**Baseline:** vA.D2.4-BT-PV (`bcc9043`)  
**Status:** IMPLEMENTED & TESTED  

---

## 1. Objectives & Deliverables

### Primary Goal
Replace the hardcoded `DROP TCP` operation in the Pravaah diagnostic cockpit with a transport-selectable drop console (`DROP -> [TCP | BLUETOOTH]`) to enable controlled hybrid multi-path failover and recovery experiments without altering core routing or networking architecture.

### Secondary Goal
Formalize Bluetooth RFCOMM lifecycle telemetry by auditing the twelve forensic checkpoints (`BT-01` through `BT-12`) in `AndroidBluetoothRfcommTransport.kt` and promoting key lifecycle boundaries (`BT-07`, `BT-08`, `BT-12`) to permanent diagnostic status.

---

## 2. Non-Goals (Scope Boundaries)
- ❌ **No Core networking rewrites:** `PeerRouter`, `PathSelectionPolicy`, `CompositeTransport`, and `PeerConnectivity` remain strictly untouched.
- ❌ **No UI routing logic:** The UI never selects routes manually; `PathSelectionPolicy` automatically resolves routes upon path state changes.
- ❌ **No iQOO insecure RFCOMM fallback:** Security mode changes remain isolated to future transport sprints.
- ❌ **No fake state injection:** All UI metrics, path transitions, and selected routes derive directly from actual Pravaah state.

---

## 3. Technical Architecture & Delta

### 3.1 UI & Interaction Layer
- **Layout XML (`activity_diagnostic.xml`):**
  - Updated `btnSimulateDrop` label from `"DROP TCP"` to `"DROP"`.
- **Activity (`DiagnosticActivity.kt`):**
  - Replaced direct TCP invocation with `showDropTransportDialog()`.
  - Implemented an `AlertDialog` offering:
    1. **TCP** → invokes `simulateTransportDrop("tcp")`
    2. **BLUETOOTH** → invokes `simulateTransportDrop("bluetooth")`
    3. **CANCEL** → dismisses cleanly without action
  - Preserved `simulateTcpDrop()` as a compatibility wrapper delegating to `simulateTransportDrop("tcp")`.

### 3.2 Diagnostic Drop Execution Mechanism
- Iterates over active paths in `manager.connectivityRegistry.lookup(peer)`.
- Matches against the requested transport name (case-insensitive).
- Applies `conn.addPath(path.deactivate())` to mark the path `INACTIVE`.
- Emits real-time diagnostic events:
  - `OP: DROP requested target=<transport>`
  - `PATH: <pathId> (<transport>) ACTIVE->INACTIVE`
- Invokes `updateDashboard()`, triggering `PathSelectionPolicy` evaluation and immediate UI dashboard refresh.

---

## 4. Test Strategy

1. **Unit Testing (`DiagnosticTransportDropTest.kt`):**
   - Assert TCP path drop deactivates TCP and leaves Bluetooth untouched.
   - Assert Bluetooth path drop deactivates Bluetooth and leaves TCP untouched.
   - Assert drop targeting an unestablished transport safely no-ops.
2. **Core Maven Regression:**
   - Run full 414 test suite across Core modules.
3. **Android Gradle Unit Tests:**
   - Execute `testDebugUnitTest` verifying all Android unit tests pass.
