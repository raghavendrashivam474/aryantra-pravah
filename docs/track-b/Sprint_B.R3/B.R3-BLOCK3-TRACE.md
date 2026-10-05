# B.R3 Block 3 — Test Baseline & Architecture Trace
## Timestamp: 2026-10-05 08:30:36

## Test Results:
See console output above.

## Key Architecture Findings:

### Message Identity: ALREADY EXISTS
- Protocol Message: record(type, senderId, messageId, payload)
- ApplicationMessage: messageId (UUID), sender (PeerId), content, timestamp, conversationId
- Both levels have stable messageId — survives transport changes

### ACK: ALREADY EXISTS (Application Layer)
- APP_MSG_ACK = 0x02 byte in application payload framing
- handleInboundAck() processes incoming ACKs
- sendApplicationAck() sends ACK back to sender
- NOT a protocol-level MessageType (only JOIN/MESSAGE/LEAVE exist)

### Delivery States: ALREADY EXISTS
- MessageState enum in DefaultApplicationMessagingService
- ConcurrentHashMap<String, MessageState> tracks per-message state
- DeliveryRetryManager has outbox + retry + listeners

### Ordering: CONFIRMED GAP
- Zero sequence/ordering mechanism in Message or ApplicationMessage
- TCP provides in-connection ordering
- Bluetooth provides in-connection ordering
- Cross-transport ordering (BT→TCP→BT) is NOT guaranteed
- This is the primary B.R3 improvement target

### TransitionBuffer: MATURE
- 64 msg / 256KB / 10s TTL defaults
- FIFO, bounded, TTL-based expiry
- Counters: buffered/flushed/expired/evicted/rejected
- Flush outside lock — well-designed

## Confirmed B.R3 Gaps:
1. No application-level sequence numbers
2. MessageState values need formal documentation
3. ACK contract needs clarification (received vs processed)
4. Cross-transport ordering not guaranteed during migration
