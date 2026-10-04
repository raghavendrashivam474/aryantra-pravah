# A.D2.7 — Validation Matrix

| Scenario | Initiator | BT Path | TCP Path | Expected Behavior | Observed Result | Status |
|---|---|---|---|---|---|---|
| **S1.1 BT Baseline** | A | ACTIVE | — | Bidirectional messaging A <-> B | Path ACTIVE, payload delivered via BT | Verified (Unit + BT-PV Baseline) |
| **S1.2 LAN Baseline** | A | — | ACTIVE | Bidirectional messaging A <-> B | Path ACTIVE, payload delivered via TCP | Verified (ConnectionLifecycleStabilizationTest) |
| **S2.1 BT Simultaneous** | A + B | ACTIVE | — | Single logical peer session | Multi-path registered, single session preserved | Verified |
| **S2.2 TCP Simultaneous** | A + B | — | ACTIVE | 2 sockets registered, deterministic path selection | Both paths tracked, router selects highest priority | Verified (ConnectionLifecycleStabilizationTest) |
| **S3.1 BT -> TCP Transition** | A | ACTIVE -> DROP | ACTIVE | Seamless switch to TCP upon activation; BT drop handled | Route shifts to TCP; BT dropped cleanly | Verified (ConnectionLifecycleStabilizationTest) |
| **S3.2 BT -> TCP Simultaneous** | A + B | ACTIVE -> DROP | ACTIVE | No orphaned paths, valid session | Authoritative cleanup on socket close | Verified |
| **S4 Asymmetric Trace** | A or B | Evaluated | Evaluated | Physical transport close eliminates state divergence | EOF propagated on drop; no zombie sockets | Verified (dropTransport integration) |