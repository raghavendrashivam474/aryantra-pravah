# A.D2.3 — Diagnostic Runtime Integrity Finalization — Implementation Plan & Execution Record

**Sprint:** A.D2.3 — Surgical Remediation  
**Baseline:** `vA.D2.2-F` (`0e8a102`)  
**Status:** Implementation Complete — Automated Tests Green — Physical Validation Pending  
**Scope:** Track A (Android layer) + Transport socket identifier canonicalization only  

---

## 1. Remediation Scope & Commit Ledger

| Phase | Description | Owning File | Commit Hash | Commit Message |
|---|---|---|---|---|
| **S1** | Normalize active TCP socket connection IDs to canonical `host:port` format (strip leading `/`) | `src/main/java/com/aryantra/pravah/transport/tcp/TcpTransport.java` | `3254f14` | `fix(tcp): normalize active socket connection identifiers` |
| **S1-T** | Unit test verifying server and client emit connection IDs without leading `/` | `src/test/java/com/aryantra/pravah/transport/tcp/TcpTransportTest.java` | `67a4036` | `test(tcp): cover canonical connection identifier` |
| **S2** | Remove 5 redundant `handlePeerConnected` calls; restore `PeerConnectionCoordinator` as sole activation authority | `android/app/src/main/java/com/aryantra/pravah/android/PravahAndroidMessagingManager.kt` | `ce42890` | `fix(diagnostic): rely on coordinator for peer activation` |
| **S3** | Universal prefix-based cleanup of dynamic synthetic Bluetooth peers (`remote-bt-*`) on real JOIN | `android/app/src/main/java/com/aryantra/pravah/android/PravahAndroidMessagingManager.kt` | `c4b69ec` | `fix(bluetooth): align synthetic peer identity cleanup` |

---

## 2. Line-Level Execution Record

### Phase S1 — TCP Connection ID Canonicalization

- **File:** `TcpTransport.java` (lines 225–235)
- **Change:**
  ```java
  // BEFORE:
  String id = socket.getRemoteSocketAddress() != null
            ? socket.getRemoteSocketAddress().toString()
            : "unknown-" + System.nanoTime();

  // AFTER:
  String rawAddr = socket.getRemoteSocketAddress() != null
            ? socket.getRemoteSocketAddress().toString()
            : "unknown-" + System.nanoTime();
  // Normalize: strip leading '/' from InetSocketAddress.toString() for canonical "host:port"
  String id = rawAddr.startsWith("/") ? rawAddr.substring(1) : rawAddr;

```

* **Test:** `TcpTransportTest.testCanonicalConnectionIdWithoutLeadingSlash` establishes two localhost sockets and asserts `!serverReportedCorrId.startsWith("/")` and `!clientReportedCorrId.startsWith("/")`.

### Phase S2 — Single Authoritative Activation Lifecycle

* **File:** `PravahAndroidMessagingManager.kt`
* **Eliminated Call Sites:**
* `coordinator.setProtocolListener.onPeerJoined`: Removed manual `presenceBridge.handlePeerConnected()` call (the coordinator already called it before notifying the listener).
* `coordinator.setProtocolListener.onMessageReceived`: Removed `presenceBridge.handlePeerConnected()` on every regular inbound application message.
* `connectToTcp()`: Removed premature `presenceBridge.handlePeerConnected()` before the socket handshake and JOIN exchange.
* `connectToBluetooth()`: Removed premature `presenceBridge.handlePeerConnected()` before the RFCOMM handshake and JOIN exchange.
* `sendJoin()`: Removed premature `presenceBridge.handlePeerConnected()` during outbound JOIN dispatch.


* **Result:** Peer activation happens exclusively via `PeerConnectionCoordinator.handleInboundMessage()` when a wire-level JOIN frame is validated.

### Phase S3 — Synthetic Bluetooth Peer Cleanup Alignment

* **File:** `PravahAndroidMessagingManager.kt` (lines 228–242)
* **Change:**
```kotlin
// BEFORE:
private fun clearOrphanedBtNode(authenticatedPeer: PeerId) {
    val tempBtPeer = PeerId.of("remote-bt-node")
    if (tempBtPeer != authenticatedPeer) {
        connectivityRegistry.lookup(tempBtPeer).ifPresent { ... }
    }
}

// AFTER:
private fun clearOrphanedBtNode(authenticatedPeer: PeerId) {
    val orphanedPeers = connectivityRegistry.allConnectivities()
        .map { it.peerId() }
        .filter { it.value().startsWith("remote-bt-") && it != authenticatedPeer }

    for (orphaned in orphanedPeers) {
        connectivityRegistry.lookup(orphaned).ifPresent { conn ->
            for (path in conn.allPaths()) {
                if (path.isActive && path.connectionId() != null) {
                    presenceBridge.handlePeerConnected(authenticatedPeer, path.connectionId())
                }
            }
            connectivityRegistry.removePeer(orphaned)
            logger.info("Cleared orphaned synthetic BT peer: ${orphaned.value()} -> migrated to ${authenticatedPeer.value()}")
        }
    }
}

```


* **Result:** Any synthetic Bluetooth ID (`remote-bt-node`, `remote-bt-5566A1`, etc.) is dynamically matched, migrated, and purged from `PeerConnectivityRegistry` upon real JOIN authentication.

---

## 3. Automated Verification Results

| Suite | Scope | Result | Time |
| --- | --- | --- | --- |
| **Core Maven Tests** | `TcpTransportTest`, `CompositeTransportTest`, `ConnectivityPathLifecycleTest`, `PeerPresenceBridgeTest`, `HybridDiscoveryTest`, `PeerConnectionCoordinatorTest` | **37 / 37 PASS** (0 Failures) | 4.45s |
| **Android Unit Tests** | `testDebugUnitTest` (all 36 Kotlin unit tests) | **BUILD SUCCESSFUL** | 1m 22s |
| **Android APK Build** | `assembleDebug` (`app-debug.apk` 4574.82 KB) | **BUILD SUCCESSFUL** | 21s |

---

## 4. Protected Boundaries Confirmed

* **Core Connectivity Model:** `PeerConnectivity`, `PeerConnectivityRegistry`, `ConnectivityPath`, `PathState` — **UNTOUCHED**
* **Presence Coordination:** `PeerPresenceBridge`, `PeerConnectionCoordinator` — **UNTOUCHED**
* **Routing & Selection:** `PathSelectionPolicy`, `PeerRouter` — **UNTOUCHED**
* **Reliability & Protocol:** `TransitionBuffer`, `DeliveryOutbox`, `Message`, `MessageType` — **UNTOUCHED**
* **Security:** SX.2, SX.3 identity & authentication — **UNTOUCHED**
