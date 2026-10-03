package com.aryntra.pravah.security.authentication;

import com.aryntra.pravah.security.identity.CryptographicIdentity;
import com.aryntra.pravah.security.identity.IdentityKeyPair;
import com.aryntra.pravah.security.identity.SignatureService;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable carrier for a cryptographic proof of possession of a private key.
 *
 * <p>Contains the signature and the claimed cryptographic identity, and provides
 * a deterministic context-binding utility to build the exact payload to be signed or verified.</p>
 *
 * <p>SX.3 — Authentication Handshake & Proof of Possession</p>
 */
public final class AuthenticationProof {
    public static final String DEFAULT_DOMAIN = "com.aryntra.pravah.auth.v1";

    private final CryptographicIdentity claimantIdentity;
    private final String challengeId;
    private final byte[] signatureBytes;

    /**
     * Standard constructor. Performs defensive copies of the signature bytes.
     */
    public AuthenticationProof(CryptographicIdentity claimantIdentity, String challengeId, byte[] signatureBytes) {
        this.claimantIdentity = Objects.requireNonNull(claimantIdentity, "claimantIdentity must not be null");
        this.challengeId = Objects.requireNonNull(challengeId, "challengeId must not be null");
        this.signatureBytes = Objects.requireNonNull(signatureBytes, "signatureBytes must not be null").clone();
    }

    /** The claimed logical identity + public key bound to this proof. */
    public CryptographicIdentity claimantIdentity() {
        return claimantIdentity;
    }

    /** The sequence identifier of the challenge being responded to. */
    public String challengeId() {
        return challengeId;
    }

    /**
     * Defensive copy of the raw digital signature bytes.
     */
    public byte[] signatureBytes() {
        return signatureBytes.clone();
    }

    /**
     * Constructs the canonical, deterministic byte payload for signing and verification.
     *
     * <p>Avoids unstable serializers or toString structures. Employs length-prefixed
     * sequential serialization of the complete context structure.</p>
     *
     * @param domain         the security domain boundary (e.g., "com.aryntra.pravah.auth.v1")
     * @param challengeId    the unique challenge ID
     * @param challengeBytes the unpredictable challenge entropy bytes
     * @param peerIdStr      the logical identifier of the claiming peer
     * @param publicKeyBytes canonical encoded bytes of the public key
     * @return the exact deterministic byte representation of the authentication context
     */
    public static byte[] constructCanonicalContext(
            String domain,
            String challengeId,
            byte[] challengeBytes,
            String peerIdStr,
            byte[] publicKeyBytes) {
        Objects.requireNonNull(domain, "domain must not be null");
        Objects.requireNonNull(challengeId, "challengeId must not be null");
        Objects.requireNonNull(challengeBytes, "challengeBytes must not be null");
        Objects.requireNonNull(peerIdStr, "peerIdStr must not be null");
        Objects.requireNonNull(publicKeyBytes, "publicKeyBytes must not be null");

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            // 1. Write Domain Context
            byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);
            dos.writeShort(domainBytes.length);
            dos.write(domainBytes);

            // 2. Write Challenge ID
            byte[] chalIdBytes = challengeId.getBytes(StandardCharsets.UTF_8);
            dos.writeShort(chalIdBytes.length);
            dos.write(chalIdBytes);

            // 3. Write Challenge Entropy Bytes
            dos.writeShort(challengeBytes.length);
            dos.write(challengeBytes);

            // 4. Write Claimed PeerID
            byte[] peerBytes = peerIdStr.getBytes(StandardCharsets.UTF_8);
            dos.writeShort(peerBytes.length);
            dos.write(peerBytes);

            // 5. Write Public Key canonical bytes (X.509 DER representation)
            dos.writeInt(publicKeyBytes.length);
            dos.write(publicKeyBytes);

            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize canonical authentication context", e);
        }
    }

    /**
     * Utility builder to generate a cryptographically valid proof responding to a challenge.
     *
     * @param challenge the challenge issued by the verifier
     * @param identity  the public cryptographic identity claimed
     * @param keyPair   the private keypair owned by the claimant
     * @param domain    the authentication domain context
     * @return a valid {@link AuthenticationProof} containing the cryptographic signature
     */
    public static AuthenticationProof generate(
            AuthenticationChallenge challenge,
            CryptographicIdentity identity,
            IdentityKeyPair keyPair,
            String domain) {
        Objects.requireNonNull(challenge, "challenge must not be null");
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(keyPair, "keyPair must not be null");
        Objects.requireNonNull(domain, "domain must not be null");

        byte[] canonicalPayload = constructCanonicalContext(
                domain,
                challenge.challengeId(),
                challenge.challengeBytes(),
                identity.peerId().value(),
                identity.encodedPublicKey()
        );

        byte[] signature = SignatureService.sign(keyPair, canonicalPayload);
        return new AuthenticationProof(identity, challenge.challengeId(), signature);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AuthenticationProof that = (AuthenticationProof) o;
        return claimantIdentity.equals(that.claimantIdentity)
                && challengeId.equals(that.challengeId)
                && Arrays.equals(signatureBytes, that.signatureBytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(claimantIdentity, challengeId);
        result = 31 * result + Arrays.hashCode(signatureBytes);
        return result;
    }

    @Override
    public String toString() {
        return "AuthenticationProof[claimant=" + claimantIdentity.peerId().value()
                + ", challengeId=" + challengeId
                + ", sigHash=" + Integer.toHexString(Arrays.hashCode(signatureBytes))
                + "]";
    }
}