# PRAVAAH — A.D3-P1 Patch Report
**Patch Name:** Live Wire Bounded Scroll & Auto-Follow UX Polish  
**Parent Sprint:** A.D3 — Pravaah Experience Foundation & UI Contract  
**Target Branch:** `main`  
**Tag:** `v-A.D3-P1`  
**Status:** ✅ **COMPLETE — Verified Green & Deployed**

---

## 1. Problem Statement & UX Motivation
In A.D3, the Live Wire Forensic Stream appended events directly to a single uncontained `TextView` inside the main activity `ScrollView`. As real-time network, path, and protocol events accumulated, the Live Wire panel expanded vertically without bound.

This caused two UX issues:
1. The persistent cockpit surface kept growing vertically, pushing layout elements out of view.
2. Inspecting past forensic events was difficult because new incoming events either jittered the viewport or forced the user to manually re-scroll.

---

## 2. Technical Implementation & UX Behavior

### 2.1 Bounded Vertical Viewport
- Wrapped `tvLog` inside a dedicated inner `ScrollView` (`id/svLog`) with a fixed height of `150dp` and `fillViewport="true"`.
- The main cockpit layout height remains completely stable regardless of event volume.

### 2.2 Smart Auto-Follow vs History Inspection (`LiveWirePanel.kt`)
- Added an `OnScrollChangeListener` on `svLog` to compute scroll position relative to total content height.
- **When at Bottom:** Incoming events trigger `svLog.fullScroll(View.FOCUS_DOWN)` automatically so the latest forensic events are immediately visible.
- **When Scrolled Up:** If the user scrolls up to inspect historical logs, auto-scroll pauses so the user can read undisturbed without viewport jitter.

### 2.3 Re-Follow Affordance Badge (`tvFollowBadge`)
- Added a floating status badge (`↓ NEW EVENTS`) at the top-right header of the Live Wire panel.
- Appears only when new events arrive while the user is manually scrolled up.
- Tapping the badge smoothly scrolls `svLog` to the bottom and re-engages automatic log following.

---

## 3. Files Modified
- `android/app/src/main/res/layout/activity_diagnostic.xml` — Bounded `svLog` `ScrollView` container (150dp fixed height) and `tvFollowBadge` header overlay.
- `android/app/src/main/java/.../presentation/LiveWirePanel.kt` — Scroll listener, bottom detection, and smart auto-follow state machine.
- `android/app/src/main/java/.../DiagnosticActivity.kt` — Bound `svLog` and `tvFollowBadge` views during activity creation.
- `pravaah-ad3-debug.apk` — Updated physical APK binary (2.62 MB).

---

## 4. Verification Summary
- **Android Unit Tests:** 33 / 33 PASSED (100%)
- **APK Assembly:** Clean build (`2.62 MB`)
- **Physical Device UX Check:** Viewport stays strictly fixed at 150dp; log auto-scrolls at bottom and holds position when scrolled up.