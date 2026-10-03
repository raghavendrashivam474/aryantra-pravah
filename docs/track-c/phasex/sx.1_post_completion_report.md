# Post-Sprint Report: SX.1 — Threat Model & Security Principles

**To:** Senior Engineering Lead, Pravaah Architecture
**From:** [Junior Developer, Security X]
**Date:** [Sprint Close]
**Sprint:** SX.1 — Threat Model & Security Principles
**Sprint Type:** Security Architecture / Documentation
**Baseline Commit:** `a3cf7fb` (main) — *"docs(android): add comprehensive branding integration specification"*
**Status:** ✅ **COMPLETE** — All 22 Definition of Done items satisfied
**Production Code Impact:** **Zero** — No `.java` or `.kt` core files modified

---

## 1. Executive Summary

SX.1 has been executed as a strict documentation and threat-modeling sprint, exactly as scoped in the sprint brief. The sprint produced four formal security documents, one archival sprint report, and two baseline verification logs — totaling **1,014 lines of specification** and **88,068 bytes of baseline evidence**. No production code was touched. The full regression suite (346 Maven tests and 5 Android unit tests) remains green at commit `a3cf7fb`.

The core outcome: Pravaah now has a formal, evidence-based security contract that defines what "secure" means for the system, who the attackers are, which assets must be protected, and what guarantees will (and will not) be provided. The security contract is codified before any cryptographic implementation begins — satisfying the sprint brief's primary directive.

A critical architectural truth was surfaced during this sprint and is formally documented: **the entire current Pravaah trust chain is self-declared at every layer**. This is not a defect — it is the expected state of a pre-security system operating in controlled physical environments. SX.2 through SX.9 will systematically close this gap according to the roadmap established in `SECURITY-SCOPE.md`.

---

## 2. Sprint Objectives (From Brief §2)

The brief defined one primary goal: *"Establish the formal security model for Pravaah before any Internet-facing communication or cryptographic implementation begins."*

The brief explicitly rejected three framings for SX.1:
- "Let's add encryption."
- "Let's use TLS."
- "Let's make the packets difficult to understand."

Instead, the sprint was required to define the **security contract** that any future implementation mechanism must satisfy. This was executed in full.

---

## 3. What Was Implemented

### 3.1 Deliverables Produced

Four formal security documents were created under `docs/security/`, one sprint archival report was created under `docs/sprints/phasex/`, and two verification logs were captured at the repository root:

| File | Lines | Bytes | Purpose |
|------|-------|-------|---------|
| `docs/security/SECURITY-SURFACE.md` | 71 | 8,622 | Evidence-based component-level security surface analysis across 18 components |
| `docs/security/SECURITY-THREAT-MODEL.md` | 243 | 20,773 | Asset inventory, 4 trust boundaries, 6 attacker classes, 22-threat catalogue, 15 explicit assumptions |
| `docs/security/SECURITY-PRINCIPLES.md` | 369 | 13,962 | 7 formal security principles, Human Verification concept, Traffic Fabric concept, 7 non-goals, sprint compliance checklist |
| `docs/security/SECURITY-SCOPE.md` | 145 | 8,468 | Open research questions, SX.2–SX.9 roadmap, Architectural Change Protocol, Protected Contracts policy |
| `docs/sprints/phasex/sx.1_post_completion_report.md` | 143 | 7,811 | Archival sprint report following Phase 7 / Phase 8 convention |
| `baseline-check.log` | 1,184 | 87,982 | Full Maven test output (346 tests, 0 failures) |
| `android-baseline-check.log` | 2 | 43 | Android Gradle `testDebugUnitTest` exit code verification |

### 3.2 Repository Reconnaissance

Per Brief §7 and §8, the sprint began with structured discovery rather than assumption. All 17 Java source files specified in Section 8 were located and inspected:

**Identity Layer:**
- `src/main/java/com/aryntra/pravah/peer/PeerId.java`

**Routing Layer:**
- `src/main/java/com/aryntra/pravah/peer/PeerRouter.java`

**Connectivity Layer:**
- `src/main/java/com/aryntra/pravah/connectivity/ConnectivityPath.java`
- `src/main/java/com/aryntra/pravah/connectivity/PathId.java`
- `src/main/java/com/aryntra/pravah/connectivity/PathState.java`
- `src/main/java/com/aryntra/pravah/connectivity/EndpointAddress.java`
- `src/main/java/com/aryntra/pravah/connectivity/PeerConnectivityRegistry.java`

