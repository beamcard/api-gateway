package com.beamcard.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class AuthRateLimitFilterTest {

    private static final GatewayFilterChain PASS = exchange -> Mono.empty();

    private static RateLimitProperties props(int limit) {
        return new RateLimitProperties(true, limit, 60, 1, 10_000, List.of("/auth/login"));
    }

    private static HttpStatusCode run(AuthRateLimitFilter filter, String path, String ip) {
        MockServerHttpRequest request = MockServerHttpRequest.post(path)
                .remoteAddress(new InetSocketAddress(ip, 40000))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, PASS).block();
        return exchange.getResponse().getStatusCode();
    }

    @Test
    void blocksAfterLimit_forSameIp() {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(props(2));

        assertThat(run(filter, "/auth/login", "1.2.3.4")).isNull();
        assertThat(run(filter, "/auth/login", "1.2.3.4")).isNull();
        assertThat(run(filter, "/auth/login", "1.2.3.4")).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void tracksIpsIndependently() {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(props(1));

        assertThat(run(filter, "/auth/login", "1.1.1.1")).isNull();
        assertThat(run(filter, "/auth/login", "1.1.1.1")).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // A different IP still has its full allowance.
        assertThat(run(filter, "/auth/login", "2.2.2.2")).isNull();
    }

    @Test
    void doesNotLimitUnlistedPaths() {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(props(1));

        assertThat(run(filter, "/auth/me", "1.2.3.4")).isNull();
        assertThat(run(filter, "/auth/me", "1.2.3.4")).isNull(); // never limited
    }

    @Test
    void sets429WithRetryAfterHeader() {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(props(1));
        run(filter, "/auth/login", "9.9.9.9");

        MockServerHttpRequest request = MockServerHttpRequest.post("/auth/login")
                .remoteAddress(new InetSocketAddress("9.9.9.9", 40000))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, PASS).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("60");
    }

    @Test
    void disabledFilterNeverLimits() {
        RateLimitProperties disabled = new RateLimitProperties(false, 1, 60, 1, 10_000, List.of("/auth/login"));
        AuthRateLimitFilter filter = new AuthRateLimitFilter(disabled);

        assertThat(run(filter, "/auth/login", "1.2.3.4")).isNull();
        assertThat(run(filter, "/auth/login", "1.2.3.4")).isNull();
    }
}
