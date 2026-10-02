# Phase 7 — Android / Real Device Runtime
## Sprint Completion Report (S7.1 – S7.3)

### Baseline
| Item | Value |
|---|---|
| Branch | main |
| Version | v0.6.6 |
| HEAD (pre-Phase 7) | 4f41f1d |
| Core Tests (pre) | 319 / 319 passing |
| Core Tests (post) | 319 / 319 passing |
| Regressions | 0 |

---

## S7.1 — Android Runtime Foundation

### Objective
Establish an Android runtime foundation to host Pravah core without modifying core domain models.

### Implemented
- **Android Gradle project** (`android/`) with AGP 8.2.2, Kotlin 1.9.22, compileSdk 34, minSdk 26.
- **`PravahAndroidRuntime.kt`**: Lifecycle adapter mapping Android start/stop to `PravahRuntime` states. Enforces clean re-instantiation from `STOPPED`/`FAILED` states to prevent lifecycle corruption.
- **Core JAR integration**: Android module depends on `pravah-core.jar` via `implementation(files("../libs/pravah-core.jar"))`.

### Architecture Changes
None to core. Additive Android module only.

### Files Added
- `android/build.gradle.kts`, `android/settings.gradle.kts`, `android/gradle.properties`
- `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/com/aryntra/pravah/android/PravahAndroidRuntime.kt`
- `android/app/src/test/java/com/aryntra/pravah/android/PravahAndroidRuntimeTest.kt`

### Tests
| Suite | Count | Result |
|---|---|---|
| Android Runtime Tests | 4 | ✅ Passing |
| Core Regression | 319 | ✅ Passing |

### Definition of Done
- [x] Android project/module exists
- [x] Kotlin Android environment builds
- [x] Pravah core integration boundary exists
- [x] Existing core remains unchanged
- [x] Pravah can initialize from Android
- [x] Pravah can shut down cleanly
- [x] Lifecycle behavior is explicit
- [x] Android-specific code is isolated
- [x] Android tests pass
- [x] Existing 319 tests still pass

---

## S7.2 — Android Networking

### Objective
Enable Android networking using the existing `Transport` contract without leaking socket details to application code.

### Transport Contract Revalidation
| Question | Answer |
|---|---|
| What does Transport own? | Socket lifecycles, reader threads, I/O streams, connection map, event dispatch |
| What does the caller own? | Instantiation, start/stop, listener registration, outbound connect, frame dispatch |
| Who opens connections? | Inbound: `acceptLoop()`. Outbound: caller via `connect(host, port)` |
| Who closes connections? | `stop()`, I/O failure, or remote stream end (`-1`) |
| How are inbound connections delivered? | `TransportListener.onConnectionOpened(connectionId)` |
| How are connection IDs created? | `socket.getRemoteSocketAddress().toString()` |
| How are failures reported? | Unchecked `PravahException` + `onConnectionClosed()` |

### Implemented
- **`PravahAndroidNetworkManager.kt`**: Transport coordinator managing TCP lifecycle, connection tracking, listener dispatch, and `PeerConnectivityRegistry` integration (path promotion on connect, deactivation on disconnect).
- No `AndroidTcpTransport` created — existing `TcpTransport` is reused directly.

### Architecture Changes
None to core. Additive Android networking adapter only.

### Files Added
- `android/app/src/main/java/com/aryntra/pravah/android/PravahAndroidNetworkManager.kt`
- `android/app/src/test/java/com/aryntra/pravah/android/PravahAndroidNetworkTest.kt`

### Tests
| Suite | Count | Result |
|---|---|---|
| Android Network Tests | 4 | ✅ Passing |
| Core Regression | 319 | ✅ Passing |

### Definition of Done
- [x] Android networking implementation exists
- [x] Existing Transport contract preserved
- [x] TCP connection can be established and closed
- [x] Failures propagate correctly
- [x] Resources cleaned up
- [x] Connectivity model remains authoritative
- [x] No Android networking leaks into application layer
- [x] Tests pass
- [x] Existing 319 tests remain green

