package com.aryntra.pravah.messaging.reliability;

import com.aryntra.pravah.messaging.ConversationId;
import com.aryntra.pravah.peer.PeerId;

import java.util.List;
import java.util.Set;

/**
 * Tracks per-recipient delivery state for group messages.
 *
 * Unlike DeliveryOutbox (one message -> one peer), GroupDeliveryOutbox
 * models one logical group message as multiple independent delivery intents,
 * one per recipient PeerId.
 *
 * Each recipient has independent PENDING/COMPLETED/ABANDONED state and
 * independent attempt counts for bounded retry.
 */
public interface GroupDeliveryOutbox {

    /**
     * Registers delivery intents for all recipients of a group message.
     * Each recipient starts in PENDING state with attemptCount = 0.
     */
    void enqueueGroupMessage(String messageId, ConversationId groupId, Set<PeerId> recipients);

    /**
     * Returns the list of recipients still in PENDING state for a given message.
     */
    List<PeerId> findPendingRecipients(String messageId);

    /**
     * Returns message IDs that have pending delivery for a specific peer.
     * Used during reconnect to find group messages needing retry for this peer.
     */
    List<String> findPendingMessageIdsForPeer(PeerId peer);

    /**
     * Marks a specific recipient's delivery as COMPLETED.
     */
    void markRecipientCompleted(String messageId, PeerId recipient);

    /**
     * Marks a specific recipient's delivery as ABANDONED.
     */
    void markRecipientAbandoned(String messageId, PeerId recipient);

    /**
     * Increments the attempt count for a specific recipient and returns the new count.
     */
    int incrementAttemptCount(String messageId, PeerId recipient);

    /**
     * Returns the current attempt count for a specific recipient.
     */
    int getAttemptCount(String messageId, PeerId recipient);

    /**
     * Returns the OutboxState for a specific recipient of a message.
     */
    OutboxState getRecipientState(String messageId, PeerId recipient);

    /**
     * Returns true if all recipients for a message are in COMPLETED state.
     */
    boolean allRecipientsCompleted(String messageId);
}