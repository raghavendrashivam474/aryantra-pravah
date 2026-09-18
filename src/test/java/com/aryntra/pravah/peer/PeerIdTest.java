package com.aryntra.pravah.peer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PeerId - S3.1 Identity")
class PeerIdTest {

    @Test
    @DisplayName("of() creates valid PeerId")
    void ofCreatesValidPeerId() {
        PeerId id = PeerId.of("alice");
        assertEquals("alice", id.value());
    }

    @Test
    @DisplayName("of() rejects null")
    void ofRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> PeerId.of(null));
    }

    @Test
    @DisplayName("of() rejects empty string")
    void ofRejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> PeerId.of(""));
    }

    @Test
    @DisplayName("of() rejects blank/whitespace string")
    void ofRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> PeerId.of("   "));
    }

    @Test
    @DisplayName("of() trims whitespace")
    void ofTrimsWhitespace() {
        PeerId id = PeerId.of("  alice  ");
        assertEquals("alice", id.value());
    }

    @Test
    @DisplayName("equal values produce equal PeerIds")
    void equalValuesAreEqual() {
        PeerId a = PeerId.of("peer-1");
        PeerId b = PeerId.of("peer-1");
        assertEquals(a, b);
    }

    @Test
    @DisplayName("different values produce unequal PeerIds")
    void differentValuesAreUnequal() {
        PeerId a = PeerId.of("peer-1");
        PeerId b = PeerId.of("peer-2");
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("hashCode is consistent for equal PeerIds")
    void hashCodeConsistent() {
        PeerId a = PeerId.of("peer-1");
        PeerId b = PeerId.of("peer-1");
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("toString contains the value")
    void toStringContainsValue() {
        PeerId id = PeerId.of("alice");
        assertTrue(id.toString().contains("alice"));
    }

    @Test
    @DisplayName("generate() produces valid PeerId")
    void generateProducesValid() {
        PeerId id = PeerId.generate();
        assertNotNull(id);
        assertFalse(id.value().isEmpty());
    }

    @Test
    @DisplayName("generate() produces unique PeerIds")
    void generateProducesUnique() {
        PeerId a = PeerId.generate();
        PeerId b = PeerId.generate();
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("PeerId is not equal to null")
    void notEqualToNull() {
        PeerId id = PeerId.of("alice");
        assertNotEquals(null, id);
    }

    @Test
    @DisplayName("PeerId is not equal to different type")
    void notEqualToDifferentType() {
        PeerId id = PeerId.of("alice");
        assertNotEquals("alice", id);
    }
}