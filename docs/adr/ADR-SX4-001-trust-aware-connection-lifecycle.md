# ADR-SX4-001: Trust-Aware Connection Lifecycle Separation

## Status
Accepted

## Context
Prior to SX.4 (up through vB.R3), Pravaah determined peer usability solely through transport connectivity and protocol session presence:
CONNECTED (transport) + JOINED (protocol) => APPLICATION USABLE

This created critical architectural flaws:
1. Anyone could announce a JOIN frame claiming any PeerId without cryptographic verification.
2. No mechanism existed to verify whether the entity on the other end possessed the private key corresponding to the claimed identity.
3. Multi-path environments (e.g. TCP + Bluetooth) had no coherent model for whether trust attached to the physical wire or the logical peer.

SX.2 introduced Ed25519 identity primitives (CryptographicIdentity, IdentityKeyPair, PublicKeyCodec). SX.3 introduced cryptographic proof-of-possession verification (AuthenticationService, AuthenticationChallenge, AuthenticationProof). However, neither was integrated into the operational connection lifecycle or routing engine.

## Decision
We established a strict three-dimensional separation of concerns:
1. **Connectivity**: Managed by Transport and PeerConnectivity. Determines physical path reachability (ACTIVE, CANDIDATE, INACTIVE).
2. **Protocol Presence**: Managed by PeerPresenceBridge and ProtocolSessionManager. Tracks logical session announcements (JOINED, LEFT).
3. **Trust**: Managed by PeerTrustManager and TrustPolicy. Tracks cryptographic proof verification and authorization (UNKNOWN, AUTHENTICATING, AUTHENTICATED, TRUSTED, REJECTED).

### Key Decisions
* **Trust is peer-level, not path-level**: A peer authenticated over one path is TRUSTED across all active paths. Path failover (e.g., dropping TCP to use Bluetooth) preserves trust.
* **Fail-Closed Application Usability**: PeerRouter enforces that MessageType.MESSAGE can only be routed to peers in TrustState.TRUSTED. Any message attempted to an untrusted peer throws PeerRoutingException.
* **Exempt Control Frames**: Handshake frames (JOIN, LEAVE, AUTH_CHALLENGE, AUTH_PROOF) bypass application trust gating to enable bootstrapping.
* **Zero Duplication**: Reuses existing SX.2 and SX.3 primitives without re-implementation.

## Consequences
### Positive
* Spoofed identities cannot receive application messages.
* Multi-path failover seamlessly retains authenticated trust without renegotiation overhead.
* Cryptographic proof of possession is verified deterministically with replay resistance.
* Clean separation ensures security evolution (e.g. key rotation or certificates) requires zero changes to transports or delivery outbox.

### Negative
* Handshake requires an additional roundtrip (AUTH_CHALLENGE -> AUTH_PROOF) before application messages can flow.
