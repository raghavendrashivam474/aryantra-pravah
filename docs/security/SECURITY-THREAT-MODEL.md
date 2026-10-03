# Pravaah Formal Threat Model
## SX.1 — Threat Model & Security Principles

This document establishes the formal threat model for Pravaah before any Internet-facing communication or cryptographic implementation begins. 

---

## 1. Asset Inventory (Section 12)

This inventory defines the assets we aim to protect, categorized by their structural and runtime roles.

### 1.1 Identity Assets
*   **Peer Identity (PeerId)**: Representing *who* is communicating. Today, this is a plain UUID string. In future phases, this must map to a stable, cryptographically verifiable representation.
*   **Device Identity**: Physical attributes of the running node (e.g., Android hardware features, MAC addresses, IP addresses).
*   **Pairing State**: Information indicating whether two peers have successfully completed verification and established a mutual relationship.
*   **Trust Relationship**: The logical metadata binding two or more PeerIs as "mutually trusted."

### 1.2 Communication Assets
*   **Message Content (Payload)**: The raw application payload transmitted over paths. Contains potentially sensitive user data, media, or signaling.
*   **Message Authenticity**: The binding that guarantees a message was actually authored by the declared senderId.
*   **Message Ordering**: The correct delivery sequence of messages within a conversation.
*   **Message Freshness**: The guarantee that a received message is part of current execution and is not a replayed historic packet.
*   **Session State**: Liveness and connection tracking in the ProtocolSessionManager.
*   **ACK State**: Reliable delivery records, outboxes, and state tracking under the messaging/reliability layer.

### 1.3 Security Assets
*   **Session Keys (Future)**: Ephemeral keys used to protect session traffic.
*   **Long-Term Identity Keys (Future)**: Asymmetric key pairs representing a peer's permanent cryptographic anchor.
*   **Ephemeral Session Material (Future)**: Temporary state (e.g., Diffie-Hellman shares) used during handshakes.
*   **Key Lifecycle State**: The state of initialization, storage, rotation, and revocation of cryptographic keys.

### 1.4 Metadata Sensitivity Analysis
*   **Peer Identifiers**: Revealing *who* is in proximity. Considered **sensitive** under standard deployments; should eventually be obfuscated on untrusted paths.
*   **Connection Timing**: Timestamps of message transmission and connection lifecycles.
*   **Path Type & Availability**: Knowledge of whether a target is using TCP, Bluetooth, or both.
*   **Traffic Volume & Packet Sizes**: Sizes of encrypted frames, transmission frequency, and overall throughput.
*   *Note on Metadata:* Pravaah does **not** guarantee perfect metadata hiding in its initial security iterations. However, we document path and timing metadata as sensitive to guide future protocol hardening (such as the Traffic Fabric).

---

## 2. Trust Boundaries (Section 13)

We draw clear boundaries across our software layers and physical topologies. Nothing crossing a trust boundary is trusted automatically.

```text
   [ APPLICATION LAYER ] (Trusted local application state)
================= Trust Boundary 1: API Boundary =================
[ PRAVAAH CORE ] (PeerRouter, Messaging, SessionManager)
================= Trust Boundary 2: Transport Abstraction ========
[ COMPOSITE TRANSPORT ] (Aggregates physical paths)
================= Trust Boundary 3: Radio/Network Interface ======
/
[ TCP TRANSPORT ] [ BLUETOOTH TRANSPORT ]
\ /
================= Trust Boundary 4: Local vs Remote Device =======
[ UNTRUSTED PHYSICAL MEDIUM ] (Wi-Fi LAN, RF Spectrum, Internet)

```

