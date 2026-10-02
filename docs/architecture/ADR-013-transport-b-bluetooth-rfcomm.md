# ADR-013: Transport-B Selection — Bluetooth RFCOMM

**Status:** Accepted
**Date:** 2026-10-02
**Phase:** Phase 8, Sprint S8.1
**Baseline:** vS7.5-diagnostic @ f0ee5c3

## Context

Phase 7 validated Pravaah as a real Android P2P node over TCP/IP on LAN.
Phase 8 introduces a second, heterogeneous transport to enable multi-path
connectivity. S8.1 requires a deliberate Transport-B selection based on
evidence, not speculation.

## Evaluation Matrix

| Criterion              | BLE (GATT)        | Bluetooth RFCOMM  | QUIC              | WebRTC            |
|------------------------|-------------------|-------------------|-------------------|-------------------|
| Heterogeneity          | High (radio)      | High (radio)      | Low (still IP)    | Medium (IP+ICE)   |
| Android support        | Native, complex   | Native, simple    | Requires library  | Requires library  |
| PeerId mapping         | MAC address       | MAC address       | IP+cert           | ICE candidate     |
| Addressing             | UUID+MAC          | Channel+MAC       | IP:port           | SDP blob          |
| ConnectivityPath fit   | Awkward (GATT)    | Natural (stream)  | Natural           | Awkward           |
| Testability            | Hard (GATT mock)  | Moderate          | Moderate          | Hard (ICE/STUN)   |
| Implementation scope   | Large             | Small-Medium      | Medium            | Large             |
| Future value           | IoT sensors       | Offline P2P       | Internet P2P      | NAT traversal     |
| Discovery              | BLE scan          | BT discovery      | mDNS/QUIC         | Signaling server  |
| Failure behavior       | Clean             | Clean             | Clean             | Complex           |

## Decision

**Transport-B = Bluetooth RFCOMM**

### Rationale

1. **Genuinely heterogeneous.** RFCOMM operates over the Bluetooth radio
   stack, completely independent of TCP/IP. This gives Pravaah real
   multi-path diversity: WiFi LAN + Bluetooth.

2. **Natural fit with existing Transport contract.** RFCOMM provides
   stream-oriented sockets (BluetoothSocket) with getInputStream()
   and getOutputStream(), mapping directly to the existing
   Transport.send(destinationId, payload) and TransportListener
   model. No datagram-to-stream adaptation needed.

3. **EndpointAddress compatibility.** The existing
   `EndpointAddress(host, port, transportScheme)` record works:
   - `host` = Bluetooth MAC address (e.g., `"AA:BB:CC:DD:EE:FF"`)
   - `port` = RFCOMM channel (1-30, fits 0-65535 validation)
   - `transportScheme` = `"bluetooth"`

4. **No internet infrastructure.** RFCOMM requires no STUN, TURN,
   signaling server, or cloud rendezvous. Two phones in a room work.

5. **Android-native.** `android.bluetooth.BluetoothAdapter` and
   `BluetoothServerSocket` are part of the Android SDK. No third-party
   dependencies required.

6. **S7.5 evidence alignment.** The S7.5 physical debugging log showed
   LAN connectivity fragility (WiFi sleep, IP changes, subnet isolation).
   Bluetooth provides a completely independent physical layer that
   survives WiFi failures.

7. **Realistic scope.** RFCOMM server+client is implementable within
   S8.2-S8.3 by a junior developer. BLE GATT would require service
   definitions, characteristic negotiation, and MTU fragmentation
   logic that would consume the entire phase.

### Rejected alternatives

- **BLE GATT:** Too complex for initial hybrid transport. GATT's
  request/response model doesn't map cleanly to the streaming
  `Transport` contract. Better suited for a future Phase 9+ IoT
  transport.

- **QUIC:** Still IP-based, so not meaningfully heterogeneous.
  Requires a QUIC library (no native Java/Android support).
  Valuable for future internet P2P but not for Phase 8's goal.

- **WebRTC:** Requires ICE/STUN/TURN infrastructure and a signaling
  channel. Violates the S8.3 constraint of no internet infrastructure.
  Valuable for future NAT traversal phase.

## Known Architectural Gap

`PeerRouter` currently holds a single `Transport transport` field:

```java
private final Transport transport;
```

This means the router can only send through one transport at a time.
This gap will NOT be resolved in S8.1. The planned approach for
S8.2-S8.3 is a CompositeTransport adapter that implements the
existing Transport interface while delegating to multiple
concrete transports internally. This preserves the PeerRouter
contract without rewriting it.

## Consequences

- S8.2 will implement `BluetoothRfcommTransport` behind the
  existing `Transport` interface.
- S8.3 will extend discovery to produce Bluetooth address candidates.
- The `EndpointAddress` record requires no modification.
- The `ConnectivityPath` model requires no modification.
- `PeerConnectivityRegistry` already supports multi-path per peer.
- A `CompositeTransport` will be needed before S8.4 messaging.

## Compatibility

- All 342 existing tests remain unaffected.`
- No changes to `TcpTransport`, `PeerRouter`, or protocol layer.
- Phase 6/7 invariants preserved.
