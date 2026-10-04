# A.D2.7 — Root Cause Forensic Report
**Date:** 2026-10-05 02:16  
**Baseline:** vA.D2.6  
**Branch:** feature/sprint-ad2.7  

---

## 1. Executive Summary
Physical testing under vA.D2.6 revealed asymmetric messaging behavior and path inconsistencies during:
1. Simultaneous TCP initiation (both devices tapping CONNECT TCP).
2. Bluetooth -> TCP transport switching and transport drop simulation.

Investigation across Core and Android layers proved the root causes across three architectural boundaries.

---

## 2. Identified Root Causes

### Root Cause 1: Dual-Socket Collision during Simultaneous Initiation (Class C/D)
- **Mechanism:** When Device A connects to Device B and Device B connects to Device A simultaneously:
  - Socket 1 is formed (Client A -> Server B, e.g. connId on A: 192.168.1.10:8080, on B: 192.168.1.20:54321).
  - Socket 2 is formed (Client B -> Server A, e.g. connId on B: 192.168.1.20:8080, on A: 192.168.1.10:54322).
- **Core State Impact:**
  - PeerConnectionCoordinator stores connection mappings in a ConcurrentHashMap<PeerId, String> peerToConnection. When both Socket 1 and Socket 2 deliver JOIN messages, the last JOIN overwrites peerToConnection.
  - PeerConnectivity registers two active paths for scheme 	cp.
  - If one socket suffers a transport drop or half-closure, routing falls over or stalls because the single connection mapping in PeerRegistry and PeerConnectionCoordinator can point to the broken socket while the other remains open.

### Root Cause 2: Reciprocal JOIN Flow & Identity Stabilization (Class B)
- **Mechanism:** When A initiates a connection and sends a JOIN (join-A-B), B's PravahAndroidMessagingManager receives it and automatically responds with eplyJoin (eply-join-B-A).
- **Observation:** If both sides initiate simultaneously, both sides send join-* and both sides reply with eply-join-*, generating 4 JOIN frames across 2 sockets. This creates redundant path activations in PeerPresenceBridge.activatePath().

### Root Cause 3: Incomplete Drop Semantics in Diagnostic Simulator (Class A/B)
- **Mechanism:** In DiagnosticActivity.kt, simulateTransportDrop called conn.addPath(path.deactivate()) locally in PeerConnectivityRegistry, but did not close the underlying socket/stream or notify the peer.
- **Result:** The local device marked the path INACTIVE, but the remote device still believed the path was ACTIVE and continued dispatching frames to it, resulting in perceived asymmetric messaging ( \to B$ failing/buffering, while  \to A$ appeared to work over the unclosed physical socket).

---

## 3. Remediation Strategy
1. **Core Lifecycle Invariant Protection:** Ensure PeerConnectionCoordinator and PeerPresenceBridge handle multi-socket deduplication and path cleanup cleanly.
2. **Android Orchestration & Reciprocal JOIN:** Ensure reciprocal JOIN is idempotent and does not thrash path registry when reciprocal JOINs cross on the wire.
3. **DROP Transport Diagnostics:** Provide clear separation between local path deactivation and transport teardown in the diagnostic layer.
4. **Comprehensive Test Suite:** Unit tests for single-sided TCP/BT, simultaneous TCP connection, and BT->TCP transition.
