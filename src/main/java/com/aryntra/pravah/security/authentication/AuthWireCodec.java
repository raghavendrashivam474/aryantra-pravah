package com.aryntra.pravah.security.authentication;

import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.security.identity.CryptographicIdentity;
import com.aryntra.pravah.security.identity.PublicKeyCodec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Objects;

/**
 * Deterministic binary serializer and deserializer for authentication handshake wire payloads.
 *
 * <p>Wire Formats:</p>
 * <pre>
 * AUTH_CHALLENGE Payload:
 *   - [short] challengeId length (bytes)
 *   - [bytes] challengeId (UTF-8)
 *   - [short] challengeBytes length
 *   - [bytes] challengeBytes
 *   - [long]  expiryEpochMilli
 *
 * AUTH_PROOF Payload:
 *   - [short] peerId length (bytes)
 *   - [bytes] peerId (UTF-8)
 *   - [int]   publicKey DER length
 *   - [bytes] publicKey DER bytes
 *   - [short] challengeId length
 *   - [bytes] challengeId (UTF-8)
 *   - [short] signatureBytes length
 *   - [bytes] signatureBytes
 * </pre>
 *
 * <p>SX.4 — Trust-Aware Connection Lifecycle</p>
 */
public final class AuthWireCodec {

    private AuthWireCodec() {
        // Utility class
    }

    /**
     * Serializes an {@link AuthenticationChallenge} into wire payload bytes.
     */
    public static byte[] encodeChallenge(AuthenticationChallenge challenge) {
        Objects.requireNonNull(challenge, "challenge must not be null");
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            byte[] idBytes = challenge.challengeId().getBytes(StandardCharsets.UTF_8);
            dos.writeShort(idBytes.length);
            dos.write(idBytes);

            byte[] chalBytes = challenge.challengeBytes();
            dos.writeShort(chalBytes.length);
            dos.write(chalBytes);

            dos.writeLong(challenge.expiryTime().toEpochMilli());
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode challenge", e);
        }
    }

    /**
     * Deserializes wire payload bytes into an {@link AuthenticationChallenge}.
     */
    public static AuthenticationChallenge decodeChallenge(byte[] payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        if (payload.length < 12) {
            throw new IllegalArgumentException("Payload too short for AuthenticationChallenge");
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(payload);
             DataInputStream dis = new DataInputStream(bais)) {

            int idLen = dis.readShort() & 0xFFFF;
            byte[] idBytes = new byte[idLen];
            dis.readFully(idBytes);
            String challengeId = new String(idBytes, StandardCharsets.UTF_8);

            int bytesLen = dis.readShort() & 0xFFFF;
            byte[] chalBytes = new byte[bytesLen];
            dis.readFully(chalBytes);

            long expiryMillis = dis.readLong();
            Instant expiry = Instant.ofEpochMilli(expiryMillis);

            return AuthenticationChallenge.of(challengeId, chalBytes, expiry);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed challenge payload", e);
        }
    }

    /**
     * Serializes an {@link AuthenticationProof} into wire payload bytes.
     */
    public static byte[] encodeProof(AuthenticationProof proof) {
        Objects.requireNonNull(proof, "proof must not be null");
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            CryptographicIdentity identity = proof.claimantIdentity();

            // 1. PeerId
            byte[] peerIdBytes = identity.peerId().value().getBytes(StandardCharsets.UTF_8);
            dos.writeShort(peerIdBytes.length);
            dos.write(peerIdBytes);

            // 2. Encoded Public Key
            byte[] pubKeyBytes = identity.encodedPublicKey();
            dos.writeInt(pubKeyBytes.length);
            dos.write(pubKeyBytes);

            // 3. Challenge ID
            byte[] idBytes = proof.challengeId().getBytes(StandardCharsets.UTF_8);
            dos.writeShort(idBytes.length);
            dos.write(idBytes);

            // 4. Signature
            byte[] sigBytes = proof.signatureBytes();
            dos.writeShort(sigBytes.length);
            dos.write(sigBytes);

            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode proof", e);
        }
    }

    /**
     * Deserializes wire payload bytes into an {@link AuthenticationProof}.
     */
    public static AuthenticationProof decodeProof(byte[] payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        if (payload.length < 10) {
            throw new IllegalArgumentException("Payload too short for AuthenticationProof");
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(payload);
             DataInputStream dis = new DataInputStream(bais)) {

            // 1. PeerId
            int peerIdLen = dis.readShort() & 0xFFFF;
            byte[] peerIdBytes = new byte[peerIdLen];
            dis.readFully(peerIdBytes);
            PeerId peerId = PeerId.of(new String(peerIdBytes, StandardCharsets.UTF_8));

            // 2. Encoded Public Key
            int pubKeyLen = dis.readInt();
            byte[] pubKeyBytes = new byte[pubKeyLen];
            dis.readFully(pubKeyBytes);
            PublicKey publicKey = PublicKeyCodec.decode(pubKeyBytes);
            CryptographicIdentity identity = CryptographicIdentity.of(peerId, publicKey);

            // 3. Challenge ID
            int idLen = dis.readShort() & 0xFFFF;
            byte[] idBytes = new byte[idLen];
            dis.readFully(idBytes);
            String challengeId = new String(idBytes, StandardCharsets.UTF_8);

            // 4. Signature
            int sigLen = dis.readShort() & 0xFFFF;
            byte[] sigBytes = new byte[sigLen];
            dis.readFully(sigBytes);

            return new AuthenticationProof(identity, challengeId, sigBytes);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed proof payload", e);
        }
    }
}
