package com.aryntra.pravah.messaging;

import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S4.5 GroupConversation Domain Tests")
class GroupConversationTest {

    private final PeerId alice = PeerId.of("alice");
    private final PeerId bob = PeerId.of("bob");
    private final PeerId charlie = PeerId.of("charlie");
    private final ConversationId groupId = new ConversationId("group:dev-team");

    @Nested
    @DisplayName("Creation & Validation")
    class CreationTests {

        @Test
        @DisplayName("create valid group conversation with multiple participants")
        void createValidGroup() {
            GroupConversation group = new GroupConversation(groupId, "Dev Team", Set.of(alice, bob, charlie));
            assertEquals(groupId, group.conversationId());
            assertEquals("Dev Team", group.name());
            assertEquals(3, group.participantCount());
            assertTrue(group.hasParticipant(alice));
            assertTrue(group.hasParticipant(bob));
            assertTrue(group.hasParticipant(charlie));
        }

        @Test
        @DisplayName("factory helper generates unique group ConversationId")
        void factoryHelper() {
            GroupConversation g1 = GroupConversation.create("Team A", Set.of(alice, bob));
            GroupConversation g2 = GroupConversation.create("Team A", Set.of(alice, bob));

            assertNotNull(g1.conversationId());
            assertTrue(g1.conversationId().value().startsWith("group:"));
            assertNotEquals(g1.conversationId(), g2.conversationId());
        }

        @Test
        @DisplayName("rejects null or invalid parameters")
        void validationInvariants() {
            assertThrows(NullPointerException.class, () -> new GroupConversation(null, "Test", Set.of(alice)));
            assertThrows(NullPointerException.class, () -> new GroupConversation(groupId, null, Set.of(alice)));
            assertThrows(IllegalArgumentException.class, () -> new GroupConversation(groupId, "  ", Set.of(alice)));
            assertThrows(NullPointerException.class, () -> new GroupConversation(groupId, "Test", null));
            assertThrows(IllegalArgumentException.class, () -> new GroupConversation(groupId, "Test", Set.of()));
        }
    }

    @Nested
    @DisplayName("Participant Lifecycle")
    class ParticipantLifecycleTests {

        @Test
        @DisplayName("add and remove participants dynamically")
        void addAndRemove() {
            GroupConversation group = new GroupConversation(groupId, "Devs", Set.of(alice, bob));
            assertEquals(2, group.participantCount());

            assertTrue(group.addParticipant(charlie));
            assertEquals(3, group.participantCount());
            assertTrue(group.hasParticipant(charlie));

            // Adding duplicate participant returns false
            assertFalse(group.addParticipant(charlie));

            // Remove participant
            assertTrue(group.removeParticipant(bob));
            assertEquals(2, group.participantCount());
            assertFalse(group.hasParticipant(bob));

            // Removing nonexistent participant returns false
            assertFalse(group.removeParticipant(bob));
        }

        @Test
        @DisplayName("removing the last participant throws IllegalStateException")
        void cannotRemoveLastParticipant() {
            GroupConversation group = new GroupConversation(groupId, "Solo", Set.of(alice));
            assertThrows(IllegalStateException.class, () -> group.removeParticipant(alice));
        }

        @Test
        @DisplayName("participants() returns unmodifiable snapshot")
        void unmodifiableParticipants() {
            GroupConversation group = new GroupConversation(groupId, "Devs", Set.of(alice, bob));
            Set<PeerId> participants = group.participants();

            assertThrows(UnsupportedOperationException.class, () -> participants.add(charlie));
        }
    }

    @Nested
    @DisplayName("Equality & Identity")
    class EqualityTests {

        @Test
        @DisplayName("equality is based strictly on stable ConversationId")
        void equalityById() {
            GroupConversation g1 = new GroupConversation(groupId, "Name 1", Set.of(alice, bob));
            GroupConversation g2 = new GroupConversation(groupId, "Name 2", Set.of(charlie));

            assertEquals(g1, g2);
            assertEquals(g1.hashCode(), g2.hashCode());
        }
    }
}