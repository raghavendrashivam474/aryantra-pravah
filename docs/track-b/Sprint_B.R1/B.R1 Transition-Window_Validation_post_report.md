# Post-Sprint Report: B.R1 Transition-Window Validation

**To:** Senior Engineering Lead, Track B — Core Evolution
**From:** [Junior Engineer], Pravaah Core Team
**Date:** 2026-10-04
**Sprint:** B.R1 Transition-Window Validation (Runtime Validation Sprint)
**Baseline:** `vB.R1` (commit `3481d6d`)
**Sprint Classification:** Evidence Gathering — Non-Invasive Observation
**Status:** ✅ Complete — Recommendation Ready for Review

---

## 1. Executive Summary

This sprint was explicitly scoped as a **validation sprint, not a feature sprint**. Per your directive, the objective was not to fix or extend failover behavior, but to **measure, classify, and document** the exact fate of application messages dispatched during the narrow interval between primary-path failure (TCP) and alternate-path activation (Bluetooth).

### Headline Finding

> **Messages dispatched during the active-to-candidate transition window experience 100% deterministic loss.**
> The `PeerRouter` throws `PeerRoutingException` synchronously and drops the payload. There is no router-level buffering, no retry waiting, and no path-activation-triggered flush mechanism.

### Decision

> **B.R2 REQUIREMENT IDENTIFIED.** The evidence conclusively establishes the need for bounded in-flight buffering as the next sprint objective.

### Discipline Record

* ✅ Zero production code modifications
* ✅ Zero existing test modifications
* ✅ Zero regressions introduced (403/403 project tests passing)
* ✅ No speculative B.R2 implementation attempted
* ✅ All findings backed by reproducible deterministic experiments

---

## 2. Sprint Mandate Compliance

Per the sprint brief, the following constraints were observed:

| Prohibition | Compliance |
|---|---|
| No message queues | ✅ None implemented |
| No retry buffers | ✅ None implemented |
| No acknowledgement protocol changes | ✅ Unchanged |
| No persistence layer additions | ✅ Unchanged |
| No resend logic | ✅ Unchanged |
| No transport changes | ✅ Unchanged |
| No router redesign | ✅ `PeerRouter` untouched |
| No new delivery semantics | ✅ Existing contract preserved |
| No B.R2 implementation | ✅ Observations only, no fixes |

The sprint flow followed the prescribed path:

```
B.R1 current implementation
    ↓
transition-window experiment
    ↓
observed behavior
    ↓
delivery semantics
    ↓
requirement
    ↓
B.R2 only if required  ← we reached here with evidence
```

---

## 3. What Was Implemented

### 3.1 Deliverables Produced

Three artifacts, all additive, all non-invasive to production code:

| File | Type | Purpose |
|---|---|---|
| `docs/track-b/BR1-TRANSITION-WINDOW-INVENTORY.md` | Documentation | Pre-experiment system inventory answering plan questions A–H |
| `src/test/java/com/aryntra/pravah/connectivity/TransitionWindowValidationTest.java` | Test Harness | 5-test controlled experiment with scripted transport |
| `docs/track-b/BR1-TRANSITION-WINDOW-RESULTS.md` | Documentation | Final empirical results, outcome table, B.R2 recommendation |

### 3.2 Controlled Experiment Design

A JUnit 5 nested test class, `TransitionWindowValidationTest`, was constructed around a `ScriptedTransport` helper that permits millisecond-precise, deterministic failure injection on individual `connectionId` handles.

Four distinct experimental phases were implemented:

| Phase | Setup | Hypothesis Being Tested |
|---|---|---|
| **A — Baseline** | TCP + BT both `ACTIVE`, no failure | Control: happy path delivers all messages |
| **B — Failover without gap** | TCP fails, BT already `ACTIVE` | Known B.R1 behavior: lossless intra-call failover |
| **C — Transition window** | TCP fails, BT in `CANDIDATE` state | **Core unknown:** what happens to messages in the gap? |
| **D — Duplicates/ordering** | Failover with active BT | Verify no synthetic duplicates or reordering introduced |

### 3.3 Test Harness Architecture

The `ScriptedTransport` class was designed to be the smallest possible test seam:

* Implements the existing `Transport` interface with no new abstractions.
* Exposes `markFailing(connectionId)` and `markHealthy(connectionId)` for runtime state manipulation.
* Maintains two independent logs: `sendLog` (every attempted write) and `deliveryLog` (every successful write).
* Does not use `Thread.sleep()` — timing is modeled through explicit sequential operations.

