package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.messaging.ConversationId;
import com.aryntra.pravah.peer.PeerId;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of GroupDeliveryOutbox.
 *
 * Internal model: messageId -> (PeerId -> RecipientRecord)
 * Thread-safe via ConcurrentHashMap.
 */
public class InMemoryGroupDeliveryOutbox implements GroupDeliveryOutbox {

    private record RecipientRecord(OutboxState state, int attemptCount) {}

    private final Map<String, Map<PeerId, RecipientRecord>> entries = new ConcurrentHashMap<>();

    @Override
    public void enqueueGroupMessage(String messageId, ConversationId groupId, Set<PeerId> recipients) {
        Objects.requireNonNull(messageId);
        Objects.requireNonNull(recipients);

        Map<PeerId, RecipientRecord> recipientMap = new ConcurrentHashMap<>();
        for (PeerId peer : recipients) {
            recipientMap.put(peer, new RecipientRecord(OutboxState.PENDING, 0));
        }
        entries.putIfAbsent(messageId, recipientMap);
    }

    @Override
    public List<PeerId> findPendingRecipients(String messageId) {
        Map<PeerId, RecipientRecord> recipientMap = entries.get(messageId);
        if (recipientMap == null) return List.of();

        return recipientMap.entrySet().stream()
                .filter(e -> e.getValue().state() == OutboxState.PENDING)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public List<String> findPendingMessageIdsForPeer(PeerId peer) {
        Objects.requireNonNull(peer);
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Map<PeerId, RecipientRecord>> msgEntry : entries.entrySet()) {
            RecipientRecord record = msgEntry.getValue().get(peer);
            if (record != null && record.state() == OutboxState.PENDING) {
                result.add(msgEntry.getKey());
            }
        }
        return List.copyOf(result);
    }

    @Override
    public void markRecipientCompleted(String messageId, PeerId recipient) {
        updateRecipientState(messageId, recipient, OutboxState.COMPLETED);
    }

    @Override
    public void markRecipientAbandoned(String messageId, PeerId recipient) {
        updateRecipientState(messageId, recipient, OutboxState.ABANDONED);
    }

    @Override
    public int incrementAttemptCount(String messageId, PeerId recipient) {
        Map<PeerId, RecipientRecord> recipientMap = entries.get(messageId);
        if (recipientMap == null) return 0;

        RecipientRecord current = recipientMap.get(recipient);
        if (current == null) return 0;

        int newCount = current.attemptCount() + 1;
        recipientMap.put(recipient, new RecipientRecord(current.state(), newCount));
        return newCount;
    }

    @Override
    public int getAttemptCount(String messageId, PeerId recipient) {
        Map<PeerId, RecipientRecord> recipientMap = entries.get(messageId);
        if (recipientMap == null) return 0;
        RecipientRecord record = recipientMap.get(recipient);
        return record != null ? record.attemptCount() : 0;
    }

    @Override
    public OutboxState getRecipientState(String messageId, PeerId recipient) {
        Map<PeerId, RecipientRecord> recipientMap = entries.get(messageId);
        if (recipientMap == null) return null;
        RecipientRecord record = recipientMap.get(recipient);
        return record != null ? record.state() : null;
    }

    @Override
    public boolean allRecipientsCompleted(String messageId) {
        Map<PeerId, RecipientRecord> recipientMap = entries.get(messageId);
        if (recipientMap == null || recipientMap.isEmpty()) return false;
        return recipientMap.values().stream()
                .allMatch(r -> r.state() == OutboxState.COMPLETED);
    }

    private void updateRecipientState(String messageId, PeerId recipient, OutboxState newState) {
        Map<PeerId, RecipientRecord> recipientMap = entries.get(messageId);
        if (recipientMap == null) return;
        RecipientRecord current = recipientMap.get(recipient);
        if (current == null) return;
        recipientMap.put(recipient, new RecipientRecord(newState, current.attemptCount()));
    }
}