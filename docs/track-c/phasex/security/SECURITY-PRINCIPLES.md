# Pravaah Security Principles
## SX.1 — Threat Model & Security Principles

> **Motto:** *We do not fear experimentation. We control it.*
>
> This document defines the inviolable security principles that all
> future Security X sprints (SX.2 through SX.9) must obey. No
> implementation may contradict these principles without a formal ADR
> and senior review.

---

## 1. The Seven Pravaah Security Principles (Section 16)

### Principle 1 — Connectivity ≠ Trust

```text
DISCOVERED  ≠  CONNECTED  ≠  AUTHENTICATED  ≠  TRUSTED
```

**Statement:** The fact that a device is reachable over a transport path
does not imply that the device is who it claims to be, nor that it is
benevolent.

**Evidence from current codebase:**
- `LanPeerDiscovery.processIncomingData()` accepts a PeerId from an
  unauthenticated UDP broadcast payload.
- `DiscoveredAddressCandidate.toCandidatePath()` promotes a discovered
  address to a connectivity path with zero verification.
- `ProtocolSessionManager.processMessage()` dispatches on
  `message.senderId()`, which is a self-declared string inside the
  payload.

**Requirement for SX.2–SX.9:** Every layer that currently accepts a
self-declared identity must eventually verify that identity against a
cryptographic binding before granting any trust-dependent privilege
(message delivery, session establishment, group membership).

---

### Principle 2 — Path ≠ Peer Identity

```text
TCP connection     ≠  Peer identity
Bluetooth link     ≠  Peer identity
Any future path    ≠  Peer identity
```

**Statement:** A transport connection is a *way to reach* a peer, not
*proof of* a peer. Multiple paths may reach the same peer, and a single
path may be hijacked without the peer's identity changing.

**Evidence from current codebase:**
- `ConnectivityPath` binds a `PeerId` to an `EndpointAddress` and
  a `transportName`, but the binding is asserted by the discovery
  layer, not verified by any challenge-response.
- `CompositeTransport.onDataReceived(String senderId, byte[] payload)`
  receives a senderId from the transport layer. If two paths carry
  different senderIds for the same physical device, the system has no
  mechanism to reconcile them.
- `EndpointAddress` uses raw IP+port for TCP and raw MAC+channel for
  Bluetooth — both are spoofable at their respective network layers.

**Requirement for SX.2–SX.9:** The security architecture must maintain
a clear separation between *path authentication* (this connection is
alive and reaches the expected endpoint) and *peer authentication* (the
entity at the other end cryptographically proves ownership of a PeerId).

---

### Principle 3 — Security Survives Protocol Knowledge

**Statement:** Assume the attacker knows everything about how Pravaah
works: the protocol format, the frame structure, the discovery
mechanism, the routing logic, the path selection policy, the Traffic
Fabric algorithm, and the source code itself.

**Formally:**


Known protocol
Known architecture
Known algorithms
Known scheduling rules
────────────────────────
Must NOT allow:
• Plaintext recovery of message payloads
• Message forgery (accepted messages the attacker authored)
• Peer impersonation (accepted identity the attacker does not own)
• Accepted replay of previously captured traffic




**Rationale:** We will NOT use security-by-obscurity as the primary
security boundary. Unusual mechanisms may provide *additional* defense
in depth, but they must never be the *sole* mechanism preventing an
attack.

**Evidence from current codebase:**
- The wire format (`PR` + `0x01` + type + length-prefixed fields)
  is fully deterministic and trivially reverse-engineerable.
- `MessageEncoder` and `MessageParser` are straightforward binary
  serialization with no secret-dependent operations.

**Requirement for SX.2–SX.9:** All security guarantees must derive from
cryptographic keys that remain secret, not from the secrecy of the
protocol design.

---

### Principle 4 — Cryptography Is the Security Foundation

**Statement:** Experimental architecture (Traffic Fabric, multi-path
distribution, stream obfuscation) may provide *additional resistance*
to specific attack classes. It must never *replace* cryptographic
guarantees.

**Hierarchy:**
text

Layer 1 (Foundation):  Cryptographic primitives
                       (authenticated encryption, key exchange, signatures)
Layer 2 (Hardening):   Protocol-level protections
                       (sequence numbers, nonces, session binding)