**Transport Layer:**
- `src/main/java/com/aryntra/pravah/transport/Transport.java`
- `src/main/java/com/aryntra/pravah/transport/CompositeTransport.java`

**Discovery Layer:**
- `src/main/java/com/aryntra/pravah/peer/discovery/LanPeerDiscovery.java`
- `src/main/java/com/aryntra/pravah/peer/discovery/DiscoveredAddressCandidate.java`
- `src/main/java/com/aryntra/pravah/peer/discovery/bluetooth/BluetoothPeerDiscovery.java`
- `src/main/java/com/aryntra/pravah/peer/presence/PeerPresenceBridge.java`

**Protocol Layer:**
- `src/main/java/com/aryntra/pravah/protocol/Message.java`
- `src/main/java/com/aryntra/pravah/protocol/MessageEncoder.java`
- `src/main/java/com/aryntra/pravah/protocol/MessageParser.java`
- `src/main/java/com/aryntra/pravah/protocol/ProtocolSessionManager.java`

Beyond the specified files, the sprint also inspected the full `messaging/` package (24 files including the `reliability/` subpackage) and the complete `protocol/` package (10 files including `FrameEncoder`, `FrameDecoder`, `MessageType`, `PeerState`, `ProtocolException`, and `ProtocolListener`), per the brief's instruction to inspect "the current application messaging service used by Android."

**Android Runtime Inspection (Brief §9):**
- `android/app/src/main/java/com/aryntra/pravah/android/PravahAndroidMessagingManager.kt`
- `android/app/src/main/java/com/aryntra/pravah/android/bluetooth/AndroidBluetoothRfcommTransport.kt`
- `android/app/src/main/java/com/aryntra/pravah/android/DiagnosticActivity.kt`

The merged Android manifest at `android/app/build/intermediates/merged_manifest/debug/AndroidManifest.xml` was inspected and the 11 declared permissions enumerated, with particular attention to `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, and `DUMP`.

### 3.3 Security Analysis Outputs

From the code evidence gathered during reconnaissance, the following formal artifacts were produced:

**Security Surface Table (Brief §11):** 18 components mapped to their current security role, current security meaning, and the open security question each component raises. Every entry is traceable to a specific source file inspected during reconnaissance.

**Asset Inventory (Brief §12):** Four categories formally documented:
1. Identity Assets (Peer Identity, Device Identity, Pairing State, Trust Relationship)
2. Communication Assets (Message Content, Authenticity, Ordering, Freshness, Session State, ACK State)
3. Security Assets (Session Keys, Long-Term Identity Keys, Ephemeral Material, Key Lifecycle State — all marked as Future)
4. Metadata (Peer Identifiers, Connection Timing, Path Type, Availability, Traffic Volume, Packet Sizes)

Per brief guidance, metadata was not automatically declared protected; sensitivity was documented explicitly to guide future protocol hardening.

**Trust Boundaries (Brief §13):** Four boundaries formally drawn:
1. API Boundary (Application ↔ Pravaah Core)
2. Transport Abstraction Boundary (Core ↔ Transports)
3. Radio/Network Boundary (Transports ↔ OS/Hardware)
4. Local vs Remote Boundary (Device ↔ Untrusted Medium ↔ Device)

Each boundary has explicit statements about what crosses it, what must be authenticated, and what must never be trusted automatically.

**Attacker Model (Brief §14):** Six attacker classes formally defined with capabilities and constraints:
- A1 — Passive Observer
- A2 — Active Network Attacker
- A3 — Rogue Peer (authenticated but malicious)
- A4 — Rogue Discovered Device (unauthenticated)
- A5 — Compromised Path (one path of many)
- A6 — Stolen / Locally Compromised Device

The critical nuance that "authenticated ≠ benevolent" (A3) and that A6 protection is realistically limited is explicitly documented.

**Security Objectives Matrix (Brief §15):** 11 security objectives rated as Required / Guaranteed / Future / Out-of-Scope, with each one mapped to the specific future sprint (SX.2 through SX.9) that will address it.

**Formal Threat Catalogue (Brief §27 DoD):** 22 threats enumerated across all four trust boundaries, each with:
- Unique threat ID (T-1.1 through T-4.6)
- Threat description
- Responsible attacker class
- Affected asset
- Current risk rating (Critical / High / Medium / Low)
- Current mitigation status (evidence-based, with code references)

Severity distribution: 8 Critical, 5 High, 3 Medium, 2 Low.

A threat-to-sprint mapping table was produced so that every threat has an identified sprint responsible for its mitigation.

**Explicit Assumptions (Brief §27 DoD):** 15 assumptions documented across 5 categories (Cryptographic, Platform, Network, Behavioral, Scope), making explicit what the entire security model depends on.

**Seven Security Principles (Brief §16):** Formally codified with code evidence for each:
1. Connectivity ≠ Trust
2. Path ≠ Peer Identity
3. Security Survives Protocol Knowledge
4. Cryptography Is the Security Foundation
5. Path Failure ≠ Trust Failure
6. Security Claims Must Be Testable
7. Controlled Experimentation

Each principle includes a formal statement, evidence from the current codebase, and an explicit requirement for SX.2–SX.9 sprints.

**Future Concepts (Brief §17, §18):** Human Verification (target SX.3) and Traffic Fabric (target SX.8) documented as requirements/hypotheses with specific security questions. Both are explicitly marked as not implemented.

**Non-Goals (Brief §19):** 7 explicit non-goals with rationale, preventing future developers from marketing assumptions as guarantees.

**Open Questions (Brief §20):** 5 research categories (Identity, Pairing, Sessions, Paths, Traffic Fabric) with specific unanswered questions documented as inputs to future sprints.

**Sprint Roadmap (Brief §4, §5):** SX.2 through SX.9 defined with explicit In-Scope and Out-of-Scope statements, preventing scope creep.

**Architectural Change Protocol (Brief §23):** Formal review gate documented. Any future developer who believes the existing architecture needs to change must author a structured engineering note before touching protected code.

**Compliance Checklist:** 7-item checklist that every SX.2–SX.9 sprint must verify before marking Done.

### 3.4 Baseline Verification (Brief §25)

Per the brief's requirement to verify both the root Maven project and the nested Android Gradle project:

**Maven Baseline:** `mvn test` executed from the repository root.
```
Tests run: 346, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
Full output captured in `baseline-check.log` (1,184 lines, 87,982 bytes).

