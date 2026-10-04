# A.D2.6 — TCP Delivery Root Cause Report

## Executive Summary
A forensic investigation into TCP application delivery inconsistencies under physical/lifecycle transitions revealed four distinct, interacting behaviors across the architectural boundaries between Track A (DiagnosticActivity, DiagnosticModelMapper), Core Application Messaging (DefaultApplicationMessagingService), and Multi-Path Routing (PeerRouter, PeerPresenceBridge, TransitionBuffer).

## Root Cause Breakdown

### Target T1: TCP Readiness & JOIN Reciprocity
- **Boundary:** DiagnosticActivity.kt / PravahAndroidMessagingManager.kt / PeerConnectionCoordinator.java
- **Mechanism:**
  - In DiagnosticActivity.connectTcp(), after manager.connectToTcp() and manager.sendJoin(), the initiator called manager.replyJoin(targetPeerId).
  - 
eplyJoin() calls 
outer.send(remotePeerId, joinMsg).
  - Because the path on the initiator is not yet ACTIVE (waiting for remote peer's inbound JOIN to be received and processed by presenceBridge.handlePeerConnected), 
outer.send captures the reciprocal JOIN in TransitionBuffer.
  - TransitionBuffer flushes only when PathState.ACTIVE is triggered.
  - This creates an unnecessary transition buffering of protocol control messages.

### Target T2: Application-Level SENT Observability Gap
- **Boundary:** DefaultApplicationMessagingService.java / PeerRouter.java / DiagnosticActivity.kt
- **Mechanism:**
  - When PeerRouter.send() successfully buffers an in-flight message in TransitionBuffer during a connectivity transition window, it returns normally (without throwing an exception) because buffering is the desired resilience strategy.
  - DefaultApplicationMessagingService interprets this as MessageState.SENT.
  - DiagnosticActivity posts an immediate TX event to the Live Wire panel.
  - However, no physical bytes have crossed the socket. If the remote node never establishes an ACTIVE path within the 10-second TTL, the message expires in the buffer. The sender UI shows TX, but the receiver never sees RX.

### Target T3: Dispatch Route Reporting Invariant Violation
- **Boundary:** DiagnosticModelMapper.kt / PeerRouter.java
- **Mechanism:**
  - DiagnosticModelMapper.kt mapped the global peer DISPATCH ROUTE by querying manager.router.resolveConnectionId(conn.peerId()).
  - PeerRouter.resolveConnectionId() fell back to 
egistry.lookup(destination) when ActivePaths() was empty.
  - PeerRegistry held a stale connection ID from a previous connected state.
  - As a result, the UI displayed DISPATCH ROUTE: 10.177.x.x:port even while TCP: CANDIDATE and Bluetooth: INACTIVE (0 active paths).
  - Invariant Requirement: *If no ACTIVE path exists for a peer, DISPATCH ROUTE must evaluate to NONE.*

### Target T4: Transition Buffer Drain vs Disconnected Direct Send
- **Boundary:** PeerRouter.java / TransitionBuffer.java
- **Mechanism:**
  - While candidate paths exist, messages are captured by TransitionBuffer (increasing 	otalBuffered).
  - When all paths become inactive/pruned, candidate list empties. Subsequent messages bypass TransitionBuffer and attempt legacy dispatch, throwing "Cannot send payload: No active TCP connection".
  - The earlier buffered messages expire after 10s, increasing 	otalExpired.
  - Telemetry counters (Buffered: 6, Expired: 4, Flushed: 0) accurately reflect that messages were buffered during the transition window but timed out because no candidate path transitioned to ACTIVE before the TTL elapsed.

## Remediation Classification
- **T3 Fix:** 🟢 **Case A (Track A Local)** — Enforce active path presence invariant in DiagnosticModelMapper.kt.
- **T1 Fix:** 🟢 **Case A (Track A Local)** — Ensure protocol reciprocal JOIN in PravahAndroidMessagingManager uses direct transport send via sendJoin rather than routed send when path is not yet active.
- **T2 Observability:** 🟢 **Case A (Track A Local)** — Clearly delineate buffered vs transmitted status in diagnostic telemetry.