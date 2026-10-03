# Pravaah Security Surface Analysis
## SX.1 — Threat Model & Security Principles

> **Generated from:** commit a3cf7fb (main)
> **Sprint:** SX.1 — Threat Model & Security Principles
> **Status:** Initial evidence-based analysis

---

## 1. Security Surface Table (Section 11)

| Component | Current Role | Security Meaning Today | Security Question |
|-----------|-------------|----------------------|-------------------|
| **PeerId** | Logical peer identity (UUID string) | Identifier only. No cryptographic binding. Generated via `UUID.randomUUID()`. Not persistent across restarts. | How does identity become authentic? Any peer can claim any PeerId. |
| **PathId** | Unique path identifier (UUID string) | Identifies a specific route to a peer. Deterministic derivation from peer+scheme+host+port in `DiscoveredAddressCandidate.toPathId()`. | Can an attacker predict or collide PathIds? Deterministic derivation means predictable. |
| **EndpointAddress** | Network location (host:port:scheme) | Raw addressing. TCP uses IP+port. Bluetooth uses MAC+channel. No verification that the address belongs to the claimed peer. | Can endpoint addresses be spoofed? Yes — both IP and MAC are spoofable at their respective layers. |
| **ConnectivityPath** | Binds PeerId to transport endpoint | Immutable record linking WHO (PeerId) + HOW (transport) + WHERE (endpoint) + STATE. States: CANDIDATE → ACTIVE → INACTIVE. **No TRUSTED/VERIFIED state exists.** | At what point is a path considered trustworthy? Currently: never explicitly. ACTIVE means connected, not verified. |
| **DiscoveredAddressCandidate** | Discovery → Connectivity bridge | Converts raw discovery data into a ConnectivityPath via `toCandidatePath()` with **zero authentication**. Self-declared PeerId from discovery payload is accepted as-is. | Can discovery inject fake peers? Yes. A rogue device can broadcast any PeerId via LAN UDP. |
| **LanPeerDiscovery** | UDP broadcast discovery | Sends/receives peer announcements via `DatagramSocket`. `processIncomingData()` accepts PeerId from the UDP payload without verification. | Can an attacker inject fake discovery announcements? Yes. UDP is unauthenticated. Any LAN device can broadcast. |
| **BluetoothPeerDiscovery** | Bluetooth device discovery | Uses Android Bluetooth adapter for device scanning. | Does Bluetooth presence imply trust? No, but the current architecture has no mechanism to distinguish trusted from untrusted Bluetooth peers. |
| **PeerRouter** | Logical message routing | Routes messages by PeerId through `PeerConnectivityRegistry` + `PathSelectionPolicy`. Uses `Transport.send(String destinationId, byte[])` — the transport layer receives a raw string, not a verified identity. | How is routing bound to authenticated identity? Currently: it isn't. Routing trusts the PeerId string. |
| **CompositeTransport** | Multi-path transport aggregation | Implements `Transport`. Aggregates multiple sub-transports. `onDataReceived(String senderId, byte[] payload)` accepts senderId from the transport layer without cross-verification. | Can one compromised path affect session trust? Potentially yes — all paths feed into the same listener with no per-path authentication. |
| **Transport (interface)** | Transport contract | Minimal: `send(String, byte[])`, `setListener()`, lifecycle. **No authentication hooks. No encryption hooks. No identity verification methods.** | Where should secure session semantics live? Not in the current interface. The contract is purely about connectivity. |
| **Message** | Protocol message (record) | Immutable: type + senderId + messageId + payload. `senderId` is a self-declared string in the payload. Defensive copies on payload. | Can senderId be forged? Yes. The senderId is carried inside the message payload and is not verified against the transport connection. |
| **MessageEncoder** | Binary serialization | Wire format: `PR` magic + version 0x01 + type code + senderId (UTF-8, length-prefixed) + messageId + payload. **No checksum. No MAC. No signature. No encryption.** | Can an attacker craft a valid-looking message? Yes. The format is fully deterministic and publicly documented. Any byte sequence matching the format will be accepted. |
| **MessageParser** | Binary deserialization | Strict parsing: validates magic, version, type code, length fields, trailing bytes. Rejects malformed frames. But **trusts all semantic content** (senderId, messageId, payload) after structural validation. | Can a structurally valid but semantically forged message be accepted? Yes. Parser validates structure, not authenticity. |
| **FrameEncoder/FrameDecoder** | Stream framing layer | Separate from message encoding. Handles TCP stream framing (length-prefix for message boundaries). | Does framing provide any security? No. Framing solves stream reassembly, not authenticity. |
| **ProtocolSessionManager** | Protocol state machine | Tracks `PeerState` per peerId string. Handles JOIN/MESSAGE/LEAVE transitions. `processMessage()` dispatches based on `message.senderId()` — the self-declared field. | Does "session" imply trust? No. Session state tracks protocol liveness (joined/left), not authentication. |
| **Messaging Layer** | Application messaging (24 files) | Full application messaging: conversations, group conversations, message history (SQLite + in-memory), delivery reliability (outbox, retry, ACK). | Where should secure session semantics live? The messaging layer assumes the protocol layer delivers authentic messages. That assumption is currently unfounded. |
| **Android Runtime** | Mobile platform integration | `PravahAndroidMessagingManager` bridges Android ↔ Pravaah core. `AndroidBluetoothRfcommTransport` provides Bluetooth RFCOMM. `DiagnosticActivity` shows connectivity. | Where should human verification occur? The DiagnosticActivity currently shows topology for human observation. Future pairing UI would extend this. |
| **Android Permissions** | OS-level capability grants | INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE, BLUETOOTH (full stack: SCAN/ADVERTISE/CONNECT), ACCESS_FINE_LOCATION, **DUMP**. | Which permissions expand the attack surface? DUMP is notable — allows system diagnostic data access. BLUETOOTH_ADVERTISE means this device is discoverable. |

