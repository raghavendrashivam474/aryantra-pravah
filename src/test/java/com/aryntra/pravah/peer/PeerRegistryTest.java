package com.aryntra.pravah.peer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PeerRegistry - S3.2 In-Memory Peer Registry")
class PeerRegistryTest {

    private PeerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PeerRegistry();
    }

    @Test
    @DisplayName("register and lookup connected peer record")
    void registerAndLookup() {
        PeerId peerId = PeerId.of("peer-alice");
        PeerRecord record = PeerRecord.connected(peerId, "tcp-conn-1");

        registry.register(record);

        Optional<PeerRecord> found = registry.lookup(peerId);
        assertTrue(found.isPresent());
        assertEquals(peerId, found.get().peerId());
        assertEquals("tcp-conn-1", found.get().connectionId());
        assertTrue(found.get().isConnected());
    }

    @Test
    @DisplayName("register with convenience method")
    void registerConvenience() {
        PeerId peerId = PeerId.of("peer-bob");
        PeerRecord record = registry.register(peerId, "tcp-conn-2");

        assertEquals(peerId, record.peerId());
        assertEquals("tcp-conn-2", record.connectionId());
        assertTrue(registry.contains(peerId));
    }

    @Test
    @DisplayName("lookup unknown peer returns Optional.empty()")
    void lookupUnknown() {
        PeerId peerId = PeerId.of("unknown-peer");
        Optional<PeerRecord> found = registry.lookup(peerId);
        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("lookup with null returns Optional.empty()")
    void lookupNull() {
        Optional<PeerRecord> found = registry.lookup(null);
        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("lookupByConnectionId finds registered connected peer")
    void lookupByConnectionId() {
        PeerId peerId = PeerId.of("peer-charlie");
        registry.register(peerId, "tcp-conn-3");

        Optional<PeerRecord> found = registry.lookupByConnectionId("tcp-conn-3");
        assertTrue(found.isPresent());
        assertEquals(peerId, found.get().peerId());
    }

    @Test
    @DisplayName("lookupByConnectionId with unknown or null returns empty")
    void lookupByConnectionIdUnknown() {
        assertTrue(registry.lookupByConnectionId("unknown-conn").isEmpty());
        assertTrue(registry.lookupByConnectionId(null).isEmpty());
        assertTrue(registry.lookupByConnectionId("").isEmpty());
    }

    @Test
    @DisplayName("duplicate registration replaces previous record deterministically")
    void duplicateRegistrationReplacesRecord() {
        PeerId peerId = PeerId.of("peer-david");
        registry.register(peerId, "tcp-conn-old");
        assertEquals("tcp-conn-old", registry.lookup(peerId).get().connectionId());

        // Update connection ID (e.g. reconnected on new socket)
        registry.register(peerId, "tcp-conn-new");
        assertEquals(1, registry.size());
        assertEquals("tcp-conn-new", registry.lookup(peerId).get().connectionId());
    }

    @Test
    @DisplayName("remove peer removes entry from registry")
    void removePeer() {
        PeerId peerId = PeerId.of("peer-eve");
        registry.register(peerId, "tcp-conn-5");

        Optional<PeerRecord> removed = registry.remove(peerId);
        assertTrue(removed.isPresent());
        assertEquals("tcp-conn-5", removed.get().connectionId());
        assertFalse(registry.contains(peerId));
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("remove non-existing peer returns empty")
    void removeNonExisting() {
        Optional<PeerRecord> removed = registry.remove(PeerId.of("ghost"));
        assertTrue(removed.isEmpty());
        assertTrue(registry.remove(null).isEmpty());
    }

    @Test
    @DisplayName("allPeers returns unmodifiable snapshot of registered peers")
    void allPeersCollection() {
        PeerId a = PeerId.of("peer-a");
        PeerId b = PeerId.of("peer-b");
        registry.register(a, "conn-a");
        registry.register(b, "conn-b");

        assertEquals(2, registry.allPeers().size());
        assertThrows(UnsupportedOperationException.class, () -> registry.allPeers().clear());
    }

    @Test
    @DisplayName("clear empties registry")
    void clearRegistry() {
        registry.register(PeerId.of("p1"), "conn-1");
        registry.register(PeerId.of("p2"), "conn-2");
        assertEquals(2, registry.size());

        registry.clear();
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("PeerRecord invariants and disconnected state")
    void peerRecordInvariants() {
        PeerId id = PeerId.of("peer-x");
        PeerRecord disconnected = PeerRecord.disconnected(id);
        assertFalse(disconnected.isConnected());
        assertTrue(disconnected.optionalConnectionId().isEmpty());

        PeerRecord updated = disconnected.withConnectionId("conn-10");
        assertTrue(updated.isConnected());
        assertEquals("conn-10", updated.connectionId());

        assertThrows(NullPointerException.class, () -> new PeerRecord(null, "conn-1"));
        assertThrows(NullPointerException.class, () -> PeerRecord.connected(id, null));
    }

    @Test
    @DisplayName("concurrent registrations and lookups are safe")
    void concurrentOperations() throws InterruptedException {
        int threads = 8;
        int operationsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        PeerId id = PeerId.of("thread-" + threadId + "-peer-" + i);
                        registry.register(id, "conn-" + threadId + "-" + i);
                        if (registry.lookup(id).isPresent()) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(threads * operationsPerThread, registry.size());
        assertEquals(threads * operationsPerThread, successCount.get());
    }
}