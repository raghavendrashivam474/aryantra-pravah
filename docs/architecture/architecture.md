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

## 4. Phase 1 — TCP Transport Architecture
* **Implementation:** `TcpTransport` implements `Transport` using standard Java networking (`ServerSocket`, `Socket`).
* **Connection Registry:** Replaced the single-active-connection model with a thread-safe registry (`ConcurrentHashMap`) tracking active `TcpConnection` objects. This allows a single transport instance to maintain multiple concurrent inbound and outbound connections.
* **Connection Identity:** Peer identity is pragmatically identified using remote socket addresses (`/ip:port` normalized representation), leaving logical handshake identities to higher protocol layers.
* **Concurrency Model:** A single `acceptLoop` runs on a background accept thread, while each accepted connection spawns an independent, lightweight `readLoop` reader thread.
* **Failure & Connection Isolation:** Each connection has isolated error boundary handling. A reading or writing failure on one connection cleans up only its own resources and removes itself from the registry, leaving all other active connections completely unaffected.
* **Lifecycle & Resources:** Explicit, thread-safe, and repeatable `start()` and `stop()` lifecycle. Stopping the transport systematically closes the listening server socket, shuts down and unbinds every registered connection, interrupts reader threads, and safely clears the registry.