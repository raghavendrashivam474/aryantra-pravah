# Pravaah Security Roadmap & Scope
## SX.1 — Threat Model & Security Principles

This document acts as the planning blueprint and scope enforcement contract for the subsequent Security X sprints. It lists our unresolved research questions, divides responsibilities across target sprints, and details the mandatory change protocol.

---

## 1. Open Questions (Section 20)

These questions represent systemic challenges identified during our code discovery. They **must not** be answered with simple assumptions during implementation; they are research inputs for future sprints.

### 1.1 Identity
*   **Identity Binding:** Should `PeerId` remain a purely logical, randomized UUID string, or should it be derived directly from a cryptographic public key (e.g., hash of an asymmetric public key)?
*   **Persistence & Reinstalls:** How does a peer's long-term identity persist across application uninstalls on Android? If a private key is lost, how does a peer re-establish identity without breaking existing trust relationships on other devices?
*   **Device vs. Software Identity:** Is `PeerId` bound to the physical hardware (e.g., using Android Keystore/TEE backed keys) or is it a software-level artifact?

### 1.2 Pairing
*   **Trust Lifecycle:** Is human verification a one-time enrollment process or a recurring security checkpoint?
*   **Out-of-Band Channels:** Beyond manual visual comparison of short codes, can we utilize camera/QR-code scanning or NFC taps to relay pairing packets securely?
*   **Unpaired Interaction Limits:** What subset of protocol features (if any) are allowed to execute with an unpaired candidate device? (e.g., Does discovery execute, but application messaging reject?)

### 1.3 Sessions
*   **Session Lifecycle:** What events establish a secure session? Does a session survive local application restarts or must it re-handshake?
*   **Resumption:** Does Pravaah support low-latency session resumption (like TLS 1.3 0-RTT), or must every path selection change trigger a fresh key exchange?
*   **Multi-Device Sessions:** If one user owns multiple devices, does each device maintain a distinct `PeerId`, or do they share an identity key across a sub-grid?

### 1.4 Paths
*   **Path Authentication:** When a new path (e.g., moving from TCP to Bluetooth) is activated, how does the session layer verify that the physical connection belongs to the same logical `PeerId` without performing a full pairing handshake again?
*   **Path Quarantine:** If a path experiences high packet injection failures or structural corruption, what are the thresholds for quarantining that specific `EndpointAddress`?

### 1.5 Traffic Fabric
*   **Hiding Characteristics:** What metrics are we trying to hide from passive observers? Packet size distribution? Transmission interval timing?
*   **Complexity Budget:** What is the maximum acceptable latency, throughput, and battery overhead that the experimental Traffic Fabric can introduce before it fails its feasibility metrics?

---

## 2. Security X Sprint Roadmap (Section 4 & 5)

To prevent scope creep, work is strictly partitioned across the following sequence.

```text
SX.1 (This Sprint) ──► Threat Model & Principles (Zero Code)
│
├──► SX.2: Cryptographic Identity (PeerId ↔ Key Binding)
│
├──► SX.3: Pairing Protocol (Short Code, Human Verification)
│
├──► SX.4: Secure Sessions (Handshake, Ephemeral Keys, Rekey)
│
├──► SX.5: Secure Framing (Wire Format Encryption, MAC, Nonces)
│
├──► SX.6: Path Verification (Challenge-Response on Paths)
│
├──► SX.7: Key Lifecycle & Keystore (Android TEE Storage)
│
├──► SX.8: Traffic Fabric (Multi-path experimental obfuscation)
│
└──► SX.9: Security Regression & Verification
```

### SX.2 — Cryptographic Identity
*   **Focus:** Bind `PeerId` to cryptographic primitives.
*   **In-Scope:** Public key representation, identity generation, signature utility wrappers, serialization of public keys.
*   **Out-of-Scope:** Session key exchanges, encrypted frames, UI changes.

### SX.3 — Pairing Protocol
*   **Focus:** Human-verifiable key enrollment.
*   **In-Scope:** Diffie-Hellman/ECDH exchange protocol, short code generation, pairing state machine, pairing success/failure persistence.
*   **Out-of-Scope:** Frame encryption, persistent message encryption.

