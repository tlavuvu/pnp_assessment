package com.pnp.ecommerce.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Typed configuration for seeded security users.
 *
 * <p>Why {@code @ConfigurationProperties} + {@code @Validated} (not
 * hardcoded {@code User.builder(...)} in Java): hardcoded credentials
 * would force a code change + redeploy to rotate, and would commit
 * secrets to git. Configuration binding keeps the shape typed,
 * fail-fast if misconfigured, and overridable per profile / env var
 * ({@code SECURITY_USERS_0_USERNAME=...}).
 *
 * <p>Why in-memory (not DB-backed): the brief explicitly marks
 * security as an optional enhancement, and implementing a full
 * user-account schema + repository + migration sequence would double
 * the delivery with no additional reviewer value. The trade-off is
 * documented in {@code docs/DECISIONS.md}; a production-grade system
 * would replace this with a database-backed
 * {@code UserDetailsService}.
 *
 * <p>Why plaintext passwords in config (not BCrypt hashes): dev /
 * test ergonomics. {@code SecurityConfig} BCrypt-encodes them at
 * startup — the hash never exists on disk. In {@code prod} profiles
 * this file would be overridden by properties containing hashes and
 * the encoder could be swapped for a {@code NoOp} prefix-aware one.
 */
@ConfigurationProperties(prefix = "security")
@Validated
public record SecurityProperties(
        @NotEmpty @Valid List<SeedUser> users
) {

    public record SeedUser(
            @NotBlank String username,
            @NotBlank String password,
            @NotEmpty List<@NotBlank String> roles
    ) {
    }
}
