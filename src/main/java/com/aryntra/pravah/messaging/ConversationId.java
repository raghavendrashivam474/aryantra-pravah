package com.aryntra.pravah.messaging;

import java.util.Objects;

/**
 * Value object representing a logical conversation identity.
 */
public record ConversationId(String value) {
    public ConversationId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("ConversationId must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}