### 2.1 The Core Boundaries
1.  **API Boundary (Application ↔ Pravaah Core)**: The application supplies data and expects isolation. The core must protect its internal cryptographic state from application-level memory leaks.
2.  **Transport Abstraction Boundary (Core ↔ Transports)**: The core passes serialized bytes to concrete transports. The concrete transports must only concern themselves with moving bytes. They must **never** be responsible for validating the semantic authenticity of those bytes.
3.  **Radio/Network Boundary (Transports ↔ OS/Hardware)**: The interface with the operating system socket layers and Bluetooth stacks. This boundary has the highest capability exposure (requiring permissions like BLUETOOTH_CONNECT, BLUETOOTH_SCAN, etc.).
4.  **Local vs Remote Boundary (Device A ↔ Untrusted Network ↔ Device B)**: Once bytes leave the physical device, they traverse untrusted space. Every incoming byte sequence from this boundary is considered **hostile** until proven otherwise by cryptographic means.

---

## 3. Attacker Model (Section 14)

We evaluate our security guarantees against six distinct threat agents.

```text
   ┌────────────────────────┐
   │   A1: Passive Observer │ (Observes physical medium)
   └───────────┬────────────┘
               │
   ┌───────────▼────────────┐
   │ A2: Active Net Attacker│ (Injects, drops, modifies, replays)
   └───────────┬────────────┘
               │
   ┌───────────▼────────────┐
   │     A3: Rogue Peer     │ (Legitimate but behaving maliciously)
   └───────────┬────────────┘
               │
   ┌───────────▼────────────┐
   │ A4: Rogue Discovered   │ (Injects unauthenticated candidates)
   └───────────┬────────────┘
               │
   ┌───────────▼────────────┐
   │   A5: Compromised Path │ (Controls one out of multiple active paths)
   └───────────┬────────────┘
               │
   ┌───────────▼────────────┐
   │  A6: Stolen/Compromised│ (Local physical or privileged root access)
   └────────────────────────┘
```

*   **A1 — Passive Observer**:
    *   *Capabilities:* Can intercept packets over the local network (LAN) or radio spectrum (Bluetooth sniffers). Can capture timestamps, volume, sequence order, and payload sizes.
    *   *Constraints:* Cannot modify messages, inject frames, block packets, or impersonate endpoints.
*   **A2 — Active Network Attacker**:
    *   *Capabilities:* Can intercept, modify, inject, drop, reorder, and replay traffic at will on any network path. Can spoof IP addresses and MAC addresses.
    *   *Constraints:* Cannot extract local device secrets unless the endpoints have cryptographic flaws.
*   **A3 — Rogue Peer**:
    *   *Capabilities:* A fully authenticated Pravaah participant (e.g., possesses valid keys or completed pairing) that behaves maliciously. Can send abusive payloads, violate protocol liveness rules, or attempt to crawl device files.
    *   *Constraints:* Bound by the limits of local endpoint sandboxing. *Note:* Authentication establishes identity; it does not make the peer trustworthy in every possible sense.
*   **A4 — Rogue Discovered Device**:
    *   *Capabilities:* An unauthenticated device in physical or network range. Can announce fake PeerIds via UDP multicast, advertise fraudulent Bluetooth channels, or broadcast garbage discovery candidates.
    *   *Constraints:* Cannot spoof cryptographic signatures of paired devices (once crypto is established).
*   **A5 — Compromised Path**:
    *   *Capabilities:* Controls exactly one of multiple active paths connecting Peer A and Peer B (e.g., controls the TCP path while the Bluetooth path remains clean).
    *   *Constraints:* The security model must guarantee that compromise of one physical path does **not** automatically compromise the secure peer relationship or expose the session running over other paths.
*   **A6 — Stolen / Locally Compromised Device**:
    *   *Capabilities:* Has full physical or root/administrative access to the local device. Can dump system memory, bypass storage permissions, read database files (such as SqliteMessageHistoryStore), and extract private key material.
    *   *Constraints:* We do not promise protection that the architecture cannot realistically provide. If the local OS is fully compromised and memory can be read, local cryptographic keys are compromised.

---

## 4. Candidate Requirements Matrix (Section 15)

The following matrix formally specifies which security objectives must be achieved, who provides them, and their scheduling priority.

