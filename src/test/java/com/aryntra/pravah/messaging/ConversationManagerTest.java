package com.aryntra.pravah.messaging;

import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S4.2 & S4.5 ConversationManager Semantics Tests")
class ConversationManagerTest {

    private PeerId alice;
    private PeerId bob;
    private PeerId charlie;
    private ConversationManager aliceManager;

    @BeforeEach
    void setUp() {
        alice = PeerId.of("alice");
        bob = PeerId.of("bob");
        charlie = PeerId.of("charlie");
        aliceManager = new ConversationManager(alice);
    }

    @Test
    @DisplayName("getOrCreateDirectConversation should create clean active conversation")
    void testGetOrCreate() {
        Conversation conv = aliceManager.getOrCreateDirectConversation(bob);
        assertNotNull(conv);
        assertEquals(2, conv.participants().size());
        assertTrue(conv.participants().contains(alice));
        assertTrue(conv.participants().contains(bob));
    }

    @Test
    @DisplayName("Reconciliation: alice->bob and bob->alice must derive the exact same ID")
    void testCanonicalIdReconciliation() {
        ConversationId id1 = ConversationManager.deriveDirectConversationId(alice, bob);
        ConversationId id2 = ConversationManager.deriveDirectConversationId(bob, alice);

        assertEquals(id1, id2, "Canonical direct IDs must be order-independent");
        assertEquals("direct:alice:bob", id1.value(), "ID should be sorted alphabetically");
    }

    @Test
    @DisplayName("Should retrieve existing conversation via manager lookup")
    void testManagerLookup() {
        Conversation created = aliceManager.getOrCreateDirectConversation(bob);
        ConversationId cid = created.conversationId();

        Optional<Conversation> lookup = aliceManager.getConversation(cid);
        assertTrue(lookup.isPresent());
        assertEquals(created, lookup.get());
    }

    @Test
    @DisplayName("Should reject self-conversations strictly")
    void testRejectSelfConversation() {
        assertThrows(IllegalArgumentException.class, () -> aliceManager.getOrCreateDirectConversation(alice));
    }

    @Test
    @DisplayName("Should successfully list and remove conversations")
    void testListAndRemove() {
        Conversation c1 = aliceManager.getOrCreateDirectConversation(bob);
        Conversation c2 = aliceManager.getOrCreateDirectConversation(charlie);

        assertEquals(2, aliceManager.listConversations().size());

        aliceManager.removeConversation(c1.conversationId());
        assertEquals(1, aliceManager.listConversations().size());
        assertFalse(aliceManager.getConversation(c1.conversationId()).isPresent());
    }

    // --- S4.5 Group Tests ---

    @Test
    @DisplayName("createGroup should instantiate GroupConversation with sender included")
    void testCreateGroup() {
        GroupConversation group = aliceManager.createGroup("Engineering Team", Set.of(bob, charlie));
        assertNotNull(group);
        assertTrue(group.conversationId().value().startsWith("group:"));
        assertEquals("Engineering Team", group.name());
        assertEquals(3, group.participantCount());
        assertTrue(group.hasParticipant(alice), "Local sender must be added automatically");
        assertTrue(group.hasParticipant(bob));
        assertTrue(group.hasParticipant(charlie));
    }

    @Test
    @DisplayName("registerGroupConversation should store group dynamically")
    void testRegisterGroup() {
        GroupConversation group = GroupConversation.create("Product Team", Set.of(bob, charlie));
        aliceManager.registerGroupConversation(group);

        Optional<GroupConversation> lookup = aliceManager.getGroupConversation(group.conversationId());
        assertTrue(lookup.isPresent());
        assertEquals(group, lookup.get());
    }

    @Test
    @DisplayName("Should list and remove group conversations cleanly")
    void testListAndRemoveGroups() {
        GroupConversation g1 = aliceManager.createGroup("Team A", Set.of(bob));
        GroupConversation g2 = aliceManager.createGroup("Team B", Set.of(charlie));

        assertEquals(2, aliceManager.listGroupConversations().size());

        aliceManager.removeConversation(g1.conversationId());
        assertEquals(1, aliceManager.listGroupConversations().size());
        assertFalse(aliceManager.getGroupConversation(g1.conversationId()).isPresent());
    }
}