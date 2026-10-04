# A.D2.2 — Physical Two-Device Validation Protocol

**Sprint:** A.D2.2 (Runtime Integrity)
**APK Artifact:** `android/app/build/outputs/apk/debug/app-debug.apk`
**Hardware:** 2 Physical Android Devices (Device A & Device B)

---

## Test Execution Matrix

### Test 1: Message Content Integrity (Issue 3 Fix)
- [ ] Connect Device A to Device B via TCP.
- [ ] Device A sends payload: `HELLO-TCP-001`.
- [ ] **Verify on Device B LiveWire:** Must display `▼ MSG RX: [android-...] HELLO-TCP-001` (NOT a UUID string).

### Test 2: Bluetooth Clean Identity Convergence (Issue 2 Fix)
- [ ] Tap **CONNECT BT** on Device A and select Device B from the `AlertDialog`.
- [ ] Observe topology while connecting.
- [ ] **Verify:** Once connected and joined, only ONE peer node (`android-XXXXXXXX`) is displayed in the topology.
- [ ] **Verify:** No lingering `remote-bt-XXXXXX` phantom peer exists.

### Test 3: Stale Path Pruning on Drop & Reconnect (Issue 1 Fix)
- [ ] Connect Device A to Device B via TCP.
- [ ] Tap **DROP TCP**.
- [ ] Tap **CONNECT TCP** again.
- [ ] **Verify Topology:** Must show exactly ONE active TCP path. No accumulating duplicate inactive TCP paths.

### Test 4: Multi-Path Failover & Message Delivery
- [ ] Connect both TCP and Bluetooth simultaneously.
- [ ] Topology must display both `[TCP] ● ACTIVE` and `[BLUETOOTH] ● ACTIVE`.
- [ ] Send payload: `MULTI-001`.
- [ ] Drop TCP.
- [ ] Send payload: `MULTI-002`.
- [ ] **Verify:** Message is delivered over Bluetooth seamlessly without duplicate RX events.