Layer 3 (Obfuscation): Architectural experiments
                       (Traffic Fabric, stream multiplexing, timing noise)
text


Layer 3 without Layer 1 provides zero security. Layer 1 without Layer 3
still provides strong security.

**Requirement for SX.2–SX.9:** No sprint may ship a Layer 3 feature as
a substitute for a missing Layer 1 capability.

---

### Principle 5 — Path Failure Must Not Equal Trust Failure

**Statement:** If one physical path to a peer is compromised or
disconnected, the authenticated peer relationship must survive through
remaining trustworthy paths.

**Scenario:**
text

Peer A ──── TCP (compromised) ──── ✗
     └── Bluetooth (clean) ──── ✓

Expected: The authenticated session with Peer B continues over
Bluetooth. The compromised TCP path is quarantined but does not
invalidate the peer relationship or expose session keys.
text


**Evidence from current codebase:**
- `CompositeTransport` aggregates paths but has no per-path trust
  scoring or quarantine mechanism.
- `PathState` has only CANDIDATE / ACTIVE / INACTIVE — no COMPROMISED
  or QUARANTINED state.

**Requirement for SX.2–SX.9:** The secure session layer must be bound
to the *peer identity*, not to any individual *path*. Path compromise
must be a transport-layer event, not a session-layer event.

---

### Principle 6 — Security Claims Must Be Testable

**Statement:** Never write "highly secure" or "enterprise-grade." Every
meaningful security claim must map to a specific attacker capability
that is denied under specific assumptions.

**Template:**
text

"An attacker capable of [X] cannot achieve [Y]
 under assumptions [Z]."
text


**Examples:**
- ✓ "An A2 attacker who can inject arbitrary TCP packets cannot forge a
  message accepted by the recipient, assuming the recipient's session
  key has not been compromised."
- ✗ "Messages are securely encrypted."
- ✓ "An A1 passive observer cannot recover message plaintext from
  captured frames, assuming forward secrecy keys have been destroyed."
- ✗ "The protocol is unbreakable."

**Requirement for SX.2–SX.9:** Every security feature delivered must
include at least one testable claim in this format, and ideally an
automated test or formal analysis reference.

---

### Principle 7 — Controlled Experimentation

**Statement:** Pravaah explicitly encourages unconventional security
architecture. However, every experiment must follow the scientific
method:
text

Interesting idea
     ↓
Threat model mapping
     ↓
Security hypothesis
     ↓
Controlled experiment (bounded scope, measurable outcome)
     ↓
Evidence collection
     ↓
Security analysis
     ↓
Decision (adopt / modify / abandon)
text


**What this means in practice:**
- Unusual mechanisms are welcome and will be evaluated on merit.
- Unverified security claims are not acceptable as shipping criteria.
- Experimental features must be clearly labeled as experimental in all
  documentation and must not be presented as security guarantees.
- Every experiment must have a defined scope, a success metric, and a
  kill criterion.

---

## 2. Future Concept: Human Verification (Section 17)

> **Status:** REQUIREMENT DEFINED — NOT IMPLEMENTED
> **Target Sprint:** SX.3 (Pairing Protocol)

### 2.1 The Concept

When two Pravaah devices first establish a trust relationship, a
human-verifiable step should confirm that the cryptographic binding is
correct:
text

Device A                              Device B
   │                                     │
   │  1. Key exchange completes          │
   │  2. Both derive short code          │
   │                                     │
   │     ┌───────────┐   ┌───────────┐   │
   │     │  7 3 9    │   │  7 3 9    │   │
   │     │  2 1 4    │   │  2 1 4    │   │
   │     └───────────┘   └───────────┘   │
   │                                     │
   │  3. Humans compare codes visually   │
   │  4. Humans confirm match            │
   │                                     │
   │  5. Trust relationship established  │
text


### 2.2 Security Questions for SX.3

- Is human verification a one-time enrollment or recurring
  authentication?
- What is the entropy of the short code? (6 digits ≈ 20 bits — is that
  sufficient against an active MITM during the pairing window?)
- What happens if pairing expires or is revoked?
- What happens if one device loses its identity credentials?
- Can the short code be relayed over a separate authenticated channel
  (e.g., QR code scan) instead of visual comparison?

### 2.3 Explicit Non-Requirement for SX.1

