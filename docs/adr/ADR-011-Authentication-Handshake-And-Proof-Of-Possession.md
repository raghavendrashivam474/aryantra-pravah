# ADR-011: Authentication Handshake & Proof of Possession

## Status
Accepted

## Context
SX.2 established the cryptographic identity foundation (`CryptographicIdentity`, `SignatureService`, `IdentityKeyPair`) binding `PeerId` to an Ed25519 `PublicKey`.

However, within the runtime protocol session, a remote peer could still claim any arbitrary `PeerId` in a `JOIN` frame without proving possession of the private key corresponding to that identity. Furthermore:
1. Transport connectivity (TCP/Bluetooth) was conflated with logical session admission.
2. `ProtocolSessionManager` transitioned peers immediately to `JOINED` upon receiving a `JOIN` message with zero cryptographic challenge.
3. Modifying `MessageType` codes on the wire directly would cause pre-SX.3 peers to fail with `ProtocolException` due to strict parser checking.

## Decision
We introduce a standalone, transport-independent authentication subsystem under `com.aryntra.pravah.security.authentication`:

1. **Challenge-Response Proof of Possession**:
   - The verifier generates a fresh, 256-bit entropy `AuthenticationChallenge` with an expiration window and single-use consumption state.
   - The claimant produces an `AuthenticationProof` by signing a canonical context payload with its `IdentityKeyPair` (Ed25519).

2. **Canonical Context Binding**:
   - To prevent signature harvesting, replay, or cross-protocol transplantation, the signed material is a deterministic binary payload composed of:
     `[Domain ID] + [Challenge ID] + [Challenge Entropy] + [Claimed PeerId] + [Public Key DER]`
   - No JVM serialization, `toString()`, or unstable formats are used.

3. **Replay Resistance & Fail-Closed Lifecycle**:
   - A challenge is consumed atomically on first verification attempt, preventing replay even with identical payloads.
   - Any malformed input, expired challenge, wrong key, tampered peer ID, or unsupported algorithm fails closed with an explicit `AuthenticationResult` without throwing runtime exceptions.

4. **Phase Separation**:
   - **Phase A (Core Capability — SX.3)**: Implement and fully test the cryptographic proof-of-possession engine as a pure domain capability above transport and protocol session manager.
   - **Phase B (Protocol Integration — Future)**: Wire authentication states into `ProtocolSessionManager` and `MessageType` once handshake negotiation semantics are finalized across all platforms.

## Consequences

### Positive
- **Cryptographic Proof**: Peers can conclusively prove private key possession before or alongside session establishment.
- **Transport Independence**: The authentication boundary sits above TCP, Bluetooth, and Composite transports with zero transport coupling.
- **Replay & Tamper Resistance**: Single-use challenge tracking and domain-bound canonical context prevent signature reuse.
- **Zero Network Regression**: 100% backward compatibility preserved across all 398 existing tests; no existing networking or Android baseline code broken.

### Negative / Trade-offs
- Protocol session manager is not yet enforcing authentication on live wire messages until Phase B protocol integration is scheduled.