This design preserved the following principles from the sprint brief:
* **Reuse before invention** — the harness is a `Transport` implementation, not a new abstraction.
* **Determinism over timing** — no real sockets, no sleeps, no probabilistic behavior.
* **Observability without production intrusion** — all logging is harness-local.

---

## 4. How the Investigation Was Conducted

### 4.1 Methodology Chronology

The sprint was executed in six discrete blocks, each gated by review and verification:

#### Block 1 — Reconnaissance
- Confirmed clean git state at `vB.R1` tag.
- Located all four critical files from plan §5 (`PeerRouter`, `PeerConnectionCoordinator`, `PathSelectionPolicy`, `CompositeTransport`).
- Enumerated existing failover tests to avoid duplication (`RuntimePathFailoverTest`, `RouterFailoverTest`).
- Discovered the existing reliability layer: `DeliveryOutbox`, `DeliveryRetryManager`, `ConversationManager`.

#### Block 2 — Baseline Verification & Code Inspection
- Executed `mvn test -Dtest=RuntimePathFailoverTest,RouterFailoverTest,PathSelectionTest,PathSelectionIntegrationTest` → **13/13 PASS**.
- Inspected `PeerRouter.send()` and discovered the synchronous `while(!availablePaths.isEmpty())` failover loop.
- Inspected `PeerConnectionCoordinator.onConnectionClosed()` and verified it preserves alternate paths.
- Generated the Transition Window Inventory document.

#### Block 3 — Test Infrastructure Discovery
- Studied `RuntimePathFailoverTest` and `RouterFailoverTest` to understand existing fake-transport patterns.
- Inspected `ConnectivityPath`, `PeerConnectivity`, and the `PathState` enum (`ACTIVE`, `CANDIDATE`, `INACTIVE`).
- Confirmed no reusable `FakeTransport` or `MockTransport` class existed — a new local test helper was justified.

#### Block 4 — Harness Implementation
- Authored `TransitionWindowValidationTest.java` with 5 tests across 4 nested phases.
- Compiled successfully after resolving a file-encoding issue (see §5.1).

#### Block 5 — Experiment Execution
- Ran `mvn test -Dtest=TransitionWindowValidationTest` → **5/5 PASS**.
- Each assertion encoded a specific hypothesis about transition-window behavior.

#### Block 6 — Full Regression & Reporting
- Ran complete `mvn test` → **403/403 PASS, 0 failures, 0 errors**.
- Generated the final results document in seven incremental patches for reviewability.

### 4.2 Classification Taxonomy Applied

Every message in the experiment was classified into one of six canonical outcomes:

| Outcome | Meaning |
|---|---|
| `DELIVERED_ONCE` | Routed and received exactly once |
| `DELIVERED_AFTER_RETRY` | Initial path failed, alternate path succeeded within same `send()` call |
| `LOST` | `send()` threw `PeerRoutingException`; no delivery occurred |
| `DUPLICATED` | Received more than once (not observed) |
| `REORDERED` | Arrived out of send order (not observed) |
| `UNKNOWN` | Cannot be conclusively determined (not needed) |

---

## 5. Problems Encountered and Mitigations

### 5.1 Problem: UTF-8 BOM Caused Java Compilation Failure

**Issue:**
When the test file was first written using PowerShell's `Set-Content -Encoding UTF8` cmdlet, Maven compilation failed with:

```
illegal character: '\ufeff'
```

PowerShell 5.1's default UTF-8 encoding prepends a Byte Order Mark (BOM), which Java's `javac` rejects as an invalid first character.

**Mitigation:**
Switched to the .NET API `[System.IO.File]::WriteAllText(path, content, [System.Text.UTF8Encoding]::new($false))`, which writes UTF-8 without a BOM. This resolved compilation immediately and became the standard pattern for all subsequent file writes in the sprint.

**Lesson:**
PowerShell's text-encoding defaults are not Java-safe. Any future test-file generation via PowerShell should explicitly use BOM-less UTF-8.

---

### 5.2 Problem: Maven `-Dtest` Argument Parsing in PowerShell

**Issue:**
The command `mvn test -Dtest=TestA,TestB,TestC` failed with:

```
Missing argument in parameter list.
```

PowerShell's parser interpreted the comma-separated list as multiple parameter arguments rather than a single property value.

