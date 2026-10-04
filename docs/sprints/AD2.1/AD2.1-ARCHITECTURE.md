# A.D2.1 Architecture — Diagnostic Stabilization

**Sprint:** A.D2.1 (Track A — Stabilization)
**Parent Sprint:** A.D2 — Live Network Cockpit
**Baseline:** vA.D2
**Tag:** vA.D2.1
**Sprint Type:** Micro-sprint / stabilization / physical-validation-driven

---

## 1. Mission

A.D2.1 is a forensic stabilization sprint. Its purpose is to fix and harden all confirmed issues exposed during A.D2 physical validation without disturbing established Pravaah networking behavior.

The golden rule: **Do not "fix" something just because it looks wrong. First prove what the system is doing.**

```text
Observe -> Reproduce -> Trace -> Identify owner -> Fix -> Test -> Physically verify
```

---

## 2. Architectural Layer Diagram (Unchanged from A.D2)

```text
Pravaah Core (Java, /src/) UNTOUCHED
|
v
Runtime / Manager state UNTOUCHED
|
v
PravahAndroidMessagingManager UNTOUCHED
|
v
DiagnosticModelMapper REFINED (path sorting, route semantics, identity)
|
v
DiagnosticState UNCHANGED (A.D2 model preserved)
|
v
UI Panels REFINED (dispatch route display, LiveWire simplification)
|
v
DiagnosticActivity STABILIZED (BT chooser, TCP guard, scroll)
|
v
activity_diagnostic.xml RESTRUCTURED (outer ScrollView)
```

---

## 3. Issues Addressed & Design Decisions

### 3.1 Issue A — Bluetooth Device Selection (CRITICAL)
**Problem:** `showBluetoothDeviceChooser()` auto-selected `bondedDevices.first()`, connecting to earbuds and speakers instead of Pravaah peers.
**Root Cause:** Fallback logic `bondedDevices.firstOrNull { it.name.contains("android") } ?: bondedDevices.first()` matched arbitrary devices.
**Decision:** Replaced auto-selection with an explicit `AlertDialog` requiring the user to choose from the bonded device list. The PeerId is now derived from the selected device's MAC address (`remote-bt-<last6>`) instead of a hardcoded `remote-bt-node`.
**Boundary:** Android UI only. No Core/Transport changes.

### 3.2 Issue B — Scrolling Regression (CRITICAL)
**Problem:** The diagnostic surface overflowed the viewport on small portrait screens. Only the LiveWire log was scrollable.
**Root Cause:** The root `LinearLayout` had no outer `ScrollView`. The only `ScrollView` wrapped `tvLog`.
**Decision:** Wrapped all content below the fixed header in a single outer `ScrollView` with `fillViewport="true"`. Removed the nested LiveWire `ScrollView` (nested scroll views cause Android layout conflicts). The `tvLog` is now a plain `TextView` with `minHeight="120dp"` inside the outer scroll container.
**Boundary:** Layout XML only. No Kotlin changes to panel logic.

### 3.3 Issue C — Duplicate TCP Path Records
**Problem:** Physical validation showed both `TCP ACTIVE` and `TCP INACTIVE` for the same peer.
**Root Cause:** `PeerConnectivity.addPath()` uses `Map<PathId, ConnectivityPath>`. A new connection creates a new `PathId`, so the old deactivated path persists under its old key.
**Decision:** Fixed in the UI mapper by sorting paths deterministically (ACTIVE first, then by transport name). The Core lifecycle behavior is preserved — stale paths are a Core concern for a future sprint. The UI now presents the most relevant path first.
**Boundary:** Mapper sorting only. No Core `PeerConnectivity` changes.

### 3.4 Issue D — CONNECT TCP While TCP Already Active
**Problem:** Pressing CONNECT TCP when TCP was already ACTIVE attempted a redundant socket connection that failed.
**Root Cause:** The button handler called `connectTcp()` unconditionally without checking existing path state.
**Decision:** Added an `hasActiveTcp` guard in `DiagnosticActivity` that queries `connectivityRegistry.lookup(peer).activePaths()` before initiating a connection. If TCP is already active, a SYSTEM event is logged and the connection is skipped.
**Boundary:** Activity click handler only. No Core changes.

