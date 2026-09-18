package com.aryntra.pravah.messaging;

import java.util.List;
import java.util.Optional;

/**
 * Persistence abstraction for application message history.
 * Decouples the messaging layer from any specific storage technology.
 *
 * Implementations must guarantee:
 * <ul>
 *   <li>Message identity stability (messageId is the primary key)</li>
 *   <li>Conversation identity preservation via ConversationId</li>
 *   <li>Sender identity as PeerId value (not connectionId)</li>
 *   <li>Deterministic ordering of conversation history</li>
 *   <li>Restart durability (for persistent implementations)</li>
 * </ul>
 *
 * The messaging layer depends on this abstraction, never on a
 * concrete database API directly. See ADR-006.
 */
public interface MessageHistoryStore {

    /**
     * Persists an application message with its current lifecycle state.
     *
     * @param message the application message to persist
     * @param state   the current lifecycle state at time of persistence
     * @throws IllegalArgumentException if a message with the same messageId already exists
     * @throws NullPointerException     if message or state is null
     */
    void save(ApplicationMessage message, MessageState state);

    /**
     * Retrieves a single message by its stable identity.
     *
     * @param messageId the unique message identifier
     * @return the message if found, empty otherwise
     */
    Optional<ApplicationMessage> find(String messageId);

    /**
     * Returns all messages in a conversation, ordered chronologically.
     * Ordering is deterministic: by insertion sequence, which preserves
     * timestamp ordering and breaks ties for identical timestamps.
     *
     * @param conversationId the conversation to query
     * @return ordered list of messages (empty if none exist)
     */
    List<ApplicationMessage> getConversationHistory(ConversationId conversationId);

    /**
     * Updates the lifecycle state of a previously saved message.
     *
     * @param messageId the message to update
     * @param state     the new lifecycle state
     * @throws IllegalArgumentException if the message does not exist in the store
     */
    void updateState(String messageId, MessageState state);

    /**
     * Retrieves the current lifecycle state of a persisted message.
     *
     * @param messageId the message to query
     * @return the state if the message exists, empty otherwise
     */
    Optional<MessageState> findState(String messageId);

    /**
     * Removes a message from the store entirely.
     * No-op if the message does not exist.
     *
     * @param messageId the message to delete
     */
    void delete(String messageId);
}