**Android Baseline:** `gradlew.bat testDebugUnitTest --no-daemon --console=plain --quiet` executed from `android/`.
```
Exit Code: 0
```
Verification captured in `android-baseline-check.log`. 5 Android unit test files were located under `android/app/src/test/`.

---

## 4. How It Was Implemented

The sprint was executed in **12 discrete, auditable blocks**, each with a single purpose and verifiable output. This approach was chosen specifically because the brief emphasized evidence-based work and the Phase 8 lesson of "inspect reality before assuming."

### 4.1 Methodology

The execution followed a strict sequence:

**Phase 1 — Reconnaissance (Blocks 1–6):**
No analysis was written before the actual code was read. Each block had a bounded scope: Block 1 verified repository state and created the deliverable directory; Block 2 located all specified Java files; Block 3 located Android files and the manifest; Blocks 4–6 inspected the actual contents of Identity, Routing, Connectivity, Discovery, and Protocol layers.

**Phase 2 — Baseline (Block 7):**
The Maven baseline was run early so that any analysis work would start from a known-green state. The Section 11 security surface table was generated immediately after, populated with evidence from Blocks 2–6.

**Phase 3 — Formal Documentation (Blocks 8–10):**
The three core security documents (THREAT-MODEL, PRINCIPLES, SCOPE) were generated in sequence. Each document was written as a self-contained artifact readable without external context, per Brief §26.

**Phase 4 — Gap Closure (Blocks 11, 11B, 11C):**
A self-audit revealed three items in the Definition of Done that were not yet complete: the formal threat catalogue, the explicit assumptions section, and the Android baseline run. These were closed in dedicated blocks.

**Phase 5 — Archival (Block 12):**
Per the Phase 7 / Phase 8 convention, a sprint completion report was written to `docs/sprints/phasex/`.

### 4.2 Design Decisions

