# SX.4 Current Lifecycle Map

This document maps the actual connection lifecycle of Pravaah in **vB.R3 / main** prior to the integration of SX.4 Security.

## 1. Discovered to Joined Path

```text
[Discovery] PeerDiscoveryListener / LanPeerDiscovery
↓
[Discovery Candidate] PeerConnectionCoordinator.onPeerDiscovered(DiscoveredPeer)
↓
[Establishment] CompositeTransport.connect(EndpointAddress) / TcpTransport.connect()
↓
[Physical Connection] TransportListener.onTransportConnected(PathId, Transport)
↓
[Session Init] ProtocolSessionManager.onSessionStarted(PathId)
↓
[JOIN Message sent] PeerConnectionCoordinator sends MsgType.JOIN
↓
[JOIN Message received] PeerConnectionCoordinator.handleJoinMessage(...)
↓
[Peer Usable] PeerState.JOINED
↓
[Routing/Delivery Active] PeerRouter & DeliveryRetryManager
```
## 2. Key Observations
* **Connectivity = Usable**: As soon as the physical transport is connected and the \JOIN\ message handshakes over the \ProtocolSessionManager\, the Peer is moved to \JOINED\ in \PeerState\.
* **Zero Verification**: No cryptographic identity checks, signature challenges, or trust policies are integrated into the path transition to \JOINED\.
* **Path-Level vs. Peer-Level State**: \PeerConnectivity\ manages active and candidate \ConnectivityPath\ objects, but routing is permitted as soon as the \PeerState\ of the targeted \PeerId\ is \JOINED\.
