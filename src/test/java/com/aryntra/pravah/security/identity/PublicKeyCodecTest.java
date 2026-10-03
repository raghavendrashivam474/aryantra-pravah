package com.aryntra.pravah.security.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.PublicKey;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SX.2 — PublicKeyCodec")
class PublicKeyCodecTest {

    private final IdentityGenerator generator = new IdentityGenerator();

    @Test
    @DisplayName("encode() followed by decode() produces equivalent PublicKey")
    void encodeDecodeRoundTrip() {
        IdentityKeyPair kp = generator.generate();
        PublicKey original = kp.publicKey();

        byte[] encoded = PublicKeyCodec.encode(original);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        PublicKey decoded = PublicKeyCodec.decode(encoded);
        assertNotNull(decoded);
        assertArrayEquals(original.getEncoded(), decoded.getEncoded());
        assertEquals(original.getAlgorithm(), decoded.getAlgorithm());
    }

    @Test
    @DisplayName("encode() produces deterministic output for the same key")
    void encodingIsDeterministic() {
        IdentityKeyPair kp = generator.generate();
        byte[] enc1 = PublicKeyCodec.encode(kp.publicKey());
        byte[] enc2 = PublicKeyCodec.encode(kp.publicKey());

        assertArrayEquals(enc1, enc2);
    }

    @Test
    @DisplayName("encode() returns a defensive copy")
    void encodeReturnsDefensiveCopy() {
        IdentityKeyPair kp = generator.generate();
        byte[] enc1 = PublicKeyCodec.encode(kp.publicKey());
        byte[] enc2 = PublicKeyCodec.encode(kp.publicKey());

        assertNotSame(enc1, enc2);
        enc1[0] = (byte) (enc1[0] ^ 0xFF);
        assertFalse(Arrays.equals(enc1, enc2));
    }

    @Test
    @DisplayName("encode() rejects null")
    void encodeRejectsNull() {
        assertThrows(NullPointerException.class, () -> PublicKeyCodec.encode(null));
    }

    @Test
    @DisplayName("decode() rejects null and empty arrays")
    void decodeRejectsNullAndEmpty() {
        assertThrows(NullPointerException.class, () -> PublicKeyCodec.decode(null));
        assertThrows(IllegalArgumentException.class, () -> PublicKeyCodec.decode(new byte[0]));
    }

    @Test
    @DisplayName("decode() rejects corrupted / truncated bytes")
    void decodeRejectsCorruptedBytes() {
        IdentityKeyPair kp = generator.generate();
        byte[] valid = PublicKeyCodec.encode(kp.publicKey());

        // Corrupt first byte
        byte[] corrupted = valid.clone();
        corrupted[0] = (byte) (corrupted[0] ^ 0xFF);
        assertThrows(IllegalArgumentException.class, () -> PublicKeyCodec.decode(corrupted));

        // Truncated array
        byte[] truncated = Arrays.copyOf(valid, valid.length / 2);
        assertThrows(IllegalArgumentException.class, () -> PublicKeyCodec.decode(truncated));
    }
}