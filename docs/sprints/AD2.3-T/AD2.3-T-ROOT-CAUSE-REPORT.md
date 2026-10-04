# A.D2.3-T — Root-Cause Forensic Report

**Track:** Track A — Product Experience / Diagnostic Runtime  
**Sprint:** A.D2.3-T — TCP Delivery & Connection Lifecycle Root Cause  
**Baseline:** `vA.D2.3` (`b9c8976`)  
**Status:** Root Cause Proven — No Production Code Modified  

---

## Issue 1: Missing Inbound Message (RX Event Never Appears)

### 1. Observed Symptom
When Device A sends a chat message over an active TCP connection, Device A displays a `TX` LiveWire event, but Device B never displays the corresponding `RX` LiveWire event or updates its session message history.

### 2. Exact Reproduction
1. Start two Android nodes on the same Wi-Fi.
2. Node A connects to Node B over TCP; JOIN exchange completes.
3. Node A types `"PRAVAAH-TCP-001"` and taps **SEND**.
4. Node A shows `TX: [android-B] PRAVAAH-TCP-001`.
5. Node B's LiveWire log remains completely silent (no `RX` event).

### 3. Forensic Evidence
- `DefaultApplicationMessagingService` constructor (lines 95–115) registers a `ProtocolListener` on `coordinator.setProtocolListener()` to receive inbound `MESSAGE` frames, decode application chat payloads, and notify its `ApplicationMessageListener` subscribers.
- In `DiagnosticActivity.kt` (lines 141–178), the activity registers **its own** `ProtocolListener` on `manager.coordinator.setProtocolListener()`.
- `PeerConnectionCoordinator.java` (line 86) holds a single `private volatile ProtocolListener protocolListener` field. It does not support a listener list or composite chaining.
- `PravahAndroidMessagingManager.kt` (line 64) overrides `setProtocolListener(listener)` by holding `private var downstreamListener = listener`. When `DiagnosticActivity` calls `setProtocolListener`, `downstreamListener` is overwritten from `DefaultApplicationMessagingService` to `DiagnosticActivity`.
- In `DiagnosticActivity.kt` (lines 163–167), `onMessageReceived` is intentionally empty (per A.D2.2 Fix 3 design: *"Do NOT post RX here. The decoded content is available in ApplicationMessageListener"*).
- **Result:** `DefaultApplicationMessagingService` never receives `onMessageReceived()`, `handleInboundProtocolMessage()` is never called, `ApplicationMessageListener.onMessage()` is never invoked, and the RX LiveWire event never fires.

### 4. Root Cause
**Listener Overwrite in Android Orchestration:** `PeerConnectionCoordinator` supports only a single `ProtocolListener`. The `DiagnosticActivity` overwrote the core messaging service's listener with an empty handler, severing the inbound message delivery pipeline.

### 5. Owning Boundary
**Track A (Android Orchestration & Listener Chaining)**

---

## Issue 2: Duplicate-Looking TCP ACTIVE Paths & Residual S2 Calls

### 1. Observed Symptom
Diagnostic cockpit shows multiple `[TCP] ● ACTIVE` paths and redundant activation transitions in LiveWire events.

### 2. Forensic Evidence
- Static inspection of `PravahAndroidMessagingManager.kt` revealed that lines 72–73 (in `onPeerJoined`) and line 94 (in `onMessageReceived`) still contain `presenceBridge.handlePeerConnected(remotePeer, cleanConnId(rawConnId))`.
- These calls were scheduled for removal in A.D2.3 S2, but the multi-line string replacement did not match due to whitespace differences.
- Because `onPeerJoined` and `onMessageReceived` repeatedly call `presenceBridge.handlePeerConnected()`, `PeerPresenceBridge.activatePath()` is triggered on every event, emitting duplicate path transition notifications.

### 3. Root Cause
**Incomplete S2 Patch Application:** Redundant `handlePeerConnected()` calls remained active in `PravahAndroidMessagingManager.kt`.

### 4. Owning Boundary
**Track A (`PravahAndroidMessagingManager.kt`)**

---

## Issue 3: Dispatch Route Displays Leading Slash (`/10.177.67.157:36681`)

### 1. Observed Symptom
Path panel displays `10.177.67.157:36681` (no slash), while Dispatch Route displays `/10.177.67.157:36681` (with slash).

### 2. Forensic Evidence
- `DiagnosticModelMapper.kt` (line 55) queries `manager.router.resolveConnectionId(conn.peerId())`.
- `PeerRouter.resolveConnectionId()` (line 164) returns `selected.get().connectionId()`.
- The `connectionId` inside the `ConnectivityPath` was registered by `presenceBridge.handlePeerConnected(remotePeer, connId)` before A.D2.3's transport normalization was fully integrated, or via an unnormalized wrapper path.
- `DiagnosticModelMapper.kt` line 118 strips the slash for path display (`rawConn.substring(1)`), but line 55 renders `resolvedRoute` verbatim, exposing the slash in the router's active path record.

### 3. Root Cause
**Inconsistent Connection ID Representation in Path Registry:** The `ConnectivityPath` record retains a slash-prefixed ID, creating a mismatch between path display and router dispatch route.

### 4. Owning Boundary
**Track A (`PravahAndroidMessagingManager.kt` & `DiagnosticModelMapper.kt`)**