---

## S7.3 — Android Peer Discovery

### Objective
Bring existing LAN discovery onto Android, preserving wire format, PeerId decoupling, and the Discovery ≠ Connection invariant.

### Implemented
- **`PravahAndroidDiscoveryManager.kt`**: Discovery coordinator orchestrating `LanPeerDiscovery` (UDP broadcast), `PeerPresenceBridge`, `PeerConnectivityRegistry`, and `PeerRegistry`.
- Reused existing `LanPeerDiscovery`, `DiscoveryPacket`, `DiscoveredPeer` — no `AndroidDiscoveryPacket` created.
- Discovery produces `PathState.CANDIDATE` paths with deterministic `PathId` (idempotent across repeated announcements).
- Presence transitions aligned with `PeerPresenceManager` TTL semantics: `AVAILABLE → CONNECTED → AVAILABLE (post-disconnect within TTL) → UNAVAILABLE (after TTL expiry)`.

### Architecture Changes
None to core. Additive Android discovery adapter only.

### Files Added
- `android/app/src/main/java/com/aryntra/pravah/android/PravahAndroidDiscoveryManager.kt`
- `android/app/src/test/java/com/aryntra/pravah/android/PravahAndroidDiscoveryTest.kt`

### Tests (6 Scenarios per Section 29)
| Scenario | Description | Result |
|---|---|---|
| 1 | UDP peer discovery between two nodes | ✅ |
| 2 | Duplicate discovery idempotence | ✅ |
| 3 | Discovery creates CANDIDATE path | ✅ |
| 4 | Connection promotes to ACTIVE | ✅ |
| 5 | Disconnect transitions to INACTIVE (PeerId preserved) | ✅ |
| 6 | Presence: AVAILABLE → CONNECTED → AVAILABLE → UNAVAILABLE | ✅ |

| Suite | Count | Result |
|---|---|---|
| Android Discovery Tests | 6 | ✅ Passing |
| Total Android Tests | 14 | ✅ Passing |
| Core Regression | 319 | ✅ Passing |

### Definition of Done
- [x] Android LAN discovery works
- [x] Existing discovery protocol reused
- [x] Android-specific networking concerns isolated
- [x] Discovered peers receive correct PeerId
- [x] EndpointAddress remains separate from PeerId
- [x] Discovery creates CANDIDATE path
- [x] Connection creates/promotes ACTIVE path
- [x] Disconnect produces INACTIVE path
- [x] Duplicate discovery handled safely
- [x] Existing presence semantics preserved
- [x] Tests pass
- [x] Existing 319 tests remain green

---

## Post-S7.3 Architecture
```text

                PRAVAH
                   │
          ┌────────┴────────┐
          │                 │
    Desktop Runtime    Android Runtime
          │                 │
          │                 │
          └────────┬────────┘
                   │
             Pravah Core
                   │
    ┌──────────────┼──────────────┐
    │              │              │
 Peer System   Reliability   Connectivity
    │                             │
    │                       ┌─────┴─────┐
    │                       │           │
 PeerId                TCP Path    Future Paths
    │
Discovery
    │
Presence
```

## What S7.3 Does NOT Prove (Deferred)
- Android → Android messaging (S7.4)
- Android → ACK / Reliability (S7.4)
- Two physical devices end-to-end (S7.5)

## Documentation Produced
- `docs/sprints/phase7/s7.1-android-runtime-foundation.md`
- `docs/sprints/phase7/s7.2-android-networking.md`
- `docs/sprints/phase7/s7.3-android-peer-discovery.md`
- `docs/architecture/ADR-012-android-runtime-integration.md`
- `docs/sprints/phase7/phase7-sprint-report.md` (this file)

## Next Steps
- **S7.4**: Android Messaging — prove end-to-end message delivery through the full protocol stack on Android.
- **S7.5**: First Real APK Validation — deploy to two physical Android devices and validate LAN discovery + messaging.
