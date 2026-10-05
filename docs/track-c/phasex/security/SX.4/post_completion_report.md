# Pravaah — Post-Sprint Technical Report

**Sprint:** SX.4 — Trust-Aware Connection Lifecycle
**Track:** C — Security Evolution
**Baseline:** `v-B.R3` / `main`
**Head Commit:** `12a4ef7`
**Tag:** `vSX.4`
**Author:** [Junior Developer]
**Reviewer:** [Senior Developer]
**Date:** 2026-10-05

---

## 1. Executive Summary

SX.4 integrated the cryptographic identity primitives from **SX.2** (Ed25519 identity, keypairs, DER codecs, signature service) and the proof-of-possession authentication primitives from **SX.3** (challenges, proofs, verification service) into Pravaah's live connection lifecycle and routing engine.

Prior to SX.4, Pravaah's definition of a usable peer was effectively:

```
CONNECTED (transport) + JOINED (protocol)  ⇒  APPLICATION USABLE
```

This was architecturally dangerous. Any party capable of opening a TCP or Bluetooth connection and sending a `JOIN` frame claiming any arbitrary `PeerId` was treated as fully usable. The cryptographic machinery to prevent this already existed in the codebase (SX.2, SX.3) but was never wired into the lifecycle or routing paths.

SX.4 closes that gap. The new invariant is:

```
CONNECTED  +  JOINED  +  TRUSTED  ⇒  APPLICATION USABLE
```

Trust is now a **first-class, peer-level** state, independently tracked, cryptographically established, and enforced at the routing boundary. No application-level `MessageType.MESSAGE` can be dispatched to a peer whose `TrustState` is not `TRUSTED`.

The implementation strictly obeyed the sprint directive: **smallest correct change, strongest evidence, zero silent architectural drift**. SX.2 and SX.3 classes were reused without modification. No duplicate authentication service, no duplicate signature primitive, no second trust authority was created. Transports, delivery outbox, retry manager, path selection policy, transition buffer, and B.R3 sequence machinery were not touched.

The sprint shipped in **5 production files, 2 test files, and 6 documentation artifacts**, committed in 5 capability-wise chunks and tagged `vSX.4`. The full regression suite finished green at every patch boundary.

---

## 2. Core Principles Enforced

Two principles drove every design decision:

### 2.1 Connectivity ≠ Identity ≠ Trust

Three independent state dimensions are now maintained per peer:

| Dimension | Enum / Class | Package | Tracks |
|---|---|---|---|
| Protocol Session | `PeerState` | `protocol` | `JOINED` / `LEFT` |
| Physical Reachability | `PeerPresenceState` | `peer.presence` | `UNKNOWN` / `AVAILABLE` / `CONNECTED` / `UNAVAILABLE` |
| Security Trust | `TrustState` | `security.trust` | `UNKNOWN` / `AUTHENTICATING` / `AUTHENTICATED` / `TRUSTED` / `REJECTED` |

These three dimensions are deliberately **not merged** into a single composite enum. Each has a different authority, a different lifecycle, and a different failure mode. Merging them would have reproduced the exact architectural ambiguity SX.4 was tasked with eliminating.

### 2.2 Path Failure ≠ Trust Failure

Trust is attached to the logical **peer**, not to the physical **path**. If a peer authenticates over TCP and the TCP connection subsequently drops, the peer remains `TRUSTED` and any secondary active path (e.g., Bluetooth) immediately inherits that trust. This directly supports Pravaah's multi-transport design and prevents the pathological case where a cosmetic path flap would require full re-authentication.

---

## 3. What Was Implemented

### 3.1 Protocol Layer

**File:** `src/main/java/com/aryntra/pravah/protocol/MessageType.java` *(modified)*

Added two new wire codes:

| Type | Code | Direction | Purpose |
|---|---|---|---|
| `AUTH_CHALLENGE` | `0x04` | Verifier → Claimant | 256-bit entropy challenge with expiry |
| `AUTH_PROOF` | `0x05` | Claimant → Verifier | Signed canonical context |

These travel over the **existing** framing and parsing machinery (`FrameEncoder`, `MessageParser`). No new transport primitive, no new session manager, no parallel protocol was introduced. This was a deliberate choice to avoid creating a second message bus.

### 3.2 Authentication Wire Codec

**File:** `src/main/java/com/aryntra/pravah/security/authentication/AuthWireCodec.java` *(created)*

