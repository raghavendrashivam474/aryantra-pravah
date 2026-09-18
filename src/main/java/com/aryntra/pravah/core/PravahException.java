package com.aryntra.pravah.core;

public class PravahException extends RuntimeException {
    public PravahException(String message) {
        super(message);
    }

    public PravahException(String message, Throwable cause) {
        super(message, cause);
    }
}