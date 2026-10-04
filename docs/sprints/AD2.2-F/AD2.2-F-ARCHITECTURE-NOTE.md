# A.D2.2-F — Architecture & Handoff Note for A.D2.3

## Executive Summary
The forensic audit A.D2.2-F has definitively proven that **Core routing, protocol, and transport layers are sound and defect-free**. 

All 3 reported runtime anomalies are caused exclusively by **Track A (Android layer) orchestration and normalization defects**:
1. Connection ID format discrepancy between UI connect (`"IP:Port"`) and Java sockets (`"/IP:Port"`).
2. Synthetic Bluetooth PeerId string mismatch (`"remote-bt-XXXXXX"` vs `"remote-bt-node"`).
3. Redundant lifecycle invocations in `PravahAndroidMessagingManager.kt`.

## Verified Scope for A.D2.3
No Track B (Core) architectural refactoring is required.

### Targeted Changes for A.D2.3:

```text
A.D2.3 Implementation Scope (Track A Only)
│
├── 1. Connection ID Normalization
│ ├── TcpTransport / PravahAndroidMessagingManager
│ └── Uniformly strip leading '/' from all socket connection IDs
│
├── 2. Single Authoritative Peer Activation
│ ├── PravahAndroidMessagingManager.kt
│ └── Remove redundant handlePeerConnected() calls in connectToTcp(),
│ connectToBluetooth(), sendJoin(), and onMessageReceived()
│
└── 3. Synthetic Bluetooth Peer Cleanup Alignment
├── DiagnosticActivity.kt & PravahAndroidMessagingManager.kt
└── Unify synthetic PeerId prefix and purge routine on onPeerJoined()
```

## Safety Verification
- Working tree clean: YES
- Production changes in A.D2.2-F: 0 (ZERO)
- Core Maven tests green: 22/22 (100%)
