# A.D2.4-BT-PV — Investigation Plan

**Baseline:** vA.D2.4-BT @ 4f5b656
**Parent Sprint:** A.D2.4-BT — Bluetooth RFCOMM Runtime Forensic Investigation
**Classification:** Physical Validation / Forensic Classification
**Production Fixes:** NONE unless evidence proves a Pravaah defect

---

## 1. Mission

Determine exactly whether the observed Bluetooth RFCOMM failure is caused by:
- Pravaah
- The remote device
- Bluetooth state/pairing
- The physical environment

**Primary question:** Where does the Bluetooth lifecycle actually fail, and does Pravaah own that failure?

## 2. What Already Exists (Do NOT Recreate)

- `AndroidBluetoothRfcommTransport.kt` — Android RFCOMM implementation
- 12 forensic checkpoints BT-01 through BT-12 (deployed in A.D2.4-BT)
- Standard SPP UUID: `00001101-0000-1000-8000-00805F9B34FB`
- Full static call-chain trace (documented in A.D2.4-BT)
- `capture_bt_logs.ps1` + ADB tooling

## 3. Existing Instrumentation Checkpoints

| ID | Checkpoint |
|----|-----------|
| BT-01 | Adapter available |
| BT-02 | Adapter enabled |
| BT-03 | Device resolved |
| BT-04 | Bond state |
| BT-05 | Socket created |
| BT-06 | Discovery cancelled |
| BT-07 | socket.connect() started |
| BT-08 | socket.connect() result |
| BT-09 | InputStream opened |
| BT-10 | OutputStream opened |
| BT-11 | Reader thread started |
| BT-12 | Reader EOF / IOException |

## 4. Physical Validation Matrix

| Test | Scenario | Change from Previous | Purpose |
|------|----------|---------------------|---------|
| BT-PV-01 | Adapter OFF | Baseline | Verify precondition failure classification |
| BT-PV-02 | Known-good pair A→B | Enable adapter + good peer | Control experiment |
| BT-PV-03 | Known-good app delivery | Send TX/RX test messages | Prove end-to-end lifecycle |
| BT-PV-04 | iQOO Z7 5G A→B | Change remote device ONLY | Reproduce failure |
| BT-PV-05 | iQOO re-pair | Unpair + re-pair ONLY | Test if bond state is causal |
| BT-PV-06 | Reverse direction | iQOO initiates | Test endpoint directionality |

## 5. Experimental Rule

**Change only ONE variable at a time.**

## 6. Phase Tracker

- [ ] Phase 0 — Baseline lock + workspace scaffold ✅ (this block)
- [x] Phase 1 — Inspect minimum required files ✅ COMPLETE
- [x] Phase 2 — Build + verify experimental APK ✅ COMPLETE
- [x] Phase 3 — Execute PV-01 through PV-06 ✅ COMPLETE
- [x] Phase 4 — Evidence analysis + classification ✅ COMPLETE
- [x] Phase 5 — Root-cause decision: 🟢 EXTERNAL / OEM VENDOR SPECIFIC ✅ COMPLETE

## 7. Decision Branches

- 🟢 **External/device-specific** → Close investigation, no remediation sprint
- 🔴 **Pravaah defect** → Open A.D2.4-BT-FIX with scoped remit
- 🟡 **Inconclusive** → Define smallest additional experiment, do NOT invent a fix

---

*Rule: Do not make the failing test pass. Make the failure explainable.*
*Created: 2026-10-04 19:12:57*

