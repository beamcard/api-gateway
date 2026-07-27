package com.beamcard.gateway;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("beamcard.rate-limit")
public record RateLimitProperties(
        boolean enabled, int limit, int windowSeconds, int trustedHops, int maxTrackedIps, List<String> paths) {

    public RateLimitProperties {
        if (limit <= 0) {
            limit = 20;
        }
        if (windowSeconds <= 0) {
            windowSeconds = 60;
        }
        if (trustedHops <= 0) {
            trustedHops = 1;
        }
        if (maxTrackedIps <= 0) {
            maxTrackedIps = 10_000;
        }
        if (paths == null) {
            paths = List.of();
        }
    }
}
