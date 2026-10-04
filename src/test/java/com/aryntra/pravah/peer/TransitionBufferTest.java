package com.aryntra.pravah.peer;

import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test suite for TransitionBuffer bounding, eviction, TTL, FIFO ordering,
 * and multi-threaded safety.
 */
class TransitionBufferTest {

    private TransitionBuffer buffer;
    private PeerId peerA;
    private PeerId peerB;

    @BeforeEach
    void setUp() {
        buffer = new TransitionBuffer(5, 1024, 1000); // max 5 msgs, 1KB, 1000ms TTL
        peerA = PeerId.of("peer-a");
        peerB = PeerId.of("peer-b");
    }

    private Message makeMsg(String id) {
        return new Message(MessageType.MESSAGE, "sender", id, "payload".getBytes());
    }

    private byte[] makePayload(String content) {
        return content.getBytes();
    }

    @Test
    @DisplayName("Offers and flushes messages in FIFO order for a target peer")
    void fifoOrderingOnFlush() {
        buffer.offer(peerA, makeMsg("M1"), makePayload("P1"));
        buffer.offer(peerA, makeMsg("M2"), makePayload("P2"));
        buffer.offer(peerB, makeMsg("M3"), makePayload("P3"));
        buffer.offer(peerA, makeMsg("M4"), makePayload("P4"));

        assertEquals(4, buffer.size());

        List<String> deliveredPayloads = new ArrayList<>();
        int flushed = buffer.flushForPeer(peerA, (dest, payload) -> {
            assertEquals(peerA, dest);
            deliveredPayloads.add(new String(payload));
        });

        assertEquals(3, flushed);
        assertEquals(1, buffer.size(), "PeerB message should remain buffered");
        assertEquals(List.of("P1", "P2", "P4"), deliveredPayloads, "Must preserve FIFO order");
    }

    @Test
    @DisplayName("Evicts oldest message when maximum message count limit is reached")
    void evictionOnCountLimit() {
        for (int i = 1; i <= 5; i++) {
            buffer.offer(peerA, makeMsg("M" + i), makePayload("P" + i));
        }
        assertEquals(5, buffer.size());
        assertEquals(0, buffer.totalEvicted());

        // 6th message causes oldest (M1) to be evicted
        buffer.offer(peerA, makeMsg("M6"), makePayload("P6"));

        assertEquals(5, buffer.size());
        assertEquals(1, buffer.totalEvicted());

        List<String> delivered = new ArrayList<>();
        buffer.flushForPeer(peerA, (dest, payload) -> delivered.add(new String(payload)));

        assertEquals(List.of("P2", "P3", "P4", "P5", "P6"), delivered, "P1 must have been evicted");
    }

    @Test
    @DisplayName("Evicts oldest message when byte limit is reached")
    void evictionOnByteLimit() {
        TransitionBuffer smallBuffer = new TransitionBuffer(10, 50, 5000); // 50 bytes max
        byte[] payload20 = new byte[20];

        smallBuffer.offer(peerA, makeMsg("M1"), payload20);
        smallBuffer.offer(peerA, makeMsg("M2"), payload20);
        assertEquals(40, smallBuffer.currentBytes());

        // M3 (20 bytes) would exceed 50 bytes (total 60). M1 must be evicted.
        smallBuffer.offer(peerA, makeMsg("M3"), payload20);

        assertEquals(2, smallBuffer.size());
        assertEquals(40, smallBuffer.currentBytes());
        assertEquals(1, smallBuffer.totalEvicted());
    }

    @Test
    @DisplayName("Rejects payload that exceeds maximum total buffer size")
    void rejectsOversizedPayload() {
        byte[] hugePayload = new byte[2048]; // exceeds 1024 maxBytes
        boolean accepted = buffer.offer(peerA, makeMsg("M-HUGE"), hugePayload);

        assertFalse(accepted);
        assertEquals(1, buffer.totalRejected());
        assertEquals(0, buffer.size());
    }

    @Test
    @DisplayName("Expired messages are automatically purged on offer or flush")
    void messageExpiryOnTtl() throws InterruptedException {
        TransitionBuffer shortTtlBuffer = new TransitionBuffer(10, 1024, 50); // 50ms TTL

        shortTtlBuffer.offer(peerA, makeMsg("M1"), makePayload("P1"));
        assertEquals(1, shortTtlBuffer.size());

        // Wait for TTL to elapse
        Thread.sleep(80);

        List<String> delivered = new ArrayList<>();
        int flushed = shortTtlBuffer.flushForPeer(peerA, (dest, payload) -> delivered.add(new String(payload)));

        assertEquals(0, flushed, "Expired message must not be flushed");
        assertEquals(0, shortTtlBuffer.size());
        assertEquals(1, shortTtlBuffer.totalExpired());
    }

    @Test
    @DisplayName("Discards buffered messages cleanly on peer disconnect")
    void discardOnDisconnect() {
        buffer.offer(peerA, makeMsg("M1"), makePayload("P1"));
        buffer.offer(peerA, makeMsg("M2"), makePayload("P2"));
        buffer.offer(peerB, makeMsg("M3"), makePayload("P3"));

        assertEquals(3, buffer.size());

        int discarded = buffer.discardForPeer(peerA);

        assertEquals(2, discarded);
        assertEquals(1, buffer.size());
        assertEquals(peerB.value().getBytes().length + "payload".getBytes().length > 0, !buffer.isEmpty());
    }

    @Test
    @DisplayName("Thread-safety under concurrent offer and flush operations")
    void concurrentOfferAndFlush() throws InterruptedException {
        int threadCount = 8;
        int messagesPerThread = 50;
        TransitionBuffer concurrentBuffer = new TransitionBuffer(500, 100_000, 5000);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger sendCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int m = 0; m < messagesPerThread; m++) {
                        concurrentBuffer.offer(peerA, makeMsg("T" + threadId + "-M" + m), makePayload("data"));
                        if (m % 10 == 0) {
                            concurrentBuffer.flushForPeer(peerA, (dest, payload) -> sendCount.incrementAndGet());
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // Drain any remaining
        concurrentBuffer.flushForPeer(peerA, (dest, payload) -> sendCount.incrementAndGet());

        assertEquals(threadCount * messagesPerThread, sendCount.get(),
                "All offered messages must either be flushed or accounted for");
        assertEquals(0, concurrentBuffer.size());
    }
}