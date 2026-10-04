# A.D2.3-T — Physical & Static Validation Evidence

**Track:** Track A — Product Experience / Diagnostic Runtime  
**Sprint:** A.D2.3-T — Forensic Investigation  
**Baseline:** `vA.D2.3` (`b9c8976`)  
**Mode:** Static Code & Runtime Lifecycle Trace  

---

## 1. End-to-End Trace of Message Dispatch (`PRAVAAH-TCP-001`)

The table below maps the progression of a sample message `PRAVAAH-TCP-001` across every architectural boundary:

| Boundary # | Layer | Component / Method | Observed State / Value | Status | Evidence |
|---|---|---|---|---|---|
| **B1** | UI | `DiagnosticActivity.sendPayloadMessage()` | Triggered on UI thread, dispatches to background executor | ✅ OK | `DiagnosticActivity.kt:397-407` |
| **B2** | Manager | `PravahAndroidMessagingManager.sendText()` | Delegates to `messaging.sendText(destination, content)` | ✅ OK | `PravahAndroidMessagingManager.kt:216` |
| **B3** | Application Service | `DefaultApplicationMessagingService.sendText()` | Creates `ApplicationMessage`, encodes to `Message`, frames via `FrameEncoder` | ✅ OK | `DefaultApplicationMessagingService.java:180` |
| **B4** | Routing Authority | `PeerRouter.send(destination, message)` | Resolves active paths from `connectivityRegistry` via `PathSelectionPolicy` | ✅ OK | `PeerRouter.java:97-111` |
| **B5** | Path Selection | `PathSelectionPolicy.selectPath()` | Selects primary active `ConnectivityPath` | ⚠️ AMBIGUOUS | Path retains slash-prefixed `connectionId` (`/10.177.67.157:36681`) |
| **B6** | Composite Dispatch | `CompositeTransport.send(destinationId, payload)` | Calls `resolveTransportForDestination(destinationId)` | ❌ FLAWED | `connectionTransportMap.get("/10...")` MISSES (map key is canonical `10...`); falls back to `transports.get(0)` |
| **B7** | Transport Send | `TcpTransport.send(destinationId, payload)` | Calls `lookupConnection(destinationId)` | ⚠️ FALLBACK | Exact match misses; finds socket via normalization fallback `replace("/", "")` or `size() == 1` |
| **B8** | Socket Output | `Socket.getOutputStream().write(payload)` | Socket writes framed bytes over physical TCP link | ✅ OK | Socket active |
| **B9** | Remote Socket Input | `TcpTransport.readLoop()` (Device B) | Receives raw bytes from socket input stream | ✅ OK | Sockets connected |
| **B10** | Transport Fan-Out | `TransportListener.onDataReceived()` | Forwards `senderId` (`10.177.67.X:port`) and raw bytes to coordinator | ✅ OK | `TcpTransport.java:270` |
| **B11** | Protocol Parsing | `PeerConnectionCoordinator.onDataReceived()` | `FrameDecoder` decodes bytes into `Message(type=MESSAGE, ...)` | ✅ OK | `PeerConnectionCoordinator.java:127` |
| **B12** | Inbound Handler | `PeerConnectionCoordinator.handleInboundMessage()` | Evaluates `MessageType.MESSAGE` -> forwards to `sessionManager.onMessageReceived()` | ✅ OK | `PeerConnectionCoordinator.java:184` |
| **B13** | Coordinator Fan-Out | `PeerConnectionCoordinator.sessionManager.setListener()` | Forwards to `protocolListener.onMessageReceived()` | ❌ BROKEN | `protocolListener` is `DiagnosticActivity`'s listener, NOT `DefaultApplicationMessagingService`! |
| **B14** | Application Service | `DefaultApplicationMessagingService.handleInboundProtocolMessage()` | **NEVER INVOKED** (listener was overwritten in Step B13) | ❌ DEAD | `handleInboundChat` never fires |
| **B15** | App Listener | `ApplicationMessageListener.onMessage()` | **NEVER INVOKED** | ❌ DEAD | Listener queue not triggered |
| **B16** | Diagnostic RX Event | `DiagnosticActivity` RX LiveWire display | **NEVER POSTED** (No RX event appears on screen) | ❌ MISSING | Symptom O3 confirmed |

---

## 2. Boundary Failure Summary

The physical symptom:
```text
TX: [android-12345678] PRAVAAH-TCP-001
(Expected RX never appears on remote device)
```

is definitively proven to halt at **Boundary B13 → B14**.

The physical socket receives the bytes, protocol decoding succeeds, but the coordinator's single `ProtocolListener` reference was overwritten by `DiagnosticActivity`, orphaning `DefaultApplicationMessagingService` from receiving inbound message notifications.