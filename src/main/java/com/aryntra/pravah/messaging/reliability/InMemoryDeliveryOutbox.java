package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.peer.PeerId;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class InMemoryDeliveryOutbox implements DeliveryOutbox {

    private record SequencedEntry(long seq, OutboxEntry entry) {}

    private final AtomicLong sequenceGenerator = new AtomicLong(0);
    private final Map<String, SequencedEntry> entries = new ConcurrentHashMap<>();

    private static final Comparator<SequencedEntry> DETERMINISTIC_ORDER =
            Comparator.<SequencedEntry, java.time.Instant>comparing(se -> se.entry().createdAt())
                    .thenComparingLong(SequencedEntry::seq);

    @Override
    public void enqueue(OutboxEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        long seq = sequenceGenerator.incrementAndGet();
        SequencedEntry newEntry = new SequencedEntry(seq, entry);
        SequencedEntry existing = entries.putIfAbsent(entry.messageId(), newEntry);
        if (existing != null) {
            throw new IllegalArgumentException(
                    "Outbox entry already exists for messageId: " + entry.messageId());
        }
    }

    @Override
    public List<OutboxEntry> findPending() {
        return entries.values().stream()
                .filter(se -> se.entry().state() == OutboxState.PENDING)
                .sorted(DETERMINISTIC_ORDER)
                .map(SequencedEntry::entry)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public List<OutboxEntry> findPendingForPeer(PeerId destination) {
        Objects.requireNonNull(destination, "destination must not be null");
        return entries.values().stream()
                .filter(se -> se.entry().state() == OutboxState.PENDING)
                .filter(se -> se.entry().destination().equals(destination))
                .sorted(DETERMINISTIC_ORDER)
                .map(SequencedEntry::entry)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public Optional<OutboxEntry> findByMessageId(String messageId) {
        Objects.requireNonNull(messageId, "messageId must not be null");
        return Optional.ofNullable(entries.get(messageId)).map(SequencedEntry::entry);
    }

    @Override
    public void markCompleted(String messageId) {
        Objects.requireNonNull(messageId, "messageId must not be null");
        entries.computeIfPresent(messageId, (id, se) ->
                new SequencedEntry(se.seq(), se.entry().withState(OutboxState.COMPLETED)));
    }

    @Override
    public void remove(String messageId) {
        if (messageId != null) {
            entries.remove(messageId);
        }
    }

    @Override
    public void updateAttemptCount(String messageId, int attemptCount) {
        Objects.requireNonNull(messageId, "messageId must not be null");
        entries.computeIfPresent(messageId, (id, se) ->
                new SequencedEntry(se.seq(), se.entry().withAttemptCount(attemptCount)));
    }

    @Override
    public void updateState(String messageId, OutboxState state) {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        entries.computeIfPresent(messageId, (id, se) ->
                new SequencedEntry(se.seq(), se.entry().withState(state)));
    }
}