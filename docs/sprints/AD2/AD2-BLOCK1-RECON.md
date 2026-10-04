# A.D2 Block 1 — Repository Reconnaissance Report

**Sprint:** A.D2 (Track A — Product Experience)
**Date of Execution:** 2025-07-11
**Objective:** Locate and verify the boundaries between Track A UI, the Android manager wrapper, and Core Java networking interfaces before any codebase mutations.

---

## 1. Initial Repository Inventory

The repository represents a multi-module environment split between core protocol interfaces (written in **Java**) and an Android presentation/wrapper layer (written in **Kotlin**).

### 1.1 File Extensions Profile
- **Java Sources (`.java`):** 156 files (Core protocol, routing, connectivity, reliability, transport).
- **Kotlin Sources (`.kt`):** 20 files (Android Diagnostic UI panels, Activity, and platform bindings).
- **Other Artifacts:** Gradle builds (`.kts`), Markdown docs (`.md`), layout XMLs.

### 1.2 Module Directory Hierarchy

```text
/src/main/java/com/aryntra/pravah/ <-- Core Protocol & Engine (Java)
├── peer/ <-- PeerId, PeerRegistry, TransitionBuffer
├── protocol/ <-- ProtocolListener, PeerState, Message
├── connectivity/ <-- PathState, ConnectivityPath, PathStateListener
├── transport/ <-- Transport, CompositeTransport, TcpTransport
└── messaging/ <-- ApplicationMessage, Reliability/Outbox
/android/app/src/main/java/.../ <-- Android presentation & wrapper (Kotlin)
└── android/
├── DiagnosticActivity.kt <-- UI orchestration & background threads
├── presentation/ <-- Visual panels (NodeStatus, Path, LiveWire...)
└── state/ <-- Immutable DiagnosticState & ModelMapper
```
---

## 2. Conceptual Names vs Actual Implementation Names

Core Java files reside in the root `/src` directory, explaining why initial Kotlin-only fuzzy searches missed them. The conceptual boundaries defined in the sprint document are mapped to actual Java core declarations:

| Sprint Doc Concept | Actual Class/Interface | Full Package Path |
|--------------------|------------------------|-------------------|
| **PeerRouter** | `PeerRouter.java` | `com.aryntra.pravah.peer.PeerRouter` |
| **PathSelectionPolicy** | `PathSelectionPolicy.java` | `com.aryntra.pravah.connectivity.PathSelectionPolicy` |
| **PeerConnectivity** | `PeerConnectivity.java` | `com.aryntra.pravah.connectivity.PeerConnectivity` |
| **ConnectivityPath** | `ConnectivityPath.java` | `com.aryntra.pravah.connectivity.ConnectivityPath` |
| **PathState** | `PathState.java` | `com.aryntra.pravah.connectivity.PathState` |
| **PeerConnectivityRegistry**| `PeerConnectivityRegistry.java`| `com.aryntra.pravah.connectivity.PeerConnectivityRegistry` |
| **TransportListener** | `TransportListener.java` | `com.aryntra.pravah.transport.TransportListener` |
| **PeerConnectionCoordinator**| `PeerConnectionCoordinator.java`| `com.aryntra.pravah.peer.PeerConnectionCoordinator` |
| **TransitionBuffer** | `TransitionBuffer.java` | `com.aryntra.pravah.peer.TransitionBuffer` |

---

## 3. The A.D1 Interface Boundary Analysis

Before coding, we audited the imports of `DiagnosticModelMapper.kt` to inspect the pre-existing integration boundary established in A.D1:

```kotlin
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.protocol.PeerState
```

### Observations:

1. **Low Coupling**: The A.D1 presentation layer was coupled only to `PeerId` and `PeerState`.
2. **Access Path**: Any other core information must be navigated through 
    `PravahAndroidMessagingManager` methods or properties, preventing the UI from spawning direct 
    dependencies on low-level sockets or buffers.

## 4. **Key Discovery**: `The PathStateListener`

The core discovery during Block 1 was PathStateListener.java inside the /connectivity package:

```Java
public interface PathStateListener {
    void onPathStateChanged(PeerId peerId, ConnectivityPath path, PathState previousState);
}
```

This interface is the event-driven boundary needed for Section 11 ("Live Path Transitions"). Rather than 
spawning a polling thread, we can register this listener directly with connectivityRegistry to receive 
real-time path updates.

## 5. TransitionBuffer Metric Signatures

To implement Section 18 (B.R2 Observability), we inspected the public contract of 
`TransitionBuffer.java` to verify exactly what metrics were accessible safely:

- `public int size()` — Current message queue size.
- `public int currentBytes()` — Current byte allocation.
- `public long totalBuffered()` — Cumulative count of buffered frames.
- `public long totalFlushed()` — Cumulative count of flushed frames.
- `public long totalExpired()` — Cumulative count of expired messages.
- `public long totalEvicted()` — Cumulative count of evicted messages.
- `public long totalRejected()` — Cumulative count of rejected frames.

All metrics are exposed via thread-safe lock-guarded read-only calls. This maps directly to a 
GREEN/YELLOW status for the observation map.
