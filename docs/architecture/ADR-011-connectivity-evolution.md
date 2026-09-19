# ADR-011 — Connectivity Evolution Model (S6.1–S6.3)

## Status
Accepted

## Context

Through Phase 5, Pravah's peer routing model was:

```text
PeerId → PeerRecord(peerId, connectionId) → Transport.send(connectionId, bytes)
```

This conflates "how to reach a peer" with a single raw TCP connection ID.
Future transports (BLE, QUIC, Internet relay) require representing multiple
candidate and active paths per peer independently from any single socket.

HPCA demonstrated a useful conceptual separation:
`Identity ≠ Address ≠ Reachability ≠ Connection ≠ Session`.
This ADR adopts the minimum useful subset for Pravah without importing HPCA code.

## Decision

### S6.1 — Connectivity Model
Introduce `com.aryntra.pravah.connectivity` package with:

- `EndpointAddress` — immutable value: host + port + transport scheme.
  Represents WHERE a peer can be contacted, independent of active connections.
- `PathId` — unique identifier for a communication path instance.
- `PathState` — enum: `CANDIDATE`, `ACTIVE`, `INACTIVE`.
- `ConnectivityPath` — immutable record binding PathId, PeerId, transport name,
  EndpointAddress, PathState, and optional connectionId.
- `PeerConnectivity` — aggregates paths for a single PeerId.

### S6.2 — Multi-Path Peer Representation
Introduce `PeerConnectivityRegistry` mapping `PeerId → PeerConnectivity`.
- One PeerId can have multiple concurrent paths (different transports/endpoints).
- Removing one path does not delete the peer or other paths.
- Fully additive: existing `PeerRecord`/`PeerRegistry`/`PeerRouter` unchanged.

### S6.3 — Transport Capability Model
Introduce `TransportCapabilities` immutable record with four boolean properties:
- `reliable` — guaranteed in-order loss-free delivery (TCP: true)
- `connectionOriented` — requires handshake lifecycle (TCP: true)
- `unicast` — 1-to-1 endpoint addressing (TCP: true)
- `supportsMultiplexing` — native channel multiplexing (TCP: false)

Add `default TransportCapabilities getCapabilities()` to `Transport` interface.
`TcpTransport` overrides to return `TransportCapabilities.tcp()`.
This is a backward-compatible additive change (Java default method).

## Architecture
```text

                     PeerId
                        │
                 PeerConnectivity
                        │
            ┌───────────┼───────────┐
            │           │           │
       Path A        Path B       Path C
       (TCP)        (TCP)        (BLE)
         │            │            │
     ACTIVE       CANDIDATE    CANDIDATE
     conn-01      10.0.0.5     AA:BB:CC
```

Existing flow remains valid:
```text
Application → Reliability → PeerRouter → PeerRegistry → Transport → TCP
```


## What Was NOT Changed
- `PeerId`, `PeerRecord`, `PeerRegistry`, `PeerRouter` — untouched.
- `Transport` interface — only additive default method added.
- Protocol, messaging, reliability layers — untouched.
- No path selection, ranking, or automatic failover implemented.

## HPCA Comparison
| Concept       | HPCA                    | Pravah S6.1–S6.3              |
|---------------|-------------------------|-------------------------------|
| Identity      | Separate object         | Existing `PeerId` (unchanged) |
| Address       | Rich endpoint model     | `EndpointAddress` (minimal)   |
| Reachability  | Scored path matrix      | `PathState` enum (no scoring) |
| Connection    | Full lifecycle machine  | `connectionId` string (existing) |
| Session       | Protocol-level          | `PeerState` (existing, unchanged) |

Pravah adopts the separation without the scoring/ranking complexity.

## Consequences
- Future transports can register paths without modifying peer or routing code.
- Transport capabilities enable informed routing decisions in later sprints.
- No breaking changes to any existing Phase 2–5 contracts.
