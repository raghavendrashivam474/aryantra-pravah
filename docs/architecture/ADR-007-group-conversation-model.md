# ADR-007 - Group Conversation Model & Application Framing

## Status
Accepted (S4.5)

## Problem
S4.1-S4.3 established direct (1-to-1) conversations with canonical identity
derivation (`direct:alice:bob`). S4.5 requires extending application communication
to groups with 2 or more participants, stable group identities, and application-layer
message fan-out.

## Constraints & Requirements
1. Direct conversations (`Conversation`, exactly 2 participants) must remain 100% untouched.
2. Group identity (`ConversationId`) must be stable and decoupled from participant membership changes.
3. Logical identity remains `PeerId`, transport connection identity remains `connectionId`.
4. `PeerRouter` must not be modified to add group routing; fan-out is owned by the application layer.
5. Inbound group messages must carry their `ConversationId` over the wire so recipients persist under the group context rather than direct context.

## Selected Design

### Group Model
`GroupConversation` represents a named group with a stable `ConversationId` and a `Set<PeerId>` of participants.
Participants can be added or removed without altering the `ConversationId`.

### Wire Framing
Application messages are framed with an application header byte:
- `0x01` (`APP_MSG_CHAT`): Direct chat payload `[0x01][UTF-8 text]`. Reconstructs canonical direct ID.
- `0x02` (`APP_MSG_ACK`): Delivery receipt `[0x02][UTF-8 messageId]`.
- `0x03` (`APP_MSG_GROUP_CHAT`): Group chat payload `[0x03][2-byte groupId length][UTF-8 groupId][UTF-8 text]`.

### Fan-out Semantics
When sending to a group:
1. Sender retrieves group participants from `ConversationManager`.
2. Sender persists message locally under `groupId` with state `CREATED`.
3. For each participant `P != localPeerId`:
   - Frame payload with `0x03` + `groupId` + text.
   - Route to `P` via `PeerRouter.send(P, protocolMsg)`.
4. Sender transitions message state to `SENT`.
5. Each recipient receives `0x03`, parses `groupId`, persists message under `groupId` with `DELIVERED`, notifies listeners, and replies with `0x02` ACK.