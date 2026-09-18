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