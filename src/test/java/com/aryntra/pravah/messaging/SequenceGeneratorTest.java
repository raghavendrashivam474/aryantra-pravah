package com.aryntra.pravah.messaging;

import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("B.R3 SequenceGenerator Tests")
class SequenceGeneratorTest {

    private SequenceGenerator sequenceGenerator;
    private final PeerId alice = PeerId.of("alice");
    private final PeerId bob = PeerId.of("bob");

    @BeforeEach
    void setUp() {
        sequenceGenerator = new SequenceGenerator();
    }

    @Test
    @DisplayName("Monotonically increments sequence per destination")
    void testMonotonicIncrementPerPeer() {
        assertEquals(1L, sequenceGenerator.nextSequence(alice));
        assertEquals(2L, sequenceGenerator.nextSequence(alice));
        assertEquals(3L, sequenceGenerator.nextSequence(alice));

        // Destination bob starts independently at 1
        assertEquals(1L, sequenceGenerator.nextSequence(bob));
        assertEquals(2L, sequenceGenerator.nextSequence(bob));

        // Alice continues unaffected
        assertEquals(4L, sequenceGenerator.nextSequence(alice));
    }

    @Test
    @DisplayName("currentSequence reports latest assigned without incrementing")
    void testCurrentSequence() {
        assertEquals(0L, sequenceGenerator.currentSequence(alice));
        sequenceGenerator.nextSequence(alice);
        sequenceGenerator.nextSequence(alice);
        assertEquals(2L, sequenceGenerator.currentSequence(alice));
        assertEquals(2L, sequenceGenerator.currentSequence(alice));
    }

    @Test
    @DisplayName("reset clears counter for specific peer")
    void testResetPeer() {
        sequenceGenerator.nextSequence(alice);
        sequenceGenerator.nextSequence(alice);
        assertEquals(2L, sequenceGenerator.currentSequence(alice));

        sequenceGenerator.reset(alice);
        assertEquals(0L, sequenceGenerator.currentSequence(alice));
        assertEquals(1L, sequenceGenerator.nextSequence(alice));
    }

    @Test
    @DisplayName("null destination throws IllegalArgumentException")
    void testNullDestinationThrows() {
        assertThrows(IllegalArgumentException.class, () -> sequenceGenerator.nextSequence(null));
        assertEquals(0L, sequenceGenerator.currentSequence(null));
    }

    @Test
    @DisplayName("Thread-safe concurrent increments generate distinct monotonic sequences")
    void testConcurrentIncrements() throws InterruptedException {
        int threads = 10;
        int incrementsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        List<Long> generatedSequences = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < incrementsPerThread; j++) {
                        generatedSequences.add(sequenceGenerator.nextSequence(alice));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threads * incrementsPerThread, generatedSequences.size());
        assertEquals(threads * incrementsPerThread, (int) sequenceGenerator.currentSequence(alice));
        // Verify all elements are unique
        long uniqueCount = generatedSequences.stream().distinct().count();
        assertEquals(threads * incrementsPerThread, uniqueCount);
    }
}