# Sprint A.D2.5 Validation Evidence Record
## Diagnostic Transport Drop Control & Bluetooth Lifecycle Hardening

**Sprint:** A.D2.5  
**Baseline:** vA.D2.4-BT-PV (`bcc9043`)  
**Date:** March 2025  
**Author:** Pravaah Diagnostic Team  

---

### 1.2 Android Unit Test Suite (`:app:testDebugUnitTest`)

```text
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 40s
24 actionable tasks: 14 executed, 10 up-to-date
Result: ALL PASS
Included DiagnosticTransportDropTest with 3 targeted tests:
testDropTcpDeactivatesTcpPathPreservingBluetooth — PASS
testDropBluetoothDeactivatesBluetoothPathPreservingTcp — PASS
testDropTargetNotFoundDoesNotAffectOtherPaths — PASS

```

## 2. Experiment Validation Matrices

### Experiment 1: DROP TCP Failover to Bluetooth (§25 Test 1)

| Step | Action | Expected State | Actual State | Status |
| --- | --- | --- | --- | --- |
| **1.1** | Establish TCP & Bluetooth | TCP: ACTIVE [SELECTED], BT: ACTIVE | Both paths ACTIVE, TCP selected route | **PASS** |
| **1.2** | Tap [DROP] → Select TCP | OP: DROP requested target=tcp<br>

<br>PATH: tcp ACTIVE->INACTIVE | Event logged, TCP path deactivated | **PASS** |
| **1.3** | Policy Route Evaluation | Bluetooth selected automatically | BT becomes selected route | **PASS** |
| **1.4** | Transmit Payload | Send "BT-FAILOVER-001" | Routed over Bluetooth transport successfully | **PASS** |

### Experiment 2: DROP BLUETOOTH Failover to TCP (§25 Test 2)

| Step | Action | Expected State | Actual State | Status |
| --- | --- | --- | --- | --- |
| **2.1** | Restore TCP (both ACTIVE) | TCP: ACTIVE, BT: ACTIVE | Both paths ACTIVE | **PASS** |
| **2.2** | Tap [DROP] → Select BLUETOOTH | OP: DROP requested target=bluetooth<br>

<br>PATH: bt ACTIVE->INACTIVE | Event logged, BT path deactivated | **PASS** |
| **2.3** | Policy Route Evaluation | TCP selected automatically | TCP becomes selected route | **PASS** |
| **2.4** | Transmit Payload | Send "TCP-FAILOVER-001" | Routed over TCP transport successfully | **PASS** |

### Experiment 3: Bluetooth Lifecycle & BT-12 Verification (§25 Test 3)

| Boundary | Checkpoint | Description | Status |
| --- | --- | --- | --- |
| **Connection Entry** | BT-07 | `socket.connect()` started with target MAC | Verified permanent INFO log |
| **Connection Exit** | BT-08 | `socket.connect()` result (SUCCESS/FAILED) | Verified permanent INFO log |
| **Socket Teardown** | BT-12 | Reader EOF (-1) or IOException | Verified permanent INFO/WARN log |

### Experiment 4: Recovery Behavior (§25 Test 4)

* **Observation:** After a diagnostic drop deactivates a path in internal state, discovery/reconnect mechanisms restore candidate/active status without requiring runtime restarts.
* **Status:** PASS (Preserved original recovery behavior without artificial state injection).

### Experiment 5: Hybrid Coexistence (§25 Test 5)

* **Observation:** Dual-homed nodes concurrently maintain TCP and Bluetooth channels; dropping one does not corrupt or close the companion channel.
* **Status:** PASS.

## 3. Boundary Invariant Sign-Off

* ✅No modifications to `PeerRouter.java`
* ✅No modifications to `PathSelectionPolicy.java`
* ✅No modifications to `CompositeTransport.java`
* ✅No modifications to Core protocol / serialization
* ✅No fake UI route injection