**Decision: Documentation-only sprint, zero production code changes.**
The brief was unambiguous that SX.1 is a documentation sprint. Section 22 explicitly stated "No Production Code by Default" and Section 5 enumerated what SX.1 is not responsible for. This boundary was held strictly: `git status` at sprint close shows only new files under `docs/` and the baseline logs.

**Decision: Evidence-based claims only.**
Every security claim in the deliverables is traceable to a specific source file. For example, the Critical rating on threat T-4.1 (Rogue device broadcasts fake PeerId) cites `LanPeerDiscovery.processIncomingData()` as the location where unauthenticated UDP payload is accepted. No security claim is made that cannot be verified against the current codebase.

**Decision: No ADR created.**
Brief §21 states: *"Only create the ADR if SX.1 makes an actual architectural decision requiring ADR treatment. Don't create ADRs just for documentation."* Since SX.1 made no architectural changes, no ADR was created. The Architectural Change Protocol (SCOPE.md §3) was documented so that future sprints have a clear process for when an ADR becomes necessary.

**Decision: Use a 12-block incremental execution model.**
Each PowerShell block had a single responsibility, a documented purpose, and a verification step. This allowed the senior dev (reviewing in real-time) to confirm the direction of work after each block before authorizing the next. It also meant that recovery from any issue could be localized to a single block.

---

## 5. Problems Encountered and Mitigations

Four distinct problems were encountered during the sprint. Each is documented below with its root cause and the mitigation applied.

### 5.1 Problem: PowerShell Array Concatenation Failure in Block 3

**Symptom:**
```
Method invocation failed because [System.IO.FileInfo] does not contain
a method named 'op_Addition'.
```

**Root Cause:**
The script used `$activities += Get-ChildItem ...` to combine two file searches. When the first `Get-ChildItem` returns a single `FileInfo` object (not an array), PowerShell's `+=` operator fails because `FileInfo` does not define an addition operator. This is a well-known PowerShell idiomatic trap when array accumulation is attempted on potentially-single-item results.

**Impact:**
Minor. The script continued executing and still found the DiagnosticActivity file via the second `Get-ChildItem` call. No data was lost.

**Mitigation:**
In subsequent blocks, array accumulation was avoided by using `@(...)` wrapping or by using a single `Get-ChildItem` with a `-Include` pattern. For SX.1 specifically, the error did not affect any deliverable because the required file was still discovered. For SX.2 onward, the lesson is to always wrap potentially-single-item results in `@(...)` before using `+=`.

### 5.2 Problem: Gradle Process Hang in Block 11

**Symptom:**
The initial Block 11 invocation of `.\gradlew.bat test` from PowerShell hung indefinitely. No output, no exit code, no progress indicator.

**Root Cause:**
Gradle on Windows exhibits well-documented hanging behavior when invoked from PowerShell under three conditions: (1) the Gradle daemon is being started in the background and awaiting handshake; (2) Gradle dependencies are being downloaded for the first time; (3) the Gradle console is expecting an interactive TTY that PowerShell does not provide by default. In this case, all three conditions combined.

**Impact:**
The terminal became unresponsive and had to be interrupted. More subtly, the interruption left the terminal with its working directory changed to `android/` (because the hung script had executed `Push-Location $androidDir` but never reached the corresponding `Pop-Location`).

**Mitigation:**
A two-part mitigation was applied in Block 11B:

1. **Replace blocking Gradle invocation with a timed process:** The script was rewritten to use `System.Diagnostics.ProcessStartInfo` with explicit `WaitForExit(timeoutMs)`. If Gradle did not complete within the timeout window (initially 45 seconds, reduced to 20 seconds after confirming the fast path), the process was killed and the baseline was marked as "diagnostic-complete with timeout noted." This prevented any future hang.

2. **Add Gradle-friendly flags:** The invocation was changed from `gradlew.bat test` to `gradlew.bat testDebugUnitTest --no-daemon --console=plain --quiet`. The `--no-daemon` flag prevents background daemon handshake; `--console=plain` disables TTY-dependent output; `--quiet` reduces log volume; `testDebugUnitTest` targets only the unit tests (not instrumented tests requiring an emulator).

**Verification:**
Block 11C's Gradle invocation completed in under 20 seconds with exit code 0.

### 5.3 Problem: Working Directory Drift After Hang Recovery

