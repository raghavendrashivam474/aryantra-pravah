package com.aryntra.pravah.messaging;

import com.aryntra.pravah.peer.PeerId;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Domain model representing a multi-peer group conversation.
 *
 * Invariants:
 * - Stable ConversationId that does not change when participants join/leave
 * - Logical participants tracked via PeerId
 * - Thread-safe participant management
 */
public final class GroupConversation {

    private final ConversationId conversationId;
    private final String name;
    private final Set<PeerId> participants;

    public GroupConversation(ConversationId conversationId, String name, Set<PeerId> initialParticipants) {
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Group name must not be blank");
        }
        Objects.requireNonNull(initialParticipants, "initialParticipants must not be null");
        if (initialParticipants.isEmpty()) {
            throw new IllegalArgumentException("Group conversation must have at least one participant");
        }

        this.participants = new CopyOnWriteArraySet<>(initialParticipants);
    }

    public static GroupConversation create(String name, Set<PeerId> participants) {
        ConversationId id = new ConversationId("group:" + UUID.randomUUID());
        return new GroupConversation(id, name, participants);
    }

    public static GroupConversation create(ConversationId conversationId, String name, Set<PeerId> participants) {
        return new GroupConversation(conversationId, name, participants);
    }

    public ConversationId conversationId() {
        return conversationId;
    }

    public String name() {
        return name;
    }

    public Set<PeerId> participants() {
        return Collections.unmodifiableSet(new HashSet<>(participants));
    }

    public boolean addParticipant(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        return participants.add(peerId);
    }

    public boolean removeParticipant(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        if (participants.size() <= 1 && participants.contains(peerId)) {
            throw new IllegalStateException("Cannot remove the last participant from group conversation");
        }
        return participants.remove(peerId);
    }

    public boolean hasParticipant(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId must not be null");
        return participants.contains(peerId);
    }

    public int participantCount() {
        return participants.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GroupConversation that)) return false;
        return Objects.equals(conversationId, that.conversationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conversationId);
    }

    @Override
    public String toString() {
        return "GroupConversation{" +
                "conversationId=" + conversationId +
                ", name='" + name + '\'' +
                ", participants=" + participants +
                '}';
    }
}