A deterministic, length-prefixed binary serializer for `AuthenticationChallenge` and `AuthenticationProof`. Uses `DataOutputStream` with explicit field lengths. Rejects malformed payloads with `IllegalArgumentException`.

**Wire format — `AUTH_CHALLENGE`:**
```
[short] challengeId length
[bytes] challengeId (UTF-8)
[short] challengeBytes length
[bytes] challengeBytes (32 bytes entropy)
[long]  expiryEpochMilli
```

**Wire format — `AUTH_PROOF`:**
```
[short] peerId length
[bytes] peerId (UTF-8)
[int]   publicKey DER length
[bytes] publicKey (X.509 SubjectPublicKeyInfo)
[short] challengeId length
[bytes] challengeId (UTF-8)
[short] signatureBytes length
[bytes] signatureBytes (Ed25519 signature)
```

The codec is a pure utility. It performs no verification, no trust evaluation — those responsibilities remain exclusively with `AuthenticationService` and `PeerTrustManager`.

### 3.3 Trust Lifecycle Package

**Package:** `com.aryntra.pravah.security.trust` *(created)*

Five new classes, each with a single clear responsibility:

| File | Role |
|---|---|
| `TrustState.java` | Enum: `UNKNOWN`, `AUTHENTICATING`, `AUTHENTICATED`, `TRUSTED`, `REJECTED` |
| `PeerTrustRecord.java` | Immutable record binding `PeerId` to current `TrustState` and timestamp |
| `TrustPolicy.java` | `@FunctionalInterface` evaluating whether an authenticated peer is trusted |
| `TrustStateListener.java` | Observer interface for state transitions |
| `PeerTrustManager.java` | Authoritative orchestrator: issues challenges, evaluates proofs, drives state transitions |

**Transition rules:**

```
UNKNOWN
   │  issueChallengeForPeer()
   ▼
AUTHENTICATING
   │
   │  evaluateProof()
   │    ├── SUCCESS → AUTHENTICATED → policy pass → TRUSTED
   │    │                           → policy fail → REJECTED
   │    └── FAIL (invalid / replay / mismatch) → REJECTED
```

`PeerTrustManager` holds exactly one `AuthenticationService` (from SX.3) and one `TrustPolicy`. The default policy is `TrustPolicy.allowAllAuthenticated()`, which trusts any peer whose cryptographic proof verifies successfully. The policy interface is pluggable to support future pinned keys, whitelists, certificate authorities, or device attestation without touching the manager.

### 3.4 Connection Lifecycle Integration

**File:** `src/main/java/com/aryntra/pravah/peer/PeerConnectionCoordinator.java` *(modified)*

Extended with three new optional constructor parameters:
- `PeerTrustManager trustManager`
- `IdentityKeyPair localKeyPair`
- `CryptographicIdentity localIdentity`

All existing constructor signatures were preserved and now delegate to the extended constructor with `null` values. This guarantees backward compatibility — not a single existing test or caller needed to change.

Three new behaviours were added inside `handleInboundMessage`:

1. **`AUTH_CHALLENGE` received** → `handleInboundAuthChallenge()` decodes the challenge, constructs an `AuthenticationProof` using the local keypair, and replies with `AUTH_PROOF`. Silently no-ops if no local identity is configured.
2. **`AUTH_PROOF` received** → `handleInboundAuthProof()` decodes the proof and delegates to `PeerTrustManager.evaluateProof(peerId, proof)`. The result drives the trust state transition. Decoder exceptions are caught and the peer is explicitly transitioned to `REJECTED` as a fail-closed guard.
3. **`initiateAuthentication(PeerId, connectionId)`** → New public method that issues a challenge for the target peer and dispatches it as an `AUTH_CHALLENGE` frame over the specified connection.

The existing `JOIN` handling was **not** modified. `JOIN` continues to signal presence; authentication is now a separate, orthogonal exchange that can occur before or after `JOIN`.

### 3.5 Routing Enforcement

**File:** `src/main/java/com/aryntra/pravah/peer/PeerRouter.java` *(modified)*

Added a new optional constructor parameter `PeerTrustManager trustManager`. All existing constructors preserved via delegation.

The critical change is in `send(PeerId destination, Message message)`:

```java
// Trust Check: Application messages require TRUSTED state
if (trustManager != null && message.type() == MessageType.MESSAGE) {
    TrustState state = trustManager.getTrustState(destination);
    if (state != TrustState.TRUSTED) {
        throw new PeerRoutingException(
            "Cannot route application message: peer " + destination.value() +
            " is not TRUSTED (current trust state=" + state + ")"
        );
    }
}
```

