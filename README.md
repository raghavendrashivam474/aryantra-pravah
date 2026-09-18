# Aryntra Pravah

> A mobile-first, transport-agnostic distributed communication system foundation.

---

## 1. Project Overview

**Aryntra Pravah** is designed to evolve from a Java standard-library networking and concurrency system into a resilient peer-to-peer distributed communication platform.

* **Current Phase:** Phase 0 — Foundation & Project Bootstrap
* **Initial Implementation:** Java CLI/TCP prototype (standard library)
* **Long-term Model:** Peer ↔ Peer (transport-agnostic across BLE, LAN, and Internet)

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
├── src/
│   ├── main/java/com/aryntra/pravah/
│   │   ├── core/         # Lifecycle, configuration, and orchestration
│   │   ├── protocol/     # Protocol framing, wire format, and serialization
│   │   ├── transport/    # Transport contracts and implementations
│   │   ├── peer/         # Peer identity and registry
│   │   ├── server/       # TCP Server foundation (Phase 1)
│   │   └── client/       # CLI / Client runtime (Phase 1)
│   └── test/java/        # Unit, smoke, and contract verification tests
├── docs/
│   ├── architecture/     # Architectural documentation & ADRs
│   ├── protocol/         # Wire protocol specifications
│   └── experiments/      # Contract verification benchmarks
├── pom.xml               # Maven configuration (Java 21 target)
└── README.md
```

## 6. Current Phase Status

| Sprint | Objective | Status |
| --- | --- | --- |
| **S0.1** | Project Bootstrap (Repository, Build, Test setup) | In Progress |
| **S0.2** | Architecture Contracts (Boundaries, ADR-001) | Pending |
| **S0.3** | Core Runtime Foundation (Lifecycle, Config, Logging) | Pending |
| **S0.4** | Architecture Verification (Transport contract smoke test) | Pending |
