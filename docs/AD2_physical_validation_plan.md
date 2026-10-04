# A.D2 — Physical Two-Device Validation Test Plan

**Sprint:** A.D2 (Track A — Product Experience)
**Prerequisite:** APK built from `vA.D2` tag, installed on two Android devices
**Network:** Both devices on the same Wi-Fi LAN
**Bluetooth:** Both devices paired and Bluetooth enabled

---

## Setup Checklist

- [ ] Device A: APK installed, Wi-Fi on, Bluetooth on, paired with Device B
- [ ] Device B: APK installed, Wi-Fi on, Bluetooth on, paired with Device A
- [ ] Both devices: Location permission granted, Bluetooth scan/connect granted
- [ ] Both devices: Battery > 50% (avoid power-saving interference)
- [ ] Note Device A PeerId: `android-________`
- [ ] Note Device B PeerId: `android-________`

---

## Test 1 — TCP Connection & Messaging

**Objective:** Verify TCP path appears in topology and messages flow.

| Step | Action | Expected Result | Pass? |
|------|--------|-----------------|-------|
| 1.1 | Device A: tap START | LiveWire shows "Runtime STARTED on TCP port XXXXX". Node status shows RUNNING. | [ ] |
| 1.2 | Device B: tap START | Same as above on Device B. | [ ] |
| 1.3 | Device A: tap DISCOVER | LiveWire shows "UDP Discovery STARTED". | [ ] |
| 1.4 | Wait 3 seconds | LiveWire shows "DISCOVERED: android-________ @ <IP>:<port>". | [ ] |
| 1.5 | Device A: tap CONNECT TCP | LiveWire shows "Connecting TCP...", then "TCP socket active", then "TCP JOIN sent". | [ ] |
| 1.6 | Check Topology panel | ASCII topology shows PEER node with TCP edge marked "● ACTIVE SELECTED". | [ ] |
| 1.7 | Check Path Connections | `├── [TCP] ● ACTIVE (<conn-id>) [SELECTED ROUTE]` visible. | [ ] |
| 1.8 | Device A: type "hello" and tap SEND | LiveWire shows "▲ MSG TX: SENT [android-____]: hello". | [ ] |
| 1.9 | Check Device B | LiveWire shows "▼ MSG RX: RECV [android-____]: <msg-id>". | [ ] |
| 1.10 | Check Transition Buffer | Buffer panel shows "Queue Size: 0" (message delivered, not buffered). | [ ] |

---

## Test 2 — Bluetooth Connection & Messaging

**Objective:** Verify Bluetooth path appears independently in topology.

| Step | Action | Expected Result | Pass? |
|------|--------|-----------------|-------|
| 2.1 | Both devices: tap STOP | LiveWire shows "Runtime STOPPED". Topology clears. | [ ] |
| 2.2 | Both devices: tap START | Runtime restarted. | [ ] |
| 2.3 | Device A: tap CONNECT BT | Bluetooth chooser appears. Select Device B. | [ ] |
| 2.4 | Wait for connection | LiveWire shows "Bluetooth channel active: <conn-id>". | [ ] |
| 2.5 | Check Topology panel | PEER node shows BLUETOOTH edge marked "● ACTIVE". | [ ] |
| 2.6 | Check Path Connections | `├── [BLUETOOTH] ● ACTIVE (<conn-id>) [SELECTED ROUTE]` visible. | [ ] |
| 2.7 | Device A: type "bt-test" and tap SEND | LiveWire shows "▲ MSG TX: SENT [remote-bt-node]: bt-test". | [ ] |

---

## Test 3 — Multi-Path (TCP + Bluetooth Simultaneous)

**Objective:** Verify both paths coexist in topology and dispatch route is clear.