### 3.5 Issue E — Dispatch Route Semantics
**Problem:** `[DISPATCH ROUTE]: 10.177.67.156:45221` was shown even when the TCP path was INACTIVE, creating confusion.
**Root Cause:** `PeerRouter.resolveConnectionId()` returns `null` when no active paths exist, but the mapper was displaying the raw value without semantic interpretation.
**Decision:** `PathPanel.formatDispatchRoute()` now displays `NONE (No active path)` when the resolved route is blank or "NONE". The mapper also ensures `isSelected` is only `true` when the path is both ACTIVE and matches the resolved connection ID.
**Boundary:** Panel formatting + mapper `isSelected` logic. No Core changes.

### 3.6 Issue F — Peer Identity Truncation
**Problem:** `android-1fa286bc` (16 chars) was truncated to `android-1fa2..` (12 chars), making peers hard to distinguish.
**Decision:** Increased truncation limit from 12 to 16 characters in `DiagnosticModelMapper`. Full PeerId is always shown in `PeerPanel.formatPeerHeader()`.
**Boundary:** Mapper string operation only.

### 3.7 Issue G — Topology Semantic Clarity
**Problem:** Section header "ACTIVE CONNECTIVITY & MULTI-PATH TOPOLOGY" implied all shown paths were active.
**Decision:** Changed to "LIVE NETWORK TOPOLOGY" in `activity_diagnostic.xml`.
**Boundary:** Layout XML text attribute only.

### 3.8 Issue H — LiveWire Completeness
**Problem:** PATH CHG events did not clearly distinguish transition direction.
**Decision:** Enriched PathStateListener event detail to show `transportName prev->new` format (e.g., `tcp ACTIVE->INACTIVE`).
**Boundary:** Activity event string formatting only.

### 3.9 Issue I — Stale State Accumulation
**Status:** Investigated. Root cause is in Core `PeerConnectivity` lifecycle (paths not removed on disconnect). Deferred to a future Core sprint. A.D2.1 mitigates via UI-level path sorting.

### 3.10 Issue J — STOP/START Lifecycle
**Status:** Verified correct. No changes needed. Regression test coverage added.

---

## 4. Hard Rules Compliance

| Rule | Status |
|------|--------|
| No Core networking changes | COMPLIANT — 0 Java files modified |
| No DiagnosticModelMapper bypass | COMPLIANT — all UI reads through mapper |
| No fabricated telemetry | COMPLIANT — all values from real Core state |
| No second path-selection mechanism | COMPLIANT — `PathSelectionPolicy` remains authoritative |
| No log scraping | COMPLIANT — typed listeners only |
| No polling | COMPLIANT — event-driven only |
| No protocol changes | COMPLIANT |
| No B.R2 behavior changes | COMPLIANT — read-only observation |
| No transition-hold mechanism | COMPLIANT |
| Document before architectural change | N/A — no architectural changes required |

---

## 5. Files Modified

| File | Change Type | Issue |
|------|-------------|-------|
| `activity_diagnostic.xml` | Restructured | B, G |
| `DiagnosticActivity.kt` | Stabilized | A, D, H |
| `DiagnosticModelMapper.kt` | Refined | C, E, F |
| `PathPanel.kt` | Refined | E |
| `LiveWirePanel.kt` | Simplified | B |
| `DiagnosticModelMapperTest.kt` | Extended | C, E (regression) |

**Core Java files modified: 0**

---

## 6. Test Coverage

| Test | Validates | Issue |
|------|-----------|-------|
| `testInitialStoppedStateMapping` | Clean stopped state | J |
| `testLiveTopologyAndPathStateMapping` | Multi-path topology | C |
| `testSelectedRouteIndication` | isSelected flag | E |
| `testDispatchRouteFallbackWhenNoActivePath` | NONE route display | E |
| `testDuplicatePathOrderingActiveFirst` | ACTIVE sorts first | C |
| `testLiveWireEventPassing` | Event flow | H |
| `testAsciiTopologyRenderer` | ASCII output | G |
| `testPathPanelFormatting` | Symbol rendering | C, E |

**Total: 35 tests, all passing.**
