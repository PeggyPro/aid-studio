package com.aid.common.aid.core.service;

import com.aid.common.utils.RealtimeOrigins;
import org.springframework.stereotype.Service;
import java.util.Set;

/** Uses the existing business configuration source and bounded snapshot caching convention. */
@Service
public class RealtimeOriginSettings {
    private final ConfigService config;
    private volatile Snapshot snapshot;
    private final Set<String> registeredDefaults = new java.util.concurrent.CopyOnWriteArraySet<>();
    private record Snapshot(String value, long loadedAt) { }

    public RealtimeOriginSettings(ConfigService config) { this.config = config; }

    public void registerDefault(String value) { registeredDefaults.addAll(RealtimeOrigins.parse(value)); }

    /** Preserve defaults from every registered entrypoint when opening the shared editor. */
    public Set<String> editorOrigins(String fallback) {
        registerDefault(fallback);
        return allowedOrigins(String.join("\n", registeredDefaults));
    }

    public Set<String> allowedOrigins(String environmentDefault) {
        Snapshot current = snapshot;
        long now = System.nanoTime();
        if (current == null || now - current.loadedAt() >= 5_000_000_000L) {
            synchronized (this) {
                current = snapshot;
                if (current == null || now - current.loadedAt() >= 5_000_000_000L) {
                    // Read errors propagate: never replace a restrictive policy with a permissive default.
                    String value = config.getConfigValue("realtime", "allowed_origins");
                    if (value != null) RealtimeOrigins.parse(value);
                    current = new Snapshot(value, System.nanoTime());
                    snapshot = current;
                }
            }
        }
        return Set.copyOf(RealtimeOrigins.parse(current.value() == null ? environmentDefault : current.value()));
    }
}
