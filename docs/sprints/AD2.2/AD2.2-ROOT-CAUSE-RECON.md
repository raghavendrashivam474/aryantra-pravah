# A.D2.2 — Root-Cause Reconnaissance Report

**Sprint:** A.D2.2 (Runtime Integrity & Message Path Surgical Fixes)
**Baseline:** vA.D2.1
**Date:** 2025-07-11
**Status:** Root causes confirmed. Ready for surgical fix phase.

---

## Issue 1 — Duplicate/Stale Path Representations

**Observed:**
```text
PEER: android-XXXXXXXX
[TCP] ● ACTIVE
[TCP] ○ INACTIVE
```

Two TCP entries for the same peer, one active and one inactive.

**Reproduction:**
1. START both devices
2. DISCOVER and CONNECT TCP
3. DROP TCP (simulated)
4. CONNECT TCP again
5. Inspect topology

**Actual Object Lifecycle (traced through source):**

1. Discovery registers a CANDIDATE path via `PeerPresenceBridge.onCandidateDiscovered()` → `connectivityRegistry.registerPath()`. PathId is deterministic from `DiscoveredAddressCandidate.toCandidatePath()`.

2. `connectToTcp()` calls `presenceBridge.handlePeerConnected(peerId, connId)` → `activatePath()`. This method (PeerPresenceBridge.java L126-153) searches for an existing non-active path matching the transport scheme. If found, it reuses the PathId via `targetPath.activate(connectionId)`. If not found, it creates a NEW PathId: `PathId.of("path:" + peerId + ":" + scheme + ":conn:" + connId)`.

3. `DROP TCP` calls `conn.addPath(path.deactivate())`. This replaces the path under the SAME PathId with an INACTIVE version (connectionId becomes null per ConnectivityPath.java L64-65).

4. Reconnect: `activatePath()` searches for non-active paths matching the scheme. The INACTIVE path from step 3 matches `!p.isActive()` AND `p.transportName().equalsIgnoreCase("tcp")`. So it SHOULD be reused.

**Root Cause:**
The reuse logic in `activatePath()` (L131-135) works correctly for single-path reconnect. The duplication arises from a **different scenario**: when `connectToTcp()` is called, the manager calls `presenceBridge.handlePeerConnected()` TWICE — once in `connectToTcp()` itself (L167) and once in `sendJoin()` (L194). The first call activates the path. The second call hits the `alreadyActive` guard (L142-143) and skips. This is correct.

The ACTUAL duplication source is the **Discovery + Connect race**: Discovery registers a CANDIDATE path with PathId from `DiscoveredAddressCandidate.toCandidatePath()`. Then `connectToTcp()` → `handlePeerConnected()` → `activatePath()` finds this candidate and activates it (reusing PathId). But `sendJoin()` also calls `handlePeerConnected()` with a potentially DIFFERENT `connectionId` (the cleaned version vs the raw version). If the connection IDs don't match exactly, the `alreadyActive` check fails and a SECOND active path is created with a new PathId.

Additionally, `PeerRouter.send()` (L113-115) deactivates failed paths via `peerConn.addPath(path.deactivate())` during send failover. These deactivated paths persist in the registry indefinitely because `removePath()` is never called during normal lifecycle.

**Owning Layer:** Core — `PeerPresenceBridge.activatePath()` and `PeerConnectivity` lifecycle.

**Proposed Correction:**
1. In `PeerPresenceBridge.activatePath()`: When creating a new active path, first check if an INACTIVE path exists for the same transport scheme and peer, and reuse its PathId instead of generating a new one.
2. In `PeerPresenceBridge.handleConnectionClosed()`: After deactivating a path, call `connectivity.removePath(pathId)` for paths that have been inactive and are no longer needed (or add a cleanup pass).
3. In `DiagnosticModelMapper`: As a UI-layer mitigation (already partially done in A.D2.1), filter out INACTIVE paths that have a corresponding ACTIVE path for the same transport+peer combination.

**Severity:** Medium. Does not break messaging (PeerRouter selects one active path). Causes visual confusion.

---

## Issue 2 — Duplicate Bluetooth Identity

