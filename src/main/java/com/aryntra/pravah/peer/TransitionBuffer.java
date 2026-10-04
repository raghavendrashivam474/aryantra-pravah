package com.aryntra.pravah.peer;

import com.aryntra.pravah.protocol.Message;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bounded, TTL-aware in-flight buffer for messages that arrive during
 * the connectivity transition window (no ACTIVE path, but a CANDIDATE
 * path is expected to activate).
 *
 * <p>This is NOT a general-purpose offline message queue. It exists solely
 * to bridge the short, measurable gap between one path failing and an
 * alternate path becoming ACTIVE.</p>
 *
 * <h3>Bounds (defaults)</h3>
 * <ul>
 *   <li>Max messages: {@value #DEFAULT_MAX_MESSAGES}</li>
 *   <li>Max total bytes: {@value #DEFAULT_MAX_BYTES}</li>
 *   <li>Message TTL: {@value #DEFAULT_TTL_MILLIS}ms</li>
 * </ul>
 *
 * <h3>Eviction policy</h3>
 * When the buffer is full, the oldest message is evicted to make room
 * for the newest. This preserves the most recent user intent.
 *
 * <h3>Thread safety</h3>
 * All public methods are guarded by a ReentrantLock. The flush callback
 * is invoked while the lock is NOT held to avoid re-entrancy deadlocks
 * if the callback triggers further sends.
 *
 * B.R2 — Bounded In-Flight Buffering & Transition Reliability
 */
public class TransitionBuffer {

    private static final Logger LOGGER = Logger.getLogger(TransitionBuffer.class.getName());

    public static final int DEFAULT_MAX_MESSAGES = 64;
    public static final int DEFAULT_MAX_BYTES = 256 * 1024; // 256 KB
    public static final long DEFAULT_TTL_MILLIS = 10_000;   // 10 seconds

    private final int maxMessages;
    private final int maxBytes;
    private final long ttlMillis;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<BufferedMessage> buffer = new ArrayList<>();
    private int currentBytes = 0;

    // Observability counters
    private long totalBuffered = 0;
    private long totalFlushed = 0;
    private long totalExpired = 0;
    private long totalEvicted = 0;
    private long totalRejected = 0;

    /**
     * Creates a TransitionBuffer with default bounds.
     */
    public TransitionBuffer() {
        this(DEFAULT_MAX_MESSAGES, DEFAULT_MAX_BYTES, DEFAULT_TTL_MILLIS);
    }

    /**
     * Creates a TransitionBuffer with explicit bounds.
     *
     * @param maxMessages maximum number of buffered messages
     * @param maxBytes    maximum total bytes across all buffered messages
     * @param ttlMillis   time-to-live for each buffered message in milliseconds
     */
    public TransitionBuffer(int maxMessages, int maxBytes, long ttlMillis) {
        if (maxMessages < 1) throw new IllegalArgumentException("maxMessages must be >= 1");
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be >= 1");
        if (ttlMillis < 1) throw new IllegalArgumentException("ttlMillis must be >= 1");
        this.maxMessages = maxMessages;
        this.maxBytes = maxBytes;
        this.ttlMillis = ttlMillis;
    }

    /**
     * Attempts to buffer a message for later delivery during the transition window.
     *
     * @param destination   the target peer
     * @param message       the original protocol message
     * @param framedPayload the already-encoded-and-framed payload bytes
     * @return true if the message was buffered, false if rejected (buffer full after eviction)
     */
    public boolean offer(PeerId destination, Message message, byte[] framedPayload) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(framedPayload, "framedPayload must not be null");

        long now = System.currentTimeMillis();
        long expiresAt = now + ttlMillis;
        int payloadSize = framedPayload.length;

        // Reject if a single message exceeds maxBytes
        if (payloadSize > maxBytes) {
            totalRejected++;
            LOGGER.warning("Transition buffer: single message (" + payloadSize
                    + " bytes) exceeds maxBytes (" + maxBytes + "), rejected");
            return false;
        }

        lock.lock();
        try {
            // Purge expired entries first
            purgeExpiredLocked(now);

            // Evict oldest if at capacity
            while (buffer.size() >= maxMessages || (currentBytes + payloadSize) > maxBytes) {
                if (buffer.isEmpty()) {
                    totalRejected++;
                    return false;
                }
                BufferedMessage evicted = buffer.remove(0);
                currentBytes -= evicted.framedPayload().length;
                totalEvicted++;
                LOGGER.fine("Transition buffer: evicted oldest message for peer "
                        + evicted.destination().value());
            }

            BufferedMessage bm = new BufferedMessage(destination, message, framedPayload, now, expiresAt);
            buffer.add(bm);
            currentBytes += payloadSize;
            totalBuffered++;
            LOGGER.fine("Transition buffer: buffered message for peer "
                    + destination.value() + " (size=" + buffer.size()
                    + ", bytes=" + currentBytes + ")");
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Drains all non-expired buffered messages for a specific peer and delivers
     * them via the provided send callback. Messages are delivered in FIFO order.
     *
     * <p>The callback is invoked OUTSIDE the buffer lock to prevent deadlocks
     * if the send path re-enters the buffer.</p>
     *
     * @param peerId     the peer whose path just became ACTIVE
     * @param sendAction callback that performs the actual send (destination, framedPayload)
     * @return the number of messages successfully flushed
     */
    public int flushForPeer(PeerId peerId, BiConsumer<PeerId, byte[]> sendAction) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        Objects.requireNonNull(sendAction, "sendAction must not be null");

        List<BufferedMessage> toFlush;
        long now = System.currentTimeMillis();

        lock.lock();
        try {
            purgeExpiredLocked(now);
            toFlush = new ArrayList<>();
            Iterator<BufferedMessage> it = buffer.iterator();
            while (it.hasNext()) {
                BufferedMessage bm = it.next();
                if (bm.destination().equals(peerId)) {
                    toFlush.add(bm);
                    it.remove();
                    currentBytes -= bm.framedPayload().length;
                }
            }
        } finally {
            lock.unlock();
        }

        // Flush outside lock
        int flushed = 0;
        for (BufferedMessage bm : toFlush) {
            try {
                sendAction.accept(bm.destination(), bm.framedPayload());
                flushed++;
                totalFlushed++;
                LOGGER.fine("Transition buffer: flushed message for peer " + peerId.value());
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Transition buffer: flush failed for peer "
                        + peerId.value() + ": " + ex.getMessage(), ex);
            }
        }
        return flushed;
    }

    /**
     * Drains ALL non-expired buffered messages regardless of destination.
     * Used when multiple paths activate simultaneously.
     *
     * @param sendAction callback that performs the actual send
     * @return the number of messages successfully flushed
     */
    public int flushAll(BiConsumer<PeerId, byte[]> sendAction) {
        Objects.requireNonNull(sendAction, "sendAction must not be null");

        List<BufferedMessage> toFlush;
        long now = System.currentTimeMillis();

        lock.lock();
        try {
            purgeExpiredLocked(now);
            toFlush = new ArrayList<>(buffer);
            buffer.clear();
            currentBytes = 0;
        } finally {
            lock.unlock();
        }

        int flushed = 0;
        for (BufferedMessage bm : toFlush) {
            try {
                sendAction.accept(bm.destination(), bm.framedPayload());
                flushed++;
                totalFlushed++;
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Transition buffer: flush-all failed for peer "
                        + bm.destination().value() + ": " + ex.getMessage(), ex);
            }
        }
        return flushed;
    }

    /**
     * Discards all buffered messages for a peer that has fully disconnected.
     *
     * @param peerId the disconnected peer
     * @return the number of messages discarded
     */
    public int discardForPeer(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        lock.lock();
        try {
            int discarded = 0;
            Iterator<BufferedMessage> it = buffer.iterator();
            while (it.hasNext()) {
                BufferedMessage bm = it.next();
                if (bm.destination().equals(peerId)) {
                    it.remove();
                    currentBytes -= bm.framedPayload().length;
                    discarded++;
                }
            }
            if (discarded > 0) {
                LOGGER.info("Transition buffer: discarded " + discarded
                        + " messages for disconnected peer " + peerId.value());
            }
            return discarded;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of buffered messages.
     */
    public int size() {
        lock.lock();
        try {
            return buffer.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current total bytes of buffered payloads.
     */
    public int currentBytes() {
        lock.lock();
        try {
            return currentBytes;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns true if the buffer is empty.
     */
    public boolean isEmpty() {
        return size() == 0;
    }

    // --- Observability ---

    public long totalBuffered() { return totalBuffered; }
    public long totalFlushed() { return totalFlushed; }
    public long totalExpired() { return totalExpired; }
    public long totalEvicted() { return totalEvicted; }
    public long totalRejected() { return totalRejected; }

    public int maxMessages() { return maxMessages; }
    public int maxBytes() { return maxBytes; }
    public long ttlMillis() { return ttlMillis; }

    /**
     * Purges expired entries. MUST be called while holding the lock.
     */
    private void purgeExpiredLocked(long now) {
        Iterator<BufferedMessage> it = buffer.iterator();
        while (it.hasNext()) {
            BufferedMessage bm = it.next();
            if (bm.isExpired(now)) {
                it.remove();
                currentBytes -= bm.framedPayload().length;
                totalExpired++;
                LOGGER.fine("Transition buffer: expired message for peer "
                        + bm.destination().value());
            }
        }
    }
}