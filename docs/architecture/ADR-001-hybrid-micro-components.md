# ADR-001: Hybrid Micro-Component Architecture and Strict Contracts

## Status
Accepted

## Context
Aryntra Pravah is designed to evolve from a Java standard-library networking learning project into a mobile-first, transport-agnostic distributed communication engine. We need to prevent monolithic coupling while avoiding over-engineered enterprise abstractions.

## Decision
1. We adopt a **Hybrid Micro-Component Architecture** where each component has a single, strictly defined responsibility.
2. We enforce **Strict Contracts** across architectural boundaries: Application -> Core -> Protocol -> Transport.
3. Lower layers must remain completely agnostic of higher-level semantics. Specifically, the Transport interface deals only with byte streams and connectivity, without knowledge of message semantics or business logic.

## Consequences
* **Positive:** Testability (using mock transports) and Extensibility (easy to add BLE/Wi-Fi later).
* **Negative:** Slight initial overhead in defining explicit interfaces before concrete implementations.