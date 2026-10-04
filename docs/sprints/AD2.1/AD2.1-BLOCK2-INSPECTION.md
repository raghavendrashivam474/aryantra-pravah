# A.D2.1 Block 3 & 4 — Inspection, Fix Analysis & Compilation Report

**Sprint:** A.D2.1 (Track A — Stabilization)  
**Date of Execution:** 2025-07-11  
**Objective:** Document every fix implementation, compilation issue encountered, and resolution strategy used during the stabilization sprint.

---

## 1. Fix Implementation Details

### 1.1 Fix A — Bluetooth Device Selection Dialog

* **File:** `DiagnosticActivity.kt`
* **Method:** `showBluetoothDeviceChooser()`

#### Before (A.D2):
```kotlin
val device = bondedDevices.firstOrNull {
    it.name.lowercase().contains("android")
} ?: bondedDevices.first()
val targetPeerId = PeerId.of("remote-bt-node")

```

#### After (A.D2.1):

```kotlin
val deviceNames = bondedDevices.map {
    "${it.name ?: "Unknown"} [${it.address}]"
}.toTypedArray()

AlertDialog.Builder(this)
    .setTitle("Select Pravaah BT Peer")
    .setItems(deviceNames) { _, which ->
        val selectedDevice = bondedDevices[which]
        val targetPeerId = PeerId.of(
            "remote-bt-${selectedDevice.address.replace(":", "").takeLast(6)}"
        )
        // ... connection logic
    }
    .setNegativeButton("Cancel", null)
    .show()

```

#### Key Changes:

* Explicit user selection via `AlertDialog` instead of auto-fallback
* PeerId derived from selected device MAC address (unique per device)
* Cancel option prevents accidental connections

---

### 1.2 Fix B — Layout Scrolling Restructure

* **File:** `activity_diagnostic.xml`

#### Before (A.D2):

```xml
<LinearLayout>  <!-- root, no scroll -->
    <!-- header -->
    <!-- tvStatus -->
    <!-- tvMultiPathTopology -->
    <!-- buttons -->
    <!-- message input -->
    <ScrollView>  <!-- only LiveWire scrolls -->
        <TextView id="tvLog"/>
    </ScrollView>
</LinearLayout>

```

#### After (A.D2.1):

```xml
<LinearLayout>  <!-- root -->
    <!-- fixed header -->
    <ScrollView fillViewport="true">  <!-- entire surface scrolls -->
        <LinearLayout>
            <!-- tvStatus -->
            <!-- tvMultiPathTopology -->
            <!-- buttons -->
            <!-- message input -->
            <TextView id="tvLog" minHeight="120dp"/>  <!-- no nested scroll -->
        </LinearLayout>
    </ScrollView>
</LinearLayout>

```

#### Key Changes:

* Single outer `ScrollView` wraps all diagnostic content
* Nested `ScrollView` removed (Android anti-pattern)
* `tvLog` uses `minHeight="120dp"` instead of `layout_weight`
* `paddingStart/paddingEnd` replaced `padding` on `ScrollView` for RTL safety

---

### 1.3 Fix D — TCP Idempotency Guard

* **File:** `DiagnosticActivity.kt`
* **Location:** `btnConnectTcp.setOnClickListener`

#### Before (A.D2):

```kotlin
btnConnectTcp.setOnClickListener {
    val disc = manager.discoveredPeers.firstOrNull()
    if (disc != null) connectTcp(disc.hostAddress(), disc.port(), disc.peerId())
    else addErrorEvent("No discovered peers to connect TCP")
}

```

#### After (A.D2.1):

```kotlin
btnConnectTcp.setOnClickListener {
    val disc = manager.discoveredPeers.firstOrNull()
    if (disc != null) {
        val peer = disc.peerId()
        val hasActiveTcp = manager.connectivityRegistry.lookup(peer).map { conn ->
            conn.activePaths().any {
                it.transportName().equals("tcp", ignoreCase = true)
            }
        }.orElse(false)

        if (hasActiveTcp) {
            addSystemEvent("TCP already ACTIVE for ${peer.value()} — skipping")
        } else {
            connectTcp(disc.hostAddress(), disc.port(), peer)
        }
    } else {
        addErrorEvent("No discovered peers to connect TCP")
    }
}

```

#### Key Changes:

* Queries `connectivityRegistry` for existing active TCP paths before connecting
* Logs informative SYSTEM event instead of attempting redundant connection
* No Core changes — uses existing `PeerConnectivity.activePaths()` API

---

### 1.4 Fix E — Dispatch Route Semantics

* **File:** `PathPanel.kt`
* **Method:** `formatDispatchRoute()`

#### Before (A.D2):

```kotlin
fun formatDispatchRoute(resolvedRoute: String): String {
    return " └── [DISPATCH ROUTE]: $resolvedRoute\n\n"
}

```

#### After (A.D2.1):

