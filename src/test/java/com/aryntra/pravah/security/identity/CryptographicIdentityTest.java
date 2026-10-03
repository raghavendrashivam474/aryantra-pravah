package com.aryntra.pravah.security.identity;

import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.PublicKey;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SX.2 — CryptographicIdentity Binding")
class CryptographicIdentityTest {

    private final IdentityGenerator generator = new IdentityGenerator();

    @Test
    @DisplayName("of() binds PeerId and PublicKey")
    void bindsPeerIdAndPublicKey() {
        PeerId peerId = PeerId.of("peer-node-1");
        IdentityKeyPair kp = generator.generate();

        CryptographicIdentity identity = CryptographicIdentity.of(peerId, kp.publicKey());

        assertEquals(peerId, identity.peerId());
        assertEquals(kp.publicKey(), identity.publicKey());
        assertEquals("Ed25519", identity.algorithm());
        assertArrayEquals(kp.publicKey().getEncoded(), identity.encodedPublicKey());
    }

    @Test
    @DisplayName("fromKeyPair() creates equivalent identity")
    void fromKeyPairCreatesIdentity() {
        PeerId peerId = PeerId.of("peer-node-2");
        IdentityKeyPair kp = generator.generate();

        CryptographicIdentity id1 = CryptographicIdentity.of(peerId, kp.publicKey());
        CryptographicIdentity id2 = CryptographicIdentity.fromKeyPair(peerId, kp);

        assertEquals(id1, id2);
        assertEquals(id1.hashCode(), id2.hashCode());
    }

    @Test
    @DisplayName("equal PeerId and Key produce equal CryptographicIdentity")
    void equalityContract() {
        PeerId peerId = PeerId.of("peer-same");
        IdentityKeyPair kp = generator.generate();

        CryptographicIdentity a = CryptographicIdentity.of(peerId, kp.publicKey());
        CryptographicIdentity b = CryptographicIdentity.of(peerId, kp.publicKey());

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("different PeerId produces unequal CryptographicIdentity")
    void differentPeerIdIsUnequal() {
        IdentityKeyPair kp = generator.generate();

        CryptographicIdentity a = CryptographicIdentity.of(PeerId.of("peer-A"), kp.publicKey());
        CryptographicIdentity b = CryptographicIdentity.of(PeerId.of("peer-B"), kp.publicKey());

        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("different PublicKey produces unequal CryptographicIdentity")
    void differentKeyIsUnequal() {
        PeerId peerId = PeerId.of("peer-same");
        IdentityKeyPair kp1 = generator.generate();
        IdentityKeyPair kp2 = generator.generate();

        CryptographicIdentity a = CryptographicIdentity.of(peerId, kp1.publicKey());
        CryptographicIdentity b = CryptographicIdentity.of(peerId, kp2.publicKey());

        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("round-trip through PublicKeyCodec preserves CryptographicIdentity equivalence")
    void roundTripPreservesIdentity() {
        PeerId peerId = PeerId.of("peer-roundtrip");
        IdentityKeyPair kp = generator.generate();
        CryptographicIdentity original = CryptographicIdentity.of(peerId, kp.publicKey());

        byte[] rawPublicKey = original.encodedPublicKey();
        PublicKey restoredKey = PublicKeyCodec.decode(rawPublicKey);
        CryptographicIdentity restored = CryptographicIdentity.of(peerId, restoredKey);

        assertEquals(original, restored);
        assertEquals(original.hashCode(), restored.hashCode());
    }

    @Test
    @DisplayName("toString does not leak private key material")
    void toStringSafety() {
        PeerId peerId = PeerId.of("alice");
        IdentityKeyPair kp = generator.generate();
        CryptographicIdentity id = CryptographicIdentity.fromKeyPair(peerId, kp);

        String s = id.toString();
        assertTrue(s.contains("alice"));
        assertTrue(s.contains("Ed25519"));
        assertFalse(s.contains("PrivateKey"));
    }

    @Test
    @DisplayName("rejects null parameters")
    void rejectsNulls() {
        PeerId peerId = PeerId.of("valid");
        IdentityKeyPair kp = generator.generate();

        assertThrows(NullPointerException.class, () -> CryptographicIdentity.of(null, kp.publicKey()));
        assertThrows(NullPointerException.class, () -> CryptographicIdentity.of(peerId, null));
        assertThrows(NullPointerException.class, () -> CryptographicIdentity.fromKeyPair(peerId, null));
    }
}