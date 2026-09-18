package com.aryntra.pravah.messaging;

import com.aryntra.pravah.peer.PeerId;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Logical relationship container holding participants.
 * Currently restricted to direct conversations (exactly two distinct peers).
 */
public final class Conversation {

    private final ConversationId conversationId;
    private final Set<PeerId> participants;

    public Conversation(ConversationId conversationId, Set<PeerId> participants) {
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId must not be null");
        Objects.requireNonNull(participants, "participants must not be null");
        
        if (participants.size() != 2) {
            throw new IllegalArgumentException("Direct conversation must contain exactly 2 distinct participants");
        }

        Set<PeerId> unique = new HashSet<>(participants);
        if (unique.size() != 2) {
            throw new IllegalArgumentException("Participants must be distinct");
        }

        this.participants = Collections.unmodifiableSet(unique);
    }

    public ConversationId conversationId() {
        return conversationId;
    }

    public Set<PeerId> participants() {
        return participants;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Conversation that)) return false;
        return Objects.equals(conversationId, that.conversationId) &&
               Objects.equals(participants, that.participants);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conversationId, participants);
    }

    @Override
    public String toString() {
        return "Conversation{" +
                "conversationId=" + conversationId +
                ", participants=" + participants +
                '}';
    }
}