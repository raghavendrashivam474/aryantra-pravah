# SX.4 Trust Lifecycle Specification

> **Sprint:** SX.4 — Trust-Aware Connection Lifecycle
> **Baseline:** vB.R3 / main
> **Track:** Track C — Security Evolution
> **Status:** Implemented

---

## 1. Core Principle

`
Connectivity ≠ Identity ≠ Trust
Path failure ≠ Trust failure
`

These are not documentation slogans. They are enforced architectural invariants.

---

## 2. State Model

Trust is tracked by `TrustState` in package `com.aryntra.pravah.security.trust`.

`
UNKNOWN
   │
   ▼
AUTHENTICATING
   │
   ├──────────────────► REJECTED
   │
   ▼
AUTHENTICATED
   │
   ▼
TRUSTED
`

| State            | Meaning                                                        | Application Usable? |
|------------------|----------------------------------------------------------------|---------------------|
| `UNKNOWN`      | No authentication attempted. Default for all new peers.        | No                  |
| `AUTHENTICATING`| Challenge issued, awaiting cryptographic proof.                | No                  |
| `AUTHENTICATED` | Proof verified successfully. Awaiting trust policy evaluation. | No (transient)      |
| `TRUSTED`       | Authenticated AND trust policy passed.                         | **Yes**             |
| `REJECTED`      | Authentication failed or trust policy denied.                  | No                  |

### Transition Rules

| From             | To               | Trigger                                              |
|------------------|------------------|------------------------------------------------------|
| `UNKNOWN`      | `AUTHENTICATING`| `PeerTrustManager.issueChallengeForPeer(peerId)`   |
| `AUTHENTICATING`| `AUTHENTICATED` | `AuthenticationService.verifyProof()` returns SUCCESS |
| `AUTHENTICATED` | `TRUSTED`       | `TrustPolicy.evaluate()` returns true              |
| `AUTHENTICATED` | `REJECTED`      | `TrustPolicy.evaluate()` returns false             |
| `AUTHENTICATING`| `REJECTED`      | Proof verification fails (invalid sig, expired, replay) |
| Any              | `UNKNOWN`       | `PeerTrustManager.resetPeer(peerId)`               |

---

## 3. Separation of Concerns

Pravaah now maintains three independent state dimensions per peer:

| Dimension    | Enum / Class         | Package                        | Tracks                          |
|--------------|----------------------|--------------------------------|---------------------------------|
| Protocol     | `PeerState`        | `protocol`                   | Session membership (JOINED/LEFT)|
| Presence     | `PeerPresenceState`| `peer.presence`              | Physical reachability           |
| Trust        | `TrustState`       | `security.trust`             | Cryptographic identity + policy |

A peer can be:
- `CONNECTED` (presence) + `JOINED` (protocol) + `UNKNOWN` (trust) → **not application-usable**
- `CONNECTED` + `JOINED` + `TRUSTED` → **application-usable**
- `UNAVAILABLE` (presence) + `TRUSTED` (trust) → **trusted but unreachable** (trust survives disconnect)

---

## 4. Authentication Handshake Wire Protocol

Two new `MessageType` values were added:

| Type              | Code   | Direction        | Payload                          |
|-------------------|--------|------------------|----------------------------------|
| `AUTH_CHALLENGE`| `0x04`| Verifier → Claimant | `AuthWireCodec.encodeChallenge()` |
| `AUTH_PROOF`    | `0x05`| Claimant → Verifier | `AuthWireCodec.encodeProof()`    |

### Handshake Sequence

`
Alice (Verifier)                          Bob (Claimant)
     │                                         │
     │  ◄──── JOIN ──────────────────────────── │  (1) Bob announces presence
     │                                         │
     │  ──── AUTH_CHALLENGE ──────────────────► │  (2) Alice issues challenge
     │       [challengeId, entropy, expiry]     │
     │                                         │
     │  ◄──── AUTH_PROOF ───────────────────── │  (3) Bob signs canonical context
     │       [identity, challengeId, signature] │
     │                                         │
     │  [verifyProof → TRUSTED]                │  (4) Alice verifies & evaluates
     │                                         │
     │  ◄──── MESSAGE ────────────────────────► │  (5) Application messages flow
`

### Wire Format (AuthWireCodec)

**AUTH_CHALLENGE payload:**
`
[short] challengeId length
[bytes] challengeId (UTF-8)
[short] challengeBytes length
[bytes] challengeBytes (32 bytes entropy)
[long]  expiryEpochMilli
`

**AUTH_PROOF payload:**
`
[short] peerId length
[bytes] peerId (UTF-8)
[int]   publicKey DER length
[bytes] publicKey (X.509 SubjectPublicKeyInfo)
[short] challengeId length
[bytes] challengeId (UTF-8)
[short] signatureBytes length
[bytes] signatureBytes (Ed25519 signature)
`

---

## 5. Multi-Path Trust Semantics

**Trust is a peer-level property, not a path-level property.**

`
Peer (Bob)
 ├── Identity:        CryptographicIdentity[peerId=node-bob, Ed25519]
 ├── Authentication:  AUTHENTICATED (proof verified)
 ├── Trust:           TRUSTED (policy passed)
 │
 ├── TCP path         → ACTIVE
 │    └── connectivity state only
 │
 └── Bluetooth path   → ACTIVE
      └── connectivity state only
`

### Invariants

1. Authenticating over TCP makes the **peer** TRUSTED. A subsequently attached Bluetooth path inherits peer trust without requiring a separate handshake.
2. Dropping TCP deactivates the path but does **not** change the peer's trust state.
3. If all paths go INACTIVE, the peer remains TRUSTED. Trust survives reconnection.
4. Reconnection semantics: trust persists across disconnect/reconnect cycles within the same session. A fresh challenge is only required if `PeerTrustManager.resetPeer()` is explicitly called.

---

## 6. Routing Trust Gate

`PeerRouter.send(destination, message)` enforces:

- **`MessageType.MESSAGE`** (application data): Requires `TrustState.TRUSTED`. Throws `PeerRoutingException` if not trusted.
- **`MessageType.JOIN`, `LEAVE`, `AUTH_CHALLENGE`, `AUTH_PROOF`**: Exempt from trust gating. These are protocol control messages required for the handshake itself.

This ensures the authentication handshake can complete before application messages are permitted.

---

## 7. Replay Protection

Inherited from SX.3 `AuthenticationChallenge`:

| Property         | Mechanism                                              |
|------------------|--------------------------------------------------------|
| Entropy          | 256-bit `SecureRandom` per challenge                 |
| Uniqueness       | UUID-based `challengeId`                             |
| Expiration       | 60-second validity window (`Instant`-based)          |
| Single-use       | `volatile boolean consumed` with `synchronized consume()` |
| Verification     | `AuthenticationService.verifyProof()` atomically removes challenge from pending map |

Tested in `TrustLifecycleIntegrationTest.testReplayAttackRejected()`.

---

## 8. Trust Policy

`TrustPolicy` is a `@FunctionalInterface`:

`java
boolean evaluate(CryptographicIdentity identity, AuthenticationResult authResult);
`

Default: `TrustPolicy.allowAllAuthenticated()` — any peer that cryptographically authenticates is trusted.

Custom policies can enforce:
- Pinned public keys
- Whitelists / blacklists
- Certificate authority chains
- Rate limiting
- Device attestation

Tested in `TrustLifecycleIntegrationTest.testPolicyRejection()`.
