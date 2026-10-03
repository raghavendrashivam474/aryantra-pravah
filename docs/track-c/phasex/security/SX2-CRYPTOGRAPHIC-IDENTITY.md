# SX.2 — Cryptographic Identity Foundation

**Track:** C — Security Evolution  
**Security Phase:** Security X  
**Capability:** SX.2  
**Baseline:** vSX.1  
**Depends On:** SX.1 Security Surface / Threat Model / Security Principles  

---

## 1. Architectural Mission

Pravaah separates **WHO** is communicating from **HOW** they communicate.

Prior to SX.2, identity was represented solely by logical `PeerId` strings (e.g., `android-abc123`). While this was sufficient for routing and discovery, it was purely self-declared and lacked cryptographic proof of ownership.

SX.2 introduces a foundational, transport-agnostic cryptographic identity model:

```text
              PEER IDENTITY
                   │
          ┌────────┴────────┐
          │                 │
       PeerId          Cryptographic
   (logical routing)     Identity
                          │
                     Key Material
                          │
                   ┌──────┴──────┐
                   │             │
               Public Key    Private Key
              (shareable)    (secret)
```

The core architectural invariant established is:
```text
TCP ───────────────┐
│
Bluetooth ─────────┼────► SAME PEER IDENTITY
│
Future transport ──┘
```

**Identity belongs to the peer, not to the transport.**

---

## 2. Abstraction Boundaries & Components

All SX.2 cryptographic identity primitives reside in the `com.aryntra.pravah.security.identity` package in Core:

| Class | Responsibility | Sensitivity |
|---|---|---|
| `IdentityKeyPair` | Wraps JCA Ed25519 `KeyPair`; guards algorithm; hides private key from `toString()`. | **High (Private Key)** |
| `IdentityGenerator` | Generates RFC 8032 Ed25519 key pairs using JDK JCA and `SecureRandom`. | Standard |
| `PublicKeyCodec` | Deterministically encodes/decodes public keys into standard X.509 DER byte arrays. | Public Safe |
| `CryptographicIdentity` | Immutable binding between a logical `PeerId`, `PublicKey`, and algorithm metadata. | Public Safe |
| `SignatureService` | Signs raw bytes with `IdentityKeyPair` and verifies signatures against `CryptographicIdentity`/`PublicKey`. | Core Primitive |

---

## 3. Cryptographic Invariants Preserved

1. **Connectivity ≠ Trust:** Having an active TCP or Bluetooth channel does not imply the peer is authentic.
2. **Path ≠ Identity:** Multiple paths (TCP, Bluetooth) terminate at the same `CryptographicIdentity`.
3. **Path failure ≠ Trust failure:** When a TCP path drops and failover switches to Bluetooth (Track B / B.R1), the cryptographic identity of the peer remains invariant.
4. **Transport Independence:** Zero coupling exists between `com.aryntra.pravah.security.identity` and any transport layer (`TcpTransport`, `BluetoothRfcommTransport`, `CompositeTransport`).
5. **No Private Key Leakage:** Private keys are neither serialized nor included in `toString()`, diagnostic state, routing records, or wire formats.

---

## 4. Cryptographic Choices

- **Algorithm:** Ed25519 (RFC 8032 / Edwards-curve Digital Signature Algorithm).
- **Runtime:** Native Java 21 JCA provider (`java.security.KeyPairGenerator`, `java.security.Signature`).
- **External Dependencies:** Zero (no BouncyCastle or libsodium needed).
- **Public Key Wire Representation:** Standard X.509 SubjectPublicKeyInfo DER format.
- **Signature Output:** Standard 64-byte Ed25519 signature.

---

## 5. Scope & Future Evolution

SX.2 is an **identity primitive foundation**, not a complete security system.

| In Scope for SX.2 | Deferred to Future Sprints |
|---|---|
| Key pair generation (Ed25519) | Handshake & authentication protocols |
| PeerId ↔ PublicKey binding | Transport encryption / secure session negotiation |
| Public key serialization & round-trip | Android Keystore hardware-backed keys |
| Standalone digital signature & verification | Protocol message signing integration |
| 100% clean test suite & regression | Trust evaluation & UI badges |