| Step | Action | Expected Result | Pass? |
|------|--------|-----------------|-------|
| 3.1 | Continue from Test 2 (BT active) | BT path is ACTIVE. | [ ] |
| 3.2 | Device A: tap DISCOVER | Discover Device B on LAN. | [ ] |
| 3.3 | Device A: tap CONNECT TCP | TCP path established alongside BT. | [ ] |
| 3.4 | Check Topology panel | PEER node shows TWO edges: TCP ● ACTIVE and BLUETOOTH ● ACTIVE. | [ ] |
| 3.5 | Check Path Connections | Both paths listed. One marked `[SELECTED ROUTE]` (TCP preferred per PathSelectionPolicy). | [ ] |
| 3.6 | Check Network Snapshot | "PEERS: 01 | ACTIVE PATHS: 02" | [ ] |
| 3.7 | Device A: send "multi-path" | Message delivered via selected route (TCP). | [ ] |

---

## Test 4 — Failover (TCP Drop → BT Takes Over)

**Objective:** Verify live topology reacts to path failure in real-time.

| Step | Action | Expected Result | Pass? |
|------|--------|-----------------|-------|
| 4.1 | Continue from Test 3 (TCP + BT active) | Both paths ACTIVE, TCP selected. | [ ] |
| 4.2 | Device A: tap DROP TCP | LiveWire shows "SIMULATED TCP FAILURE: Path <id> DEACTIVATED". | [ ] |
| 4.3 | Check LiveWire immediately | "⇄ PATH CHG: tcp transitioned from ACTIVE to INACTIVE" appears. | [ ] |
| 4.4 | Check Topology panel | TCP edge now shows "○ INACTIVE". BT edge still "● ACTIVE". | [ ] |
| 4.5 | Check Path Connections | TCP shows `○ INACTIVE`. BT shows `● ACTIVE [SELECTED ROUTE]`. | [ ] |
| 4.6 | Check Transition Buffer | If messages were in-flight: "Queue Size: N" then flushes to 0. | [ ] |
| 4.7 | Device A: send "after-failover" | Message delivered via Bluetooth. LiveWire shows TX event. | [ ] |

---

## Test 5 — Recovery (TCP Restores After Failover)

**Objective:** Verify topology updates when a path recovers.

| Step | Action | Expected Result | Pass? |
|------|--------|-----------------|-------|
| 5.1 | Continue from Test 4 (BT active, TCP inactive) | BT is selected route. | [ ] |
| 5.2 | Device A: tap CONNECT TCP again | TCP path re-established. | [ ] |
| 5.3 | Check LiveWire | "⇄ PATH CHG: tcp transitioned from CANDIDATE to ACTIVE" appears. | [ ] |
| 5.4 | Check Topology panel | TCP edge back to "● ACTIVE". BT still "● ACTIVE". | [ ] |
| 5.5 | Check Path Connections | TCP marked `[SELECTED ROUTE]` again (TCP preferred). | [ ] |
| 5.6 | Device A: send "recovered" | Message delivered via TCP. | [ ] |

---

## B.R2 Observability Validation (Section 30)

**Objective:** Verify TransitionBuffer counters reflect real buffering during transitions.

| Step | Action | Expected Result | Pass? |
|------|--------|-----------------|-------|
| B.1 | Establish TCP + BT multi-path (Test 3 state) | Both paths active. | [ ] |
| B.2 | Note Transition Buffer panel | All counters at 0 or low values. | [ ] |
| B.3 | Device A: send 5 rapid messages | "Tx Buffered" counter increments if any messages queued. | [ ] |
| B.4 | Device A: tap DROP TCP | If messages were buffered: LiveWire shows "⚿ BUFFER: Transition buffer holding N messages". | [ ] |
| B.5 | Wait for BT flush | "Tx Flushed" counter increments. "Queue Size" returns to 0. | [ ] |
| B.6 | Verify no data loss | Device B received all 5 messages. | [ ] |

---

## Post-Validation Cleanup

- [ ] Both devices: tap STOP
- [ ] Record any anomalies or unexpected behavior below
- [ ] Screenshot topology panel from Test 3 (multi-path) and Test 4 (failover)
- [ ] Screenshot Transition Buffer panel from B.R2 validation

---

## Anomaly Log

| Time | Test | Observation | Severity |
|------|------|-------------|----------|
| | | | |

---

## Sign-Off

| Role | Name | Date | Result |
|------|------|------|--------|
| Tester | | | PASS / FAIL |
| Reviewer | | | PASS / FAIL |