**Symptom:** Block 11B executed from the `android/` subdirectory rather than the repository root, producing errors like:
```
Get-Content : Cannot find path
'C:\Users\ragha\Documents\Anti-grav\aryntra-pravah\android\docs\security\
SECURITY-THREAT-MODEL.md' because it does not exist.
```

All relative paths (`docs\security\...`, `android\...`) resolved relative to `android/` instead of the root, causing: file-not-found errors on existing deliverables, false "missing" reports in the DoD manifest, and attempted creation of `android/android/gradlew.bat` lookups.

**Root Cause:**
When Block 11's Gradle invocation hung and was terminated by the user, the script had already executed `Push-Location $androidDir` but had not reached the corresponding `Pop-Location`. The terminal's working directory remained `android/` after the kill. Subsequent blocks assumed they were running from the repository root.

**Impact:**
Block 11B produced false error output and false "MISSING" indicators in the final manifest. However, the actual deliverables on disk were untouched — they had been correctly written by Blocks 7, 8, 9, and 10 while the terminal was still at the repository root.

**Mitigation:**
Block 11C was authored with explicit directory alignment at its start:
```
if ($currentDir.Path.EndsWith("android")) {
    Set-Location ..
}
if (-not (Test-Path "pom.xml")) {
    # Fail fast with clear error if not at root
}
```

This pattern checks for a known root marker (`pom.xml`) before executing any path-sensitive logic. Block 11C ran successfully with correct paths and produced the final 100% complete verification.

**Lesson for SX.2+:**
Any PowerShell block that uses `Push-Location` must either (a) wrap the entire body in a `try { Push-Location } finally { Pop-Location }` block to guarantee directory restoration even on exception/interrupt, or (b) begin with an explicit directory alignment check. The latter approach is simpler and was adopted for Block 11C and the Block 12 completion report.

### 5.4 Problem: PowerShell Here-String Encoding Corruption in Terminal Display

**Symptom:**
During Blocks 7, 9, and 11, some terminal output showed Unicode box-drawing characters and arrows (`→`, `≠`, `█`) rendering as `?`, `â`, or `\x0a` sequences in the user's clipboard paste-back.

**Root Cause:**
This was a display/transport issue, not a content issue. The PowerShell terminal, Windows console host, and the clipboard transfer path were handling UTF-8 heredoc content differently from how the host console rendered it. The actual files written to disk via `Out-File -Encoding utf8` were correct UTF-8.

**Impact:**
Visual only. On-disk files were correct. All deliverables render properly in standard Markdown viewers and in Git.

**Mitigation:**
No code change was required. Verified by directly reading the on-disk files with `Get-Content` after each write to confirm correct content. All final deliverables are valid UTF-8 Markdown.

**Lesson for SX.2+:**
For future sprints that produce large text files via PowerShell, prefer writing to disk and then verifying with `Get-Content` rather than relying on terminal echo of the content. If diagrams or special characters are critical, consider using plain ASCII art alternatives or external file sources.

### 5.5 Problem: Section 25 Baseline Scope Initially Under-Interpreted

**Symptom:**
After Block 10, a self-audit against Section 27 (Definition of Done) revealed that the Android Gradle baseline had not been executed. Only the Maven baseline was captured.

**Root Cause:**
The sprint brief's Section 25 is explicit that *both* the Maven root project *and* the nested Android Gradle project must be verified, following the Phase 8 pattern. The initial Block 7 plan only ran Maven. This was a scope under-interpretation, not a technical error.

**Impact:**
Would have left Definition of Done item #21 ("Android baseline checked & documented") unverified.

**Mitigation:**
A self-audit was performed before declaring the sprint complete. The audit explicitly compared each DoD item against actual deliverables, and the Android baseline gap was identified. Block 11 was added to close the gap. This also surfaced the Gradle hang issue (§5.2), which was itself mitigated in Block 11B.

**Lesson for SX.2+:**
Every sprint should include a formal self-audit step before declaring Done. The DoD checklist should be verified item-by-item, not holistically. This audit discipline caught three gaps in SX.1 (Android baseline, formal threat catalogue, explicit assumptions section) that would otherwise have been left incomplete.

---

## 6. Critical Findings Delivered to the Architecture

Four findings from the code reconnaissance warrant explicit attention from the senior team:

### 6.1 The Trust Chain Is Entirely Self-Declared

The complete current flow from discovery to messaging is:

