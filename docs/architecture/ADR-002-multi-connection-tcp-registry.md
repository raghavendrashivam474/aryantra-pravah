# ADR-002: Multi-Connection TCP Transport and Connection Registry

## Status
Accepted

## Context
During sprints S1.1 through S1.3, `TcpTransport` used a single `activeSocket` reference to establish baseline bidirectional TCP communication. While sufficient for single client-server pairs, this model had significant limitations:
1. Connecting a new peer overwrote or broke the active connection of existing peers.
2. `send(destinationId, payload)` could only dispatch to the single active peer regardless of the `destinationId` specified.
3. A failure or closure of one connection affected the entire transport state.

S1.4 and S1.5 required supporting multiple simultaneous peer connections, independent per-connection read loops, destination-aware sending, and fault-isolated lifecycles without modifying the public `Transport` interface contract or pulling in Phase 2 abstractions prematurely.

## Decision
1. **Connection Container (`TcpConnection`):** We introduced a private, cohesive internal class `TcpConnection` within `TcpTransport` encapsulating the connection's `Socket`, `OutputStream`, and dedicated `readerThread`.
2. **Concurrent Connection Registry:** We replaced `activeSocket` with a `ConcurrentHashMap<String, TcpConnection>`.
3. **Connection Identity & Addressing:** Inbound and outbound connections are registered using their remote socket address as the identifier (`socket.getRemoteSocketAddress().toString()`). Destination lookup normalizes address formats (e.g. leading slashes) and falls back gracefully when exactly one peer is connected.
4. **Isolated Lifecycle & Teardown:** Each connection's `readLoop` independently catches I/O failures, closes only that connection, interrupts only its own reader thread, and unregisters itself from the registry without disrupting other peers.
5. **Full Transport Shutdown:** `TcpTransport.stop()` marks the transport as stopped, closes the server socket to unblock `accept()`, unregisters and closes all active `TcpConnection` instances, and clears the connection registry.

## Consequences
### Positive
* **Full Multi-Connection Support:** Multiple peers can connect and communicate simultaneously without contention.
* **Fault Isolation:** A network failure or disconnect from one peer leaves other connected peers fully functional.
* **Stable Public API:** The `Transport` interface (`getName`, `start`, `stop`, `isRunning`, `send`, `setListener`) remains completely unchanged.
* **Clean Lifecycle:** No dangling threads, leaked sockets, or stale connection references upon shutdown or failure.

### Trade-offs / Limitations
* Physical remote socket address is used for peer identification at this layer; logical identity and handshake protocols will be introduced in subsequent protocol phases (Phase 2).
* Payloads remain raw byte streams (`byte[]`); message framing boundaries are intentionally deferred to Phase 2.