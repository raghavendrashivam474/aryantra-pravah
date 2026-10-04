# Sprint A.D2.5 — Post-Completion Report

**To:** Senior Developer, Pravaah Architecture
**From:** Junior Developer, Track A
**Sprint:** A.D2.5 — Diagnostic Transport Drop Control & Bluetooth Lifecycle Hardening
**Baseline:** vA.D2.4-BT-PV (`bcc9043`)
**Proposed Tag:** `vA.D2.5`
**Date:** March 2025
**Status:** COMPLETE — All DoD items satisfied, zero regressions

---

## 1. Executive Summary

Sprint A.D2.5 is complete. The diagnostic cockpit now supports deliberate, operator-selectable transport perturbation via a `[DROP] → [TCP | BLUETOOTH | CANCEL]` dialog in place of the previous single-purpose `[DROP TCP]` button. Both TCP and Bluetooth paths can now be independently deactivated by the operator, with route selection and recovery delegated — unchanged — to the existing `PathSelectionPolicy`.

The twelve BT forensic checkpoints introduced in A.D2.4-BT were audited rather than purged. `BT-07`, `BT-08`, and `BT-12` were formally promoted to permanent lifecycle markers; the remaining nine were retained as standard diagnostics with explicit justification.

**Critically, no Core, routing, security, protocol, reliability, or transport-internal code was modified.** The entire sprint was executed through the existing `PeerConnectivity.addPath(path.deactivate())` abstraction, which already supported transport-agnostic path deactivation. No architectural gap was discovered, and no `ARCHITECTURE-NOTE.md` was required.

Core Maven suite: **414/414 pass**. Android Gradle unit suite: **green**.

---

## 2. What Was Implemented

### 2.1 UI — Transport-Selectable Drop Dialog

**File:** `android/app/src/main/res/layout/activity_diagnostic.xml`
- Changed `btnSimulateDrop` text attribute: `"DROP TCP"` → `"DROP"`.
- No layout structure, weight, size, or sibling element changes. The button remains in-place in the existing hybrid-connect button row.

**File:** `android/app/src/main/java/com/aryntra/pravah/android/DiagnosticActivity.kt`
- Click handler at L122 rewired: `simulateTcpDrop()` → `showDropTransportDialog()`.
- Added `showDropTransportDialog()`: lightweight `android.app.AlertDialog` with items `["TCP", "BLUETOOTH"]` and a negative `CANCEL` button. No new activities, fragments, navigation, or decorative styling introduced. Dialog dispatches to `simulateTransportDrop("tcp")` or `simulateTransportDrop("bluetooth")`.

### 2.2 Diagnostic Drop Execution — Generalized

The original `simulateTcpDrop()` was generalized into a transport-parameterized operation that preserves the exact drop semantics proven in A.D2.4:

```kotlin
private fun simulateTransportDrop(transportName: String) {
    val peer = connectedPeerId ?: return
    addSystemEvent("DROP requested target=$transportName")
    backgroundExecutor.execute {
        try {
            manager.connectivityRegistry.lookup(peer).ifPresent { conn ->
                var found = false
                for (path in conn.activePaths()) {
                    if (path.transportName().equals(transportName, ignoreCase = true)) {
                        conn.addPath(path.deactivate())
                        found = true
                        handler.post {
                            addSystemEvent("PATH: ${path.pathId().value()} ($transportName) ACTIVE->INACTIVE")
                            updateDashboard()
                        }
                    }
                }
                if (!found) {
                    handler.post { addErrorEvent("DROP: No active $transportName path found") }
                }
            }
        } catch (e: Exception) {
            handler.post { addErrorEvent("DROP ERROR: ${e.message}") }
        }
    }
}

private fun simulateTcpDrop() { simulateTransportDrop("tcp") }
```

The legacy `simulateTcpDrop()` was preserved as a thin wrapper rather than deleted. This keeps any latent references safe and makes the generalization a pure additive refactor.

### 2.3 Event Stream Semantics

The event stream now emits a traceable sequence per drop:

```
SYSTEM: DROP requested target=tcp
SYSTEM: PATH: <path-id> (tcp) ACTIVE->INACTIVE
```

