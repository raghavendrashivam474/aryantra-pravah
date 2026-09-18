package com.aryntra.pravah.protocol;

import com.aryntra.pravah.core.PravahException;

/**
 * Thrown when a protocol violation occurs, such as malformed wire data,
 * version mismatch, or unsupported message types.
 */
public class ProtocolException extends PravahException {
    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}