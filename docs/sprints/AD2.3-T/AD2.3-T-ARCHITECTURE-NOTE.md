# A.D2.3-T — Architecture & Remediation Note for A.D2.4

## 1. Executive Summary
The forensic audit A.D2.3-T has definitively isolated the remaining TCP delivery defect to a **ProtocolListener chain overwrite in Track A**. Pravah Core transport, routing, and message encoding/decoding layers are 100% operational.

No Track B (Core) architectural modifications are required.

---

## 2. Surgical Remediation Plan for Next Sprint (A.D2.4)

### Fix 1: ProtocolListener Multi-Cast / Delegation in `PravahAndroidMessagingManager.kt`
Instead of overwriting the single `downstreamListener` when `DiagnosticActivity` registers its listener, `PravahAndroidMessagingManager` must support listener chaining or multi-cast forwarding so that **both** `DefaultApplicationMessagingService` and `DiagnosticActivity` receive coordinator lifecycle callbacks.

```kotlin
// In PravahAndroidMessagingManager.kt:
private val protocolListeners = CopyOnWriteArrayList<ProtocolListener>()

fun addProtocolListener(listener: ProtocolListener) {
    protocolListeners.add(listener)
}

// Coordinator forwards onPeerJoined, onMessageReceived, and onPeerLeft to all registered listeners in protocolListeners.

```

### Fix 2: Complete the S2 Redundant Call Removal

Surgically remove the remaining `presenceBridge.handlePeerConnected()` calls from:

* `PravahAndroidMessagingManager.kt:73` (inside `onPeerJoined`)
* `PravahAndroidMessagingManager.kt:94` (inside `onMessageReceived`)

### Fix 3: Ensure Canonical Connection ID in Router Dispatch Display

Ensure `DiagnosticModelMapper.kt` consistently renders `resolvedRoute` using canonical formatting or ensure all paths in `PeerConnectivityRegistry` store normalized connection IDs without leading slashes.

---

## 3. Scope & Safety Summary

* **Total files to touch in A.D2.4:** 2 (`PravahAndroidMessagingManager.kt`, `DiagnosticActivity.kt`)
* **Core files modified:** 0
* **Risk level:** Minimal