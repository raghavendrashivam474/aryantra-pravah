# BT Observability Decision Record — Sprint A.D2.5

## 1. Executive Summary
During Sprint A.D2.4-BT / A.D2.4-BT-PV, twelve forensic checkpoints (`BT-01` through `BT-12`) were embedded into `AndroidBluetoothRfcommTransport.kt` to diagnose RFCOMM connection establishment, discovery state, thread lifecycles, and socket terminations.

Per Sprint A.D2.5 guidance:
- Instrumentation must **not** be blindly deleted.
- Every checkpoint is formally audited below and classified for its long-term diagnostic vs forensic value.
- Key lifecycle boundaries (**BT-07**, **BT-08**, **BT-12**) are promoted to permanent core lifecycle telemetry.

---

## 2. Checkpoint Audit & Classification Matrix

| Checkpoint | Code Location (approx) | Current Purpose | Future Purpose | Decision | Classification |
|---|---|---|---|---|---|
| **BT-01** | `connect()` entry | Captures connect call invocation, adapter state, remote MAC | Guardrail verifying connection request received | **RETAIN** | Standard Diagnostic |
| **BT-02** | `connect()` adapter check | Verifies local BT adapter is enabled | Pre-flight adapter health assertion | **RETAIN** | Standard Diagnostic |
| **BT-03** | `connect()` permission check | Validates `BLUETOOTH_CONNECT` runtime permission | Security/OS permission boundary check | **RETAIN** | Standard Diagnostic |
| **BT-04** | `connect()` discovery cancel | Confirms cancellation of active BLE/classic discovery | Performance assertion (avoiding 2.4GHz contention) | **RETAIN** | Standard Diagnostic |
| **BT-05** | `connect()` remote device | Resolves `BluetoothDevice` from MAC address | Device resolution verification | **RETAIN** | Standard Diagnostic |
| **BT-06** | `connect()` socket create | Creates RFCOMM socket via standard service UUID | Socket allocation boundary | **RETAIN** | Standard Diagnostic |
| **BT-07** | `connect()` socket.connect() entry | Initiates blocking RFCOMM SDP negotiation & link key exchange | **Crucial boundary**: Distinguishes client-initiation from timeout | **PROMOTE** | **Permanent Core Lifecycle** |
| **BT-08** | `connect()` socket.connect() exit | Confirms RFCOMM L2CAP channel successfully established | **Crucial boundary**: Confirms physical link viability before framing | **PROMOTE** | **Permanent Core Lifecycle** |
| **BT-09** | `listen()` server start | Starts listening `BluetoothServerSocket` | Server lifecycle observation | **RETAIN** | Standard Diagnostic |
| **BT-10** | `listen()` accept() loop | Awaits incoming inbound RFCOMM connections | Inbound connection readiness | **RETAIN** | Standard Diagnostic |
| **BT-11** | Reader thread entry | Spawns frame deserialization loop on RFCOMM `InputStream` | Reader loop initialization | **RETAIN** | Standard Diagnostic |
| **BT-12** | Reader thread termination | Captures clean EOF (`-1`) or `IOException` upon socket drop | **Crucial boundary**: Identifies remote drop vs local close vs socket rupture | **PROMOTE** | **Permanent Core Lifecycle** |

---

## 3. Detailed Decision Rationale

### BT-07 & BT-08 (Connection Establishment Boundary)
- **Rationale**: RFCOMM connection establishment is the primary point of failure on fragmented Android OEM hardware (e.g. SDP lookup failure, page timeouts, link key negotiation rejection). Keeping explicit timestamps and status logging around `socket.connect()` is vital for telemetry without requiring a debugger.
- **Action**: Retained at `INFO` log level permanently.

### BT-12 (Reader EOF / IOException Boundary)
- **Rationale**: When a diagnostic drop or remote physical disconnect occurs, the reader thread receives either a `-1` (clean EOF) or an `IOException` (broken pipe / connection reset). Differentiating clean teardown from abrupt socket drops is essential for failover validation.
- **Action**: Retained at `INFO`/`WARN` log level permanently.

### BT-01 through BT-06, BT-09 through BT-11
- **Rationale**: These checkpoints have negligible overhead (only executed upon manual connect/start operations, never on the frame hot path). Removing them now introduces regression risk with zero latency gain.
- **Action**: Retained in-place.

---

## 4. Conclusion
All 12 checkpoints are retained in `AndroidBluetoothRfcommTransport.kt` ensuring complete continuity. BT-07, BT-08, and BT-12 are designated as permanent core architectural milestones for hybrid transport observability.
