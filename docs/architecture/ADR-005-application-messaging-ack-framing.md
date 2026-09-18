# ADR-005: Application Messaging Boundary and Delivery Acknowledgement Framing

## Status
Accepted

## Context
In Phase 3 (v0.3.6), Pravah established a frozen, verified peer substrate consisting of `PeerId`, `PeerRegistry`, `PeerRouter`, `LanPeerDiscovery`, `PeerPresenceManager`, `PeerPresenceBridge`, and `PeerConnectionCoordinator`. The wire format (v1) and protocol definitions (`Message`, `MessageType`, `MessageEncoder`, `MessageParser`, `FrameDecoder`, `FrameEncoder`) operate strictly on protocol envelopes (`JOIN`, `MESSAGE`, `LEAVE`).

In Phase 4 (S4.1–S4.3), we introduced application semantics:
1. High-level `ApplicationMessage` representation.
2. Direct `Conversation` relationships and canonical identity mapping.
3. Message lifecycle state tracking (`CREATED`, `SENT`, `DELIVERED`, `FAILED`).

To represent `DELIVERED` faithfully according to the sprint brief, the system required an explicit remote receipt mechanism confirming that the remote node's application layer received and parsed the message.

We faced an architectural choice:
1. **Option A (Protocol Modification):** Add a new `MessageType.ACK` to the wire format enum and modify `MessageParser`, `MessageEncoder`, and `ProtocolSessionManager`.
2. **Option B (Application Framing in Message Payload):** Keep the Phase 3 protocol substrate 100% frozen and frame application-level payloads with a type byte prefix (`0x01` = CHAT, `0x02` = ACK) inside the standard `MessageType.MESSAGE` envelope payload.

## Decision
We chose **Option B (Application Framing in Message Payload)**.

1. **Protocol Preservation:** The lower protocol layer continues to treat all application payloads as opaque byte arrays transported via `MessageType.MESSAGE`. No frozen Phase 3 classes were modified.
2. **Application Payload Type Prefix:**
   - `0x01` (`APP_MSG_CHAT`): Followed by the UTF-8 text content of the user message.
   - `0x02` (`APP_MSG_ACK`): Followed by the UTF-8 `messageId` of the acknowledged message.
3. **Automatic Delivery Flow:**
   - When `DefaultApplicationMessagingService` receives a `0x01` chat message, it notifies application listeners and immediately routes a `0x02` ACK packet back to the sender peer via `PeerRouter`.
   - When the sender receives a `0x02` ACK packet, it extracts the target `messageId`, updates the in-memory `MessageState` to `DELIVERED`, and triggers registered `MessageLifecycleListener` callbacks.

## Consequences

### Positive
* **Zero Foundation Contamination:** Phase 3's 179 unit/integration tests remain untouched and 100% green.
* **Separation of Concerns:** Lower protocol layers remain agnostic to chat semantics, conversations, and delivery receipts.
* **Extensibility:** The application type prefix can easily be extended in future sprints (e.g., typing indicators, reactions, attachments) without requiring wire-protocol version bumps.

### Trade-offs
* Application messaging services on both peers must adhere to the application framing convention to interpret message content and ACKs correctly.