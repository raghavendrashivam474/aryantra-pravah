# Pravaah — Sprint B.R3 Physical Validation Plan
**Track:** B — Core Evolution | **Focus:** Dual-Device Physical Verification (PV-01 to PV-08)
**Target Baseline:** v-A.D2.7 / B.R3 | **Date:** 2026-10-05

---

## 1. Objective & Scope

This test plan defines the protocol to physically validate application-level message ordering, `BUFFERED` state observability, ACK receipts, and deduplication across real physical Android devices undergoing transport migrations (TCP ↔ Bluetooth RFCOMM).

---

## 2. Test Setup & Prerequisites

### Hardware Requirements
- **Device A (Alice):** Primary sender / tester phone.
- **Device B (Bob):** Primary receiver / responder phone.
- Wi-Fi Access Point (shared subnet for TCP testing).
- Bluetooth paired between Device A and Device B.

### Software Requirements
- B.R3 debug APK installed on both devices.
- USB Debugging enabled on both devices.
- Terminal / Logcat monitoring active:
  ```bash
  # Device A Logcat Filter
  adb -s <DEVICE_A_SERIAL> logcat -v time | grep -E "DefaultApplicationMessagingService|PeerRouter|TransitionBuffer|DeliveryRetryManager"

  # Device B Logcat Filter
  adb -s <DEVICE_B_SERIAL> logcat -v time | grep -E "DefaultApplicationMessagingService|PeerRouter|TransitionBuffer"
3. Physical Test Matrix (PV-01 through PV-08)
text

+-------+--------------------------+-------------------+------------------------------------------+
| ID    | Scenario                 | Transport Route   | Key Verification Metric                  |
+-------+--------------------------+-------------------+------------------------------------------+
| PV-01 | Normal TCP Messaging     | TCP (Active)      | seq=1,2,3; state=SENT->DELIVERED; ACK RX |
| PV-02 | Normal Bluetooth RFCOMM  | BT (Active)       | seq=1,2,3; state=SENT->DELIVERED; ACK RX |
| PV-03 | TCP ➔ BT Migration       | TCP drop -> BT act| No message loss; seq preserved           |
| PV-04 | BT ➔ TCP Migration       | BT drop -> TCP act| TransitionBuffer flush; seq monotonic    |
| PV-05 | Repeated Migration       | TCP->BT->TCP->BT  | Continuous sequence increment; zero loss |
| PV-06 | Multi-message Transition | No active / cand  | Messages buffer; flush in FIFO sequence  |
| PV-07 | Ordering Verification    | Cross-transport   | Receiver UI shows strict sequence order  |
| PV-08 | Receiver Deduplication   | Replay / Flush    | Duplicate ignored; ACK sent back         |
+-------+--------------------------+-------------------+------------------------------------------+
4. Test Procedures & Pass/Fail Criteria
PV-01: TCP ➔ TCP Normal Messaging
Connect Alice and Bob on same Wi-Fi network. Ensure TCP path is ACTIVE.
Alice sends 3 messages: "M1", "M2", "M3".
Pass Criteria:
Alice logcat shows sequence numbers seq=1, seq=2, seq=3.
Wire framing 0x04 dispatched.
Message states transition CREATED ➔ SENT ➔ DELIVERED within < 200ms.
Bob receives and logs all 3 messages in order.
PV-02: BT ➔ BT Normal Messaging
Disable Wi-Fi on Alice and Bob. Establish Bluetooth RFCOMM path ACTIVE.
Alice sends 3 messages: "B1", "B2", "B3".
Pass Criteria:
Sequences assigned: seq=4, seq=5, seq=6.
Bob receives in order; Alice receives ACKs (DELIVERED).
PV-03: TCP ➔ BT Migration (Active Failover)
Re-enable Wi-Fi. Ensure TCP is ACTIVE and BT is ACTIVE / CANDIDATE.
Alice sends "T1" (seq=7). Alice disables Wi-Fi immediately.
Alice sends "T2" while Wi-Fi drops. PathSelectionPolicy falls back to BT.
Pass Criteria:
"T1" delivered via TCP; "T2" delivered via Bluetooth.
Bob receives "T1" followed by "T2".
Alice logs state DELIVERED for both messages.
PV-04: BT ➔ TCP Migration (Transition Window Capture)
Both devices on BT only (Wi-Fi off).
Alice turns off Bluetooth. Path state becomes CANDIDATE (no active transport).
Alice sends "M_trans" (seq=9).
Pass Criteria:
Alice logcat reports: TransitionBuffer: captured message ...
Alice message state explicitly transitions to BUFFERED (NOT SENT).
Alice turns Wi-Fi on. TCP establishes ACTIVE.
TransitionBuffer flushes automatically.
Bob receives "M_trans"; Alice transitions to DELIVERED.
PV-05: Repeated Transport Migration
Perform 4 consecutive migrations alternating Wi-Fi and Bluetooth toggles.
Send 2 messages during each switch.
Pass Criteria:
Total 8 messages delivered without corruption or loss.
Sequences on Bob strictly match seq=10 through seq=17.
PV-06: Multi-Message Transition Burst
Put path in CANDIDATE state (Bluetooth disconnected, Wi-Fi disabled).
Rapidly send 5 messages: "Burst-1" to "Burst-5".
Pass Criteria:
TransitionBuffer size increases from 0 to 5.
All 5 messages show MessageState.BUFFERED.
Activate TCP transport.
TransitionBuffer flushes all 5 messages in FIFO order (seq=18 to seq=22).
Bob receives all 5 in exact FIFO order.
PV-07: Ordering Under Migration
Alice sends "Fast-1" on BT.
Switch path to TCP. Alice sends "Fast-2".
Bob processes incoming packets.
Pass Criteria:
Bob application listeners receive "Fast-1" (seq=23) followed by "Fast-2" (seq=24).
No inverted display order.
PV-08: ACK & Receiver Deduplication Verification
Simulate network glitch or repeat packet: Bob receives the same messageId twice.
Pass Criteria:
First arrival: Bob logs message, notifies UI listeners, replies with 0x02 ACK.
Second arrival: Bob logs Duplicate message ...; acknowledging without re-dispatching.
Bob UI listener is NOT triggered a second time.
Bob sends second ACK back to Alice to ensure Alice's outbox is cleared.
5. Evidence Recording Template
Markdown

### Physical Validation Run: [Date & Time]
**Device A (Sender):** [Model / Android Version]
**Device B (Receiver):** [Model / Android Version]

| Test ID | Result (PASS/FAIL) | Sequence Numbers Observed | Latency (ms) | Notes |
| :--- | :---: | :---: | :---: | :--- |
| PV-01 | PASS / FAIL | [e.g., 1, 2, 3] | [e.g., 42ms] | |
| PV-02 | PASS / FAIL | [e.g., 4, 5, 6] | [e.g., 180ms] | |
| PV-03 | PASS / FAIL | [e.g., 7, 8] | [e.g., 210ms] | |
| PV-04 | PASS / FAIL | [e.g., 9] | [e.g., Transition Buffer Flush] | |
| PV-05 | PASS / FAIL | [e.g., 10-17] | [e.g., Continuous] | |
| PV-06 | PASS / FAIL | [e.g., 18-22] | [e.g., 5 items buffered & flushed] | |
| PV-07 | PASS / FAIL | [e.g., 23, 24] | [e.g., Strict sequence] | |
| PV-08 | PASS / FAIL | [e.g., Dup ACK OK] | [e.g., Single UI notification] | |