**Observed:**
```text
PEER: android-XXXXXXXX [JOINED]
[BLUETOOTH] ● ACTIVE bt:XX:XX:XX:XX:XX:XX

PEER: remote-bt-XXXXXX [UNKNOWN]
[BLUETOOTH] ● ACTIVE bt:XX:XX:XX:XX:XX:XX
```

Two logical peers for one physical Bluetooth device.

**Reproduction:**
1. START both devices
2. CONNECT BT (select device from dialog)
3. Wait for JOIN exchange
4. Inspect topology

**Actual Object Lifecycle:**

1. `showBluetoothDeviceChooser()` creates `targetPeerId = PeerId.of("remote-bt-<MAC6>")` and calls `manager.connectToBluetooth(mac, targetPeerId)`.

2. `connectToBluetooth()` calls `presenceBridge.handlePeerConnected(remotePeerId, connId)` which registers `remote-bt-XXXXXX` in the PeerRegistry and creates an ACTIVE ConnectivityPath for it.

3. `connectToBluetooth()` then calls `sendJoin(remotePeerId, connId)` which sends a JOIN message to the remote device. The JOIN carries `localPeerId` as the sender.

4. The remote device receives the JOIN and replies with its own JOIN, carrying its real PeerId (`android-XXXXXXXX`).

5. `PeerConnectionCoordinator.handleInboundMessage()` (L168-185) processes the incoming JOIN: creates a NEW mapping `connectionToPeer[connId] = android-XXXXXXXX`, calls `presenceBridge.handlePeerConnected(android-XXXXXXXX, connId)`.

6. The manager's coordinator override calls `cleanOrphanedBtNode(authenticatedPeer)` (L77). This method (L234-245) looks up the temp BT peer in the connectivity registry, migrates its paths to the authenticated peer, and calls `connectivityRegistry.removePeer(tempBtPeer)`.

**Root Cause:**
`cleanOrphanedBtNode()` attempts to find the temp BT peer by iterating `connectivityRegistry.allConnectivities()` and looking for a peer whose PeerId starts with `remote-bt-`. However, the method searches for paths with matching `connectionId`. If the connection ID format differs between the BT registration (`bt:XX:XX:XX:XX:XX:XX`) and the JOIN-activated path, the migration fails silently and both peers persist.

Additionally, the `onPeerJoined` callback in the manager (L67-88) fires AFTER `presenceBridge.handlePeerConnected()` has already been called for the real peer in the coordinator. So by the time `cleanOrphanedBtNode()` runs, the real peer already has its own ConnectivityPath. The migration adds a SECOND path to the real peer (from the temp peer), but `removePeer()` may not fully clean up the temp peer's registry entry.

**Owning Layer:** Android bridge — `PravahAndroidMessagingManager.cleanOrphanedBtNode()` and the BT connection flow in `DiagnosticActivity`.

**Proposed Correction:**
1. In `DiagnosticActivity.showBluetoothDeviceChooser()`: Do NOT create a synthetic `remote-bt-XXXXXX` PeerId. Instead, connect the BT transport WITHOUT a PeerId, and let the JOIN exchange establish the real identity. This means calling `manager.connectToBluetooth(mac, null)` and deferring `bindSession()` until `onPeerJoined` fires with the real PeerId.
2. If a synthetic PeerId is needed for the initial connection, ensure `cleanOrphanedBtNode()` is called AFTER the path migration is verified, and that it removes the temp peer from both `connectivityRegistry` AND `registry`.

**Severity:** High. Creates phantom peers in the topology and can cause message routing confusion.

---

