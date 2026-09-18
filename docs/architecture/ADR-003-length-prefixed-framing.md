# ADR-003: Length-Prefixed Stream Framing

## Status
Accepted

## Context
TCP provides a reliable stream of continuous bytes without preserving application message boundaries.
MessageEncoder outputs a deterministic byte array for a logical Message, and MessageParser strictly
reconstructs a message from an exact byte slice. However, when reading from a TCP socket, reads can be fragmented,
coalesced, or delivered in arbitrary chunks.

A mechanism is required to delineate message boundaries over a streaming transport without embedding transport
assumptions into MessageEncoder or MessageParser.

## Decision
We introduce a dedicated length-prefixed framing layer consisting of:
1. FrameEncoder: Prepends a 4-byte big-endian length prefix denoting the byte length of the encoded message.
2. FrameDecoder: Maintains a per-connection stream accumulator to extract complete frames as bytes arrive.

### Envelope Specification
```text
+-------------------------+-----------------------------------------+
| FRAME_LEN (4B, big-end) | ENCODED_MESSAGE (FRAME_LEN bytes)       |
+-------------------------+-----------------------------------------+
```

### Safety Rules

- Maximum frame size is capped at 16 MB (16,777,216 bytes) to prevent uncontrolled memory allocation.
- Frames with length <= 0 are rejected immediately with ProtocolException.
- Each TCP connection must maintain its own FrameDecoder instance. State is never shared between peers.

### Consequences

- Positive: Clean separation of concerns. MessageEncoder remains pure; TcpTransport remains byte-oriented.
- Positive: Transparent support for fragmentation, coalescing, and mixed byte chunking.
- Positive: Protection against malicious or malformed length headers.
- Negative: Adds 4 bytes of overhead per transmitted message.