### SX.4 — Secure Sessions
*   **Focus:** Session key negotiation and liveness.
*   **In-Scope:** Ephemeral handshake protocol, session keys, forward secrecy mechanisms, session state tracking in `ProtocolSessionManager`.
*   **Out-of-Scope:** Custom transport implementations, hardware key binding.

### SX.5 — Secure Framing
*   **Focus:** Wire-format confidentiality and integrity.
*   **In-Scope:** Modifying `MessageEncoder` and `MessageParser` to support authenticated encryption (AEAD), sequence numbers to prevent replays, and framing headers.
*   **Out-of-Scope:** Changing the `Transport` interfaces or routing logic.

### SX.6 — Path Verification
*   **Focus:** Cryptographic binding of physical connections to sessions.
*   **In-Scope:** Simple challenge-response exchange on newly activated `ConnectivityPath` instances to ensure path owner owns the session identity.
*   **Out-of-Scope:** Modifying existing TCP/Bluetooth socket layers.

### SX.7 — Key Lifecycle & Keystore
*   **Focus:** Safe storage of long-term credentials.
*   **In-Scope:** Android Keystore integration, TEE-backed key generation, private key isolation, backup/recovery strategies.
*   **Out-of-Scope:** Protocol changes.

### SX.8 — Traffic Fabric (Experimental Hardening)
*   **Focus:** Multi-stream distribution and traffic analysis mitigation.
*   **In-Scope:** Multi-path frame scheduling rules, artificial padding, dummy frames, timing jitter injection.
*   **Out-of-Scope:** Primary confidentiality (this must already be solved by SX.5).

### SX.9 — Security Regression & Verification
*   **Focus:** Pen-testing, performance validation, and code audit.
*   **In-Scope:** Automated replay tests, network failure fuzzing, resource exhaustion validation.
*   **Out-of-Scope:** Adding new features.

---

## 3. Architectural Change Protocol (Section 23)

If a developer during future sprints believes the existing architecture (transports, routing, discovery) is insufficient to satisfy a security requirement:

**THEY MUST NOT IMMEDIATELY MODIFY THE PROTECTED CORE.**

Instead, they must strictly follow this review gate:

1.  **Stop Work:** Cease implementation on the affected component.
2.  **Author an Engineering Note:** Create a document summarizing the limitation using this markdown template:
    `markdown
    ### Architectural Limitation
    *   **Current State:** <Explain the current code design>
    *   **Observed Problem:** <Evidence-backed explanation of the issue>
    *   **Impediment Reason:** <Why the current contract prevents security>
    *   **Evaluated Options:**
        1. <Option A + Impact>
        2. <Option B + Impact>
    *   **Recommended Option:** <Chosen path>
    *   **Regression Footprint:** <Affected tests and components>
    `
3.  **Submit for Review:** The note must undergo senior engineering review.
4.  **Author ADR:** If approved, a formal Architecture Decision Record (ADR-015+) must be written before any production code is touched.

---

## 4. Protected Existing Behavior (Section 24)

The following components are protected under the SX.1 baseline. No modification is allowed to change their structural interfaces or basic behavioral guarantees during initial security sprints:

1.  **Multi-Path Independence:** If a device loses its TCP connection but retains Bluetooth, the logical `PeerId` connection must survive intact.
2.  **Interface Integrity:** `Transport`, `TransportListener`, and `PeerRouter` interfaces are preserved to ensure backward compatibility.
3.  **Discovery/Addressing Decoupling:** Discovery remains a candidate-gathering phase. Discovery must **never** write directly to session-key databases.

---

## 5. SX.1 Sprint Completion Sign-off (Section 27)

*   **Existing Core Intact:** [CONFIRMED] No Java or Kotlin production files have been modified.
*   **Regression Suite:** [CONFIRMED] 346 tests executed successfully with 0 failures, 0 errors, and 0 skips.
*   **Documentation Surface:** [CONFIRMED] All three required deliverables generated inside `docs/security/`.

This blueprint is ready to guide Security X development.
