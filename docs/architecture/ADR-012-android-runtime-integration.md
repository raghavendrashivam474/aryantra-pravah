# ADR-012: Android Runtime Integration Strategy

## Status
Accepted (Phase 7, S7.1–S7.3)

## Context
Aryntra Pravah (v0.6.6) is a single-module project targeting Java 21 with a complete
peer-to-peer communication stack: Transport (TCP), Protocol (framing/encoding), Peer System,
LAN Discovery (UDP broadcast), Presence, Connectivity (multi-path), Reliability (outbox/retry),
and Application Messaging.

Phase 7 introduces Android devices into the Pravah topology. Android requires ART execution,
lifecycle-aware component interactions, Gradle build semantics, and Kotlin-first idioms, while
the existing Pravah core is pure platform-independent Java.

The central architectural rule is: **Android is a runtime environment, not a new Pravah architecture.**

## Proposed Alternatives

### Alternative 1: Fork and Re-architect for Android
Reimplement the Pravah stack natively in Android using Android framework APIs (`android.net.wifi`, `NsdManager`, etc.).
- *Drawback*: Duplicates core domain logic, fractures test suites, creates architectural divergence, and breaks the universal Pravah protocol invariant.

### Alternative 2: Multi-Module Maven with Android Profiles
Attempt to configure Maven to compile Android artifacts alongside core Java.
- *Drawback*: Maven Android plugins are deprecated and lack native Android Studio / AGP 8+ toolchain support.

### Alternative 3: Additive Android Gradle Module referencing Core JAR (Selected)
Establish an `android/` application module built with Gradle and Android Gradle Plugin (AGP) that consumes the compiled `pravah-core.jar` as a direct dependency. Android-specific adapters (`PravahAndroidRuntime`, `PravahAndroidNetworkManager`, `PravahAndroidDiscoveryManager`) wrap the core contracts without modifying core internal logic.
- *Benefit*: 100% backward compatibility, zero churn on the 319 existing core tests, standard Android developer experience, and clear architectural boundaries.

## Decision
We select **Alternative 3: Additive Android Gradle Module**.

### Layering Model
┌─────────────────────────────────────────────────────────────┐
│ Android Application │
└──────────────────────────────┬──────────────────────────────┘
│
┌──────────────────────────────▼──────────────────────────────┐
│ Android Runtime Adapters │
│ - PravahAndroidRuntime (Lifecycle & Re-instantiation) │
│ - PravahAndroidNetworkManager (Transport Coordinator) │
│ - PravahAndroidDiscoveryManager (LAN Discovery & Presence) │
└──────────────────────────────┬──────────────────────────────┘
│
┌──────────────────────────────▼──────────────────────────────┐
│ Pravah Core JAR │
│ - Transport Contract (TcpTransport) │
│ - Protocol & Framing (Wire Format v1) │
│ - Peer System (PeerId, PeerRegistry, PeerRouter) │
│ - Connectivity (PeerConnectivityRegistry, Paths) │
│ - Presence & Bridge (PeerPresenceBridge, Manager) │
│ - Reliability (DeliveryOutbox, DeliveryRetryManager) │
└─────────────────────────────────────────────────────────────┘

text


## Architectural Invariants Preserved
1. **Identity Decoupling**: `PeerId` remains logical (WHO). It is never equated to IP address, MAC address, `PathId`, `ConnectionId`, or Android device ID.
2. **Discovery ≠ Connection**: Discovered peers register a `PathState.CANDIDATE` path in `PeerConnectivityRegistry`. Paths are promoted to `PathState.ACTIVE` only upon successful transport connection.
3. **Application Routing**: Applications send messages to logical `PeerId` destinations via `PeerRouter`, never to physical socket endpoints.
4. **Presence Lifecycle**: Android lifecycle changes do not automatically alter peer network presence; network reachability and TTL remain authoritative.
5. **Universal Protocol**: Uses the exact same wire framing and message serialization across desktop and Android runtimes.

## Verification Strategy
- **S7.1**: `PravahAndroidRuntimeTest` verifies clean initialization, startup, shutdown, and repeated lifecycle re-instantiation.
- **S7.2**: `PravahAndroidNetworkTest` verifies TCP transport lifecycle, connection failures, bidirectional byte exchange, and path activation/deactivation.
- **S7.3**: `PravahAndroidDiscoveryTest` verifies UDP LAN discovery, duplicate announcement idempotence, candidate path creation, active promotion, inactive transitions, and presence TTL state changes.
- **Core Regression**: All 319 core Java tests must continuously pass with 0 failures or errors.
