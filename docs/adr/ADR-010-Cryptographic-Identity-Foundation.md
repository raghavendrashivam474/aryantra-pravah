# ADR-010: Cryptographic Identity Foundation and PeerId Binding

## Status
Accepted

## Context
Pravaah peers previously identified themselves purely through self-declared `PeerId` values (e.g., `android-fc34fd1e`, UUIDs). With the introduction of multi-path continuity (Track B / B.R1) where a peer session transitions dynamically across physical transports (e.g., TCP to Bluetooth RFCOMM), the system requires a verifiable guarantee that:
> *"The physical path changed, but the peer entity did not."*

We evaluated two architectural approaches for introducing cryptographic identity:
1. **Cryptographically Derived PeerId (e.g., public key hash as PeerId):** Mutate `PeerId` semantics directly so that `PeerId` equals or is derived from the public key.
2. **Parallel Cryptographic Identity Binding:** Keep `PeerId` unchanged as the logical routing/addressing identifier and create an explicit, immutable `CryptographicIdentity` binding `PeerId` to an asymmetric public key (`PeerId â†” PublicKey`).

## Decision
We adopt **Approach 2 (Parallel Cryptographic Identity Binding)** under the following rules:

1. **`PeerId.java` remains unchanged:**
   - Existing discovery, routing, session managers, outboxes, and tests continue operating without regressions.
   - `PeerId` represents *logical addressing*, while `CryptographicIdentity` represents *verifiable ownership*.

2. **Algorithm & Key Standard:**
   - We select **Ed25519** (RFC 8032) for asymmetric key pairs and signatures.
   - We use the standard JDK 21 Java Cryptography Architecture (`KeyPairGenerator.getInstance("Ed25519")`, `Signature.getInstance("Ed25519")`), avoiding external dependencies.

3. **Public Key Serialization Format:**
   - Public keys are serialized using canonical X.509 SubjectPublicKeyInfo DER encoding (`PublicKeyCodec`).

4. **Layer Decoupling & Sensitivity Boundary:**
   - `IdentityKeyPair` isolates the `PrivateKey`, preventing accidental disclosure via `toString()` or logs.
   - `CryptographicIdentity` exposes only the `PeerId`, `PublicKey`, and algorithm metadata.
   - No transport or protocol layer (`TcpTransport`, `BluetoothRfcommTransport`, `PeerRouter`, `MessageEncoder`) has direct coupling to identity generation or private keys.

## Consequences

### Positive
- **Zero regressions:** All 378+ existing tests, Android build, and physical multi-path flows remain 100% green.
- **Transport Agnostic:** The identity model is decoupled from physical network media.
- **Clean Upgrade Path:** Future capabilities (pairing, authentication handshakes, secure sessions) can consume `CryptographicIdentity` and `SignatureService` without modifying network plumbing.

### Negative / Trade-offs
- Verification is not yet automated at the message frame level (intentionally deferred to future security sprints).