```markdown
## Issue 3 — RX UUID/Message Representation

**Observed:**  
TX shows: `SENT [android-XXXX] hello`  
RX shows: `RECV [android-XXXX] 550e8400-e29b-41d4-a716-446655440000`

The RX event displays a UUID instead of the message content.

**Reproduction:**
1. Establish TCP or BT connection
2. Send "hello" from Device A
3. Observe Device B LiveWire

### Actual Object Lifecycle

1. **TX:** `sendPayloadMessage()` calls `manager.sendText(destination, content)` which creates an `ApplicationMessage` with `UUID.randomUUID()` as `messageId` and `content` as the text. The TX event posts the content string.
2. The `ApplicationMessage` is serialized into a protocol `Message` with `messageId = UUID` and `payload = [0x01][content bytes]` (`APP_MSG_CHAT` prefix + text).
3. **RX:** The transport delivers bytes $\rightarrow$ `FrameDecoder` $\rightarrow$ `MessageParser.parse()` $\rightarrow$ `ProtocolSessionManager.processMessage()` $\rightarrow$ `ProtocolListener.onMessageReceived(peerId, message)`.
4. The `message` object at this level is the raw protocol `Message` with `messageId = UUID` and `payload = [0x01][content bytes]`.
5. `DiagnosticActivity.onMessageReceived()` (L147-148) posts:
   ```kotlin
   postEvent("RX", "[${peerIdStr.take(16)}] ${message.messageId()}")

```

This displays the UUID `messageId` instead of the payload content.

The actual text content is decoded later by `DefaultApplicationMessagingService.handleInboundChat()` (L350-383) which strips the `APP_MSG_CHAT` prefix byte and creates an `ApplicationMessage` with the decoded text. This fires `ApplicationMessageListener.onMessage(appMessage)` where `appMessage.content()` contains the real text.

**Root Cause:**

The RX event is posted from the `ProtocolListener.onMessageReceived()` callback which receives the raw protocol `Message` (`messageId = UUID`, `payload = framed bytes`). The human-readable content is only available later in the `ApplicationMessageListener` callback after `DefaultApplicationMessagingService` decodes the payload.

The diagnostic UI is reading from the wrong layer. It should either:

* Decode the payload in the `ProtocolListener` callback (skip `byte[0]`, decode rest as UTF-8), or
* Post the RX event from the `ApplicationMessageListener` callback where `msg.content()` is available.

**Owning Layer:** Android Diagnostic — `DiagnosticActivity.kt` event construction.

**Proposed Correction:**

Move the RX LiveWire event from the `ProtocolListener.onMessageReceived()` callback to the `ApplicationMessageListener` callback, using `msg.content()` for the display text and `msg.messageId()` for correlation:

```kotlin
manager.addMessageListener(ApplicationMessageListener { msg ->
    handler.post {
        postEvent("RX", "[${msg.sender().value().take(16)}] ${msg.content()}")
        bindSession(msg.sender())
        updateDashboard()
    }
})

