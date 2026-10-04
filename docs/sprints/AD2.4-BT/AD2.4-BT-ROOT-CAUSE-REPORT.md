# A.D2.4-BT — Root Cause Report

**Status:** PENDING — Awaiting Phase 2 runtime evidence and Phase 3 physical validation

## Preliminary Assessment (Phase 1 Only)

**Root cause:** Bluetooth RFCOMM socket.connect() fails during SDP/service negotiation
with Android native IOException "read failed, socket might closed or timeout, read ret: -1"

**Boundary:** AndroidBluetoothRfcommTransport.kt line 166 → Android Bluetooth native stack

**Evidence:** Static trace of complete call chain from UI button to socket.connect()

**Reproduction:** Select iQOO Z7 5G from bonded devices → tap Connect BT → error appears

**Not responsible:**
- TCP transport (validated in A.D2.4)
- Hybrid routing (validated in A.D2.4)
- Core protocol layer (never reached)
- JOIN/peer activation (never reached)
- Permissions (correctly declared and requested)
- Device selection (MAC from bondedDevices is correct)

**Recommended action:** Phase 2 runtime instrumentation to determine whether:
1. The remote device is actually listening on SPP UUID
2. The failure is device-specific (iQOO) or universal
3. The L2CAP channel establishes but SDP negotiation fails

---

*This report will be finalized after Phase 3 physical validation.*