| Objective | Required? | Currently Guaranteed? | Targeted Sprint | Out of Scope / Limitations |
|-----------|-----------|-----------------------|-----------------|----------------------------|
| **Confidentiality** | **Yes** | No | SX.4 (Secure Sessions) | Observers can still read packet sizes & traffic frequency. |
| **Integrity** | **Yes** | No | SX.4 (Secure Sessions) | Packet reordering can still be attempted at transport level. |
| **Peer Authenticity** | **Yes** | No | SX.2 (Crypto Identity) | Does not guarantee that the human holding the device is benevolent. |
| **Session Authenticity** | **Yes** | No | SX.4 (Secure Sessions) | Transport-level disconnections can still drop the session. |
| **Replay Resistance** | **Yes** | No | SX.4 (Secure Sessions) | Network-level duplicate frames must be rejected by session core. |
| **Freshness** | **Yes** | No | SX.4 (Secure Sessions) | System clocks can drift; must handle sequence-based freshness. |
| **Forward Secrecy** | **Yes** | No | SX.4 (Secure Sessions) | Only applies to sessions established *after* long-term key initialization. |
| **Key Isolation** | **Yes** | No | SX.4 (Secure Sessions) | Compromise of local private key breaks all isolation. |
| **Path Independence** | **Yes** | No | SX.8 (Traffic Fabric) | Paths are treated as untrusted channels. Loss of one path is a transport event, not a security breach. |
| **Availability** | No | No | Out of Scope | Active RF jamming and network-level packet dropping (DoS) cannot be architecturally prevented. |
| **Metadata Resistance**| No | No | SX.8 (Traffic Fabric / Future) | Absolute metadata hiding is out of scope. Traffic Fabric targets analysis mitigation, not perfect anonymity. |

---

*Prepared as an input blueprint for Security X implementation phases (SX.2 through SX.9).*

---

## 5. Formal Threat Catalogue (Section 27 DoD)

Systematic enumeration of threats mapped across trust boundaries, attacker classes, and affected assets. Each threat is rated by current exploitability given the evidence gathered in Blocks 2–6.

### 5.1 Threat Matrix: Trust Boundary × Attacker Class

#### Boundary 1: API Boundary (Application ↔ Pravaah Core)

| ID | Threat | Attacker | Asset Affected | Current Risk | Mitigation Status |
|----|--------|----------|---------------|--------------|-------------------|
| T-1.1 | Malicious application injects forged PeerId into Pravaah core API | A3 (Rogue Peer) | Peer Identity | **High** | No API-level identity validation exists |
| T-1.2 | Application reads in-memory session keys from Pravaah core | A6 (Compromised Device) | Session Keys, Long-Term Keys | **Critical** | No memory isolation; keys will live in JVM heap |
| T-1.3 | Application leaks message history via insecure storage | A6 (Compromised Device) | Message Content, ACK State | **High** | `SqliteMessageHistoryStore` and `SqliteDeliveryOutbox` use unencrypted SQLite |

#### Boundary 2: Transport Abstraction (Core ↔ Transports)

| ID | Threat | Attacker | Asset Affected | Current Risk | Mitigation Status |
|----|--------|----------|---------------|--------------|-------------------|
| T-2.1 | CompositeTransport delivers message with spoofed senderId from one path, accepted as authentic by core | A5 (Compromised Path) | Message Authenticity, Peer Authenticity | **Critical** | `onDataReceived(String senderId, byte[])` trusts senderId from transport without cross-path verification |
| T-2.2 | Transport layer drops or reorders messages to manipulate conversation state | A5 (Compromised Path) | Message Ordering, Session State | **Medium** | `messaging/reliability` layer provides retry/ACK but no cryptographic sequence enforcement |
| T-2.3 | Rogue transport implementation registered via `CompositeTransport.registerTransport()` | A3 (Rogue Peer) | All Communication Assets | **Low** | Registration is programmatic; requires code-level access, not network-level |

#### Boundary 3: Radio/Network Interface (Transports ↔ OS/Hardware)

