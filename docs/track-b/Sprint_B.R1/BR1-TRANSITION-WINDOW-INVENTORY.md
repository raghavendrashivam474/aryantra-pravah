# B.R1 Transition Window Inventory & Baseline Analysis

**Track:** B — Core Evolution  
**Sprint Type:** Runtime validation / evidence gathering  
**Baseline:** vB.R1  
**Status:** In-Progress Investigation  

---

## 1. System Inventory Matrix

| Dimension | Question | Baseline Architectural Reality (B.R1) |
|---|---|---|
| **A. Path Failure Trigger** | What constitutes path failure? What event marks TCP as failed? | Transport throws `IOException` / `SocketException` on write, or `TransportListener.onConnectionClosed` / `onConnectionFailed` is triggered upon EOF or socket reset. |
| **B. Router Notification** | When does the router learn about failure? | Synchronously during `PeerRouter.send()` when `transport.send()` throws, or asynchronously when `PeerConnectionCoordinator` receives connection-closed events and updates the candidate/path state. |
| **C. Alternate Path Usability** | When does the alternate path become selectable? | When `PathSelectionPolicy.selectBestPath()` is evaluated against registered connections in state `ACTIVE` (or usable candidates). If Bluetooth is already in `ACTIVE` state, it is immediately selectable; if `CONNECTING`, it cannot be selected until handshake/state completes. |
| **D. In-Flight send() during Gap** | What happens to `send()` during the gap? | If `PeerRouter.send()` experiences an exception on the primary path, it catches the transport exception, marks/removes the failed path from active candidates for that routing attempt, re-evaluates `PathSelectionPolicy`, and retries *synchronously* on the next available active path if one exists. If no alternate path is currently `ACTIVE`, `send()` fails and throws or returns failure. |
| **E. Existing Delivery Signal** | What is the highest observable delivery signal? | 1. `PeerRouter.send()` boolean / void completion (indicates transport socket acceptance only).<br>2. Transport-level ACK packet / message-level ACK (`DeliveryOutbox` / `DeliveryRetryManager` tracking message IDs).<br>3. Remote peer receipt callback (`TransportListener.onMessageReceived`). |
| **F. Automatic Retry Behavior** | Are messages already retried? | At `PeerRouter` level: intra-send failover retry exists across currently active alternate paths.<br>At `DeliveryRetryManager` level: asynchronous higher-layer retry exists for outbox-backed messages with timeouts. |
| **G. Message ID Preservation** | Are message IDs preserved across retries? | Yes. Application-level `Message` objects maintain immutable `messageId` across both router-level path failover and outbox-level retries. |
| **H. Deterministic Test Seam** | Can the experiment deterministically place messages inside the window? | Yes, by utilizing a controllable test transport harness (`FakeTransport` / scriptable transport callbacks) that coordinates state transition timestamps with message dispatch offsets. |

---

## 2. Transition Window Lifecycle Decomposition

```text
       T0 (Dual-link Active)
       TCP = ACTIVE, BT = ACTIVE (or CANDIDATE)
                │
       T1 (Failure Begins / Transport degradation)
                │
       T2 (TCP Send Fails / Disconnect detected)
          ───► Bucket 1: Message dispatched prior to failure detection
                │
       T3 (Transition Window Gap: TCP Inactive, BT transitioning/activating)
          ───► Bucket 2: Message dispatched DURING transition window
                │
       T4 (BT Activated / Stable Selection)
          ───► Bucket 3: Message dispatched post alternate activation
                │
       T5 (Normal traffic on BT)
```

## 3. Observability & Outcome Classification Taxonomy

Every message sent across the experiment is classified into one of the following canonical states:

- **`DELIVERED_ONCE`**: Message accepted, routed to remote peer exactly once, ACKed.
- **`DELIVERED_AFTER_RETRY`**: Initial path attempt failed, router or retry manager rerouted to alternate
  path, remote peer received and ACKed.
- **`LOST`**: Message accepted or attempted by caller, not received by remote peer, no subsequent delivery.
- **`DUPLICATED`**: Message received and processed >1 time by remote peer.
- **`REORDERED`**: Messages Mᵢ,Mⱼ where i < j arrive at remote peer with M<sub>j</sub> before M<sub>i</sub>
- **`UNKNOWN`**: Delivery state cannot be conclusively proven with existing signals.

## 4. Test Seam & Validation Strategy

1. **No production alterations**: Production routing algorithms in `PeerRouter`, 
   `PeerConnectionCoordinator`, and `CompositeTransport` remain untouched.
2. **Deterministic Controlled Harness**: Use controlled mock/fake transports in JUnit to simulate 
   millisecond-precise failure injection and asynchronous path activation delays.
3. **Observability Harness**: Measure `send()` outcome, `onMessageReceived` at destination, and ACK emission timestamp per message ID.