This is the fail-closed gate. It applies **only** to `MessageType.MESSAGE`. Control-plane frames (`JOIN`, `LEAVE`, `AUTH_CHALLENGE`, `AUTH_PROOF`) are exempt by design — otherwise the handshake itself could never complete.

The entire multi-path dispatch, failover, transition buffer, and B.R3 delivery logic is untouched below this check. The trust gate is a thin boundary that either rejects the send immediately or lets the existing machinery proceed normally.

---

## 4. Test Coverage

Two new test files were added under `src/test/java/com/aryntra/pravah/security/trust/`.

### 4.1 `PeerTrustManagerTest.java` — Unit Tests

| Test | Verifies |
|---|---|
| `testChallengeCodecRoundtrip` | `AuthWireCodec` encode/decode preserves `challengeId`, entropy bytes, and expiry millisecond precision |
| `testProofCodecRoundtrip` | `AuthWireCodec` encode/decode preserves `PeerId`, DER public key, `challengeId`, and signature bytes |
| `testHappyPathAuthentication` | `UNKNOWN → AUTHENTICATING → TRUSTED` transitions fire correctly and `TrustStateListener` is notified |
| `testInvalidProofTransitionsToRejected` | Mismatched `challengeId` or malformed signature produces `REJECTED` |
| `testPeerIdMismatch` | A proof signed by Eve submitted under Alice's `PeerId` is caught and REJECTED (defence against identity injection) |

### 4.2 `TrustLifecycleIntegrationTest.java` — Integration Tests

| Test | Scenario |
|---|---|
| `testRouterFailsClosedForUntrustedPeer` | `PeerRouter.send(MESSAGE)` throws `PeerRoutingException` for peers in `UNKNOWN` and `AUTHENTICATING` |
| `testEndToEndHandshakeEnablesRouting` | Full wire-level handshake: `JOIN` → `AUTH_CHALLENGE` → `AUTH_PROOF` → peer becomes `TRUSTED` → application message routes successfully |
| `testMultiPathTrustInvariance` | Peer authenticates on TCP, then Bluetooth path is attached. Bluetooth routing works without a separate handshake. Dropping TCP leaves peer `TRUSTED` and traffic flows over Bluetooth |
| `testReplayAttackRejected` | Replaying an identical valid proof is rejected with `CHALLENGE_EXPIRED_OR_CONSUMED` and peer moves to `REJECTED` |
| `testPolicyRejection` | A custom `TrustPolicy` that returns `false` causes an authenticated peer to transition to `REJECTED` even though the signature is cryptographically valid |

### 4.3 Regression

Full `./gradlew testDebugUnitTest --continue` suite was executed at every patch boundary:

- After Patch 1 (protocol vocabulary + trust foundation): **GREEN**
- After Patch 2 (codec + trust manager): **GREEN**
- After Patch 3 (coordinator + router integration): **GREEN**
- After Patch 4 (integration tests): **GREEN**
- Final verification: **BUILD SUCCESSFUL**

Zero existing tests were modified. Zero existing tests regressed. The B.R3 delivery outbox, retry manager, transition buffer, path selection policy, framing, and session manager tests all remained green throughout.

---

## 5. Problems Faced and Mitigations

### 5.1 Discovery: Repository Structure Was Not What Was Assumed

**Problem:** The initial baseline block assumed Gradle at the repo root. The actual Gradle project lived at `android/gradlew.bat`, and the main Java source tree was at `src/main/java/...` (not under the Android module). The first `./gradlew test` call failed with "Directory does not contain a Gradle build."

**Mitigation:** Added a defensive repo-scan step (`Block 1.2`) that locates `gradlew.bat` dynamically and runs `testDebugUnitTest` from the correct directory. This established a reliable baseline before any production code was touched. The lesson here was non-trivial: the brief explicitly said *"Do not start coding immediately. Pravaah already has substantially more security infrastructure than a fresh project."* — the baseline verification pass is exactly what caught the structural mismatch.

### 5.2 File Discovery Returned Empty Results

**Problem:** The initial `Get-ChildItem -Recurse -Filter "*.kt"` returned only Kotlin files. The entire core production tree is in **Java**, under `src/main/java/com/aryntra/pravah/...`. The security, authentication, lifecycle, and routing files were all invisible to the first search.