| ID | Threat | Attacker | Asset Affected | Current Risk | Mitigation Status |
|----|--------|----------|---------------|--------------|-------------------|
| T-3.1 | Attacker captures all TCP payloads in plaintext over LAN | A1 (Passive Observer) | Message Content | **Critical** | No encryption on wire; `MessageEncoder` outputs plaintext |
| T-3.2 | Attacker captures all Bluetooth RFCOMM payloads in plaintext | A1 (Passive Observer) | Message Content | **High** | Bluetooth RFCOMM provides link-layer encryption but keys are device-level, not application-level; sniffing with Ubertooth is feasible |
| T-3.3 | Attacker injects forged TCP frames with valid `PR` magic and structure | A2 (Active Network) | Message Integrity, Message Authenticity | **Critical** | `MessageParser` validates structure only; no MAC or signature |
| T-3.4 | Attacker replays captured valid messages over TCP | A2 (Active Network) | Message Freshness | **Critical** | No nonce, timestamp, or sequence number in wire format |
| T-3.5 | Attacker injects forged Bluetooth RFCOMM frames | A2 (Active Network) | Message Integrity | **High** | Same structural-only validation as TCP |
| T-3.6 | Attacker drops TCP packets to disrupt reliable delivery | A2 (Active Network) | Availability, ACK State | **Medium** | `DeliveryRetryManager` provides retry but no cryptographic proof of delivery |
| T-3.7 | Attacker observes packet sizes and timing to infer conversation patterns | A1 (Passive Observer) | Metadata (Traffic Volume, Timing) | **Medium** | No padding, no dummy traffic, no timing jitter |

#### Boundary 4: Local vs Remote (Device ↔ Untrusted Network ↔ Device)

| ID | Threat | Attacker | Asset Affected | Current Risk | Mitigation Status |
|----|--------|----------|---------------|--------------|-------------------|
| T-4.1 | Rogue device broadcasts fake PeerId via UDP LAN discovery | A4 (Rogue Discovered) | Peer Identity, Trust Relationship | **Critical** | `LanPeerDiscovery.processIncomingData()` accepts self-declared PeerId from UDP payload |
| T-4.2 | Rogue device advertises fraudulent Bluetooth discovery candidate | A4 (Rogue Discovered) | Peer Identity, Endpoint Address | **High** | `BluetoothPeerDiscovery` accepts discovered devices without verification |
| T-4.3 | Active MITM intercepts TCP connection, relays modified messages between two legitimate peers | A2 (Active Network) | All Communication Assets | **Critical** | No end-to-end encryption or authentication; MITM is trivial on unencrypted TCP |
| T-4.4 | Attacker performs PeerId collision by generating UUIDs until matching a target | A2 (Active Network) | Peer Identity | **Low** | UUID v4 collision probability is negligible (~10⁻³⁷), but PeerId has no crypto binding so collision is not the real attack — impersonation is |
| T-4.5 | Attacker poisons discovery to redirect peers to attacker-controlled endpoint | A4 (Rogue Discovered) | Endpoint Address, Path Trust | **High** | `DiscoveredAddressCandidate.toCandidatePath()` promotes any discovered address to CANDIDATE without verification |
| T-4.6 | Stolen device used to impersonate legitimate peer to all existing contacts | A6 (Compromised Device) | Peer Identity, Trust Relationship, Message Content | **Critical** | PeerId is a random UUID with no device binding; if the UUID is extracted from storage, full impersonation is possible |

### 5.2 Threat Summary by Severity

| Severity | Count | Threat IDs |
|----------|-------|------------|
| **Critical** | 8 | T-1.2, T-2.1, T-3.1, T-3.3, T-3.4, T-4.1, T-4.3, T-4.6 |
| **High** | 5 | T-1.1, T-1.3, T-3.2, T-3.5, T-4.2, T-4.5 |
| **Medium** | 3 | T-2.2, T-3.6, T-3.7 |
| **Low** | 2 | T-2.3, T-4.4 |

### 5.3 Threat-to-Sprint Mapping

