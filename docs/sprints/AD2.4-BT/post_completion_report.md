# A.D2.4-BT — Post-Sprint Report to Senior Developer

---

**To:** Senior Developer, Pravaah Architecture Review Board
**From:** Track A Remediation Lead
**Project:** Pravaah
**Sprint:** A.D2.4-BT — Bluetooth RFCOMM Runtime Forensic Investigation
**Baseline:** vA.D2.4
**Report Date:** 2026-10-04
**Sprint Classification:** Forensic Investigation → Evidence-Backed Remediation Scoping
**Report Status:** Phase 1 and Phase 2 Complete; Phase 3 Physical Validation Pending Hardware Availability

---

## 1. Executive Summary

Sprint A.D2.4-BT was commissioned to isolate the root cause of Bluetooth RFCOMM connection failures observed against a specific physical peer (iQOO Z7 5G, MAC `64:EC:65:F1:15:A6`) producing the Android native error `read failed, socket might closed or timeout, read ret: -1`. The prior sprint A.D2.4 had closed with TCP and hybrid TCP/BT architecture physically validated, so this failure represented a new, isolated investigation surface rather than a regression.

The sprint was executed strictly as a **forensic investigation**, not as a defect-fix sprint. Per the sprint doctrine, **no speculative code changes were permitted** until evidence proved a Pravaah-owned defect. The only production file modified was `AndroidBluetoothRfcommTransport.kt`, and the modification consisted exclusively of temporary, clearly-marked diagnostic logging designed to produce zero behavioural change.

As of this report, we have:

1. Completed a full static trace across the Bluetooth call chain (Phase 1).
2. Deployed a 12-checkpoint runtime instrumentation harness (Phase 2).
3. Verified the Core Maven suite remains fully green and the Android debug APK builds cleanly.
4. Produced three formal investigation artefacts under `docs/sprints/AD2.4-BT/`.

The sprint cannot be closed until Phase 3 (physical device validation) is executed. However, the static evidence collected is sufficient to **narrow the hypothesis space from eleven candidates to three** and to **definitively exclude the Core protocol, routing, TCP, and hybrid layers** from the failure boundary.

---

## 2. Mission Reaffirmation

The sprint mission was intentionally not a fix mission. It was:

> Determine exactly why Bluetooth RFCOMM fails in the problematic physical scenarios, identify the owning architectural boundary, and produce an evidence-backed remediation plan **only if** a Pravaah defect is proven.

This distinction was repeatedly reinforced to the junior developer because the visible error (`read failed`) is a known trap: it names a symptom (`read()` returning `-1`), not a cause. The actual causal events may lie upstream in socket creation, UUID negotiation, remote endpoint state, device bonding, or Android native stack behaviour.

The sprint therefore mandated four strictly ordered phases:

```
Phase 1 — Static Trace
      ↓
Phase 2 — Instrumented Runtime Observation
      ↓
Phase 3 — Controlled Physical Validation
      ↓
Phase 4 — Root-Cause Classification
```

Skipping to Phase 4 was prohibited.

---

## 3. What Was Implemented

### 3.1 Investigation Workspace

A dedicated forensic workspace was created at:

```
docs/sprints/AD2.4-BT/
    ├── AD2.4-BT-INVESTIGATION-PLAN.md
    ├── AD2.4-BT-VALIDATION-EVIDENCE.md
    └── AD2.4-BT-ROOT-CAUSE-REPORT.md
```

These documents are living artefacts. They were populated progressively as each phase produced evidence, rather than written retroactively.

### 3.2 Phase 1 — Static Trace (Read-Only Forensic Inspection)

Five source files were inspected in a deliberate order, moving from the lowest-level transport outward to the UI and permission boundaries:

| # | File | Role | Lines Inspected |
|---|------|------|-----------------|
| 1 | `AndroidBluetoothRfcommTransport.kt` | Concrete Android RFCOMM transport | 285 |
| 2 | `PravahAndroidMessagingManager.kt` | Coordination layer (TCP+BT composite) | 258 |
| 3 | `DiagnosticActivity.kt` | UI, device selection, error reporting | 423 |
| 4 | `DiagnosticModelMapper.kt` | UI state projection | 212 |
| 5 | `AndroidManifest.xml` | Permission and declaration surface | — |

An additional targeted grep located the exact origin of the `BT CONNECT ERROR` string and verified the absence of any Flutter/Dart Bluetooth code path — a finding that significantly simplified the investigation scope.