**Mitigation:** Rewrote the inventory step to include both `*.kt` and `*.java`, exclude `build/` and `.gradle/` directories, and print the full relative path. This surfaced all 182 source files cleanly and revealed the existing SX.2 and SX.3 packages at:
- `src/main/java/com/aryntra/pravah/security/identity/`
- `src/main/java/com/aryntra/pravah/security/authentication/`

Without this corrective step the sprint would have mistakenly duplicated primitives that already existed — exactly the hard prohibition the brief warned against.

### 5.3 Serialization Was Not Pre-Built in SX.3

**Problem:** SX.3's `AuthenticationChallenge` and `AuthenticationProof` had no wire serialization. They were purely in-memory objects. The brief did not want these classes modified ("Do not redesign these APIs before understanding them"). But the handshake had to travel over the existing `Message` wire format.

**Mitigation:** Created a separate, pure-utility class `AuthWireCodec` in the same `security.authentication` package. It uses only the public accessors (`challengeId()`, `challengeBytes()`, `expiryTime()`, `claimantIdentity()`, `signatureBytes()`) and the existing `PublicKeyCodec` from SX.2 for the DER-encoded public key. Zero modifications to `AuthenticationChallenge` or `AuthenticationProof`. The codec is strictly a serialization boundary and is independently unit-tested with roundtrip assertions.

### 5.4 Backward Compatibility of `PeerConnectionCoordinator`

**Problem:** `PeerConnectionCoordinator` has two existing public constructors. Several production call sites and all existing tests use them. Adding mandatory parameters would have forced edits across the entire call graph — directly violating the "smallest correct change" principle.

**Mitigation:** Introduced a new three-argument extended constructor accepting `PeerTrustManager`, `IdentityKeyPair`, and `CryptographicIdentity`. Both existing constructors were preserved and now delegate to the extended constructor with `null` values. Inside `handleInboundAuthChallenge` and `handleInboundAuthProof`, null-guards ensure the coordinator silently no-ops authentication if trust management is not configured. This made SX.4 opt-in at the integration layer without regressing anything.

### 5.5 `PeerRouter` Breaking Protocol Handshakes

**Problem:** The first version of the router trust gate rejected **all** messages to untrusted peers. This broke the handshake itself — `AUTH_CHALLENGE` and `AUTH_PROOF` could never be sent, because at that moment the peer is by definition not yet `TRUSTED`.

**Mitigation:** Narrowed the gate to apply **only** to `MessageType.MESSAGE`. Control-plane message types (`JOIN`, `LEAVE`, `AUTH_CHALLENGE`, `AUTH_PROOF`) bypass the trust check. This matches the correct security model: control plane bootstraps trust, application plane requires trust.

### 5.6 Multi-Path Semantics: Peer-Level vs. Path-Level

**Problem:** The brief explicitly warned not to make trust path-level ("The expected direction is likely peer-level trust, but the implementation must establish this from the existing architecture and requirements"). The temptation when looking at `PeerConnectivity`'s path-centric structure is to add a `trusted` flag to `ConnectivityPath`. This would have been architecturally wrong.

**Mitigation:** `PeerTrustManager` keeps its own `ConcurrentHashMap<PeerId, PeerTrustRecord>` keyed by `PeerId`, not by `PathId` or `connectionId`. `PeerRouter` consults `trustManager.getTrustState(destination)` using only the `PeerId`. `ConnectivityPath` was not modified at all. The multi-path invariance test (`testMultiPathTrustInvariance`) locks this in: a peer authenticated once remains trusted when a second transport attaches, and remains trusted when the first transport drops.

### 5.7 Replay Protection: Already Present but Needed Enforcement

**Problem:** SX.3's `AuthenticationChallenge` already had `consume()` / `isExpired()` / `isConsumed()`. But if a verifier never actually called `verifyProof()` through `AuthenticationService`, the single-use guarantee could be bypassed. SX.4 had to make sure every proof evaluation went through the service's `verifyProof(proof)` path, which removes the challenge from the pending map atomically before calling `verifyDirect()`.

**Mitigation:** `PeerTrustManager.evaluateProof()` strictly delegates to `authService.verifyProof(proof)`. The replay test (`testReplayAttackRejected`) submits the same proof twice: the first returns `SUCCESS` and transitions to `TRUSTED`; the second returns `CHALLENGE_EXPIRED_OR_CONSUMED` and transitions to `REJECTED`. The replay defence provided by SX.3 is now observably enforced end-to-end.

### 5.8 PowerShell Encoding and Gradle Daemon Noise

