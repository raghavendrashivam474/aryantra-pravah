package com.aryntra.pravah.messaging;

import com.aryntra.pravah.peer.PeerId;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages active conversations and guarantees logical direct conversation uniqueness
 * regardless of which peer initiated the conversation.
 */
public class ConversationManager {

    private final PeerId localPeerId;
    private final Map<ConversationId, Conversation> conversations = new ConcurrentHashMap<>();

    public ConversationManager(PeerId localPeerId) {
        this.localPeerId = Objects.requireNonNull(localPeerId, "localPeerId must not be null");
    }

    /**
     * Retrieves or creates a stable canonical direct conversation with the target peer.
     * Guaranteed to prevent self-conversations and automatically reconcile A->B and B->A.
     */
    public Conversation getOrCreateDirectConversation(PeerId targetPeer) {
        Objects.requireNonNull(targetPeer, "targetPeer must not be null");
        if (localPeerId.equals(targetPeer)) {
            throw new IllegalArgumentException("Self-conversations are not allowed");
        }

        ConversationId derivedId = deriveDirectConversationId(localPeerId, targetPeer);
        return conversations.computeIfAbsent(derivedId, id -> {
            Set<PeerId> participants = Set.of(localPeerId, targetPeer);
            return new Conversation(id, participants);
        });
    }

    public Optional<Conversation> getConversation(ConversationId conversationId) {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        return Optional.ofNullable(conversations.get(conversationId));
    }

    public List<Conversation> listConversations() {
        return new ArrayList<>(conversations.values());
    }

    public void removeConversation(ConversationId conversationId) {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        conversations.remove(conversationId);
    }

    /**
     * Deterministically derives a unique direct conversation ID based on lexically sorted peer IDs.
     */
    public static ConversationId deriveDirectConversationId(PeerId p1, PeerId p2) {
        Objects.requireNonNull(p1, "p1 must not be null");
        Objects.requireNonNull(p2, "p2 must not be null");
        
        String val1 = p1.value();
        String val2 = p2.value();
        
        String combined = val1.compareTo(val2) < 0 
                ? "direct:" + val1 + ":" + val2 
                : "direct:" + val2 + ":" + val1;
                
        return new ConversationId(combined);
    }
}