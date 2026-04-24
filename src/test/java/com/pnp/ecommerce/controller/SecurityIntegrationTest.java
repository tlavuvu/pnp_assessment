package com.pnp.ecommerce.controller;

import com.pnp.ecommerce.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the three security paths that the rest of the suite
 * asserts only in passing:
 *
 * <ol>
 *   <li>Anonymous request → 401 envelope + {@code WWW-Authenticate}.</li>
 *   <li>Bad credentials → 401 envelope.</li>
 *   <li>Wrong role → 403 envelope.</li>
 * </ol>
 *
 * <p>These are basic-security coverage per the brief; deeper tests
 * (method-level authorisation, rate limiting) belong in their own
 * dedicated specs and are deferred.
 */
class SecurityIntegrationTest extends AbstractPostgresIntegrationTest {

    @Test
    @DisplayName("anonymous GET /products: 401 with UNAUTHORIZED envelope and WWW-Authenticate")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("bad credentials: 401 UNAUTHORIZED envelope")
    void badCredentialsIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/products/1")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("admin", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("USER role on admin-only POST /products: 403 FORBIDDEN envelope")
    void userRoleCannotCreateProduct() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .with(asUser())
                        .contentType("application/json")
                        .content("""
                                {"name":"X","price":1.00,"stock":1}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("USER role on admin-only GET /reports: 403 FORBIDDEN envelope")
    void userRoleCannotReadReports() throws Exception {
        mockMvc.perform(get("/api/v1/reports/top-products")
                        .param("from", Instant.now().minusSeconds(60).toString())
                        .param("to", Instant.now().plusSeconds(60).toString())
                        .param("limit", "10")
                        .with(asUser()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("actuator health is publicly accessible")
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
