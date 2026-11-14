package com.erpschool.saas.security;

import com.erpschool.common.dto.ApiResponse;
import com.erpschool.saas.support.ClientIp;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limits on the unauthenticated and credential endpoints,
 * keyed by client address. In-memory on purpose: the platform runs as one
 * application instance on one VPS, so no shared store is needed.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private record Rule(String name, int limit, long windowMs) {
    }

    private static final class Counter {
        long windowStart;
        int count;
    }

    private final boolean enabled;
    private final Rule login;
    private final Rule select;
    private final Rule register;
    private final Rule refresh;
    private final Rule publicWrite;
    private final Rule publicRead;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public RateLimitFilter(ObjectMapper objectMapper,
                           @Value("${app.security.rate-limit.enabled:true}") boolean enabled,
                           @Value("${app.security.rate-limit.login-per-minute:20}") int loginPerMinute,
                           @Value("${app.security.rate-limit.register-per-hour:10}") int registerPerHour,
                           @Value("${app.security.rate-limit.public-write-per-minute:30}") int publicWritePerMinute,
                           @Value("${app.security.rate-limit.public-read-per-minute:240}") int publicReadPerMinute) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.login = new Rule("login", loginPerMinute, 60_000);
        this.select = new Rule("select", loginPerMinute, 60_000);
        this.refresh = new Rule("refresh", Math.max(60, loginPerMinute * 3), 60_000);
        this.register = new Rule("register", registerPerHour, 3_600_000);
        this.publicWrite = new Rule("public-write", publicWritePerMinute, 60_000);
        this.publicRead = new Rule("public-read", publicReadPerMinute, 60_000);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        Rule rule = enabled ? ruleFor(request.getMethod(), request.getRequestURI()) : null;
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }
        long now = System.currentTimeMillis();
        String key = rule.name() + "|" + ClientIp.of(request);
        long retryAfterMs;
        Counter counter = counters.computeIfAbsent(key, k -> new Counter());
        synchronized (counter) {
            if (now - counter.windowStart >= rule.windowMs()) {
                counter.windowStart = now;
                counter.count = 0;
            }
            counter.count++;
            retryAfterMs = counter.count > rule.limit() ? rule.windowMs() - (now - counter.windowStart) : 0;
        }
        if (counters.size() > 50_000) {
            counters.entrySet().removeIf(e -> now - e.getValue().windowStart > 3_600_000);
        }
        if (retryAfterMs > 0) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfterMs / 1000)));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ApiResponse.fail("RATE_LIMITED",
                    "Too many attempts. Please wait a moment and try again."));
            return;
        }
        chain.doFilter(request, response);
    }

    private Rule ruleFor(String method, String path) {
        boolean post = "POST".equals(method);
        if (post && path.equals("/api/v1/auth/login")) {
            return login;
        }
        if (post && path.equals("/api/v1/auth/select")) {
            return select;
        }
        if (post && path.equals("/api/v1/auth/refresh")) {
            return refresh;
        }
        if (post && path.equals("/api/v1/public/register")) {
            return register;
        }
        if (path.startsWith("/api/v1/public/")) {
            return "GET".equals(method) ? publicRead : publicWrite;
        }
        return null;
    }
}