Route selection is **not** manually logged by the UI — the resulting selected-route change surfaces naturally through `updateDashboard()` → `DiagnosticModelMapper` → the existing path panel, driven entirely by `PathSelectionPolicy`. This complies strictly with §12 of the brief: "do not generate events that claim a transition that didn't happen."

### 2.4 Bluetooth Lifecycle Classification

All 14 occurrences of `[BT-FORENSIC] BT-xx` checkpoints across `AndroidBluetoothRfcommTransport.kt` (L153–L275) were audited and classified in `docs/sprints/AD2.5/BT-OBSERVABILITY-DECISION.md`:

- **Promoted to Permanent Core Lifecycle:** `BT-07` (socket.connect start), `BT-08` (socket.connect result SUCCESS/FAILED), `BT-12` (reader EOF / IOException).
- **Retained as Standard Diagnostic:** `BT-01` through `BT-06`, `BT-09` through `BT-11`.

No checkpoints were deleted. Per §11, "If the safest conclusion is to retain the existing instrumentation for now, that is acceptable" — and that is the conclusion we reached. All existing `BT-FORENSIC` logging continues to flow unchanged; only the semantic classification was formalized.

### 2.5 Unit Test Coverage

**New file:** `android/app/src/test/java/com/aryntra/pravah/android/DiagnosticTransportDropTest.kt`

Three JUnit 5 tests validating the drop isolation guarantees against the actual `PeerConnectivity` API:
1. `testDropTcpDeactivatesTcpPathPreservingBluetooth` — asserts TCP drop leaves BT active.
2. `testDropBluetoothDeactivatesBluetoothPathPreservingTcp` — asserts BT drop leaves TCP active.
3. `testDropTargetNotFoundDoesNotAffectOtherPaths` — asserts drop on an unestablished transport is a safe no-op.

---

## 3. Files Touched

| File | Scope | Nature |
|---|---|---|
| `android/app/src/main/res/layout/activity_diagnostic.xml` | 1-line change | Button text relabel |
| `android/app/src/main/java/com/aryntra/pravah/android/DiagnosticActivity.kt` | ~45 lines added/replaced | Dialog + generalized drop |
| `android/app/src/test/java/com/aryntra/pravah/android/DiagnosticTransportDropTest.kt` | New file (~110 LOC) | Unit tests |
| `docs/sprints/AD2.5/IMPLEMENTATION-PLAN.md` | New | Sprint planning doc |
| `docs/sprints/AD2.5/BT-OBSERVABILITY-DECISION.md` | New | BT checkpoint audit |
| `docs/sprints/AD2.5/VALIDATION-EVIDENCE.md` | New | Test & experiment evidence |
| `docs/sprints/AD2.5/post_completion_report.md` | New | Sprint closure doc |

**Files explicitly NOT touched (per §17 protected boundaries):**
`PeerRouter`, `CompositeTransport`, `PathSelectionPolicy`, `PeerConnectivity`, `PeerConnectivityRegistry`, `ConnectivityPath`, `PeerConnectionCoordinator`, `PeerPresenceBridge`, `BluetoothRfcommTransport` (core), TCP transport, protocol, reliability, security, `TransitionBuffer`, `PravahAndroidMessagingManager`, `DiagnosticState`, `DiagnosticModelMapper`.

The entire sprint lives inside Track A. No Track B/C surface was crossed.

---

## 4. Problems Encountered & Mitigations

This section is deliberately honest — several problems were encountered during Block 5 (unit test authoring) and each is documented below.

### Problem 1: Mismatched Test Framework Assumption
**Symptom:** My initial `DiagnosticTransportDropTest.kt` used JUnit 4 (`org.junit.Test`, `org.junit.Assert.*`). Gradle build failed immediately:
```
Unresolved reference: Test
Unresolved reference: assertFalse
```
**Root cause:** I assumed JUnit 4 without first inspecting a sibling test file. The Android module standardizes on **JUnit 5 Jupiter** (`org.junit.jupiter.api.*`), as evidenced by `PravahAndroidHybridTest.kt` and `DiagnosticModelMapperTest.kt`.
**Mitigation:** Scanned sibling test files for the correct import block before rewriting. Switched to `org.junit.jupiter.api.Test`, `Assertions.assertEquals`, and added `@DisplayName` to match project convention.
**Lesson:** When adding new tests, inspect at least one existing test file in the same module first. Framework assumptions are the fastest way to burn a Gradle cycle.

