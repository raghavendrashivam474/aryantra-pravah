# A.D2.4 — Physical & Automated Validation Plan

**Sprint:** A.D2.4 — Diagnostic Runtime Integrity Surgical Remediation  
**Baseline:** `vA.D2.3-T` (`0427471`)  
**Status:** Automated Tests Green — Physical Validation Pending  
**Scope:** Prove physical TCP message delivery (A → B and B → A), single path lifecycle, and truthful UI representation.

---

## 1. Automated Regression Verification (Already Passed)

| Test Suite | Target | Result | Status |
|---|---|---|---|
| **Core Maven Targeted Suite** | 44 Core JVM Tests (Transport, Coordinator, Presence, Lifecycle) | 44 / 44 PASS (0 Failures) | ✅ PASSED |
| **Android Unit Tests** | `testDebugUnitTest` | BUILD SUCCESSFUL | ✅ PASSED |
| **Android Debug APK** | `assembleDebug` | `app-debug.apk` built cleanly | ✅ PASSED |

---

## 2. Physical Two-Device TCP Validation Scenarios

### Scenario TCP-01: Clean Single TCP Connection
- **Action:** Device A starts, Device B starts. Device A discovers Device B and taps **CONNECT TCP** once.
- **Expected Results:**
  - Exactly **1** peer record for Device B on Device A.
  - Exactly **1** `[TCP] ● ACTIVE` path row.
  - Endpoint displayed: Canonical `host:port` (no leading `/`).
  - Dispatch route displayed: Canonical `host:port` (no leading `/`).
  - Exactly **1** `PATH: tcp CANDIDATE->ACTIVE` or `null->ACTIVE` LiveWire event (no duplicate burst).

### Scenario TCP-02: Message Delivery (Device A → Device B)
- **Action:** On Device A, enter message `"PRAVAAH-TCP-A-001"` in the chat box and tap **SEND**.
- **Expected Results:**
  - **Device A:** LiveWire displays `TX: [android-B...] PRAVAAH-TCP-A-001`.
  - **Device B:** LiveWire displays `RX: [android-A...] PRAVAAH-TCP-A-001`.
  - **Device B:** Peer session updates, message count increments.
  - **Verification:** Both `DefaultApplicationMessagingService` and `DiagnosticActivity` received the event without listener collision.

### Scenario TCP-03: Reverse Message Delivery (Device B → Device A)
- **Action:** On Device B, enter message `"PRAVAAH-TCP-B-001"` in the chat box and tap **SEND**.
- **Expected Results:**
  - **Device B:** LiveWire displays `TX: [android-A...] PRAVAAH-TCP-B-001`.
  - **Device A:** LiveWire displays `RX: [android-B...] PRAVAAH-TCP-B-001`.
  - **Verification:** Bidirectional symmetry confirmed over the single physical TCP socket.

### Scenario TCP-04: Duplicate Connection Guard
- **Action:** While connected, tap **CONNECT TCP** again on Device A.
- **Expected Results:**
  - Toast/Log: `"TCP already ACTIVE for peer — skipping"`.
  - No second socket created.
  - No duplicate path row added.

### Scenario TCP-05: Disconnect and Reconnect Cycle
- **Action:** Tap **STOP** on Device A, restart, and reconnect TCP.
- **Expected Results:**
  - Previous path deactivated cleanly.
  - Reconnect establishes new single active path.
  - Inbound and outbound messaging continues to function seamlessly.

---

## 3. Hybrid Coexistence Sanity Check

- **Action:** Connect both TCP and Bluetooth to the same peer.
- **Expected Results:**
  - **Legitimate Multi-Path:**
    ```
    Peer
    ├── [TCP] ● ACTIVE
    └── [BLUETOOTH] ● ACTIVE
    ```
  - `PathSelectionPolicy` prioritizes TCP.
  - Dispatches route correctly without duplicate message receipt.

---

## 4. Acceptance Sign-Off Criteria

- [ ] TCP-01 passed (single active path, canonical IDs).
- [ ] TCP-02 passed (A → B delivery with visible RX).
- [ ] TCP-03 passed (B → A reverse delivery with visible RX).
- [ ] TCP-04 passed (duplicate connect cleanly guarded).
- [ ] TCP-05 passed (reconnect cycle preserves single path).
- [ ] Hybrid check passed (multi-path representation preserved).