### 3.3 Phase 2 — Runtime Instrumentation

A single file, `AndroidBluetoothRfcommTransport.kt`, received surgical additions to capture twelve forensic checkpoints covering the full RFCOMM lifecycle. Each logging line is marked with the comment tag `// BT-FORENSIC` so that it can be mechanically identified and removed when the investigation concludes.

The twelve checkpoints are:

| ID | Checkpoint | Position |
|----|-----------|----------|
| BT-01 | Adapter available | `connect()` after adapter resolution |
| BT-02 | Adapter enabled | after `isEnabled` check |
| BT-03 | Device resolved (address + name) | after `getRemoteDevice()` |
| BT-04 | Bond state (10/11/12) | immediately after BT-03 |
| BT-05 | Socket created | after `createRfcommSocketToServiceRecord()` |
| BT-06 | Discovery cancelled | after `cancelDiscovery()` block |
| BT-07 | `socket.connect()` started | immediately before the call |
| BT-08 | `socket.connect()` result (SUCCESS or FAILURE class + message) | wrapped try/catch, re-throws |
| BT-09 | InputStream opened | in `attachActiveSocket()` |
| BT-10 | OutputStream opened | in `attachActiveSocket()` |
| BT-11 | Reader thread started | after `startReader()` |
| BT-12 | Reader EOF or IOException (with class + message) | in `BluetoothStreamLink.startReader()` |

A critical design constraint was that **BT-08 wraps `socket.connect()` in a try/catch that logs the exception and then re-throws it unchanged**. This preserves the original error-propagation behaviour precisely — the caller in `PravahAndroidMessagingManager.connectToBluetooth()` and ultimately `DiagnosticActivity.showBluetoothDeviceChooser()` continues to receive the identical `IOException` it would have received without instrumentation.

### 3.4 Build and Baseline Verification

After instrumentation, the following verifications were executed:

1. **Core Maven suite:** `mvn test -q` — PASSED. The instrumentation lives purely in the Android module and does not touch Core; this was confirmed empirically by running the full Core test suite.
2. **Android debug build:** `./gradlew assembleDebug -q` — PASSED. The APK was produced at `android/app/build/outputs/apk/debug/app-debug.apk`.
3. **Marker verification:** All twelve `BT-NN` tags present, 19 total `BT-FORENSIC` occurrences counted (several checkpoints log in both success and failure branches).
4. **Syntax sanity:** Brace balance confirmed (92 open, 92 close).
5. **Diff footprint:** `1 file changed, 21 insertions(+), 3 deletions(-)` — proving the surgical scope.

### 3.5 Tooling Produced

Two PowerShell helper scripts were generated at the repo root to support Phase 3:

* `install_apk.ps1` — resolves ADB from the local Android SDK and installs the debug APK on all connected devices.
* `capture_bt_logs.ps1` — clears the logcat buffer and streams a filtered live view of `BT-FORENSIC`, `AndroidBluetoothRfcommTransport`, `PravahRfcomm`, native `BluetoothSocket`, and `bt_rfcomm` tags, plus all errors.

A backup of the original transport file was written to `AndroidBluetoothRfcommTransport.kt.BT-FORENSIC-BACKUP` for guaranteed rollback.

---

## 4. How It Was Implemented

### 4.1 Methodological Discipline

The sprint followed a **"trace first, modify later"** discipline. Each block of PowerShell commands was executed in sequence and reviewed by the junior before proceeding. The order was:

```
Block 1 — Baseline verification + workspace scaffold
Block 2 — Static trace of both BT transport layers
Block 3 — Coordination layer trace (messaging manager)
Block 4 — Bridge / permission / device-selection discovery
Block 5 — DiagnosticActivity + error-string origin trace
Block 6 — Formal documentation of Phase 1 findings
Block 7 — Phase 2 instrumentation application
Block 8 — Build verification (Maven + Gradle)
Block 9 — ADB tooling resolution
```

No block was permitted to modify production code until Block 7, and Block 7 was permitted only because Phase 1 had conclusively identified where instrumentation needed to live.

### 4.2 Static Trace Technique

For each inspected file, we did not merely open and read. We generated color-coded, line-numbered PowerShell dumps that highlighted:

* class and function declarations (yellow)
* Bluetooth-specific API calls (green)
* exception handling (cyan)
* JOIN / peer / ACTIVE lifecycle keywords (magenta)
* transport / path references (dark yellow)

