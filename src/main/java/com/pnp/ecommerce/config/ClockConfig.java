package com.pnp.ecommerce.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides a {@link Clock} bean so services can read time deterministically.
 *
 * <p>Why inject a {@link Clock} instead of calling {@link java.time.Instant#now()}
 * directly: time-sensitive code (report ranges, audit timestamps,
 * cancellation windows) becomes trivially testable by overriding this
 * bean with {@link Clock#fixed(java.time.Instant, java.time.ZoneId)}
 * in test configurations. Inline {@code Instant.now()} cannot be
 * controlled and turns into the most common reason a date-based test
 * is "flaky".
 *
 * <p>Lives in {@code config/} (not {@code util/}) because it is a
 * Spring {@code @Configuration} — by convention Spring configuration
 * classes live in this package and {@code util/} is reserved for
 * framework-agnostic helpers.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
