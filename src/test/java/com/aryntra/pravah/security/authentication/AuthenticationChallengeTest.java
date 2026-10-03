package com.aryntra.pravah.security.authentication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SX.3: AuthenticationChallenge & Result Lifecycle Tests")
class AuthenticationChallengeTest {

    @Test
    @DisplayName("Should generate distinct fresh challenges with high entropy")
    void testChallengeGenerationFreshness() {
        Set<String> ids = new HashSet<>();
        Set<String> entropyHexes = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            AuthenticationChallenge challenge = AuthenticationChallenge.generate();

            assertNotNull(challenge.challengeId());
            assertFalse(challenge.challengeId().isBlank());
            assertEquals(32, challenge.challengeBytes().length, "Challenge entropy must be 32 bytes (256-bit)");
            assertTrue(challenge.isValid(), "Fresh challenge must initially be valid");
            assertFalse(challenge.isExpired(), "Fresh challenge must not be expired");
            assertFalse(challenge.isConsumed(), "Fresh challenge must not be consumed");

            ids.add(challenge.challengeId());
            entropyHexes.add(Arrays.toString(challenge.challengeBytes()));
        }

        assertEquals(100, ids.size(), "All challenge IDs must be distinct");
        assertEquals(100, entropyHexes.size(), "All challenge entropy byte arrays must be unique");
    }

    @Test
    @DisplayName("Should enforce single-use consumption semantics")
    void testSingleUseConsumption() {
        AuthenticationChallenge challenge = AuthenticationChallenge.generate();

        assertTrue(challenge.isValid());
        assertFalse(challenge.isConsumed());

        // First consumption must succeed
        assertTrue(challenge.consume(), "First consumption should return true");
        assertTrue(challenge.isConsumed(), "Challenge must now be marked consumed");
        assertFalse(challenge.isValid(), "Consumed challenge must no longer be valid");

        // Second consumption must fail
        assertFalse(challenge.consume(), "Subsequent consumption must return false");
        assertTrue(challenge.isConsumed());
        assertFalse(challenge.isValid());
    }

    @Test
    @DisplayName("Should properly recognize expired challenges")
    void testExpiredChallengeHandling() {
        Instant pastTime = Instant.now().minusSeconds(10);
        byte[] bytes = new byte[32];
        AuthenticationChallenge expiredChallenge = AuthenticationChallenge.of("exp-1", bytes, pastTime);

        assertTrue(expiredChallenge.isExpired(), "Past expiry time must report expired");
        assertFalse(expiredChallenge.isValid(), "Expired challenge must not be valid");
    }

    @Test
    @DisplayName("Should protect internal challenge bytes via defensive copies")
    void testDefensiveCopying() {
        AuthenticationChallenge challenge = AuthenticationChallenge.generate();
        byte[] rawCopy1 = challenge.challengeBytes();
        byte[] rawCopy2 = challenge.challengeBytes();

        assertNotSame(rawCopy1, rawCopy2, "challengeBytes() must return defensive clones");
        assertArrayEquals(rawCopy1, rawCopy2);

        // Mutate local array
        rawCopy1[0] = (byte) (rawCopy1[0] ^ 0xFF);
        assertFalse(Arrays.equals(rawCopy1, challenge.challengeBytes()),
                "Mutating returned array must not corrupt internal challenge state");
    }

    @Test
    @DisplayName("Should correctly evaluate AuthenticationResult predicates")
    void testAuthenticationResultBehavior() {
        assertTrue(AuthenticationResult.SUCCESS.isSuccess());
        assertFalse(AuthenticationResult.INVALID_PROOF.isSuccess());
        assertFalse(AuthenticationResult.CHALLENGE_EXPIRED_OR_CONSUMED.isSuccess());
        assertFalse(AuthenticationResult.IDENTITY_MISMATCH.isSuccess());
        assertFalse(AuthenticationResult.MALFORMED_INPUT.isSuccess());
        assertFalse(AuthenticationResult.UNSUPPORTED_ALGORITHM.isSuccess());
    }
}