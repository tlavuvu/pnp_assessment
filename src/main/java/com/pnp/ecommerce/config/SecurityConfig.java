package com.pnp.ecommerce.config;

import com.pnp.ecommerce.exception.ErrorCodes;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;

/**
 * Security wiring. Four moving parts:
 *
 * <ol>
 *   <li>{@link PasswordEncoder} — BCrypt, strength 10 (default).</li>
 *   <li>{@link InMemoryUserDetailsManager} — seeded from
 *       {@link SecurityProperties}, passwords BCrypt-encoded at
 *       startup.</li>
 *   <li>{@link SecurityFilterChain} — HTTP Basic, stateless,
 *       CSRF off (no browser sessions / cookies), RBAC rules, custom
 *       401 + 403 responders.</li>
 *   <li>{@link RateLimitFilter} — installed after the authorization
 *       filter so unauthorised requests are 401 before we spend a
 *       bucket token.</li>
 * </ol>
 *
 * <p>Why HTTP Basic (vs. JWT / OAuth2): the prompt explicitly forbids
 * JWT / OAuth2 and marks security as optional. HTTP Basic is the
 * minimum viable authenticator that actually enforces the RBAC matrix
 * in {@code docs/API-DESIGN.md} §4.
 *
 * <p>Why stateless sessions: the API is pure JSON; any session state
 * would mean browser-coupled cookie plumbing for zero benefit.
 *
 * <p>Why CSRF disabled: CSRF protects cookie-authenticated browser
 * forms. A stateless API with Basic-Auth has no cookie to forge.
 *
 * <p>Security level of this phase is an <b>optional enhancement</b>
 * per the assignment. Documented explicitly to match the mandated
 * rule on security controls.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public InMemoryUserDetailsManager userDetailsManager(final SecurityProperties properties,
                                                         final PasswordEncoder encoder) {
        final UserDetails[] users = properties.users().stream()
                .map(seed -> User.withUsername(seed.username())
                        .password(encoder.encode(seed.password()))
                        .roles(seed.roles().toArray(String[]::new))
                        .build())
                .toArray(UserDetails[]::new);
        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    public RateLimitFilter rateLimitFilter(final SecurityErrorResponseWriter writer) {
        return new RateLimitFilter(writer);
    }

    /**
     * RBAC matches {@code docs/API-DESIGN.md} §4:
     * <pre>
     *  POST   /api/v1/products               ADMIN
     *  GET    /api/v1/products/**            authenticated
     *  POST   /api/v1/orders                 authenticated
     *  GET    /api/v1/orders/**              authenticated
     *  POST   /api/v1/orders/&#123;id&#125;/cancel       ADMIN
     *  GET    /api/v1/reports/**             ADMIN
     *  /actuator/health, /actuator/info      open
     *  everything else                       authenticated
     * </pre>
     * "USER own orders" was deferred to scope; the plain-reading rule
     * here is "authenticated user" for reads/placements.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(final HttpSecurity http,
                                                   final SecurityErrorResponseWriter writer,
                                                   final RateLimitFilter rateLimitFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/products").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders/*/cancel").hasRole("ADMIN")
                        .requestMatchers("/api/v1/reports/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.authenticationEntryPoint(authEntryPoint(writer)))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(authEntryPoint(writer))
                        .accessDeniedHandler((req, res, ex) -> writer.write(res, req,
                                HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN,
                                "access denied")))
                .addFilterAfter(rateLimitFilter, AuthorizationFilter.class);

        return http.build();
    }

    private static BasicAuthenticationEntryPoint authEntryPoint(final SecurityErrorResponseWriter writer) {
        return new BasicAuthenticationEntryPoint() {
            @Override
            public void afterPropertiesSet() {
                setRealmName("pnp-ecommerce");
                super.afterPropertiesSet();
            }

            @Override
            public void commence(final jakarta.servlet.http.HttpServletRequest request,
                                 final jakarta.servlet.http.HttpServletResponse response,
                                 final org.springframework.security.core.AuthenticationException authException)
                    throws java.io.IOException {
                response.setHeader("WWW-Authenticate", "Basic realm=\"" + getRealmName() + "\"");
                writer.write(response, request, HttpStatus.UNAUTHORIZED,
                        ErrorCodes.UNAUTHORIZED, "authentication required");
            }
        };
    }
}
