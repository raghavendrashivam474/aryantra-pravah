# A.D3 UI Contract & Presentation Model

## 1. Guiding Principles
1. **Human on the surface. Network underneath. Truth everywhere.**
2. **One persistent world, multiple ways of looking into it.**
3. **No duplicate authorities:** UI never calculates trust, never implements routing failover, and never invents delivery state. It presents facts exposed by Core.

---

## 2. Progressive Technical Disclosure Contract

| Disclosure Level | Target Audience / Mode | Message View Example | Network / Peer View Example |
| :--- | :--- | :--- | :--- |
| **Level 1 — Human** | Normal Chat Surface | `✓ Delivered` | **Raghav's Pixel**<br>● Reachable |
| **Level 2 — Network-Aware** | Contextual Glance | `✓ Delivered via Bluetooth` | **Raghav's Pixel**<br>● Active via BT |
| **Level 3 — Technical** | Detailed Inspection | `TCP dropped → BT selected`<br>`ACK received` | **Paths:**<br>TCP: Inactive<br>BT: Active (Selected) |
| **Level 4 — Forensic** | Live Wire / Deep Dive | `09:42:03.241 PATH tcp ACTIVE→INACTIVE`<br>`09:42:03.247 BUFFER seq=108`<br>`09:42:03.782 ACK id=8f21` | Message ID: `8f21...`<br>Seq: `108`<br>Round-trip: `42ms` |

---

## 3. Presentation State Hierarchy (`PravaahWorld`)

```text
PravaahWorld (Root UI Model)
├── LocalNode
│ ├── humanName: String
│ ├── technicalId: String (PeerId)
│ ├── activeTransports: List<String>
│ └── bufferSummary: TransitionBufferState
│
├── Peers: List<PeerContextState>
│ ├── humanName: String
│ ├── technicalId: String
│ ├── presenceState: PresenceState (ONLINE / REACHABLE / OFFLINE)
│ ├── trustState: TrustState (UNKNOWN / AUTHENTICATING / TRUSTED / REJECTED)
│ ├── paths: List<PathItemState>
│ └── deliverySummary: DeliverySummary (deliveredCount, pendingCount)
│
├── Conversation: List<UiMessageItem>
│ ├── messageId: String
│ ├── senderId: String
│ ├── content: String
│ ├── timestamp: String
│ ├── isOutgoing: Boolean
│ ├── deliveryState: MessageDeliveryStatus
│ └── journey: MessageJourneyState
│
├── Topology: TopologyState (ASCII / Graph nodes & edges)
│
└── LiveWireEvents: List<LiveWireEvent> (Forensic Stream)
```
---

## 4. Message Journey Contract

A message is not a static string; it is a tracked physical journey:

```text
Accepted (Local node queue)
↓
Buffered (Transition buffer waiting for route)
↓
Path Selected / Changed (TCP / BT)
↓
Dispatched (Transmitted over wire)
↓
Acknowledged (Protocol ACK received)
↓
Delivered (End-to-end confirmation)
```

### Invariant Rules:
*   Every journey step must be backed by a concrete Core timestamp and state event.
*   If path switching cannot be causally proven for a specific `messageId`, do NOT manufacture a synthetic path change step.
*   Progressive tap allows inspecting the full forensic trace for any selected message.