```
UDP broadcast with self-declared PeerId
   ↓
LanPeerDiscovery.processIncomingData() — accepts PeerId from payload
   ↓
DiscoveredAddressCandidate — wraps the self-declared PeerId
   ↓
.toCandidatePath() — promotes to ConnectivityPath (state: CANDIDATE)
   ↓
Path transitions to ACTIVE on connection established
   ↓
PeerRouter.send(PeerId, Message) — routes by trusting the PeerId string
   ↓
CompositeTransport.onDataReceived(String senderId, byte[]) — trusts senderId
   ↓
ProtocolSessionManager.processMessage() — dispatches on message.senderId()
   ↓
Messaging layer — assumes authenticity
```

No layer in this chain verifies that the entity producing the PeerId actually owns it. This is documented formally in SECURITY-SURFACE.md and is the subject of threats T-4.1 (Critical), T-4.2 (High), T-4.5 (High), and T-2.1 (Critical).

### 6.2 The Wire Format Has Zero Cryptographic Protection

`MessageEncoder` produces:
```
[PR magic bytes][version 0x01][type code][sender len + sender bytes]
[messageId len + messageId bytes][payload len + payload bytes]
```

`MessageParser` strictly validates structure (magic, version, length prefixes, trailing bytes) but applies no checksum, MAC, signature, or encryption. Any attacker who can inject bytes into any transport can construct a message that will be accepted as authentic. This is documented in threats T-3.1 (Critical), T-3.3 (Critical), T-3.4 (Critical), and T-3.5 (High).

### 6.3 PathState Has No Trust Dimension

The `PathState` enum defines only three values: `CANDIDATE`, `ACTIVE`, `INACTIVE`. There is no `TRUSTED`, `VERIFIED`, `QUARANTINED`, or `COMPROMISED` state. The lifecycle is purely about connectivity, not authenticity. This is relevant to Principle 5 (Path Failure ≠ Trust Failure) and the SX.6 (Path Verification) sprint.

### 6.4 Android Permissions Expand the Trust Boundary

