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
* **Implementation:** `TcpTransport` implements `Transport` using Java standard library networking (`ServerSocket`, `Socket`).
* **Concurrency Model:** Daemon background threads manage socket acceptance (`acceptLoop`) and inbound stream reading (`readLoop`).
* **Lifecycle & Resources:** Explicit start/stop controls with resource cleanup on server sockets, active sockets, and I/O streams.
* **Decoupling:** Higher layers interact solely with `Transport` and `TransportListener` using raw byte payloads (`byte[]`), keeping protocol and message semantics uncoupled.