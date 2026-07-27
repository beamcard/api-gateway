package com.beamcard.gateway;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Fixed-window, per-IP rate limiter for abuse-prone auth endpoints (login/signup/forgot/oauth).
 * In-memory (no Redis) — counters live in this gateway instance and reset on restart; fine for a
 * single instance at launch scale. Exceeding the limit returns 429 with {@code Retry-After} before
 * the request is proxied downstream.
 */
@Component
public class AuthRateLimitFilter implements GlobalFilter, Ordered {

    private final RateLimitProperties props;
    private final RemoteAddressResolver addressResolver;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public AuthRateLimitFilter(RateLimitProperties props) {
        this.props = props;
        // Trust the last N hops of X-Forwarded-For (Railway edge = 1); falls back to the socket
        // address when no XFF header is present (local dev).
        this.addressResolver = XForwardedRemoteAddressResolver.maxTrustedIndex(props.trustedHops());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!props.enabled() || !isRateLimited(exchange)) {
            return chain.filter(exchange);
        }
        long now = System.currentTimeMillis();
        if (allowed(clientIp(exchange), now)) {
            return chain.filter(exchange);
        }
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().add(HttpHeaders.RETRY_AFTER, Integer.toString(props.windowSeconds()));
        return response.setComplete();
    }

    private boolean isRateLimited(ServerWebExchange exchange) {
        if (exchange.getRequest().getMethod() != HttpMethod.POST) {
            return false;
        }
        return props.paths().contains(exchange.getRequest().getPath().value());
    }

    private boolean allowed(String key, long now) {
        long windowMs = props.windowSeconds() * 1000L;
        Counter counter = counters.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart >= windowMs) {
                return new Counter(now); // start a fresh window
            }
            existing.count++;
            return existing;
        });
        if (counters.size() > props.maxTrackedIps()) {
            purgeExpired(now, windowMs);
        }
        return counter.count <= props.limit();
    }

    /** Best-effort eviction of entries whose window has elapsed; keeps the map bounded. */
    private void purgeExpired(long now, long windowMs) {
        counters.values().removeIf(counter -> now - counter.windowStart >= windowMs);
    }

    private String clientIp(ServerWebExchange exchange) {
        InetSocketAddress address = addressResolver.resolve(exchange);
        if (address != null && address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return "unknown";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE; // reject floods before any routing work
    }

    /** Mutable per-IP window counter; guarded by {@link ConcurrentHashMap#compute}. */
    private static final class Counter {
        private final long windowStart;
        private int count;

        Counter(long windowStart) {
            this.windowStart = windowStart;
            this.count = 1;
        }
    }
}