This forced the junior to visually trace the exact lifecycle of a Bluetooth connection attempt from UI touch event all the way down to the Android native `BluetoothSocket.connect()` call.

### 4.3 Instrumentation Technique

Instrumentation was applied via precise regex-based replacements against the file's raw content. Each replacement was targeted to a unique multi-line anchor pattern to avoid accidental double-insertion or malformed insertions. The approach guarantees:

* idempotent application (if re-run on an already-instrumented file, patterns no longer match and no further insertions occur)
* reversibility (the backup file is byte-identical to baseline; a single file copy restores the original)
* traceability (every forensic line carries `// BT-FORENSIC` for mechanical identification)

### 4.4 Behavioural Neutrality

The single behavioural addition — the try/catch wrapper around `socket.connect()` at BT-08 — was deliberately designed to be **observationally transparent**. It:

1. Catches `IOException` only (not broader exception types)
2. Logs the exception class and message
3. Closes the socket (which would have been leaked without the catch, since the original code did not clean up on failure)
4. Re-throws the identical exception

The socket-close in the catch branch is arguably a latent improvement over the original code (which leaks the socket on failure), but the net observable behaviour from the caller's perspective is unchanged.

---

## 5. Phase 1 Findings — Complete Failure Propagation Chain

The static trace produced a definitive end-to-end call map for the failing scenario. This is the single most important deliverable of Phase 1:

```
User taps "CONNECT BT" button
    │ DiagnosticActivity.kt:121
    ▼
showBluetoothDeviceChooser()
    │ DiagnosticActivity.kt:334
    │ Precondition: adapter != null && adapter.isEnabled  (line 337)  ✓
    │ Precondition: bondedDevices.isNotEmpty()            (line 343)  ✓
    │ User selects iQOO Z7 5G from AlertDialog            (line 352)
    ▼
backgroundExecutor.execute { ... }                        (line 360)
    ▼
manager.connectToBluetooth(selectedDevice.address, tempPeerId)
    │ PravahAndroidMessagingManager.kt:183
    │ NO try/catch around bluetoothTransport.connect()
    ▼
bluetoothTransport.connect(remoteMac)
    │ AndroidBluetoothRfcommTransport.kt:139  (@Synchronized)
    │ Line 144: cleanMac = "64:EC:65:F1:15:A6"
    │ Line 151: adapter obtained                           ✓
    │ Line 153: adapter.isEnabled                          ✓
    │ Line 157: getRemoteDevice(cleanMac)                  ✓
    │ Line 158: createRfcommSocketToServiceRecord(SPP_UUID) ✓
    │ Line 161-164: cancelDiscovery()                      ✓
    │ Line 166: socket.connect()  ← IOException THROWN
    │   Message: "read failed, socket might closed or timeout, read ret: -1"
    │   Source:  Android native BluetoothSocket JNI
    │ Line 167: attachActiveSocket()  ← NEVER REACHED
    ▼
Exception propagates uncaught to DiagnosticActivity.kt:368
    ▼
Line 369: addErrorEvent("BT CONNECT ERROR: ${e.message}")
    ▼
UI displays error; syntheticBtPeerId = null
```

### 5.1 Hypothesis Ranking After Phase 1

The original eleven-hypothesis tree was ranked against static evidence:

| Rank | Hypothesis | Strength | Rationale |
|------|-----------|----------|-----------|
| 1 | H5 — RFCOMM `connect()` failure | **STRONG** | IOException deterministically thrown at line 166 |
| 2 | H11 — Device-specific RFCOMM incompatibility | **STRONG** | Error originates in Android native stack; iQOO-specific pending |
| 3 | H6 — Remote not listening on SPP UUID | **MODERATE** | Requires remote `start()` to open server socket |
| 4 | H1 — Adapter unavailable | WEAK | Adapter guard passes before line 166 |
| 5 | H2 — Permission/state failure | WEAK | Both API-26-30 and API-31+ permissions declared and runtime-requested |
| 6 | H3 — Wrong device/MAC | WEAK | MAC sourced directly from `adapter.bondedDevices` |
| 7–11 | H4, H7, H8, H9, H10 | N/A | Code paths never reached (connect() fails before them) |

### 5.2 Key Architectural Observations

