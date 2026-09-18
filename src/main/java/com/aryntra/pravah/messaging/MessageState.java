package com.aryntra.pravah.messaging;

/**
 * Logical states representing the message lifecycle.
 */
public enum MessageState {
    CREATED,
    SENT,
    DELIVERED,
    FAILED
}