**Mitigation:**
Quoted the entire `-D` argument as a single string: `mvn test "-Dtest=TestA,TestB,TestC"`. This bypassed PowerShell's parameter tokenization and passed the complete argument to Maven unchanged.

---

### 5.3 Challenge: Distinguishing "Send Returned Successfully" from "Message Delivered"

**Issue:**
Per the sprint brief §7, we had to be rigorous about not conflating these events:

```
send() returned successfully
   ≠ remote peer received message
   ≠ remote peer processed message
   ≠ remote peer ACKed message
```

**Mitigation:**
The `ScriptedTransport` was instrumented with two separate logs:
* `sendLog` — records *every attempted* write regardless of outcome.
* `deliveryLog` — records *only successful* writes (where no exception was thrown).

This separation allowed each test assertion to be precise about which observable it was measuring, and ensured we never claimed "delivered" when we only had evidence of "send attempted."

For this validation sprint, "transport socket accepted bytes" was treated as the strongest existing in-process delivery signal, consistent with the existing `PeerRouter` contract. End-to-end ACK validation via `DeliveryOutbox` was deliberately out of scope.

---

### 5.4 Challenge: Resisting the Urge to "Fix" During Observation

**Issue:**
Upon discovering during Block 2 that `PeerRouter.send()` falls through to `PeerRoutingException` when no active paths remain, the natural engineering instinct was to immediately propose a buffer or retry wait.

**Mitigation:**
Per sprint brief §22 (*"Absolutely do not implement B.R2 during this sprint"*), the finding was recorded as observable evidence only. The results document explicitly separates the **observation** (100% loss during gap) from the **recommendation** (B.R2 bounded buffering), preserving the engineering decision chain:

```
observation → delivery contract → requirement → B.R2 decision
```

No prototype buffer, no exploratory queue, no "while we're here" code additions were introduced.

---

### 5.5 Challenge: Documenting a Large Report Without a Single Monolithic Write

**Issue:**
The final results document is substantial (~200 lines of structured Markdown). A single-shot PowerShell write would have been difficult to review, modify, or roll back section-by-section.

**Mitigation:**
Switched to a seven-patch incremental authoring approach: each section (header, delivery contract, test method, observations, outcome table, findings, recommendations) was appended via a separate `[System.IO.File]::AppendAllText` call. This enabled per-section review, trivial rollback (truncate the file), and clean mental checkpoints during authoring.

---

## 6. Key Technical Findings

### 6.1 PeerRouter.send() Behavioral Model (as discovered)

```java
public void send(PeerId destination, Message message) {
    // ...
    List<ConnectivityPath> availablePaths = new ArrayList<>(peerConn.activePaths());
    while (!availablePaths.isEmpty()) {
        Optional<ConnectivityPath> selected = selectionPolicy.selectPath(...);
        if (selected.isEmpty()) break;

        ConnectivityPath path = selected.get();
        try {
            transport.send(path.connectionId(), framed);
            return; // SUCCESS: exit immediately
        } catch (Exception ex) {
            peerConn.addPath(path.deactivate()); // mark dead
            availablePaths.remove(path);          // try next
        }
    }
    // falls through → PeerRoutingException thrown
}
```

**Critical insight:** The `activePaths()` method returns only paths where `state == ACTIVE && connectionId != null`. A `CANDIDATE` path is **invisible** to the router. Therefore, if TCP fails and BT is still negotiating its connection, the `while` loop exits with an empty list, and the message is lost immediately.

### 6.2 Transition Window Lifecycle

```
  T0                    T2                          T4
  TCP=ACTIVE            TCP fails                   BT=ACTIVE
  BT=CANDIDATE          TCP=INACTIVE                (handshake complete)
  │                     │                           │
  │─ Bucket 1 ──────────│─────── Bucket 2 ──────────│── Bucket 3 ──►
    Pre-failure           TRANSITION GAP              Post-transition
    (lossless)            (100% LOSS)                 (lossless)
```

### 6.3 Empirical Outcome Table

| Msg | Phase | Bucket | Path Attempted | Final Outcome |
|:---:|:-----:|:------:|:-------------:|:--------------|
| M001–M003 | A | Control | TCP | `DELIVERED_ONCE` |
| M004 | B | Post-failover | TCP→BT | `DELIVERED_AFTER_RETRY` |
| M005 | B | Post-failover | BT | `DELIVERED_ONCE` |
| M006 | C | Pre-fail | TCP | `DELIVERED_ONCE` |
| **M007** | **C** | **Gap** | **TCP(fail)→None** | **`LOST`** |
| **M008** | **C** | **Gap** | **None available** | **`LOST`** |
| **M009** | **C** | **Gap** | **None available** | **`LOST`** |
| M010 | C | Post-BT | BT | `DELIVERED_ONCE` |

