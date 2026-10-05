package com.aryntra.pravah.messaging;

import com.aryntra.pravah.peer.PeerId;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Generates per-peer monotonic sequence numbers for application-level
 * message ordering across transport migrations.
 *
 * <p>Each (local peer, remote peer) pair gets an independent monotonically
 * increasing counter. Sequence numbers start at 1 and increment per message.
 * Counters are in-memory only and reset on application restart — this is
 * acceptable because ordering is a per-session concern.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Fully thread-safe. Uses {@link ConcurrentHashMap} with
 * {@link AtomicLong} counters for lock-free increment.</p>
 *
 * <h3>B.R3 Context</h3>
 * <p>TCP and Bluetooth each guarantee in-connection ordering, but during
 * transport migration (BT->TCP->BT), messages can arrive out of order at
 * the receiver. This generator provides the sequence numbers that allow
 * the receiver to detect and correct reordering.</p>
 */
public class SequenceGenerator {

    private static final Logger LOGGER =
            Logger.getLogger(SequenceGenerator.class.getName());

    /**
     * Per-destination monotonic counters.
     * Key: remote PeerId, Value: next sequence to assign.
     */
    private final ConcurrentHashMap<PeerId, AtomicLong> counters =
            new ConcurrentHashMap<>();

    /**
     * Returns the next sequence number for the given destination peer.
     * Thread-safe and monotonically increasing per peer.
     *
     * @param destination the remote peer
     * @return a positive sequence number (starts at 1)
     */
    public long nextSequence(PeerId destination) {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        long seq = counters
                .computeIfAbsent(destination, k -> new AtomicLong(0))
                .incrementAndGet();
        LOGGER.finest(() -> "Sequence " + seq + " assigned for peer " + destination.value());
        return seq;
    }

    /**
     * Returns the most recently assigned sequence for the given peer,
     * or 0 if no messages have been sent to that peer yet.
     *
     * @param destination the remote peer
     * @return the current sequence number (0 if none assigned)
     */
    public long currentSequence(PeerId destination) {
        if (destination == null) return 0;
        AtomicLong counter = counters.get(destination);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Resets the sequence counter for a specific peer.
     * Primarily useful for testing.
     *
     * @param destination the remote peer
     */
    public void reset(PeerId destination) {
        if (destination != null) {
            counters.remove(destination);
        }
    }

    /**
     * Resets all sequence counters.
     * Primarily useful for testing.
     */
    public void resetAll() {
        counters.clear();
    }

    /**
     * Returns the number of peers with active sequence counters.
     */
    public int activePeerCount() {
        return counters.size();
    }
}