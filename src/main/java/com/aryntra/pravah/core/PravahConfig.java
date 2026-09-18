package com.aryntra.pravah.core;

import java.util.Objects;

/**
 * Minimal immutable configuration for Pravah runtime.
 */
public record PravahConfig(String appName, String environment, int port) {

    public static final String DEFAULT_APP_NAME = "Aryntra-Pravah";
    public static final String DEFAULT_ENVIRONMENT = "development";
    public static final int DEFAULT_PORT = 9090;

    public PravahConfig {
        Objects.requireNonNull(appName, "appName must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1024 and 65535: " + port);
        }
    }

    public static PravahConfig defaultConfig() {
        return new PravahConfig(DEFAULT_APP_NAME, DEFAULT_ENVIRONMENT, DEFAULT_PORT);
    }
}