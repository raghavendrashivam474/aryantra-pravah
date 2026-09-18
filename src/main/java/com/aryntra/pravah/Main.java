package com.aryntra.pravah;

import com.aryntra.pravah.core.PravahConfig;
import com.aryntra.pravah.core.PravahRuntime;

public final class Main {

    private Main() {
        // Prevent instantiation
    }

    public static void main(String[] args) {
        PravahConfig config = PravahConfig.defaultConfig();
        PravahRuntime runtime = new PravahRuntime(config);

        // Register JVM shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::stop));

        runtime.start();
        
        // In S0 foundation mode, perform a clean immediate lifecycle verification run
        runtime.stop();
    }
}