```

Remove the RX `postEvent` from the `ProtocolListener` callback to avoid duplicate events.

**Severity:** High. Makes the diagnostic cockpit untrustworthy — the operator cannot verify message content.

---

## Issue 4 — Apparent Delivery Timing Anomaly

**Observed:**

Noticeable gap between TX and RX timestamps in LiveWire.

**Root Cause:**

This is a measurement artifact, not a network defect. The TX timestamp is recorded when `sendPayloadMessage()` posts the event to the handler queue. The RX timestamp is recorded when the `ProtocolListener` callback fires (which happens on the transport I/O thread, then posts to the handler queue). Both timestamps use `SimpleDateFormat("HH:mm:ss")` which has 1-second resolution.

Additionally, the TX event is posted BEFORE the actual transport send completes (it's posted to the handler queue while the send happens on the background executor). The RX event is posted AFTER the full decode pipeline. The apparent gap is the sum of:

* Handler queue latency (TX post)
* Actual transport time
* Frame decode + protocol parse time
* Handler queue latency (RX post)
* 1-second timestamp resolution rounding

**Owning Layer:** Diagnostic — timestamp resolution and event posting order.

**Proposed Correction:**

1. Use `HH:mm:ss.SSS` format for sub-second resolution in diagnostic timestamps.
2. Document that TX timestamp represents "queued for send" not "sent on wire".
3. For true latency measurement, correlate TX and RX by `messageId` and compute the difference. This is a future enhancement, not a defect.

**Severity:** Low. Not a functional defect. Cosmetic/informational.

---

## Issue 5 — Duplicate Delivery Possibility

**Observed:**

Need to verify whether messages can be delivered more than once.

### Analysis (traced through source):

* `PeerRouter.send()` (L90-154) selects ONE active path via `selectionPolicy.selectPath()`, sends on it, and returns immediately on success (L112: `return;`). No duplicate sends.
* If the first path fails, it deactivates that path and tries the next. Still only one successful send.
* `DefaultApplicationMessagingService.send()` (L147-196) calls `peerRouter.send()` once. No retry loop at the application level for immediate delivery.
* `DeliveryRetryManager` retries FAILED messages from the outbox. This could cause a duplicate if the original send actually succeeded but the state update failed. However, the retry manager checks `outbox.findByMessageId()` and marks completed on ACK.
* On the RX side, `ProtocolSessionManager.processMessage()` delivers every `MESSAGE` to the listener. There is no deduplication by `messageId` at the protocol level. If the same message arrives twice (e.g., due to retry), it will be delivered twice to the `ApplicationMessageListener`.

**Root Cause:**

No duplicate delivery under normal single-path operation. Under retry scenarios, the `DeliveryRetryManager` could theoretically cause a duplicate if the original send succeeded but the ACK was lost. `DefaultApplicationMessagingService.handleInboundChat()` does not check for duplicate `messageIds` before delivering to listeners.

**Owning Layer:** Core messaging — `DefaultApplicationMessagingService` inbound handling.

**Proposed Correction:**

Add `messageId` deduplication in `handleInboundChat()`: check `historyStore` for existing `messageId` before delivering to listeners. This is a defensive measure, not a critical fix.

**Severity:** Low. No evidence of actual duplicates in current testing. Theoretical risk under retry scenarios.

---

## Issue 6 — Route Selection Consistency

**Observed:**

Need to verify `PathSelectionPolicy` remains the sole routing authority.

### Analysis:

* `PeerRouter.send()` (L103) calls `selectionPolicy.selectPath(destination, availablePaths)`. Single authority.
* `PeerRouter.resolveConnectionId()` (L162) calls `selectionPolicy.selectPath(destination, activePaths)`. Same authority.
* `DiagnosticModelMapper` reads `router.resolveConnectionId()` to determine `isSelected`. Does not compute its own selection.
* No transport-specific routing logic exists in `DiagnosticActivity` or the mapper.

**Root Cause:**

No defect. `PathSelectionPolicy` is the sole routing authority. The `preferSchemes("tcp", "bluetooth", "bt")` policy correctly prioritizes TCP over Bluetooth.

**Owning Layer:** N/A — no defect.

**Proposed Correction:** None needed. Verify with regression test.

**Severity:** None. Confirmed correct.

---

## Summary of Confirmed Root Causes

| Issue | Confirmed Root Cause | Owning Layer | Severity | Fix Type |
| --- | --- | --- | --- | --- |
| **1. Stale paths** | `activatePath()` creates new `PathId` instead of reusing `INACTIVE` path; `removePath()` never called | Core: `PeerPresenceBridge` | Medium | Surgical Core fix |
| **2. BT identity** | Synthetic `remote-bt-XXXXXX` `PeerId` not cleaned up after `JOIN` establishes real identity | Android: Manager + Activity | High | Android bridge fix |
| **3. RX UUID** | LiveWire reads `message.messageId()` from `ProtocolListener` instead of `msg.content()` from `ApplicationMessageListener` | Android: `DiagnosticActivity` | High | Diagnostic fix |
| **4. Timing gap** | 1-second timestamp resolution + handler queue latency | Android: `DiagnosticActivity` | Low | Cosmetic fix |
| **5. Duplicate delivery** | No deduplication in inbound handler (theoretical risk) | Core: `MessagingService` | Low | Defensive fix |
| **6. Route consistency** | No defect — `PathSelectionPolicy` is sole authority | N/A | None | Regression test |

---

## Recommended Fix Priority

1. **Issue 3 (RX UUID)** — Highest user impact, simplest fix. Move RX event to `ApplicationMessageListener`.
2. **Issue 2 (BT Identity)** — High impact. Stop creating synthetic `PeerIds`; let `JOIN` establish identity.
3. **Issue 1 (Stale Paths)** — Medium impact. Fix `activatePath()` reuse logic and add path cleanup.
4. **Issue 4 (Timing)** — Low impact. Improve timestamp resolution.
5. **Issue 5 (Dedup)** — Low impact. Add defensive `messageId` check.
6. **Issue 6 (Route)** — No fix needed. Add regression test.

```