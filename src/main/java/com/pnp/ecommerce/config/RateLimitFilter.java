package com.pnp.ecommerce.config;

import com.pnp.ecommerce.exception.ErrorCodes;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-principal Bucket4j rate limiter.
 *
 * <p>Why a filter (vs. {@code @Aspect} or interceptor): Spring
 * Security already exposes its pipeline as a servlet filter chain.
 * Placing the limiter as a filter lets it run AFTER authentication
 * (so the principal is known) and BEFORE controllers (so request
 * bodies don't have to be materialised for a request we will 429).
 *
 * <p>Why in-process {@link java.util.concurrent.ConcurrentHashMap} of
 * buckets (vs. Redis / Hazelcast): the brief explicitly forbids
 * Redis. A single-instance deploy is the assumed topology; a
 * multi-instance deploy would multiply the effective quota by the
 * instance count, which is a documented risk — not a bug.
 *
 * <p>Why two categories ({@code WRITE}, {@code REPORT}) and no
 * {@code READ} bucket: matches {@code docs/API-DESIGN.md} §5 —
 * "reads unthrottled at the app layer". Unthrottled reads can be
 * handled by upstream infra (CDN, API gateway) if ever needed.
 *
 * <p>Why <em>greedy</em> refill (not intervally): smoothes token
 * accrual — 30 tokens / minute means "one every 2 seconds" rather
 * than "full refill every 60 s", which a bursty client will find
 * more useful.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int WRITE_CAPACITY = 30;
    private static final int REPORT_CAPACITY = 10;
    private static final Duration REFILL_WINDOW = Duration.ofMinutes(1);

    private static final String CATEGORY_WRITE = "WRITE";
    private static final String CATEGORY_REPORT = "REPORT";
    private static final String PRINCIPAL_ANONYMOUS = "anonymous";

    private static final String PATH_PRODUCTS = "/api/v1/products";
    private static final String PATH_ORDERS = "/api/v1/orders";
    private static final String PATH_CANCEL = "/api/v1/orders/*/cancel";
    private static final String PATH_REPORTS = "/api/v1/reports/**";

    private final SecurityErrorResponseWriter errorResponseWriter;
    private final PathMatcher pathMatcher = new AntPathMatcher();
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(final SecurityErrorResponseWriter errorResponseWriter) {
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain chain) throws ServletException, IOException {
        final String category = classify(request);
        if (category == null) {
            chain.doFilter(request, response);
            return;
        }
        final String bucketKey = principalId() + ":" + category;
        final Bucket bucket = buckets.computeIfAbsent(bucketKey, k -> newBucket(category));
        final ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", Long.toString(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }
        final long retryAfterSeconds = Math.max(1L, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        errorResponseWriter.write(response, request, HttpStatus.TOO_MANY_REQUESTS,
                ErrorCodes.RATE_LIMITED,
                "rate limit exceeded; retry after " + retryAfterSeconds + "s");
    }

    private String classify(final HttpServletRequest request) {
        final String method = request.getMethod();
        final String path = request.getRequestURI();
        if (HttpMethod.GET.matches(method) && pathMatcher.match(PATH_REPORTS, path)) {
            return CATEGORY_REPORT;
        }
        if (HttpMethod.POST.matches(method)
                && (pathMatcher.match(PATH_PRODUCTS, path)
                    || pathMatcher.match(PATH_ORDERS, path)
                    || pathMatcher.match(PATH_CANCEL, path))) {
            return CATEGORY_WRITE;
        }
        return null;
    }

    private static String principalId() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return PRINCIPAL_ANONYMOUS;
        }
        return auth.getName();
    }

    private static Bucket newBucket(final String category) {
        final int capacity = CATEGORY_REPORT.equals(category) ? REPORT_CAPACITY : WRITE_CAPACITY;
        final Bandwidth bandwidth = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, REFILL_WINDOW)
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }
}
