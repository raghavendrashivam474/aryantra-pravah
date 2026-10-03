# SX.3 - Authentication Inventory

**Track:** C - Security Evolution
**Capability:** SX.3 - Authentication Handshake and Proof of Possession
**Baseline:** vSX.2
**Status:** INSPECTION COMPLETE - Ready for implementation

---

## 1. Current Identity Claim Mechanism

### Where does a peer currently claim its identity?

In the JOIN message senderId field. The senderId is a raw UTF-8 string
in UUID format from PeerId.value(). There is no cryptographic binding
between the senderId and any key material. Any peer can claim any senderId.

Source: PeerConnectionCoordinator.handleInboundMessage()

### Where does the receiving peer learn that identity?

PeerConnectionCoordinator.handleInboundMessage() creates a PeerId from
the raw senderId string and immediately binds it to the transport
connection. No verification occurs. The log even says "Peer successfully
authenticated and connected" despite zero actual authentication.

### When does the existing session become JOINED?

Immediately upon processing the JOIN message in
ProtocolSessionManager.handleJoin(). There is zero gap between
"transport connected" and "session joined." This is the primary
security gap SX.3 addresses.

---

## 2. Protocol Architecture Summary

### Wire Format

Offset  Size   Field
0       2      Magic: PR 0x50 0x52
2       1      Version: 0x01
3       1      MessageType code
4       2      senderId length unsigned short
6       N      senderId UTF-8
6+N     2      messageId length unsigned short
8+N     M      messageId UTF-8
8+N+M   4      payload length int
12+N+M  P      payload raw bytes

### MessageType Codes

Code   Type      Status
0x01   JOIN      Active
0x02   MESSAGE   Active
0x03   LEAVE     Active
0x04   unused    Available
0x05   unused    Available

### PeerState Enum

JOINED - Peer sent valid JOIN
LEFT   - Peer sent valid LEAVE

No authentication states exist.

### ProtocolListener Callbacks

onPeerJoined      - JOIN processed
onMessageReceived - MESSAGE processed
onPeerLeft        - LEAVE processed

No onPeerAuthenticated callback exists.

---

## 3. SX.2 Cryptographic Primitives Available

All in com.aryntra.pravah.security.identity:

**SignatureService**:
  sign IdentityKeyPair byte[] returns byte[]
  verify CryptographicIdentity byte[] byte[] returns boolean
  verify PublicKey byte[] byte[] returns boolean

**CryptographicIdentity**:
  of PeerId PublicKey
  peerId returns PeerId
  publicKey returns PublicKey
  encodedPublicKey returns byte[] X.509 DER

**IdentityKeyPair**:
  publicKey returns PublicKey
  privateKey returns PrivateKey

**PublicKeyCodec**:
  encode PublicKey returns byte[]
  decode byte[] returns PublicKey

**IdentityGenerator**:
  generate returns IdentityKeyPair

**PeerId**:
  of String returns PeerId
  generate returns PeerId
  value returns String UUID

**Algorithm**: Ed25519 RFC 8032 Java 15+ JCA
Signature size: 64 bytes
Public key encoding: X.509 SubjectPublicKeyInfo DER approx 44 bytes

---

## 4. Critical Inventory Answers

### Where could proof-of-possession be introduced?

Conceptually between transport connection and JOIN processing.
Practically for SX.3 Core: as a standalone capability in
security/authentication/ independent of protocol session lifecycle.

### Does the existing protocol have a challenge mechanism?

NO. MessageType has no AUTH types. PeerState has no auth states.
ProtocolSessionManager has no auth state machine.

### Can SX.3 be implemented above existing transports?

YES. Transport operates on raw byte arrays. Authentication logic
can operate entirely above this boundary.

### Does the protocol need a new message type?

YES for full protocol integration. But adding new MessageType codes
would break backward compatibility because MessageType.fromCode
throws IllegalArgumentException for unknown codes. Therefore SX.3
Core proceeds without protocol wire changes.

### Would changing the protocol break existing peers?

YES. Old peers would throw ProtocolException on unknown type codes.
The frame would be dropped. This is survivable but must be documented.

---

## 5. SX.3 Implementation Strategy

### Phase A: Core Authentication Capability - THIS SPRINT

Build standalone Core capability in security/authentication/.

**Delivers**:
- Challenge generation with cryptographically secure random
- Canonical authentication context encoding
- Proof creation signing challenge plus context with private key
- Proof verification against public key plus context
- Replay resistance via challenge lifecycle management
- Comprehensive security test suite

**Does NOT**:
- Modify MessageType Message MessageEncoder MessageParser
- Modify PeerState ProtocolSessionManager ProtocolListener
- Modify PeerConnectionCoordinator Transport TransportListener
- Modify PeerId PeerRegistry PeerPresenceBridge
- Change any wire protocol behavior
- Break any existing test

### Phase B: Protocol Integration - FUTURE requires approval

Would add AUTH_CHALLENGE 0x04 and AUTH_PROOF 0x05 to MessageType,
add auth states to PeerState, gate JOINED on authentication,
update PeerConnectionCoordinator, handle backward compatibility,
and create an ADR. Explicitly out of scope for this sprint.

---

## 6. Security Invariants

Invariant                                  Current       Post-SX.3-A
TCP connected != authenticated             NO AUTH       Capability exists
Bluetooth connected != authenticated       NO AUTH       Capability exists
JOIN received != cryptographically verified BLIND TRUST  Capability exists
Path failure != authentication failure     N/A           By design
Multi-path = single identity               YES B.R1      Preserved
Private key never leaves signing boundary  YES SX.2      Preserved

---

## 7. Files to Create - Phase A

**Main**:
  security/authentication/AuthenticationChallenge.java
  security/authentication/AuthenticationProof.java
  security/authentication/AuthenticationService.java
  security/authentication/AuthenticationResult.java

**Test**:
  security/authentication/AuthenticationChallengeTest.java
  security/authentication/AuthenticationServiceTest.java
  security/authentication/AuthenticationReplayTest.java
  security/authentication/AuthenticationMalformedTest.java

---

## 8. Files to NOT Modify - Phase A

MessageType.java, Message.java, MessageEncoder.java, MessageParser.java,
PeerState.java, ProtocolSessionManager.java, ProtocolListener.java,
PeerConnectionCoordinator.java, Transport.java, TransportListener.java,
PeerId.java, PeerRegistry.java, PeerPresenceBridge.java,
Any Android code, Any existing test.

---

Generated from live codebase inspection. All findings based on actual
source code at baseline vSX.2.
