# SX.4 Integrated Architecture Specification

> **Sprint:** SX.4 — Trust-Aware Connection Lifecycle
> **Baseline:** vB.R3 / main
> **Track:** Track C — Security Evolution

---

## 1. System Architecture Diagram
```text
                     PEER (PeerId)
                           │
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
      [Identity]    [Authentication]    [Trust]
      (SX.2 Ed25519)  (SX.3 Challenge)  (SX.4 Policy)
           │               │               │
           └───────────────┼───────────────┘
                           ▼
                   PeerTrustManager
                           │
                  [TRUSTED Gate]
                           ▼
                      PeerRouter
                           │
          ┌────────────────┴────────────────┐
          ▼                                 ▼
     [B.R3 Delivery]               [TransitionBuffer]
    (Outbox / Retry)               (Failover Window)
          │                                 │
          └────────────────┬────────────────┘
                           ▼
                 PeerConnectivityRegistry
                           │
           ┌───────────────┴───────────────┐
           ▼                               ▼
      [TCP Path]                     [BT Path]
    (Active/Inactive)             (Active/Inactive)
```
---

## 2. Component Responsibilities

### Security Subsystem (com.aryntra.pravah.security)
* **security.identity (SX.2)**: Cryptographic identity foundation. Provides Ed25519 key generation, canonical public key serialization (PublicKeyCodec), and digital signatures (SignatureService).
* **security.authentication (SX.3 & SX.4)**: Challenge-response protocol engine. Provides AuthenticationService, AuthenticationChallenge, AuthenticationProof, and AuthWireCodec for binary over-the-wire framing.
* **security.trust (SX.4)**: Authoritative trust lifecycle management. Provides PeerTrustManager, TrustPolicy, TrustStateListener, and PeerTrustRecord.

### Core Coordination & Routing (com.aryntra.pravah.peer)
* **PeerConnectionCoordinator**: Coordinates transport I/O, decodes wire frames, maps connections to PeerId, coordinates presence with PeerPresenceBridge, and drives authentication handshakes.
* **PeerRouter**: Enforces trust boundaries before message dispatch. Dispatches application messages across active paths via PathSelectionPolicy, or queues them in TransitionBuffer during path migration.

---

## 3. The Usability Invariant

A remote peer is considered **Application-Usable** if and only if:

```text
PeerPresenceState == CONNECTED (At least one physical transport path is active)
AND
PeerState == JOINED (Protocol session has received a valid JOIN)
AND
TrustState == TRUSTED (Cryptographic proof verified AND TrustPolicy evaluated to true)
```

If TrustState != TRUSTED, all MessageType.MESSAGE transmissions are rejected at the PeerRouter boundary.
