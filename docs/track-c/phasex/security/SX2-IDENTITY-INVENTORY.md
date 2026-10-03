# SX.2 Engineering Note — Identity Inventory

## Current Architecture
PeerId is a final value object wrapping a single String value.
- Created via PeerId.of(String) or PeerId.generate() (UUID).
- Serialized on the wire as UTF-8 string in MessageEncoder (senderId) and DiscoveryPacket (peerId).
- Consumed by ~80 source files across connectivity, messaging, discovery, protocol, and presence.

## Requirement
Establish a cryptographically bindable identity foundation (Ed25519 key pair bound to PeerId)
without modifying PeerId, transport, protocol, or discovery contracts.

## Observed Limitation
PeerId is self-declared. No cryptographic proof of ownership exists.
The wire format carries senderId as a plain UTF-8 string with no signature.

## Affected Contracts
- PeerId.java (value semantics, equals/hashCode)
- MessageEncoder / MessageParser (senderId as String)
- DiscoveryPacket (peerId as String)
- DiscoveredAddressCandidate (PeerId record component)

## Decision
**Do NOT modify PeerId.** Create a parallel CryptographicIdentity binding in a new
com.aryntra.pravah.security.identity package. This follows the spec Section 14
"Safer initial direction" approach.

## Approach
1. IdentityKeyPair — wraps java.security.KeyPair (Ed25519)
2. IdentityGenerator — generates key pairs using SecureRandom
3. CryptographicIdentity — binds PeerId + public key + algorithm metadata
4. PublicKeyCodec — deterministic serialize/deserialize of public keys
5. SignatureService — sign/verify primitive (no protocol integration)

## Compatibility Impact
Zero. PeerId, MessageEncoder, MessageParser, DiscoveryPacket, all transports unchanged.

## Test Impact
New test package only. No existing tests modified.
