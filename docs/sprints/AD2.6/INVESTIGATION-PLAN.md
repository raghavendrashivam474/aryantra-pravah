# A.D2.6 — TCP Delivery Deep Inspection & Surgical Remediation

> **Hard Boundary:** Do not optimize for making the error disappear.
> Optimize for making the lifecycle explainable.

## Classification
Investigation → Evidence → Conditional Surgical Remediation

## Baseline
- Commit: `e142a47`
- Prior sprint: A.D2.5 (DROP transport UI + path deactivation)

## Investigation Targets & Forensic Results

### T1 — TCP Readiness Gap
- **Symptom:** TCP socket active + JOIN sent → application send fails with "No active TCP connection"
- **Proven Root Cause:** 
  1. TCP initiator sends JOIN via raw compositeTransport.send(connId, ...) but PeerPresenceBridge only activates the ConnectivityPath when an *inbound* JOIN is parsed by PeerConnectionCoordinator.handleInboundMessage.
  2. The initiator called manager.replyJoin(targetPeerId) which invokes 
outer.send(). Because the path was still CANDIDATE, the reciprocal JOIN was buffered in TransitionBuffer.
  3. TransitionBuffer requires path activation to flush, creating a mutual dependency / transition-window lock if the remote peer's reply is delayed.
- **Status:** ✅ Completed & Empirically Proven (proveT1_T4_FallbackToLegacyThrowsNoActiveConnection)

### T2 — TCP Application Delivery Gap
- **Symptom:** TX observed in UI, RX not observed on remote diagnostic node
- **Proven Root Cause:** 
  1. In DefaultApplicationMessagingService.send(), when PeerRouter.send() captures an outbound payload in TransitionBuffer (due to CANDIDATE path state), PeerRouter.send() returns cleanly without throwing an exception.
  2. DefaultApplicationMessagingService treats the non-exceptional return as successful dispatch, updating the message state to MessageState.SENT.
  3. DiagnosticActivity.sendPayloadMessage() posts a TX event to the dashboard.
  4. However, zero bytes reached the physical network wire; the payload remains buffered in TransitionBuffer awaiting path activation. If the path does not activate within 10 seconds, the message silently expires (TransitionBuffer.totalExpired).
- **Status:** ✅ Completed & Empirically Proven (proveT2_CandidatePathBuffersAndReportsSentWithoutTransportWrite)

### T3 — Dispatch Route Consistency
- **Symptom:** Stale dispatch route shown when no ACTIVE path exists (e.g. Bluetooth: INACTIVE, TCP: CANDIDATE, DISPATCH ROUTE: 10.177.x.x:port)
- **Proven Root Cause:**
  1. DiagnosticModelMapper.kt (line 55) queries manager.router.resolveConnectionId(conn.peerId()).
  2. PeerRouter.resolveConnectionId() checks connectivityRegistry active paths. When ActivePaths() is empty, it falls back to legacy PeerRegistry.lookup(destination).
  3. PeerRegistry retains the historical connection ID where 
ecord.isConnected() is still true from initial registration.
  4. 
esolveConnectionId() returns this legacy string, and DiagnosticModelMapper displays it under DISPATCH ROUTE despite conn.hasActivePath() being False.
- **Status:** ✅ Completed & Empirically Proven (proveT3_ResolveConnectionIdReturnsStaleLegacyRouteWhenNoActivePaths)

### T4 — Transition Buffer vs Direct-Send
- **Symptom:** Buffer counters increment/expire (Buffered: 6, Expired: 4, Flushed: 0) while separate message throws No active TCP connection.
- **Proven Root Cause:**
  1. Messages submitted while candidate paths exist enter TransitionBuffer.offer() and increment 	otalBuffered.
  2. If candidate paths are subsequently pruned/dropped, the candidate list becomes empty.
  3. Subsequent messages bypass TransitionBuffer (because candidatePaths().isEmpty()) and fall back to legacy PeerRegistry -> CompositeTransport -> TcpTransport.send().
  4. Since the TCP socket is closed or mismatching, TcpTransport.lookupConnection() returns null and throws "Cannot send payload: No active TCP connection to <dest>".
  5. The earlier buffered messages expire after 10s TTL, incrementing 	otalExpired.
- **Status:** ✅ Completed & Empirically Proven (proveT1_T4_FallbackToLegacyThrowsNoActiveConnection)