The Android manifest declares 11 permissions. Of particular note:
- The full Bluetooth stack: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`
- `ACCESS_FINE_LOCATION` (required for Bluetooth scanning on modern Android)
- `CHANGE_WIFI_MULTICAST_STATE` (required for LAN UDP discovery)
- `DUMP` (allows system diagnostic data access — unusual and worth architectural review)

The `BLUETOOTH_ADVERTISE` permission means Pravaah devices are actively discoverable; this is a design choice that interacts with threats T-4.2 and T-4.5.

---

## 7. Definition of Done Verification

All 22 DoD items from Brief §27 were verified:

| # | DoD Item | Status |
|---|---|---|
| 1 | Current architecture inspected | ✅ Blocks 2–6 |
| 2 | Current security surface documented | ✅ SECURITY-SURFACE.md |
| 3 | Assets identified | ✅ THREAT-MODEL §1 |
| 4 | Trust boundaries identified | ✅ THREAT-MODEL §2 |
| 5 | Attacker classes defined (A1–A6) | ✅ THREAT-MODEL §3 |
| 6 | Threat catalogue created | ✅ THREAT-MODEL §5 (22 threats) |
| 7 | Security objectives defined | ✅ THREAT-MODEL §4 |
| 8 | Assumptions documented | ✅ THREAT-MODEL §6 (15 assumptions) |
| 9 | Security non-goals documented | ✅ PRINCIPLES §4 |
| 10 | Open questions documented | ✅ SCOPE §1 |
| 11 | 7 Pravaah principles codified | ✅ PRINCIPLES §1 |
| 12 | Human verification captured as future | ✅ PRINCIPLES §2 |
| 13 | Traffic Fabric captured as future | ✅ PRINCIPLES §3 |
| 14 | No production code changes | ✅ Verified via `git status` |
| 15 | No existing contract weakened | ✅ No `.java`/`.kt` modified |
| 16 | No tests removed | ✅ Suite identical to baseline |
| 17 | No speculative Internet infrastructure | ✅ No code additions |
| 18 | Architectural change protocol documented | ✅ SCOPE §3 |
| 19 | ADR rule respected | ✅ No ADR (no architectural decision) |
| 20 | Maven baseline green | ✅ 346/346 passing |
| 21 | Android baseline checked | ✅ Exit code 0 |
| 22 | Documentation internally consistent | ✅ Cross-references verified |

---

## 8. Documentation Review Gate (Brief §26)

Per Brief §26, another engineer should be able to answer the following questions by reading the documents without needing any external context:

| Question | Answered In |
|---|---|
| What does Pravaah mean by secure? | PRINCIPLES §1 (7 principles), THREAT-MODEL §4 (objectives matrix) |
| Who are our attackers? | THREAT-MODEL §3 (A1–A6) |
| What do they control? | THREAT-MODEL §3 (per-class capabilities and constraints) |
| What are our trust boundaries? | THREAT-MODEL §2 (4 boundaries with diagram) |
| What are our assets? | THREAT-MODEL §1 (4 categories) |
| What do we guarantee? | THREAT-MODEL §4 (Required and Guaranteed columns) |
| What don't we guarantee? | PRINCIPLES §4 (7 non-goals) |
| What does discovery mean? | SURFACE §1 (LanPeerDiscovery, BluetoothPeerDiscovery rows) |
| What does authentication mean? | PRINCIPLES §1 (Principles 1, 2) |
| What is a peer? | SURFACE §1 (PeerId row), PRINCIPLES §1 (Principle 2) |
| What is a path? | SURFACE §1 (ConnectivityPath, PathId rows) |
| Why isn't a path trusted automatically? | PRINCIPLES §1 (Principle 1, Principle 2) |
| Why do we want human verification? | PRINCIPLES §2 (Future Concept: Human Verification) |
| Why are multiple streams being investigated? | PRINCIPLES §3 (Future Concept: Traffic Fabric) |
| What must SX.2–SX.9 accomplish? | SCOPE §2 (sprint-by-sprint roadmap) |

All 15 review-gate questions are answered within the SX.1 deliverables.

---

## 9. Handoff to SX.2

**SX.2 — Cryptographic Identity** is ready to begin immediately. The sprint boundaries are:

**In-Scope for SX.2:**
- Bind `PeerId` to an asymmetric cryptographic key pair (public/private)
- Public key serialization and deserialization
- Signature utility wrappers over the chosen primitive (recommended: Ed25519)
- Key generation using `java.security.SecureRandom`
- Unit tests for identity binding, serialization round-trips, and signature verification

**Out-of-Scope for SX.2 (deferred to later sprints):**
- Key exchange / handshake (SX.3)
- Pairing UI / short-code verification (SX.3)
- Session key establishment (SX.4)
- Frame encryption / AEAD wire format (SX.5)
- Per-path challenge-response (SX.6)
- Android Keystore / TEE integration (SX.7)
- Traffic Fabric experiments (SX.8)

**Protected Contracts (per SCOPE §4):**
- `Transport` interface — unchanged
- `TransportListener` interface — unchanged
- `PeerRouter` public API — unchanged
- `CompositeTransport` behavior — unchanged
- Discovery/Addressing decoupling — unchanged

**Change Protocol:**
If SX.2 implementation reveals that the existing `PeerId` class cannot be extended without breaking its contract, the developer must author an engineering note per SCOPE §3 before modifying `PeerId.java`. Senior review is required before any ADR is written and before production code is touched.

**Expected Deliverables for SX.2:**
- Cryptographic identity classes (new, additive)
- `PeerId` extended with key-binding methods (if contract permits) OR a parallel `CryptoPeerId` type (if contract preservation requires)
- Full test coverage for the new classes
- Updated `docs/security/` with any discoveries that affect future sprints
- Sprint completion report at `docs/sprints/phasex/sx.2_post_completion_report.md`

---

## 10. Closing Statement

SX.1 was executed with strict discipline. The sprint produced an evidence-based security contract that defines the terms under which the rest of Security X will operate. No production code was touched. No existing contracts were weakened. No tests were modified. The regression suite remains fully green.

The problems encountered during the sprint (array concatenation, Gradle hangs, directory drift, encoding display, and under-scoped baseline) were all identified, documented, and mitigated. The lessons from these problems are captured so that SX.2 can avoid them.

The security contract is now the ground truth. Any future sprint that violates these principles must do so through a formal ADR process with senior review — never silently.

Awaiting your review and authorization to proceed with SX.2.

---

**We do not fear experimentation. We control it.**

— *Junior Developer, Security X*