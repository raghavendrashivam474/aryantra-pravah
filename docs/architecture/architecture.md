# Aryntra Pravah — System Architecture

## 1. Project Purpose & Long-Term Vision
Aryntra Pravah is a mobile-first, transport-agnostic distributed communication platform. While starting as a standard Java TCP client-server foundation for networking mastery, Pravah is designed to evolve into a peer-to-peer (P2P) communication fabric capable of dynamic transport switching (BLE, LAN, Internet/TCP).

## 2. Core Architectural Principles
* **Hybrid Micro-Components:** Small, single-responsibility components with strict separation.
* **Strict Contracts:** Components communicate strictly via interfaces.
* **Dependency Direction:** Application -> Core -> Protocol -> Transport -> Network. Lower layers never depend on higher-layer semantics.

## 3. Key Component Boundaries
* **Transport Boundary:** Decoupled transport contracts allowing core to switch between TCP, BLE, and LAN easily.
* **Message Boundary:** Separation between raw bytes, structured frames, and rich application message entities.
* **Peer Boundary:** Separation between logical peer identities (`PeerId`) and transient transport socket descriptors (`connectionId`).
* **Application Boundary:** Separation between network protocol envelopes (`Message`) and rich domain models (`ApplicationMessage`, `Conversation`).

## 4. Phase 1 — TCP Transport Architecture
* **Implementation:** `TcpTransport` implements `Transport` using standard Java networking (`ServerSocket`, `Socket`).
* **Connection Registry:** Thread-safe registry (`ConcurrentHashMap`) tracking active `TcpConnection` objects. This allows a single transport instance to maintain multiple concurrent inbound and outbound connections.
* **Connection Identity:** Transport-level connection is identified by remote socket addresses (`/ip:port` normalized representation).
* **Concurrency Model:** A single `acceptLoop` runs on a background accept thread; each accepted connection spawns an independent, lightweight `readLoop` reader thread.
* **Failure & Connection Isolation:** Error isolation per connection. A failure on one connection cleans up only its own resources without affecting other connections.
* **Lifecycle & Resources:** Explicit `start()` and `stop()` lifecycle with clean socket and thread teardown.

## 5. Phase 2 — Wire Protocol & Framing Architecture
* **Length-Prefixed Framing:** `FrameEncoder` / `FrameDecoder` implement 4-byte big-endian length prefix framing (`[LENGTH (4 bytes)] [PAYLOAD (N bytes)]`).
* **Protocol Message Schema:** `Message` record with `MessageType` (`JOIN`, `MESSAGE`, `LEAVE`), `senderId`, `messageId`, and byte payload.
* **Serialization:** `MessageEncoder` / `MessageParser` serialize and parse protocol envelopes with strict wire-format validation and maximum payload guardrails (ADR-003).
* **Protocol Session Management:** `ProtocolSessionManager` manages peer lifecycle states (`DISCONNECTED`, `JOINED`, `LEFT`) and rejects invalid state transitions (e.g., `MESSAGE` before `JOIN`).

## 6. Phase 3 — Peer Communication Substrate Architecture
* **Logical Identity:** `PeerId` represents the immutable, cryptographic or alphanumeric identity of a node, decoupled from IP addresses and socket descriptors.
* **Peer Registry:** `PeerRegistry` maps `PeerId` to active transport `connectionId`s.
* **Routing:** `PeerRouter` allows upper layers to address messages by `PeerId` rather than socket identifiers (`send(PeerId, Message)`).
* **Discovery:** `LanPeerDiscovery` broadcasts and listens for UDP discovery beacons on local subnets.
* **Presence Engine:** `PeerPresenceManager` maintains TTL-based presence states (`UNKNOWN`, `DISCOVERED`, `AVAILABLE`, `CONNECTED`, `UNAVAILABLE`) with background heartbeat monitoring.
* **Bridge & Coordination:** `PeerPresenceBridge` connects discovery beacons to presence and registry. `PeerConnectionCoordinator` binds transport connection lifecycle events to the protocol session manager and presence state machine.

## 7. Phase 4 — Application Messaging Layer Architecture
* **Application Message Boundary:** `ApplicationMessage` represents user/application payloads (`messageId`, `sender`, `content`, `timestamp`, `conversationId`). Callers use `ApplicationMessagingService` without direct coupling to TCP, sockets, or protocol framing.
* **Conversation Model:** `Conversation` is an immutable domain container containing a unique `ConversationId` and exactly two distinct `PeerId` participants. `ConversationManager` guarantees deterministic, order-independent canonical identity derivation (`alice ↔ bob` resolves to `direct:alice:bob` regardless of initiator).
* **Message Lifecycle & Delivery Receipts:**
  - `MessageState`: State machine tracking message progress (`CREATED` → `SENT` → `DELIVERED` | `FAILED`).
  - Application ACK Framing: Inbound chat payloads (`0x01` CHAT) automatically trigger application-level delivery receipts (`0x02` ACK) back to the sender within the existing protocol envelope.
  - Delivery Correlation: Received ACKs correlate with outgoing `messageId`s and trigger `MessageLifecycleListener` callbacks to update UI/application state to `DELIVERED`.

## 8. Complete System Layer Stack

```text
┌─────────────────────────────────────────────────────────┐
│                      APPLICATION                        │
│                                                         │
│  UI / Chat Consumers / Domain Controllers               │
└────────────────────────────┬────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│              APPLICATION MESSAGING LAYER                │
│                                                         │
│  ApplicationMessage · Conversation · ConversationManager│
│  MessageState · ApplicationMessagingService             │
└────────────────────────────┬────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│                    PROTOCOL LAYER                       │
│                                                         │
│  Message · MessageType · ProtocolSessionManager         │
│  MessageEncoder · MessageParser · FrameEncoder/Decoder  │
└────────────────────────────┬────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│                     PEER SYSTEM                         │
│                                                         │
│  PeerId · PeerRegistry · PeerRouter · PeerConnection    │
│  LanPeerDiscovery · PeerPresenceManager · PresenceBridge│
└────────────────────────────┬────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│                   TRANSPORT LAYER                       │
│                                                         │
│  Transport · TransportListener · TcpTransport           │
└────────────────────────────┬────────────────────────────┘
                             │
                             ▼
                  Network / TCP Sockets
```