```kotlin
fun formatDispatchRoute(resolvedRoute: String): String {
    val display = if (resolvedRoute.isBlank() || resolvedRoute == "NONE")
        "NONE (No active path)"
    else resolvedRoute
    return " └── [DISPATCH ROUTE]: $display\n\n"
}

```

#### Additionally in `DiagnosticModelMapper.kt`:

```kotlin
val isSelected = try {
    val selectedConnId = manager.router.resolveConnectionId(conn.peerId())
    val thisConnId = path.optionalConnectionId().orElse("")
    isActive &&  // <-- A.D2.1: must be ACTIVE to be selected
    selectedConnId != null &&
    thisConnId.isNotEmpty() &&
    selectedConnId == thisConnId
} catch (_: Exception) {
    false
}

```

#### Key Changes:

* "NONE" now explicitly annotated as `"(No active path)"`
* `isSelected` requires `isActive` — an `INACTIVE` path can never be selected

---

### 1.5 Fix F — Peer Identity Truncation

* **File:** `DiagnosticModelMapper.kt`
* **Before:** `peerIdVal.take(12) + ".."`
* **After:** `peerIdVal.take(16) + ".."`
* **Key Changes:** Accommodates the standard `android-XXXXXXXX` format (16 chars) without truncation.

---

### 1.6 Fix G — Topology Header Label

* **File:** `activity_diagnostic.xml`
* **Before:** `-- ACTIVE CONNECTIVITY & MULTI-PATH TOPOLOGY --`
* **After:** `-- LIVE NETWORK TOPOLOGY --`

---

### 1.7 Fix H — LiveWire Event Format

* **File:** `DiagnosticActivity.kt`
* **Location:** `PathStateListener` callback
* **Before:** `"${path.transportName()} transitioned from $prev to ${path.state()}"`
* **After:** `"${path.transportName()} ${prev}->${path.state()}"`
* **Key Changes:** More compact format (e.g., `tcp ACTIVE->INACTIVE` instead of `tcp transitioned from ACTIVE to INACTIVE`).

---

## 2. Compilation Issues Encountered

### 2.1 AAPT2 Resource Merge Failure

* **Error:**
```text
ERROR: activity_diagnostic.xml.AD21.bak: The file name must end with .xml

```


* **Root Cause:** The `Write-Utf8NoBom` helper created `.AD21.bak` backup files inside `res/layout/`. Android AAPT2 requires all files in resource directories to have valid `.xml` extensions.
* **Resolution:** Purged all `.bak` files from the `android/` directory tree before rebuilding. Updated the backup strategy to store `.bak` files outside the `res/` tree in future sprints.

### 2.2 LiveWirePanel Constructor Change

* **Issue:** The A.D2 `LiveWirePanel` constructor required `(ScrollView, TextView)`. The A.D2.1 layout removed the nested `ScrollView`, so the constructor was simplified to `(TextView)`.
* **Resolution:** Rewrote `LiveWirePanel.kt` to accept only `TextView`. Removed `scrollToBottom()` logic since the outer `ScrollView` handles scrolling automatically.

---

## 3. Regression Test Additions

Two new test cases were added to `DiagnosticModelMapperTest.kt`:

### 3.1 `testDispatchRouteFallbackWhenNoActivePath`

Registers only an `INACTIVE` TCP path and verifies:

* `resolvedRoute` equals `"NONE"`
* `PathPanel.formatDispatchRoute()` outputs `"NONE (No active path)"`

### 3.2 `testDuplicatePathOrderingActiveFirst`

Registers both an `INACTIVE` and `ACTIVE` TCP path for the same peer and verifies:

* The `ACTIVE` path appears first in `state.paths`
* The `ACTIVE` path has `isSelected = true`
* The `INACTIVE` path has `isSelected = false`

**Final Test Count:** 35 tests, all passing.

---

## 4. What Was NOT Changed

| Component | Reason |
| --- | --- |
| **Core Java engine (`/src/`)** | Stabilization scope — no Core defects confirmed |
| **`DiagnosticState.kt`** | A.D2 model is correct; issues were in mapping/presentation |
| **`TransitionBuffer.java`** | B.R2 behavior preserved; read-only observation |
| **`PeerConnectivity.java`** | Stale path cleanup deferred to future Core sprint |
| **`PeerRouter.java`** | Route resolution logic is correct; display was the issue |
| **`PathSelectionPolicy`** | Authoritative selection unchanged |
| **Protocol/Security layers** | Out of scope |

---

## 5. Deferred Items

| Item | Reason for Deferral | Future Sprint |
| --- | --- | --- |
| **Issue I: Stale path accumulation** | Requires Core `PeerConnectivity` lifecycle changes (calling `removePath()` on disconnect) | Future Core sprint |
| **ROUTE CHANGE LiveWire event** | Requires tracking previous selected route between dashboard updates | Future UI sprint |
| **Bluetooth device name discovery** | Mapping BT MAC addresses to Pravaah PeerIds requires discovery protocol integration | SX.3-B or future |

```