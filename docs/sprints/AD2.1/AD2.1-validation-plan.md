# A.D2.1 — Physical Two-Device Validation Plan

**Sprint:** A.D2.1 (Track A — Stabilization)
**APK:** `android/app/build/outputs/apk/debug/app-debug.apk` (vA.D2.1)
**Prerequisites:** Two Android devices, same Wi-Fi LAN, Bluetooth paired

---

## Setup

- [ ] Install APK on both devices: `adb install -r app-debug.apk`
- [ ] Grant Bluetooth + Location permissions on both
- [ ] Verify both devices see each other in Bluetooth paired list
- [ ] Note Device A PeerId and Device B PeerId

---

## Test 1 — Discovery

| Step | Action | Expected | Pass |
|------|--------|----------|------|
| 1.1 | Device A: START | "Runtime STARTED on TCP port XXXXX" | [ ] |
| 1.2 | Device B: START | Same | [ ] |
| 1.3 | Device A: DISCOVER | "DISCOVERED: android-XXXXXXXX @ IP:PORT" | [ ] |
| 1.4 | Verify | Discovered peer is the correct Pravaah device, not a random host | [ ] |

## Test 2 — Bluetooth Selection (Issue A Validation)

| Step | Action | Expected | Pass |
|------|--------|----------|------|
| 2.1 | Device A: CONNECT BT | **AlertDialog appears** with device list | [ ] |
| 2.2 | Verify dialog | Shows paired devices with names and MAC addresses | [ ] |
| 2.3 | Select correct Pravaah device | "User selected BT device: ..." in LiveWire | [ ] |
| 2.4 | Verify connection | "Bluetooth channel active: bt:XXXX" | [ ] |
| 2.5 | **Negative test:** Cancel dialog | No connection attempted | [ ] |
| 2.6 | **Verify:** No earbuds/speakers connected | Only the selected Pravaah device | [ ] |

## Test 3 — TCP Connection

| Step | Action | Expected | Pass |
|------|--------|----------|------|
| 3.1 | Both: STOP, then START | Clean restart | [ ] |
| 3.2 | Device A: DISCOVER | Device B appears | [ ] |
| 3.3 | Device A: CONNECT TCP | "TCP socket active", "TCP JOIN sent" | [ ] |
| 3.4 | Check topology | TCP edge: "ACTIVE SELECTED" | [ ] |
| 3.5 | Send message | TX event on A, RX event on B | [ ] |

## Test 4 — TCP Duplicate Connect (Issue D Validation)

| Step | Action | Expected | Pass |
|------|--------|----------|------|
| 4.1 | Continue from Test 3 (TCP active) | TCP ACTIVE shown | [ ] |
| 4.2 | Device A: CONNECT TCP again | "TCP already ACTIVE — skipping duplicate connect" | [ ] |
| 4.3 | Verify | No error, no second connection attempt | [ ] |
| 4.4 | Verify topology | Still exactly one TCP ACTIVE edge | [ ] |

## Test 5 — Multi-Path

| Step | Action | Expected | Pass |
|------|--------|----------|------|
| 5.1 | Continue from Test 3 (TCP active) | TCP ACTIVE | [ ] |
| 5.2 | Device A: CONNECT BT (select Device B) | BT ACTIVE | [ ] |
| 5.3 | Check topology | Two edges: TCP ACTIVE + BT ACTIVE | [ ] |
| 5.4 | Check dispatch route | One marked [SELECTED ROUTE] (TCP preferred) | [ ] |
| 5.5 | Check snapshot | "PEERS: 01 | ACTIVE PATHS: 02" | [ ] |

## Test 6 — TCP Failover

| Step | Action | Expected | Pass |
|------|--------|----------|------|
| 6.1 | Continue from Test 5 (TCP + BT active) | Both active | [ ] |
| 6.2 | Device A: DROP TCP | "SIMULATED TCP FAILURE" | [ ] |
| 6.3 | Check LiveWire | "PATH: tcp ACTIVE->INACTIVE" | [ ] |
| 6.4 | Check topology | TCP: INACTIVE, BT: ACTIVE [SELECTED ROUTE] | [ ] |
| 6.5 | Check dispatch route | BT connection ID or "NONE" if BT also dropped | [ ] |
| 6.6 | Send message | Delivered via BT | [ ] |

## Test 7 — Recovery

| Step | Action | Expected | Pass |
|------|--------|----------|------|
| 7.1 | Continue from Test 6 (BT active, TCP inactive) | BT selected | [ ] |
| 7.2 | Device A: CONNECT TCP | TCP re-established | [ ] |
| 7.3 | Check LiveWire | "PATH: tcp CANDIDATE->ACTIVE" or similar | [ ] |
| 7.4 | Check topology | TCP ACTIVE [SELECTED ROUTE], BT ACTIVE | [ ] |
| 7.5 | Verify no duplicate paths | At most one ACTIVE TCP + one ACTIVE BT per peer | [ ] |

## Test 8 — Runtime Repetition (Issue I Validation)

| Step | Action | Expected | Pass |
|------|--------|----------|------|
| 8.1 | Connect TCP | Active | [ ] |
| 8.2 | DROP TCP | Inactive | [ ] |
| 8.3 | Connect TCP again | Active | [ ] |
| 8.4 | DROP TCP | Inactive | [ ] |
| 8.5 | Connect TCP again | Active | [ ] |
| 8.6 | Check topology | No unexplained accumulation of path entries | [ ] |
| 8.7 | Note | Some INACTIVE residue is expected (Core deferred). ACTIVE should be singular. | [ ] |

## Test 9 — STOP/START (Issue J Validation)

| Step | Action | Expected | Pass |
|------|--------|----------|------|
| 9.1 | Device A: STOP | "Runtime STOPPED", status shows STOPPED | [ ] |
| 9.2 | Check topology | "No connected peer paths" | [ ] |
| 9.3 | Check buffer | All zeros | [ ] |
| 9.4 | Device A: START | "Runtime STARTED" | [ ] |
| 9.5 | Device A: DISCOVER | Discovery works after restart | [ ] |

## Test 10 — Scrolling (Issue B Validation)

| Step | Action | Expected | Pass |
|------|--------|----------|------|
| 10.1 | Establish multi-path (Test 5 state) | Content fills screen | [ ] |
| 10.2 | Scroll from top to bottom | All sections visible: status, topology, paths, buffer, buttons, message, LiveWire | [ ] |
| 10.3 | Scroll back to top | Header and status visible | [ ] |
| 10.4 | Tap buttons while scrolled | Buttons respond correctly | [ ] |
| 10.5 | Type message | Keyboard appears, input field accessible | [ ] |
| 10.6 | Send message | LiveWire updates, scroll follows | [ ] |
| 10.7 | Portrait orientation | All content accessible via scroll | [ ] |

---

## Anomaly Log

| Time | Test | Observation | Severity |
|------|------|-------------|----------|
| | | | |

## Sign-Off

| Role | Name | Date | Result |
|------|------|------|--------|
| Tester | | | PASS / FAIL |
| Reviewer | | | PASS / FAIL |
