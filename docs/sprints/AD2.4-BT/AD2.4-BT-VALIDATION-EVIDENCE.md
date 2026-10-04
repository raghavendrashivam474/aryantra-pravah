# A.D2.4-BT — Validation Evidence Record

## Phase 1 — Static Trace Results

### Files Inspected
| # | File | Lines | Key Finding |
|---|------|-------|-------------|
| 1 | AndroidBluetoothRfcommTransport.kt | 285 | socket.connect() unprotected at L166; reader silent on failure |
| 2 | PravahAndroidMessagingManager.kt | 258 | connectToBluetooth() unprotected at L184; JOIN conditional on remotePeerId |
| 3 | DiagnosticActivity.kt | 423 | BT CONNECT ERROR origin at L369; device chooser at L334 |
| 4 | DiagnosticModelMapper.kt | 212 | BT path display logic; no connection involvement |
| 5 | AndroidManifest.xml | - | Permissions correct for API 26-30 and 31+ |

### Error String Trace
- **Origin:** DiagnosticActivity.kt:369
- **Format:** `"BT CONNECT ERROR: ${e.message}"`
- **Source of e.message:** Android native BluetoothSocket.connect() JNI
- **Exact message:** "read failed, socket might closed or timeout, read ret: -1"

## Phase 2 — Runtime Instrumentation Results

*(To be filled after instrumented build + physical test)*

| Checkpoint | Expected | Actual | Pass/Fail |
|-----------|----------|--------|-----------|
| BT-01 Adapter available | true | | |
| BT-02 Adapter enabled | true | | |
| BT-03 Device resolved | BluetoothDevice | | |
| BT-04 Bond state | BOND_BONDED | | |
| BT-05 Socket created | BluetoothSocket | | |
| BT-06 Discovery cancelled | true | | |
| BT-07 connect() started | - | | |
| BT-08 connect() result | success/exception | | |
| BT-09 InputStream | opened | | |
| BT-10 OutputStream | opened | | |
| BT-11 Reader started | thread running | | |
| BT-12 Reader outcome | data/EOF/error | | |

## Phase 3 — Physical Test Matrix

| Test | Device | Adapter | Paired | Socket | Connect | Reader | JOIN | Result |
|------|--------|---------|--------|--------|---------|--------|------|--------|
| BT-01 | - | OFF | - | - | - | - | - | |
| BT-02 | Known-good | ON | Yes | | | | | |
| BT-03 | iQOO Z7 5G | ON | Yes | | | | | |
| BT-04 | iQOO re-pair | ON | Re-paired | | | | | |
| BT-05 | Reverse dir | ON | Yes | | | | | |
| BT-06 | Msg delivery | Both | Yes | | | | | |

---

*All evidence must be timestamped and reproducible.*
