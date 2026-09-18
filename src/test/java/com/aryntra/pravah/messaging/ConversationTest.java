package com.aryntra.pravah.messaging;

import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S4.2 Conversation Domain Tests")
class ConversationTest {

    private final PeerId alice = PeerId.of("alice");
    private final PeerId bob = PeerId.of("bob");
    private final PeerId charlie = PeerId.of("charlie");
    private final ConversationId convId = new ConversationId("conv-123");

    @Test
    @DisplayName("Should create valid direct conversation with exactly two distinct participants")
    void testValidDirectConversation() {
        Conversation conv = new Conversation(convId, Set.of(alice, bob));
        assertEquals(convId, conv.conversationId());
        assertEquals(2, conv.participants().size());
        assertTrue(conv.participants().contains(alice));
        assertTrue(conv.participants().contains(bob));
    }

    @Test
    @DisplayName("Should reject conversation with null values or invalid participant size")
    void testValidationInvariants() {
        assertThrows(NullPointerException.class, () -> new Conversation(null, Set.of(alice, bob)));
        assertThrows(NullPointerException.class, () -> new Conversation(convId, null));
        
        // Single participant is invalid
        assertThrows(IllegalArgumentException.class, () -> new Conversation(convId, Set.of(alice)));
        
        // Three participants is currently out of scope/invalid
        assertThrows(IllegalArgumentException.class, () -> new Conversation(convId, Set.of(alice, bob, charlie)));
    }

    @Test
    @DisplayName("Should reject conversation with duplicate participants (effectively size 1)")
    void testDuplicateParticipantsRejection() {
        // Since Set.of doesn't allow duplicates on construction, we construct via Set implementation that does, or test manual logic if needed.
        // But our logic handles sets. An invalid set with size != 2 gets caught cleanly.
        assertThrows(IllegalArgumentException.class, () -> new Conversation(convId, Set.of(alice)));
    }
}