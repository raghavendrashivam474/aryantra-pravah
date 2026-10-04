# A.D2.6 — Validation Evidence

## Automated Forensic Test Suite
- **Suite:** `com.aryntra.pravah.peer.TcpDeliveryForensicInvestigationTest`
- **Environment:** Java 21 / JUnit 5 / Maven Surefire 3.2.5
- **Status:** 3/3 Tests Passing

| Test Case | Target | Invariant Tested | Result |
|---|---|---|---|
| `proveT2_CandidatePathBuffersAndReportsSentWithoutTransportWrite` | T2 | Message buffered in TransitionBuffer reports SENT while transport write count = 0 | ✅ PASSED |
| `proveT3_ResolveConnectionIdReturnsStaleLegacyRouteWhenNoActivePaths` | T3 | PeerRouter.resolveConnectionId returns stale legacy ID when ActivePaths() is empty | ✅ PASSED |
| `proveT1_T4_FallbackToLegacyThrowsNoActiveConnection` | T1, T4 | Empty candidate paths bypass buffer and fail on stale legacy connection ID | ✅ PASSED |

## Connection-ID Consistency Trace

| Boundary | Socket Listener (Server) | Socket Connect (Client) | Resolution Rule |
|---|---|---|---|
| TcpTransport.attachActiveSocket | 
awAddr.startsWith("/") ? sub(1) : rawAddr | 
awAddr.startsWith("/") ? sub(1) : rawAddr | Canonical host:port |
| TcpTransport.lookupConnection | Exact match → Normalized match → Single-conn fallback | Exact match → Normalized match → Single-conn fallback | Slashes stripped |
| CompositeTransport.resolve | connectionTransportMap.get(id) | Scheme prefix / cached map / default fallback | Fast path + prefix |
| PeerPresenceBridge.activatePath | Target scheme matching connection ID format | Target scheme matching connection ID format | Creates/promotes active path |
| PeerRouter.resolveConnectionId | Active path connectionId | Falls back to legacy PeerRegistry | **Identified Stale Fallback Gap** |

## Physical Diagnostics Trace Matrix

| Scenario | State at Send | Buffer Action | Transport Action | UI Event | Remote RX |
|---|---|---|---|---|---|
| S1: Settled Active TCP | ACTIVE | None (Bypass) | Direct socket write | TX (Delivered) | YES |
| S2: Discovered Candidate TCP | CANDIDATE | Buffered in TransitionBuffer | None | TX (Buffered) | NO (Until Activated) |
| S3: Dropped Transport Transition | CANDIDATE | Buffered in TransitionBuffer | None | TX (Buffered) | Flushed on Reconnect |
| S4: Disconnected No Candidate | NONE | Rejected | Legacy fallback → Fail | SEND ERROR | NO |