### Problem 2: Wrong Package Path for Core Types
**Symptom:**
```
Unresolved reference: PeerId
Unresolved reference: PeerConnectivity
Unresolved reference: PathState
```
**Root cause:** I guessed packages like `com.aryntra.pravah.core.connectivity.*` and `com.aryntra.pravah.core.identity.*`. The actual Core package layout is flat: `com.aryntra.pravah.connectivity.*` and `com.aryntra.pravah.peer.*`.
**Mitigation:** Inspected `DiagnosticModelMapperTest.kt` import block, which gave me the correct package paths verbatim. Rewrote imports.
**Lesson:** Never guess package paths — the sibling test files are the ground truth.

### Problem 3: Wrong Factory Method Names on `ConnectivityPath`
**Symptom:**
```
Unresolved reference: create    (on PeerConnectivity.create)
Unresolved reference: direct    (on ConnectivityPath.direct)
```
**Root cause:** I invented factory method names (`create`, `direct`, `of`) based on common Java idioms rather than reading the actual API. `ConnectivityPath` is a Java `record` with explicit static factories: `active(...)`, `candidate(...)`, `inactive(...)`. `PeerConnectivity` uses a plain public constructor, not a static factory.
**Mitigation:** Read `ConnectivityPath.java` and `PeerConnectivity.java` directly. Discovered the real factory signatures:
```java
public static ConnectivityPath active(PathId, PeerId, String transport, EndpointAddress, String connectionId)
public PeerConnectivity(PeerId peerId)  // plain constructor
```
Rewrote test helpers to use `ConnectivityPath.active(...)` and `PeerConnectivity(peerId)` directly.
**Lesson:** When touching a Java `record` from Kotlin, read the record declaration — do not infer factory conventions.

### Problem 4: Private Field Access Attempt
**Symptom:**
```
Cannot access 'paths': it is private in 'PeerConnectivity'
Expression 'paths' of type 'Map<PathId, ConnectivityPath>' cannot be invoked as a function
```
**Root cause:** I called `conn.paths()` and `conn.paths().values.find { ... }`, assuming a public accessor. `PeerConnectivity.paths` is a private `LinkedHashMap` field with no public getter.
**Mitigation:** Enumerated the actual public methods via regex scan:
```
public Optional<ConnectivityPath> findPath(PathId pathId)
public Collection<ConnectivityPath> allPaths()
public List<ConnectivityPath> activePaths()
```
Switched assertions to `conn.findPath(PathId.of("path-tcp-1")).get().isActive`, which is both correct and idiomatic.
**Lesson:** When unsure about a public API surface, list the public methods first before writing against assumed accessors.

### Pattern Across Problems 1–4
Four build failures in sequence — each one caused by **assuming the API shape instead of reading it**. The mitigation pattern that eventually worked:

> **Inspect first, write second.** For any unfamiliar type: locate the source file, scan its public method signatures, scan at least one existing consumer, *then* write the code.

Once I adopted this discipline rigorously in Block 5f, the build went green on the first attempt.

### Problem 5: BT Checkpoint Count Discrepancy
**Symptom:** The sprint brief references "twelve BT checkpoints" (§10), but the forensic scan found **14 occurrences** of `[BT-FORENSIC] BT-xx` markers in `AndroidBluetoothRfcommTransport.kt`.
**Root cause:** Two checkpoints (`BT-08` and `BT-12`) each appear twice — once for the SUCCESS/EOF branch and once for the FAILED/IOException branch. The brief's "twelve" refers to twelve *distinct* lifecycle events (BT-01 through BT-12), not twelve raw log lines.
**Mitigation:** Classified by lifecycle identity (12 unique checkpoints), not raw log line count. The `BT-OBSERVABILITY-DECISION.md` matrix is organized by checkpoint ID, which matches the brief's conceptual model.
**Lesson:** When the brief uses a count, confirm whether it refers to logical events or physical log statements.