**Problem:** Several PowerShell `Here-String` outputs contained Unicode glyphs (arrows, box-drawing) that got mangled in the terminal transcript. Also, Gradle emitted SDK warnings and daemon messages that mixed with real build output.

**Mitigation:** All generated files were written with explicit `-Encoding utf8`. Build output verification was narrowed to `Select-Object -Last 10` so the `BUILD SUCCESSFUL` / exit code line was always visible. This did not affect correctness — the compiler and test runner always saw clean UTF-8 source — but it made the sprint logs noisier than ideal. For future sprints, piping Gradle output to a dedicated log file and inspecting it separately would be cleaner.

### 5.9 Line Endings Warning

**Problem:** Git emitted `LF will be replaced by CRLF` warnings on every staged file during the commit chunks. This is cosmetic on Windows but can cause diff noise and cross-platform friction.

**Mitigation:** No action taken within SX.4 because this is a repo-wide hygiene matter, not a sprint-specific regression. A follow-up should add a `.gitattributes` file enforcing `* text=auto eol=lf` for Java sources to standardise line endings across the team.

---

## 6. What Was Deliberately Not Done

Following the brief's explicit prohibitions:

- No encryption (AES, TLS, Noise) was added. The brief marked this out of scope.
- No key exchange, forward secrecy, keystore, or key rotation was added.
- No cloud PKI or external trust anchor was introduced.
- `PathSelectionPolicy`, `CompositeTransport`, `TcpTransport`, `AndroidBluetoothRfcommTransport`, `TransitionBuffer`, `DeliveryRetryManager`, `SqliteDeliveryOutbox`, and all B.R3 sequence machinery were not modified.
- `DiagnosticActivity` and the Android Diagnostic UI panels were not modified. Any UI exposure of trust state belongs to a separate A-track sprint.
- `AuthenticationService`, `AuthenticationChallenge`, `AuthenticationProof`, `AuthenticationResult`, `CryptographicIdentity`, `IdentityKeyPair`, `IdentityGenerator`, `PublicKeyCodec`, and `SignatureService` were not modified.
- `PeerState` and `PeerPresenceState` were not modified. The temptation to add `TRUSTED` / `AUTHENTICATED` to `PeerState` was explicitly resisted in favour of a separate `TrustState` enum.

---

## 7. Artifacts and Commit Topology

### Production (5 files)
```
src/main/java/com/aryntra/pravah/protocol/MessageType.java          [modified]
src/main/java/com/aryntra/pravah/security/authentication/AuthWireCodec.java  [new]
src/main/java/com/aryntra/pravah/security/trust/TrustState.java              [new]
src/main/java/com/aryntra/pravah/security/trust/PeerTrustRecord.java         [new]
src/main/java/com/aryntra/pravah/security/trust/TrustPolicy.java             [new]
src/main/java/com/aryntra/pravah/security/trust/TrustStateListener.java      [new]
src/main/java/com/aryntra/pravah/security/trust/PeerTrustManager.java        [new]
src/main/java/com/aryntra/pravah/peer/PeerConnectionCoordinator.java         [modified]
src/main/java/com/aryntra/pravah/peer/PeerRouter.java                        [modified]
```

### Tests (2 files)
```
src/test/java/com/aryntra/pravah/security/trust/PeerTrustManagerTest.java         [new]
src/test/java/com/aryntra/pravah/security/trust/TrustLifecycleIntegrationTest.java [new]
```

### Documentation (6 files)
```
docs/adr/ADR-SX4-001-trust-aware-connection-lifecycle.md
docs/sprints/SX.4/SX4-SECURITY-CONTRACT.md
docs/sprints/SX.4/SX4-SPRINT-REPORT.md
docs/track-c/phasex/security/SX.4/SX4-CURRENT-LIFECYCLE.md
docs/track-c/phasex/security/SX.4/SX4-TRUST-LIFECYCLE.md
docs/track-c/phasex/security/SX.4/SX4-ARCHITECTURE.md
```

### Commit Chunks (capability-wise, in dependency order)

| Chunk | Commit | Scope |
|---|---|---|
| 1 | `055b177` | `feat(security): define authentication protocol types and wire serialization` |
| 2 | `95e1c8f` | `feat(security): implement trust state machine and PeerTrustManager` |
| 3 | `61e3f66` | `feat(peer): integrate trust lifecycle and fail-closed message gating` |
| 4 | `0ac1c0c` | `test(security): add comprehensive trust lifecycle and multi-path tests` |
| 5 | `12a4ef7` | `docs(security): document SX.4 architecture, security contract, and sprint report` |