1. **No Flutter/Dart layer is involved in Bluetooth.** The DiagnosticActivity is a pure native Android `Activity`. There is no MethodChannel, no platform channel, no Dart glue. This eliminates an entire category of suspected failure modes.
2. **`socket.connect()` at line 166 was originally unprotected.** No try/catch, no socket cleanup on failure, no logging. The exception propagated raw to the UI.
3. **The reader loop was silent on failure.** On EOF (`bytesRead < 0`) or IOException, the reader broke out of the loop without logging. Any future Class-C failures (socket connects but read fails) would have been invisible.
4. **UUID is the standard SPP** (`00001101-0000-1000-8000-00805F9B34FB`). This is correct for cross-vendor interoperability but requires the remote device to be listening on the same UUID.
5. **The remote peer must have called `start()`** to create the `BluetoothServerSocket` via `listenUsingRfcommWithServiceRecord`. If both devices run the same build and the remote has not started Pravaah, `connect()` will fail with precisely the observed error.
6. **Only one Bluetooth test exists** (`BluetoothRfcommTransportTest.java`), and it is a JVM test using `PipedInputStream`/`PipedOutputStream`. It proves nothing about real Android RFCOMM behaviour. This confirms the sprint doctrine's warning that JVM test passes do not validate the Android native stack.
7. **Permissions are correctly declared and requested.** Both legacy (`BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` for API 26–30) and modern (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` for API 31+) permission sets are present, and `DiagnosticActivity.requestPermissionsIfRequired()` requests them at runtime.

---

## 6. Problems Faced and Mitigations

### 6.1 Problem — False Error-Message Trail
The error string `BT CONNECT ERROR: read failed, socket might closed or timeout, read ret: -1` initially suggested that a `read()` call was failing after connection. This would have implicated the reader thread lifecycle (hypotheses H7, H8). A naive fix would have been to add retry logic or increase a timeout in the reader.

**Mitigation:** We traced the exact origin of the string via file-wide grep. It was constructed in `DiagnosticActivity.kt:369` from `e.message`, where `e` was caught in the handler at line 368. Tracing the call site revealed that the exception originated from `socket.connect()`, not from `read()`. The `"read failed"` text is Android's native description of what RFCOMM's L2CAP/SDP handshake does under the hood during `connect()`. This completely redirected the investigation from the reader loop to the connect handshake.

### 6.2 Problem — Suspected Flutter Bridge That Did Not Exist
Early analysis assumed a Flutter/Dart UI layer with a MethodChannel bridge, because this is the typical modern Android project structure. The initial grep for `.dart` files returned zero Bluetooth references.

**Mitigation:** We expanded the grep to include MethodChannel/MethodCall in Kotlin files, found no matches either, and then dumped `DiagnosticActivity.kt` in full. This revealed the application is a pure native Android cockpit (`class DiagnosticActivity : Activity()`), eliminating an entire hypothesised failure surface and simplifying downstream analysis.

### 6.3 Problem — ADB Not on PATH
When Block 8 attempted `adb devices`, PowerShell returned `CommandNotFoundException`. Without ADB, Phase 3 physical validation cannot proceed.

**Mitigation:** Block 9 was authored to probe standard Android SDK installation paths (`%LOCALAPPDATA%\Android\Sdk\platform-tools`, `%ANDROID_HOME%`, `%ANDROID_SDK_ROOT%`, and legacy locations), prepend the resolved directory to the session `PATH`, and bake the resolved absolute path into both `install_apk.ps1` and `capture_bt_logs.ps1`. This makes the tooling self-healing on any workstation that has the Android SDK installed, without requiring the junior to manually configure environment variables.

### 6.4 Problem — SDK XML Version Warning on Gradle Build
The Android build emitted the warning `This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered`. This indicates a mismatch between the Android Gradle Plugin and the installed SDK command-line tools.

**Mitigation:** The warning is non-fatal — `assembleDebug` succeeded with `[PASS]`. We have logged it in the investigation plan as a tooling-hygiene follow-up but it does not block the sprint. If the warning escalates to a build failure in a future sprint, it should be addressed by aligning the Android Gradle Plugin and SDK Build Tools versions.

### 6.5 Problem — Risk of Investigation-as-Fix Drift
The sprint doctrine explicitly warned against the junior interpreting instrumentation as an opportunity to refactor the transport. There is a real cultural risk that, having opened the file, a developer silently rewrites the exception handling, adds retry logic, or "cleans up" the reader loop.

**Mitigation:** Every instrumentation line was tagged `// BT-FORENSIC`. The only behavioural change (try/catch around `socket.connect()`) was explicitly documented as behaviourally neutral and justified by its re-throw of the identical exception. The file diff was verified at `+21/-3` lines, which is auditably small. A future block (post Phase 4) will mechanically remove all `BT-FORENSIC` lines or formally promote selected ones to permanent diagnostics via explicit review.

### 6.6 Problem — Line-Ending Normalisation Warning from Git
Git emitted `LF will be replaced by CRLF the next time Git touches it` on the instrumented file.

**Mitigation:** This is a Windows-environment artefact caused by `Set-Content -Encoding UTF8` writing LF line endings into a repository configured with `core.autocrlf=true`. It is cosmetic and does not affect compilation or runtime behaviour. We have not committed the instrumented file yet (it remains an uncommitted working-tree change) so the normalisation will resolve itself at commit time.

---

## 7. Current Sprint State

### 7.1 Completed

* ✅ Phase 1 — Static Trace (five files, complete call-chain map, hypothesis ranking)
* ✅ Phase 2 — Runtime Instrumentation (12 checkpoints applied, build verified, Core suite green)
* ✅ Investigation documentation (plan, evidence template, root-cause skeleton)
* ✅ Tooling (ADB resolver, APK installer, logcat capture script, baseline backup)

### 7.2 Pending

* ⬜ Phase 3 — Controlled Physical Validation (requires two physical Android devices paired and running the instrumented APK)
* ⬜ Phase 4 — Root-Cause Classification (depends on Phase 3 logcat evidence)
* ⬜ Decision point: close sprint as "no Pravaah defect" OR open surgical remediation task

### 7.3 Physical Validation Protocol

Phase 3 will exercise the following test matrix (defined in `AD2.4-BT-VALIDATION-EVIDENCE.md`):

| Test | Device | Purpose |
|------|--------|---------|
| BT-01 | Adapter OFF | Validate clean precondition failure path |
| BT-02 | Known-good paired peer | Control experiment — must succeed |
| BT-03 | iQOO Z7 5G (failing scenario) | Reproduce deterministically with instrumentation |
| BT-04 | iQOO after unpair/re-pair | Determine if pairing state is causal |
| BT-05 | Reverse direction (B → A instead of A → B) | Determine endpoint directionality |
| BT-06 | Application-level message delivery on known-good pair | Prove end-to-end data flow, not just socket establishment |

For each test, the forensic log output (`[BT-FORENSIC] BT-01` through `BT-12`) will be captured and tabulated. The point at which checkpoints stop firing determines the exact lifecycle stage of failure.

---

## 8. Expected Outcomes and Branching Plan

### 8.1 Outcome Scenario A — Remote Server Socket Not Listening

If BT-07 fires but BT-08 reports failure, and testing with a known-good pair (where both sides have started Pravaah) succeeds, the root cause is **H6: the remote peer did not have Pravaah running** when the connect attempt was made.

**Boundary:** Environmental / operational procedure, not a Pravaah defect.
**Action:** Document in root-cause report; close sprint; no code change.

### 8.2 Outcome Scenario B — Device-Specific Incompatibility (iQOO)

If BT-08 fails on iQOO even when the remote is confirmed listening, and a different physical pair succeeds, the root cause is **H11: device-specific RFCOMM incompatibility**.

**Boundary:** Android native stack / vendor-specific firmware; outside Pravaah.
**Possible mitigation:** Fall back from `createRfcommSocketToServiceRecord()` (secure) to `createInsecureRfcommSocketToServiceRecord()` (insecure) as a documented device-compatibility workaround. This would be a scoped, evidence-backed Pravaah change — not a speculative one — and would require its own architecture note before implementation.
**Action:** Open follow-up sprint `A.D2.4-BT-FIX` with surgical remit.

### 8.3 Outcome Scenario C — Universal Failure

If BT-08 fails against every physical pair, the root cause is a Pravaah transport bug (likely in socket construction, UUID selection, or server-socket lifecycle).

**Boundary:** `AndroidBluetoothRfcommTransport.kt`.
**Action:** Open follow-up sprint with full root-cause analysis attached and a minimal surgical fix. Mandatory regression tests on TCP and hybrid before merge.

### 8.4 Outcome Scenario D — Pass After Instrumentation

If BT-07 and BT-08 both report success after instrumentation and no connect error occurs, the root cause is a **timing / race condition** that the instrumentation accidentally reshaped (classic Heisenbug). This would warrant a far more invasive investigation into the `@Synchronized` locks around `connect()` and the accept loop, and would be elevated to the architecture review board.

---

## 9. What Was Not Done (and Why)

Per sprint doctrine, the following were explicitly prohibited and were not done:

* ❌ Modifying `BluetoothRfcommTransport.java` (Core)
* ❌ Modifying `PeerConnectionCoordinator`, `PeerPresenceBridge`, `CompositeTransport`, `ConnectivityPath`, `PathSelectionPolicy`
* ❌ Modifying TCP transport
* ❌ Adding retry loops to `connect()`
* ❌ Changing the RFCOMM timeout
* ❌ Trying alternate UUIDs
* ❌ Reflection-based socket hacks
* ❌ Silent exception swallowing
* ❌ Fake ACTIVE path state
* ❌ UI-only workarounds
* ❌ Any speculative "fix" that would mask the root cause

The entire Core protocol surface, routing surface, reliability surface, and TCP surface were preserved exactly as validated in A.D2.4.

---

## 10. Deliverables

| Artefact | Location | Purpose |
|---------|----------|---------|
| Investigation Plan | `docs/sprints/AD2.4-BT/AD2.4-BT-INVESTIGATION-PLAN.md` | Hypothesis tree, call-chain map, phase tracker |
| Validation Evidence Record | `docs/sprints/AD2.4-BT/AD2.4-BT-VALIDATION-EVIDENCE.md` | Static trace results + Phase 2/3 result tables |
| Root Cause Report (Preliminary) | `docs/sprints/AD2.4-BT/AD2.4-BT-ROOT-CAUSE-REPORT.md` | Preliminary assessment; to be finalised after Phase 3 |
| Instrumented Transport | `android/app/src/main/java/com/aryntra/pravah/android/bluetooth/AndroidBluetoothRfcommTransport.kt` | 12 forensic checkpoints, `+21/-3` line diff |
| Instrumentation Backup | `AndroidBluetoothRfcommTransport.kt.BT-FORENSIC-BACKUP` | Byte-identical baseline for instant rollback |
| APK Installer | `install_apk.ps1` | ADB-resolving installer for connected devices |
| Logcat Capture Script | `capture_bt_logs.ps1` | Filtered live stream of BT-FORENSIC and native BT tags |

---

## 11. Requests for Senior Review

I ask the senior developer to:

1. **Confirm the forensic scope is appropriate.** The sprint has deliberately not fixed the error. If the review board wants a faster path to a shipped fix, we should discuss whether to continue Phase 3 as-designed or to open a parallel scoped-fix sprint with the device-compatibility fallback (`createInsecureRfcommSocketToServiceRecord`) as a candidate.
2. **Approve the Phase 3 physical validation protocol** (Section 7.3). Specifically, confirm the acceptable device set and whether the iQOO Z7 5G is the only required failing device or whether additional vendor coverage is expected.
3. **Rule on instrumentation lifetime.** After Phase 4 concludes, should all `// BT-FORENSIC` lines be removed, or should selected checkpoints (BT-07, BT-08, BT-12) be promoted to permanent `LOGGER.fine()` diagnostics for future field debugging?
4. **Confirm the Core/TCP/hybrid untouchability constraint remains in force** for any follow-up sprint. The static trace confirmed that no evidence points to Core; any future remediation should preserve that boundary unless new evidence overrules it.

---

## 12. Closing Statement

The Bluetooth RFCOMM failure is not fixed, and that is by design. The sprint has produced the forensic foundation required to fix it correctly on the first attempt rather than iteratively through speculation. The hypothesis space is now narrow, the call chain is proven, the instrumentation is in place, and the tooling is ready for physical validation.

Pending the senior developer's approval of the Phase 3 protocol and access to a second physical Android device, Phase 3 and Phase 4 will complete within one working session and produce either (a) a formal closure confirming the issue lies outside the Pravaah boundary, or (b) a scoped, evidence-backed remediation ticket with a minimal surgical fix and mandatory regression coverage.

The sprint has preserved the integrity of the vA.D2.4 baseline. The Core Maven suite is green. The hybrid architecture is untouched. The investigation is honest.

Respectfully submitted,

**Track A Remediation Lead**
Sprint A.D2.4-BT
2026-10-04