# Aryntra Pravah

> A mobile-first, transport-agnostic distributed communication system foundation.

---

## 1. Project Overview

**Aryntra Pravah** is designed to evolve from a Java standard-library networking and concurrency system into a resilient peer-to-peer distributed communication platform.

* **Current Phase:** Phase 6 - Connectivity Evolution (Completed S6.1-S6.3)
* **Initial Implementation:** Java CLI/TCP prototype (standard library)
* **Long-term Model:** Peer <-> Peer (transport-agnostic across BLE, LAN, and Internet)

---

## 2. Architectural Principles

1. **Hybrid Micro-Components:** Small, cohesive components with strict boundary separation.
2. **Strict Contracts:** Components interact strictly through explicit interfaces without monolithic coupling.
3. **Transport-Agnostic Core:** The core message and application semantics are completely decoupled from network transport mechanisms (TCP, BLE, Wi-Fi Direct).
4. **Minimal External Dependencies:** Built using Java standard library networking (`java.net`), concurrency (`java.util.concurrent`), and logging (`java.util.logging`).

---

## 3. Prerequisites

* **Java Development Kit (JDK):** Version 21 LTS or newer
* **Build System:** Apache Maven 3.9+

---

## 4. Build, Test, and Run

### Build the Project
```bash
mvn clean compile
```

### Run Tests
```Bash
mvn test
```

### Package JAR
```Bash
mvn clean package
```

### Run the Application
```Bash
java -jar target/pravah-0.1.0-SNAPSHOT.jar
```

### Or via Maven Exec:
```Bash
mvn exec:java -Dexec.mainClass="com.aryntra.pravah.Main"
```

## 5. Repository Structure
```text
aryantra-pravah/
|-- src/
|   |-- main/java/com/aryntra/pravah/
|   |   |-- core/         # Lifecycle, configuration, and orchestration
|   |   |-- protocol/     # Protocol framing, wire format, and serialization
|   |   |-- transport/    # Transport contracts and implementations
|   |   |-- connectivity/ # Path model, multi-path peer, capability model (Phase 6)
|   |   |-- peer/         # Peer identity, registry, presence, router (Phase 3)
|   |   |-- messaging/    # Application messages, conversations, lifecycle (Phase 4)
|   |   |   |-- reliability/ # Delivery outbox, retry, group reliability (Phase 5)
|   |   |-- server/       # TCP Server foundation (Phase 1)
|   |   `-- client/       # CLI / Client runtime (Phase 1)
|   `-- test/java/        # Unit, smoke, and contract verification tests
|-- docs/
|   |-- architecture/     # Architectural documentation & ADRs
|   |-- protocol/         # Wire protocol specifications
|   |-- sprints/          # Sprint plans and retrospectives (Phase 0-5)
|   `-- experiments/      # Contract verification benchmarks
|-- pom.xml               # Maven configuration (Java 21 target)
`-- README.md
```

## 6. Roadmap & Sprint Status

| Phase | Description | Status | Tests |
| --- | --- | --- | --- |
| **Phase 0** | Foundation & Project Bootstrap (S0.1–S0.4) | ✅ Completed | 1/1 |
| **Phase 1** | TCP Transport & Multi-Connection Registry (S1.1–S1.5) | ✅ Completed | 15/15 |
| **Phase 2** | Wire Protocol & Length-Prefixed Framing (S2.1–S2.5) | ✅ Completed | 49/49 |
| **Phase 3** | Peer Communication Substrate & Lifecycle (S3.1–S3.6) | ✅ Completed | 179/179 |
| **Phase 4** | Application Messaging, Conversations & Lifecycle (S4.1–S4.6) | ✅ Completed | 230/230 |
| **Phase 5** | Reliable Delivery, Bounded Retry & Group Reliability (S5.1–S5.6) | ✅ Completed | 271/271 |
| **Phase 6** | Connectivity Model, Multi-Path & Capabilities (S6.1–S6.3) | ✅ Completed | 291/291 |

### Phase 6 Breakdown
* **S6.1** Connectivity Model (EndpointAddress, PathId, PathState, ConnectivityPath, PeerConnectivity)
* **S6.2** Multi-Path Peer Representation (PeerConnectivityRegistry mapping single stable PeerId to multiple concurrent paths)
* **S6.3** Transport Capability Model (TransportCapabilities modeling properties like 
eliable or connectionOriented; Transport default method, TcpTransport capabilities)

### Phase 4 Breakdown
* **S4.1** Application Messaging Boundary (`ApplicationMessage`, `ApplicationMessagingService`, `ApplicationMessageListener`)
* **S4.2** Conversation Model (`ConversationId`, `Conversation`, `ConversationManager`)
* **S4.3** Message Lifecycle & Delivery Receipts (`MessageState`: `CREATED` → `SENT` → `DELIVERED`, application ACK framing)


### Phase 5 Breakdown
* **S5.1–S5.3** Delivery Intent, Offline Queue, Retry & Reconnection (OutboxState, DeliveryOutbox, DeliveryRetryManager)
* **S5.4** Bounded Retry & Dead-Letter (ABANDONED state, attempt counting, configurable maxAttempts)
* **S5.5** Delivery Attempt Observability (RetryAttemptListener, passive event-driven observation)
* **S5.6** Reliable Group Delivery (per-recipient GroupDeliveryOutbox, independent retry per participant)

**Next Phase:** Phase 6 — TBD