---

## 2. Key Evidence Summary

### What IS protected today
- **Structural integrity**: MessageParser rejects malformed frames (bad magic, wrong version, truncated data, trailing bytes)
- **Defensive copies**: Message record clones payload arrays
- **Null/blank validation**: All identity and address fields reject null/blank values
- **Path state machine**: Explicit CANDIDATE → ACTIVE → INACTIVE lifecycle

### What is NOT protected today
- **Identity authenticity**: PeerId is self-declared; no binding to device, key, or credential
- **Message authenticity**: senderId in payload is trusted without verification
- **Message integrity**: No checksum, MAC, or signature on the wire format
- **Message confidentiality**: Payload is plaintext on the wire
- **Replay resistance**: No nonce, timestamp, or sequence number in the protocol
- **Discovery authenticity**: LAN UDP broadcasts are unauthenticated
- **Path trust**: No mechanism to verify that a path endpoint actually belongs to the claimed peer
- **Session binding**: ProtocolSessionManager tracks liveness, not authenticated sessions

---

## 3. Immediate Threat Observations

1. **Identity Spoofing (Critical)**: Any device can generate any PeerId and announce it via LAN discovery. The entire system will route messages to/from the spoofed identity.

2. **Message Forgery (Critical)**: An attacker who can inject bytes into any transport (TCP or Bluetooth) can construct a valid Message with any senderId, any messageId, and any payload. MessageParser will accept it.

3. **Replay Attacks (High)**: Captured valid messages can be re-injected. There is no freshness mechanism.

4. **Passive Eavesdropping (High)**: All payloads are plaintext. Any observer on the LAN or Bluetooth range can read all messages.

5. **Discovery Poisoning (Medium)**: A rogue device on the LAN can broadcast fake discovery announcements, causing peers to attempt connections to attacker-controlled endpoints.

6. **Path Confusion (Medium)**: Since CompositeTransport aggregates all paths without per-path authentication, a compromised path could inject messages that appear to come from a legitimate peer.

---

*This document will be expanded with formal threat model, attacker classes, trust boundaries, and security principles in subsequent SX.1 blocks.*