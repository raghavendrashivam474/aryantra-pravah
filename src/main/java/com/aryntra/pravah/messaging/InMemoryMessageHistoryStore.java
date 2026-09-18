package com.aryntra.pravah.messaging;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * In-memory implementation of MessageHistoryStore.
 *
 * Useful for unit testing and development. Does NOT survive process
 * restart. For durable persistence, use SqliteMessageHistoryStore.
 *
 * Ordering guarantee: messages are returned in insertion order via
 * an internal monotonic sequence counter. This ensures deterministic
 * ordering even when multiple messages share the same Instant timestamp.
 */
public class InMemoryMessageHistoryStore implements MessageHistoryStore {

    private record StoredEntry(
            ApplicationMessage message,
            MessageState state,
            long sequence
    ) {}

    private final Map<String, StoredEntry> store = new ConcurrentHashMap<>();
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    @Override
    public void save(ApplicationMessage message, MessageState state) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(state, "state must not be null");

        String id = message.messageId();
        StoredEntry existing = store.putIfAbsent(id,
                new StoredEntry(message, state, sequenceCounter.incrementAndGet()));

        if (existing != null) {
            // Rollback the sequence increment is not needed — gaps are harmless
            throw new IllegalArgumentException("Message already exists: " + id);
        }
    }

    @Override
    public Optional<ApplicationMessage> find(String messageId) {
        Objects.requireNonNull(messageId, "messageId must not be null");
        StoredEntry entry = store.get(messageId);
        return entry != null ? Optional.of(entry.message()) : Optional.empty();
    }

    @Override
    public List<ApplicationMessage> getConversationHistory(ConversationId conversationId) {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        return store.values().stream()
                .filter(e -> e.message().conversationId().equals(conversationId))
                .sorted(Comparator.comparingLong(StoredEntry::sequence))
                .map(StoredEntry::message)
                .collect(Collectors.toList());
    }

    @Override
    public void updateState(String messageId, MessageState state) {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(state, "state must not be null");

        StoredEntry existing = store.get(messageId);
        if (existing == null) {
            throw new IllegalArgumentException("Message not found: " + messageId);
        }
        store.put(messageId, new StoredEntry(existing.message(), state, existing.sequence()));
    }

    @Override
    public Optional<MessageState> findState(String messageId) {
        Objects.requireNonNull(messageId, "messageId must not be null");
        StoredEntry entry = store.get(messageId);
        return entry != null ? Optional.of(entry.state()) : Optional.empty();
    }

    @Override
    public void delete(String messageId) {
        Objects.requireNonNull(messageId, "messageId must not be null");
        store.remove(messageId);
    }
}