SX.1 does **not** implement human verification. SX.1 only documents it
as a future security requirement that SX.3 must address.

---

## 3. Future Concept: Traffic Fabric (Section 18)

> **Status:** EXPERIMENTAL HYPOTHESIS — NOT IMPLEMENTED
> **Target Sprint:** SX.8 (Traffic Analysis Resistance)

### 3.1 The Hypothesis

A logical secure session could distribute encrypted frames across
multiple physical streams and paths to make traffic analysis harder:
text

Logical Secure Session
        │
        ▼
  Traffic Fabric
        │
 ┌──────┼──────┐
 ▼      ▼      ▼
Stream A Stream B Stream C
│ │ │
▼ ▼ ▼
TCP Bluetooth TCP (relay)

text


### 3.2 Potential Goals

- Distribute encrypted frames across multiple physical streams.
- Dynamically select streams to avoid predictable patterns.
- Make traffic analysis harder for A1 passive observers.
- Avoid predictable semantic mapping between application data and
  physical streams.

### 3.3 Critical Caveats

**The Traffic Fabric is NOT a confidentiality mechanism.** It operates
at Layer 3 (Obfuscation) in the hierarchy defined by Principle 4. It
provides zero security without Layer 1 cryptographic foundations.

**The experiment must measure:**
- Against which observer model does the Fabric provide measurable
  improvement?
- What is the baseline traffic-analysis accuracy without the Fabric?
- What is the traffic-analysis accuracy with the Fabric?
- Does the improvement justify the added complexity, latency, and
  battery cost?

### 3.4 Explicit Non-Requirement for SX.1

SX.1 does **not** implement the Traffic Fabric. SX.1 only documents it
as an experimental hypothesis that SX.8 must evaluate with measurable
criteria.

---

## 4. Security Non-Goals (Section 19)

The following are explicitly **out of scope** for Pravaah's security
model. These are not promises we make, and future developers must not
accidentally market these assumptions as guarantees.

| Non-Goal | Rationale |
|----------|-----------|
| **Perfect anonymity** | Pravaah peers are identified by PeerId. While we may obfuscate metadata, we do not guarantee that an observer cannot determine *who* is communicating. |
| **Perfect metadata hiding** | Packet sizes, timing, frequency, and path usage may leak information. The Traffic Fabric may mitigate this but cannot eliminate it. |
| **Protection against a fully compromised endpoint (A6)** | If an attacker has root access to the local device, they can read memory, extract keys, and intercept plaintext before encryption or after decryption. No protocol can defend against this. |
| **Guaranteed availability** | Active RF jamming, network-level packet dropping, and physical destruction of devices cannot be architecturally prevented. Pravaah provides failover between paths, not immunity to denial-of-service. |
| **Invisible traffic** | Pravaah traffic will be detectable on the network. We do not attempt to disguise Pravaah frames as other protocols (e.g., HTTPS). The `PR` magic bytes are a deliberate design choice for debuggability. |
| **Unbreakable security** | All cryptographic guarantees are conditional on the strength of the underlying primitives, the secrecy of keys, and the correctness of implementation. We guarantee resistance against defined attacker models, not mathematical impossibility of attack. |
| **Regulatory compliance** | Pravaah's security architecture is designed for technical soundness, not for compliance with specific regulatory frameworks (HIPAA, GDPR, FIPS, etc.). Compliance is a deployment concern, not a protocol guarantee. |

---

## 5. Principle Compliance Checklist for Future Sprints

Every SX.2–SX.9 sprint must verify compliance before marking Done:

- [ ] Does this sprint's work assume connectivity implies trust? → **Must not.**
- [ ] Does this sprint's work conflate path identity with peer identity? → **Must not.**
- [ ] Does this sprint's security depend on the attacker not knowing the protocol? → **Must not.**
- [ ] Does this sprint's work replace cryptography with obfuscation? → **Must not.**
- [ ] Does a single path failure break the peer trust relationship? → **Must not.**
- [ ] Are all security claims in testable "attacker X cannot achieve Y" format? → **Must be.**
- [ ] Are experimental features clearly labeled and bounded? → **Must be.**

---

*These principles are the security contract that the rest of Security X
must obey. They were derived from evidence-based inspection of the
current Pravaah codebase (commit a3cf7fb) and are subject to revision
only through formal ADR process.*
