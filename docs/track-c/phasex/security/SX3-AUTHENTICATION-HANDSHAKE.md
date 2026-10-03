# SX.3 — Cryptographic Handshake & Proof of Possession Specification

**Track:** C — Security Evolution  
**Capability:** SX.3 — Authentication Handshake & Proof of Possession  
**Depends On:** SX.2 — Cryptographic Identity Foundation  

---

## 1. Security Architecture

SX.3 addresses the core vulnerability of *Identity Spoofing* on the Pravaah network. Under prior versions, a peer could assert any logical identity (`PeerId`) in its `JOIN` message and be accepted blindly. 

With SX.3, a peer must cryptographically prove ownership of the private key corresponding to its claimed identity via an unpredictable Challenge-Response mechanism.

### Key Conceptual Boundaries
* **Connectivity ≠ Authentication**: Establishing a physical TCP/Bluetooth connection does not grant authenticated state.
* **Authentication ≠ Trust**: Verifying that a peer owns its key does not automatically place it in a trusted group (trust management is deferred to future capabilities).
* **Fail-Closed**: Any missing, malformed, or invalid verification input rejects the peer session immediately.

---

## 2. Cryptographic Protocol Layout

```text
 VERIFIER (Node A)                          CLAIMANT (Node B)
         │                                         │
         │           1. Claim Identity             │
         │◄────────────────────────────────────────┤ (e.g. PeerId, PublicKey)
         │                                         │
         │           2. Issue Challenge            │
         ├────────────────────────────────────────►│ (challengeId, entropy)
         │                                         │
         │                                         │ [ Signs canonical context ]
         │                                         │ [ with PrivateKey ]
         │                                         │
         │           3. Submit Proof               │
         │◄────────────────────────────────────────┤ (signatureBytes)
         │                                         │
 [ Consumes Challenge ]                            │
 [ Reconstructs Context ]                          │
 [ Verifies Signature ]                            │
         │                                         │
```

---

## 3. Canonical Binary Encoding Specification

To prevent signature transplantation or re-use in different protocol contexts, the verifier and claimant must construct a deterministic context payload before signing and verification. 

This payload is built of sequential fields prefixed by strict length boundaries.

### Binary Frame Layout

| Field Number | Field Name | Type | Size | Description |
|---|---|---|---|---|
| **1** | Domain Identifier Length | Unsigned Short | 2 Bytes | Length of the domain string |
| **2** | Domain Identifier Bytes | UTF-8 String | Variable | Prevents cross-app replay (default: `com.aryntra.pravah.auth.v1`) |
| **3** | Challenge ID Length | Unsigned Short | 2 Bytes | Length of the unique UUID challenge ID |
| **4** | Challenge ID Bytes | UTF-8 String | Variable | Ties signature to a specific handshake session |
| **5** | Challenge Entropy Length | Unsigned Short | 2 Bytes | Length of unpredictable challenge bytes (typically 32) |
| **6** | Challenge Entropy Bytes | Raw Bytes | Variable | Cryptographically secure verifier-generated entropy |
| **7** | Claimant PeerId Length | Unsigned Short | 2 Bytes | Length of claimant's logical PeerId |
| **8** | Claimant PeerId Bytes | UTF-8 String | Variable | Securely binds logical PeerId to the signature |
| **9** | Public Key Length | Integer | 4 Bytes | Length of public key X.509 DER payload |
| **10** | Public Key Bytes | Raw Bytes | Variable | Standard DER representation of the Ed25519 public key |

This structure guarantees that any change to the identity, key, domain, or challenge invalidates the signature, precluding tampering or spoofing.

---

## 4. Replay & Attack Defense Analysis

| Attack Vector | Threat Scenario | Mitigation Mechanism |
|---|---|---|
| **Identity Spoofing** | Adversary asserts PeerId `alice` to hijack traffic. | **Blocked**: Verifier checks signature against Alice's public key. Adversary cannot produce signature without private key. |
| **Simple Replay** | Adversary records a valid challenge signature and submits it later. | **Blocked**: Challenges are single-use. The verifier flags the challenge ID as `consumed` instantly upon verification, rendering subsequent attempts invalid. |
| **Signature Harvesting** | Adversary tricks peer into signing a bare block, then uses it to authenticate. | **Blocked**: The signature payload includes a domain-separation tag and verifier entropy, preventing raw block reuse. |
| **Man-in-the-Middle** | Adversary forwards challenge to legitimate owner, obtains signature, and relays it back. | **Blocked**: The signature is securely bound to the specific peer identity and public key. |
| **Cross-Protocol Transplant** | Adversary captures valid handshake from another service and runs it on Pravaah. | **Blocked**: Strict domain checking (`com.aryntra.pravah.auth.v1`) ensures external domain signatures fail verification. |

---

## 5. Implementation Summary

* **`AuthenticationChallenge`**: Uses standard JDK `SecureRandom` to pack 256 bits of unpredictable entropy. Marked consumed atomically upon verification.
* **`AuthenticationProof`**: Packs the cryptographic identity and digital signature. Generates canonical context on the fly.
* **`AuthenticationService`**: Orchestrates issuance, lifecycle validation, canonical reconstruction, and cryptographic verification utilizing the `SignatureService` Ed25519 implementation.