### 6.4 Reproducibility Verification

Three independent transition cycles with 5 messages each were executed back-to-back:
* **Total gap messages sent:** 15
* **Total gap messages lost:** 15
* **Loss rate:** 100.0% (fully deterministic, not timing-dependent)

---

## 7. Delivery Semantics Summary

| Scenario | Current B.R1 Semantics | Observed Loss |
|---|---|:---:|
| Active-to-Active failover | At-least-once (lossless) | 0% |
| **Active-to-Candidate transition gap** | **Fail-fast, no buffering** | **100%** |
| Post-transition (new path active) | At-least-once (lossless) | 0% |
| Duplicates during failover | None generated | 0% |

---

## 8. Recommendation: B.R2 Requirement

### 8.1 Requirement Statement

Messages accepted by the application during a path transition must be held in a **bounded in-flight buffer** and flushed when an alternate path reaches `ACTIVE` state, rather than being immediately rejected with `PeerRoutingException`.

### 8.2 Justification

Real-world ad-hoc mesh scenarios cannot guarantee that all backup paths remain continuously `ACTIVE` due to radio power constraints and connection lifecycle costs. Backup paths are frequently in `CANDIDATE` or `CONNECTING` states. The empirically measured 500ms–2500ms Bluetooth handshake window (per the B.R1 physical validation record) represents a reliability gap that upper-layer `DeliveryOutbox` retry timers may or may not cover depending on their tick interval.

### 8.3 Proposed B.R2 Scope (for your approval)

1. Define buffer sizing and eviction policies (TTL, max message count).
2. Wire `PeerConnectionCoordinator` path-activation events as flush triggers.
3. Preserve fail-fast semantics only when all candidate paths fail *or* buffer TTL expires.
4. Maintain the existing `PeerRouter` synchronous contract for active-to-active failover (no behavioral change on the happy path).

### 8.4 Alternative Considered (and Rejected)

**Alternative:** Rely on upper-layer `DeliveryRetryManager` to cover the gap.

**Rejection reason:**
* `DeliveryRetryManager` retries on fixed timer ticks (not on path state changes).
* Direct `PeerRouter.send()` callers (e.g., control plane messages, discovery packets) are not outbox-backed and would remain uncovered.
* Pushing the responsibility upward would violate the single-responsibility of the router as the authoritative dispatch point.

---

## 9. Regression & Discipline Record

| Metric | Value |
|---|---|
| New validation tests | 5/5 PASS |
| Pre-existing B.R1 tests | 13/13 PASS (unchanged) |
| **Full project suite** | **403/403 PASS, 0 failures, 0 errors** |
| Production code lines modified | **0** |
| Existing tests modified | **0** |
| New production files | **0** |
| New test files | 1 (`TransitionWindowValidationTest.java`) |
| New documentation files | 2 (inventory + results) |

---

## 10. Request to Senior

I am seeking your review on the following:

1. **Confirmation of the B.R2 requirement determination** — do the empirical results satisfy the evidence threshold you expect for sprint promotion?
2. **Approval to proceed with B.R2 scoping** — specifically the bounded in-flight buffering approach outlined in §8.3.
3. **Guidance on physical two-device validation** — the sprint brief §18 calls for Android A ↔ Android B physical validation of the same scenario. Given the deterministic in-process evidence already gathered, do you want this executed as a confirmation step before B.R2 begins, or deferred as a post-B.R2 integration validation?
4. **Review of the engineering discipline record** — I want to confirm that the non-invasive observation approach met your expectations for this type of validation sprint, so I can apply the same pattern to future sprints.

---

## 11. Attachments

1. `docs/track-b/BR1-TRANSITION-WINDOW-INVENTORY.md` — Pre-experiment system inventory
2. `src/test/java/com/aryntra/pravah/connectivity/TransitionWindowValidationTest.java` — Validation test harness
3. `docs/track-b/BR1-TRANSITION-WINDOW-RESULTS.md` — Full results document with outcome table

---

**Signed,**
[Junior Engineer]
Pravaah Core Team — Track B

**Awaiting senior review before any further Track B work commences.**