---

## 5. Problems That Did NOT Occur (Worth Noting)

- **No architectural gap for BT drop.** The brief in §18 anticipated that a diagnostic Bluetooth drop *might* require crossing into Core. It did not. The existing `path.deactivate()` method on `ConnectivityPath` is already transport-agnostic — the TCP drop implementation was always generalizable. The only reason the original `simulateTcpDrop()` was TCP-specific was a hardcoded `equals("tcp", ignoreCase = true)` filter. Parameterizing that string was sufficient.
- **No route-selection bug.** When a BT path is deactivated via `addPath(path.deactivate())`, `PathSelectionPolicy` reacts correctly and selects TCP without any UI intervention. This confirms the policy was already transport-symmetric — the previous UI just never exercised that symmetry.
- **No regressions.** Core Maven: 414/414. Android unit tests: all green including the pre-existing `DiagnosticModelMapperTest`, `PravahAndroidHybridTest`, `PravahAndroidDiscoveryTest`, `PravahAndroidMessagingTest`, `PravahAndroidNetworkTest`, `PravahAndroidRuntimeTest`.

---

## 6. Semantic Discipline (Per §21 of Brief)

The brief was emphatic that terminology must match implementation. For the record:

> The A.D2.5 `DROP → BLUETOOTH` operation performs **diagnostic path suppression / deactivation** on the internal Pravaah path state via `ConnectivityPath.deactivate()`. It does **not** physically close the underlying Bluetooth RFCOMM socket.

This is architecturally identical to the pre-existing TCP drop semantics, which were classified as diagnostic (not socket-destructive) in the A.D2.4-BT-PV report. The event stream reflects this precisely:
```
PATH: <path-id> (bluetooth) ACTIVE->INACTIVE
```
It does not say "socket closed," "disconnected," or "destroyed," because none of those occurred.

---

## 7. Definition of Done — Compliance

| Category | Requirement | Status |
|---|---|---|
| UI | `DROP TCP` replaced by `DROP` | ✅ |
| UI | TCP, Bluetooth, Cancel options | ✅ |
| UI | Cockpit style preserved | ✅ |
| TCP | Existing drop behavior preserved (via wrapper) | ✅ |
| TCP | Route changes per existing policy | ✅ |
| Bluetooth | Deliberately targetable | ✅ |
| Bluetooth | Path state changes correctly | ✅ |
| Bluetooth | Route changes per existing policy | ✅ |
| Bluetooth | Lifecycle observable (BT-07/08/12 permanent) | ✅ |
| Hybrid | TCP + BT coexistence intact | ✅ |
| Safety | No Core / routing / protocol / security changes | ✅ |
| Safety | No iQOO insecure fallback | ✅ |
| Safety | No fake state or duplicated routing logic | ✅ |
| Quality | Android unit tests green | ✅ |
| Quality | Core Maven tests green (414/414) | ✅ |
| Quality | Documentation complete | ✅ |
| Quality | No `ARCHITECTURE-NOTE.md` required | ✅ (no gap discovered) |

---

## 8. Recommendations for the Next Sprint

1. **Promote proven BT-07/08/12 checkpoints to a dedicated structured-telemetry channel.** They are currently emitted via `java.util.logging.Logger` with a `[BT-FORENSIC]` prefix. For field diagnostics, a structured event bus would be more consumable than raw logcat grepping.
2. **Consider a `DROP → BOTH` variant for future chaos-testing** — useful for verifying full-disconnection recovery paths. Out of scope for A.D2.5, but a natural next experiment.
3. **The iQOO insecure RFCOMM fallback remains open.** As §19 of the A.D2.5 brief instructed, we did not touch it. It should be its own small sprint with its own security review.

---

## 9. Sign-Off

Sprint A.D2.5 is ready for review and tagging as `vA.D2.5`. All artifacts are committed-ready, all tests are green, all protected boundaries intact. The cockpit now behaves as described in §29 of the brief — a small, honest transport-experimentation console that lets us deliberately perturb the network and observe how the existing architecture responds.

Awaiting your review before pushing the tag.

— Junior Dev, Track A