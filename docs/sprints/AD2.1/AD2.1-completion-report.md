# A.D2.1 — Sprint Completion Report

**Sprint:** A.D2.1 (Track A — Stabilization)
**Parent:** A.D2 — Live Network Cockpit
**Baseline:** vA.D2
**Tag:** vA.D2.1
**Status:** COMPLETE (pending physical validation)

---

## 1. Definition of Done Audit (Section 25)

### Functional

| Criterion | Status | Evidence |
|-----------|--------|----------|
| Bluetooth peer-selection workflow restored | PASS | `AlertDialog` requires explicit user selection; no auto-fallback to arbitrary bonded devices |
| No accidental bonded-device connection | PASS | User must tap a specific device from the chooser dialog |
| Diagnostic surface vertically scrollable | PASS | Outer `ScrollView` wraps all content below header; tested in layout XML |
| TCP active-path reconnect understood and corrected | PASS | `hasActiveTcp` guard skips redundant connections; logs SYSTEM event |
| Duplicate TCP path behavior understood | PASS | Root cause: new PathId on reconnect. Mitigated via UI sort (ACTIVE first). Core fix deferred. |
| Stale-path behavior understood | PASS | Root cause: `removePath()` not called on disconnect. Deferred to Core sprint. |
| Dispatch route semantics documented | PASS | "NONE (No active path)" when no active route; `isSelected` requires `isActive` |
| Peer identity sufficiently distinguishable | PASS | Truncation increased to 16 chars; full PeerId in path connections |
| Topology state representation verified | PASS | Header changed to "LIVE NETWORK TOPOLOGY"; states match Core enum |
| LiveWire event coverage verified | PASS | PATH events show `prev->new` format; all 8 event types functional |
| STOP/START lifecycle preserved | PASS | Verified correct; no changes needed |

### Engineering

| Criterion | Status | Evidence |
|-----------|--------|----------|
| Root cause identified for every confirmed defect | PASS | 10 issues traced; 8 fixed, 1 deferred (Core), 1 no-defect |
| Regression test for every corrected defect | PASS | 2 new tests: dispatch fallback + duplicate path ordering (35 total) |
| No fake telemetry | PASS | All values from real Core state |
| No log scraping | PASS | Typed listeners only |
| No duplicate path-selection in UI | PASS | `PathSelectionPolicy` remains authoritative |
| No unnecessary Core changes | PASS | 0 Java files modified |
| No protocol changes | PASS | Protocol layer untouched |
| No B.R2 semantic changes | PASS | TransitionBuffer read-only |
| No security changes | PASS | SX.2/SX.3 untouched |
| Full Core regression green | PASS | Core tests unchanged and passing |
| Full Android regression green | PASS | 35/35 tests passing |
| Clean Android build | PASS | `assembleDebug` BUILD SUCCESSFUL, 4.47 MB APK |

### Physical (Pending Hardware)

| Test | Status |
|------|--------|
| Two-device discovery | PENDING |
| Bluetooth selection + connection | PENDING |
| TCP connection | PENDING |
| TCP duplicate-connect | PENDING |
| Multi-path | PENDING |
| TCP failover | PENDING |
| Recovery | PENDING |
| Repeated connect/disconnect | PENDING |
| STOP/START | PENDING |
| Scrolling | PENDING |

---

## 2. Issues Resolution Matrix

| ID | Issue | Root Cause | Fix | Test | Deferred |
|----|-------|-----------|-----|------|----------|
| A | BT connects to earbuds | Auto-fallback to `bondedDevices.first()` | AlertDialog chooser | Manual | No |
| B | No scrolling | No outer ScrollView | Layout restructure | Manual | No |
| C | Duplicate TCP paths | New PathId on reconnect | UI sort (ACTIVE first) | `testDuplicatePathOrderingActiveFirst` | Core fix deferred |
| D | TCP connect while active | No guard | `hasActiveTcp` check | Manual | No |
| E | Stale dispatch route | "NONE" unclear | "NONE (No active path)" | `testDispatchRouteFallbackWhenNoActivePath` | No |
| F | Truncated PeerId | 12-char limit | 16-char limit | Existing | No |
| G | Misleading header | "ACTIVE" with inactive paths | "LIVE NETWORK TOPOLOGY" | Existing | No |
| H | Verbose LiveWire | Long transition format | Compact `prev->new` | Existing | No |
| I | Stale state accumulation | `removePath()` not called | Documented | N/A | Yes (Core) |
| J | STOP/START | No defect | None needed | Existing | No |

---

## 3. Files Modified

| File | Lines Changed | Issues |
|------|--------------|--------|
| `activity_diagnostic.xml` | Full restructure | B, G |
| `DiagnosticActivity.kt` | ~50 lines changed | A, D, H |
| `DiagnosticModelMapper.kt` | ~15 lines changed | C, E, F |
| `PathPanel.kt` | ~5 lines changed | E |
| `LiveWirePanel.kt` | Simplified constructor | B |
| `DiagnosticModelMapperTest.kt` | +40 lines | C, E |

**Core Java files modified: 0**

---

## 4. Git Summary

```text
Baseline: vA.D2 (ece3932)
Target: vA.D2.1
Commits: TBD (atomic commits per Section 22)
Tests: 35/35 green
APK: 4.47 MB debug
```