**Tag:** `vSX.4` → `12a4ef7`
**Pushed:** `origin/main` (fast-forward from `0e72a2c` to `12a4ef7`)

---

## 8. Definition of Done — Verification

| Requirement | Status | Evidence |
|---|---|---|
| Connectivity, identity, trust explicitly separated | ✅ | Three independent enums in three different packages |
| Trust ownership clearly defined | ✅ | `PeerTrustManager` is the sole authority for `TrustState` |
| Authentication lifecycle documented | ✅ | `SX4-TRUST-LIFECYCLE.md` with state table and transition rules |
| Multi-path trust semantics defined | ✅ | Section 5 of `SX4-TRUST-LIFECYCLE.md`; `testMultiPathTrustInvariance` |
| Reconnection semantics defined | ✅ | Section 5.4 of `SX4-TRUST-LIFECYCLE.md` |
| SX.2 identity primitives reused | ✅ | `IdentityKeyPair`, `PublicKeyCodec`, `CryptographicIdentity` used; zero modifications |
| SX.3 authentication primitives reused | ✅ | `AuthenticationService`, `AuthenticationChallenge`, `AuthenticationProof` used; zero modifications |
| No duplicate security implementation | ✅ | No parallel signature service, no parallel challenge store |
| Authentication integrated into actual lifecycle | ✅ | `PeerConnectionCoordinator` handles `AUTH_CHALLENGE` / `AUTH_PROOF` inline |
| Application usability has explicit trust rule | ✅ | `PeerRouter.send()` fail-closed gate on `MessageType.MESSAGE` |
| Authentication failure has deterministic behaviour | ✅ | Failed proofs → `REJECTED`; routing throws `PeerRoutingException` |
| TCP works | ✅ | All existing TCP tests green |
| Bluetooth works | ✅ | All existing Bluetooth tests green |
| Multi-path works | ✅ | All existing multi-path tests green plus new `testMultiPathTrustInvariance` |
| Path failover works | ✅ | All existing failover tests green |
| B.R3 delivery semantics intact | ✅ | All B.R3 outbox / sequence / dedup tests green |
| TransitionBuffer intact | ✅ | All transition window tests green |
| Existing authentication tests green | ✅ | SX.2 and SX.3 test suites unchanged and green |
| Full Core test suite green | ✅ | `BUILD SUCCESSFUL` on final run |
| ADR before implementation | ✅ | `ADR-SX4-001` records the architectural decision |

---

## 9. Recommendations for the Senior Reviewer

1. **Code review focus areas:**
   - `PeerTrustManager.evaluateProof()` — verify the strict ordering: proof decode → `authService.verifyProof()` → policy evaluation → state transition. The order matters for replay safety.
   - `PeerRouter.send()` — confirm the exemption list (`JOIN`, `LEAVE`, `AUTH_CHALLENGE`, `AUTH_PROOF`) is correct and complete.
   - `PeerConnectionCoordinator.handleInboundAuthProof()` — the catch-and-REJECT fallback on decode failure. Confirm this is the desired fail-closed behaviour.

2. **Line endings:** Add `.gitattributes` with `* text=auto eol=lf` to eliminate the CRLF warnings and standardise cross-platform diffs.

3. **Follow-up sprints that this sprint deliberately did not do:**
   - **A-track sprint:** expose `TrustState` through the Android Diagnostic UI alongside connectivity, paths, and delivery panels.
   - **SX.5 (proposed):** persistent trust records via SQLite (currently in-memory only).
   - **SX.6 (proposed):** challenge-response **before** `JOIN` acceptance, if the threat model requires it. Currently, `JOIN` is accepted first and authentication follows — this is correct given the sprint's "smallest correct change" directive, but a stricter model is defensible.
   - **Encryption track:** if confidentiality is required (not just authentication), a separate track for session key establishment on top of the authenticated identity.

4. **Physical validation:** The deterministic test suite is complete. A physical device-pair validation (two Android devices with the full handshake over real TCP and real Bluetooth) is recommended before `vSX.4` is promoted to a release channel.

---

## 10. Closing

SX.4 was executed strictly within the stated principle: **smallest correct change, strongest evidence, zero silent architectural drift**. No existing behaviour regressed. No existing primitive was duplicated. No shortcut was taken that weakened the security model. The result is a cleanly separated, cryptographically enforced trust lifecycle that composes naturally with Pravaah's existing multi-transport, multi-path, and reliable-delivery architecture.

The sprint is ready for review.

— [Junior Developer]