| Threat IDs | Addressed By | Sprint |
|------------|-------------|--------|
| T-4.1, T-4.2, T-4.5, T-4.4 | Cryptographic identity binding | SX.2 |
| T-4.6, T-1.1 | Device-bound key storage, pairing | SX.3, SX.7 |
| T-3.1, T-3.2, T-4.3 | End-to-end encryption | SX.4, SX.5 |
| T-3.3, T-3.4, T-3.5 | Authenticated framing, nonces, sequence numbers | SX.5 |
| T-2.1, T-2.2 | Per-path challenge-response | SX.6 |
| T-1.2, T-1.3 | Android Keystore/TEE isolation | SX.7 |
| T-3.7 | Traffic Fabric experiments | SX.8 |
| All | Regression verification | SX.9 |

---

## 6. Explicit Assumptions (Section 27 DoD)

All security guarantees in this threat model and the future Security X sprints are conditional on the following assumptions. If any assumption is violated, the corresponding guarantees may no longer hold.

### 6.1 Cryptographic Assumptions (Future — post SX.2)
1.  **Primitive Strength:** The chosen symmetric (e.g., AES-256-GCM) and asymmetric (e.g., X25519, Ed25519) primitives remain computationally infeasible to break for the operational lifetime of the system.
2.  **Random Number Generation:** The platform's CSPRNG (`java.security.SecureRandom`, Android's `/dev/urandom`) produces cryptographically unpredictable output.
3.  **Key Secrecy:** Long-term private keys and ephemeral session keys remain secret within the local device's memory and storage, except against A6 attackers.

### 6.2 Platform Assumptions
4.  **Android OS Integrity:** The Android operating system is not fundamentally compromised at the kernel level (except against A6). The Android Keystore/TEE provides meaningful hardware-backed key isolation.
5.  **JVM Memory Model:** The Java Virtual Machine does not expose heap memory to external processes without root access.
6.  **UUID Uniqueness:** `UUID.randomUUID()` produces globally unique identifiers with negligible collision probability. (This is a current assumption; post-SX.2, identity will no longer rely solely on UUID uniqueness.)

### 6.3 Network Assumptions
7.  **LAN Scope:** Current deployments operate on local area networks where all devices are physically proximate. Internet-facing deployment is explicitly deferred and will require re-evaluation of all threat ratings.
8.  **Bluetooth Range:** Bluetooth RFCOMM connections require physical proximity (~10m), which provides a weak physical-layer access control. This is not treated as a security boundary but as a practical constraint.
9.  **Transport Availability:** At least one transport path (TCP or Bluetooth) is available between communicating peers. The system does not guarantee delivery if all paths are simultaneously unavailable.

### 6.4 Behavioral Assumptions
10. **Peer Benevolence Post-Pairing:** Once two peers complete the pairing protocol (SX.3), they are assumed to follow the protocol honestly. A3 (Rogue Peer) threats are acknowledged but not fully mitigated by the protocol layer — application-level abuse controls are out of scope.
11. **Human Verification Reliability:** The human verification step (SX.3) assumes that the humans operating both devices are capable of correctly comparing short codes and will not blindly confirm a mismatch.
12. **Clock Monotonicity:** Future session protocols that rely on timestamps assume that device clocks are approximately synchronized and monotonically increasing. Sequence-number-based freshness is preferred to reduce this dependency.

### 6.5 Scope Assumptions
13. **No Relay Infrastructure:** The current threat model assumes direct peer-to-peer communication. Introduction of relay servers (future Internet deployment) will create new trust boundaries and new attacker capabilities that are not covered by this document.
14. **Single-Device Identity:** Each physical device runs a single Pravaah instance with a single PeerId. Multi-device identity federation is out of scope for the initial Security X sprints.
15. **No Regulatory Constraints:** The security architecture is designed for technical soundness, not for compliance with specific regulatory frameworks (HIPAA, GDPR, FIPS 140-2, etc.).

---

*This appendix completes the SX.1 threat model. All 22 enumerated threats and 15 explicit assumptions serve as the verification baseline for SX.2 through SX.9.*
