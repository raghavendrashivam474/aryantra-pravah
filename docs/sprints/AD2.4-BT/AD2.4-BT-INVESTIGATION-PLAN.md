# A.D2.4-BT — Bluetooth RFCOMM Runtime Forensic Investigation Plan

**Baseline:** vA.D2.4
**Sprint Type:** Forensic investigation → evidence-backed remediation proposal
**Initial production-code modification:** 0

---

## 1. Mission

Determine exactly why Bluetooth RFCOMM fails in problematic physical scenarios,
identify the owning architectural boundary, and produce an evidence-backed
remediation plan **only if** a Pravaah defect is proven.

## 2. Hypothesis Tree (Updated After Phase 1 Static Trace)

```text
BT connection failure
│
├── H1: Adapter unavailable (environment/precondition) [WEAK - adapter check passes]
├── H2: Permission/state failure [WEAK - permissions requested]
├── H3: Wrong device selected/resolved (MAC mismatch) [WEAK - MAC from bondedDevices]
├── H4: Socket creation failure [WEAK - createRfcommSocket succeeds]
├── H5: RFCOMM connect() failure [STRONG - IOException at line 166]
├── H6: Remote not listening on SPP UUID [MODERATE - remote start() required]
├── H7: Stream initialization failure [N/A - never reached]
├── H8: Reader lifecycle failure [N/A - never reached]
├── H9: JOIN/protocol lifecycle failure [N/A - never reached]
├── H10: Peer activation failure [N/A - never reached]
└── H11: Device-specific RFCOMM incompatibility [STRONG - iQOO-specific?]
```

## 3. Failure Classification

| Class | Description | Layer | Status |
|-------|-------------|-------|--------|
| A | Adapter unavailable | Environment | Ruled out for iQOO scenario |
| B | Device discovered, socket won't establish | Connection | **PRIMARY SUSPECT** |
| C | Socket connects, read fails immediately | Post-connect | Not yet observed |
| D | JOIN succeeds, peer never ACTIVE | Protocol | Not yet reached |
| E | ACTIVE path exists, messages fail | Messaging | Not yet reached |

## 4. Complete Failure Propagation Chain (Phase 1 Result)

```text
User taps "CONNECT BT"
│ DiagnosticActivity.kt:121
▼
showBluetoothDeviceChooser()
│ DiagnosticActivity.kt:334
│ Precondition: adapter != null && adapter.isEnabled (line 337) ✓
│ Precondition: bondedDevices.isNotEmpty() (line 343) ✓
│ User selects device from bonded list (line 352)
▼
backgroundExecutor.execute { ... } (line 360)
▼
manager.connectToBluetooth(address, tempPeerId)
│ PravahAndroidMessagingManager.kt:183
│ NO try/catch wrapping bluetoothTransport.connect()
▼
bluetoothTransport.connect(remoteMac)
│ AndroidBluetoothRfcommTransport.kt:139 (@Synchronized)
│ Line 144: cleanMac normalized
│ Line 151: adapter obtained ✓
│ Line 153: adapter.isEnabled ✓
│ Line 157: getRemoteDevice(cleanMac) ✓
│ Line 158: createRfcommSocketToServiceRecord(SPP_UUID) ✓
│ Line 161-164: cancelDiscovery() ✓
│ Line 166: socket.connect() ← IOException THROWN HERE
│ Message: "read failed, socket might closed or timeout, read ret: -1"
│ Source: Android native BluetoothSocket JNI layer
│ Line 167: attachActiveSocket() ← NEVER REACHED
▼
Exception propagates uncaught to DiagnosticActivity.kt:368
▼
Line 369: addErrorEvent("BT CONNECT ERROR: ${e.message}")
▼
UI displays error. syntheticBtPeerId = null.
```

## 5. Key Architectural Observations

1. **No Flutter/Dart layer involved** — DiagnosticActivity is pure Android native
2. **No MethodChannel** — Bluetooth is handled entirely in Kotlin
3. **socket.connect() at line 166 is UNPROTECTED** — no try/catch in transport
4. **Reader loop (lines 248-260) is SILENT on failure** — no logging on EOF/IOException
5. **UUID is standard SPP** — 00001101-0000-1000-8000-00805F9B34FB
6. **Remote must be listening** — requires remote Pravaah start() + server socket
7. **Only 1 BT test exists** — JVM-only piped streams, proves nothing about real RFCOMM
8. **Permissions correctly declared and requested** — both API 26-30 and 31+

## 6. Phase Tracker

- [x] Phase 1 — Static Trace (5 files) ✅ COMPLETE
- [ ] Phase 2 — Instrumented Runtime Observation
- [ ] Phase 3 — Controlled Physical Validation
- [ ] Phase 4 — Root-Cause Classification

## 7. Phase 2 Instrumentation Plan

### Objective
Add temporary diagnostic logging to AndroidBluetoothRfcommTransport.connect()
to capture the EXACT point of failure and all surrounding state.

### Target Lifecycle Checkpoints
| ID | Checkpoint | Location |
|----|-----------|----------|
| BT-01 | Adapter available | connect() line 151 |
| BT-02 | Adapter enabled | connect() line 153 |
| BT-03 | Device resolved | connect() line 157 |
| BT-04 | Bond state | After line 157 |
| BT-05 | Socket created | After line 158 |
| BT-06 | Discovery cancelled | After line 164 |
| BT-07 | connect() started | Before line 166 |
| BT-08 | connect() result | After line 166 (or catch) |
| BT-09 | InputStream opened | attachActiveSocket() line 211 |
| BT-10 | OutputStream opened | attachActiveSocket() line 212 |
| BT-11 | Reader started | After line 218 |
| BT-12 | Reader EOF/error | BluetoothStreamLink lines 250-259 |

### Rules
- Instrumentation is TEMPORARY and clearly marked with // BT-FORENSIC
- No production behavior changes
- No retry logic, no timeout changes, no UUID changes
- All logging via LOGGER.info() for visibility in logcat

## 8. Key Evidence Log

| Timestamp | Scenario | Hypothesis | Evidence | Status |
|-----------|----------|------------|----------|--------|
| Phase 1 | iQOO Z7 5G | H5 | socket.connect() throws IOException | Confirmed |
| Phase 1 | iQOO Z7 5G | H11 | Error from Android native stack | Suspected |
| Phase 1 | All | H6 | Remote must have server socket | To validate |

---

*Rule #1: Do NOT modify BluetoothRfcommTransport until evidence demands it.*
*Rule #2: Do not fix the error message. Find the event that caused the error.*
