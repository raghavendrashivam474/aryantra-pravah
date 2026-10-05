# A.D3 System Architecture & Integration Plan

## 1. Architectural Guardrails
*   **Track A Boundary:** UI communicates ONLY through `DiagnosticModelMapper` and `DiagnosticState` / `PravaahWorldState`.
*   **No Duplicate Authority:** UI never calculates trust, never manages outbox queues, and never selects routing paths.
*   **Zero Faking Policy:** Every delivery step, trust status, and path state shown in the cockpit must be backed by authoritative state from Core.

```text
+-------------------------------------------------------------------+
| UI LAYER |
| Pravaah Cockpit (Human surface + Progressive disclosure levels) |
+---------------------------------+---------------------------------+
|
v
+---------------------------------+---------------------------------+
| PRESENTATION MAPPER |
| DiagnosticModelMapper |
+---------------------------------+---------------------------------+
|
+------------------------+------------------------+
| |
v v
+--------+------------------------+ +----------------+----------------+
| NETWORK & SECURITY | | DELIVERY & MESSAGING |
| * PeerConnectivityRegistry | | * DefaultAppMessagingService |
| * PeerTrustManager (SX.4) | | * Sqlite/InMemoryOutbox (B.R3) |
| * PeerRouter & TransitionBuffer| | * SequenceGenerator |
+---------------------------------+ +---------------------------------+
```
---

## 2. Identified Architectural Gaps & Solutions

### Gap 1: Android Runtime Lacks Explicit Trust Wiring
*   **Observation:** `PravahAndroidMessagingManager` instantiates `PeerRouter` and `PeerConnectionCoordinator` without passing a `PeerTrustManager` or cryptographic identity.
*   **Solution:** Wire `PeerTrustManager`, `IdentityGenerator`, and `CryptographicIdentity` in `PravahAndroidMessagingManager` so that real cryptographic trust evaluation (SX.4) is active on Android. Expose `trustManager` as read-only to `DiagnosticModelMapper`.

### Gap 2: Delivery & Message Journey Observability
*   **Observation:** The Android UI currently listens only to incoming messages (`ApplicationMessageListener`) and sends payloads via fire-and-forget without capturing `ApplicationMessage` lifecycle (`CREATED` -> `BUFFERED` -> `SENT` -> `DELIVERED`).
*   **Solution:** Maintain an in-memory chronological UI message trace that correlates outgoing `messageId` with outbox/transition buffer states and protocol ACKs.

### Gap 3: Human vs Technical Identity
*   **Observation:** Peers currently only expose raw `PeerId` (e.g., `android-b9a8989e`).
*   **Solution:** For A.D3, establish a display resolution contract in the presentation layer (`humanDisplayName(peerId)` with fallback to formatted `take(8)` or device model name if available), strictly keeping technical `PeerId` immutable under inspection.

---

## 3. Sprint Delivery Roadmap

1. **A.D3.1 — Foundation & Presentation Model:**
   * Extend `DiagnosticState` with `PeerContextState`, `MessageJourneyState`, `UiMessageItem`, and `TrustState`.
   * Update `DiagnosticModelMapper` to map Core trust records and outbox delivery metrics.
2. **A.D3.2 — Human Identity & Peer Context:**
   * Peer-centric cockpit header and context switcher.
   * Multi-dimensional peer summary: Connectivity + Trust + Delivery.
3. **A.D3.3 — Human-First Messaging & Message Journey:**
   * Conversation list with delivery checkmarks and tap-to-inspect Journey dialog/sheet.
   * Causal verification of journey steps (Accepted -> Dispatched -> ACK).
4. **A.D3.4 — Integrated Persistent Cockpit:**
   * Unified view binding Peer Context, Messaging Surface, Network Topology, and Forensic Live Wire.
5. **A.D3.5 — Verification & Physical Validation:**
   * Unit tests for presentation mapper, UI state integrity, regression check against TCP/BT failover, and physical Android build verification.