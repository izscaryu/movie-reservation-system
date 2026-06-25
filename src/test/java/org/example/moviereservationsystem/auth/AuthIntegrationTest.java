package org.example.moviereservationsystem.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;
import org.example.moviereservationsystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 2 authorization cases plus the Phase 10 refresh-token flow:
 *   1. signup -> login -> token issued; login returns access + refresh tokens
 *   2. no token on a protected endpoint -> 401
 *   3. USER token on an admin endpoint -> 403
 *   4. ADMIN token on an admin endpoint -> 200
 *   5. refresh rotates to a new access + refresh pair
 *   6. expired refresh -> 401
 *   7. reusing a rotated (revoked) refresh -> 401 and the whole family is revoked
 *   8. logout then refresh -> 401
 */
class AuthIntegrationTest extends AbstractIntegrationTest {

    private static final long EXPECTED_ACCESS_MS = 900_000L; // 15 min dev default

    @Test
    void signupThenLogin_issuesToken() throws Exception {
        String email = uniqueEmail();
        long userId = signup(email, "password123", "Test User");
        assertThat(userId).isPositive();

        String token = login(email, "password123");
        assertThat(token).isNotBlank();
    }

    @Test
    void login_returnsAccessAndRefreshTokens() throws Exception {
        String email = uniqueEmail();
        signup(email, "password123", "Shape User");

        JsonNode body = loginJson(email, "password123");

        String accessToken = body.get("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        // `token` is the back-compat alias of accessToken (same value).
        assertThat(body.get("token").asText()).isEqualTo(accessToken);
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("expiresInMs").asLong()).isEqualTo(EXPECTED_ACCESS_MS);
        assertThat(body.get("refreshToken").asText()).isNotBlank();
        assertThat(body.get("refreshToken").asText()).isNotEqualTo(accessToken);
    }

    @Test
    void adminEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/users/1/promote"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpoint_withUserToken_returns403() throws Exception {
        String email = uniqueEmail();
        long userId = signup(email, "password123", "Plain User");
        String userToken = login(email, "password123");

        mockMvc.perform(post("/api/admin/users/" + userId + "/promote")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminEndpoint_withAdminToken_returns200() throws Exception {
        String email = uniqueEmail();
        long targetId = signup(email, "password123", "Promote Me");

        String adminToken = adminToken();

        mockMvc.perform(post("/api/admin/users/" + targetId + "/promote")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void refresh_rotatesToNewTokens() throws Exception {
        String email = uniqueEmail();
        signup(email, "password123", "Refresh User");
        JsonNode login = loginJson(email, "password123");
        String oldRefresh = login.get("refreshToken").asText();

        JsonNode refreshed = read(refresh(oldRefresh).andExpect(status().isOk()).andReturn());

        assertThat(refreshed.get("accessToken").asText()).isNotBlank();
        String newRefresh = refreshed.get("refreshToken").asText();
        assertThat(newRefresh).isNotBlank().isNotEqualTo(oldRefresh);
    }

    @Test
    void refresh_withExpiredToken_returns401() throws Exception {
        String email = uniqueEmail();
        signup(email, "password123", "Expiry User");
        String refreshToken = loginJson(email, "password123").get("refreshToken").asText();

        // Simulate the clock advancing past the refresh lifetime (the only active
        // row belongs to this just-logged-in user).
        jdbcTemplate.update("UPDATE refresh_tokens SET expires_at = ? WHERE revoked_at IS NULL",
                Timestamp.valueOf(LocalDateTime.now().minusDays(1)));

        refresh(refreshToken).andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_reusingRotatedToken_returns401AndRevokesFamily() throws Exception {
        String email = uniqueEmail();
        signup(email, "password123", "Reuse User");
        String r0 = loginJson(email, "password123").get("refreshToken").asText();

        // Rotate once: r0 is consumed (revoked), r1 issued.
        String r1 = read(refresh(r0).andExpect(status().isOk()).andReturn())
                .get("refreshToken").asText();

        // Replaying the already-revoked r0 is treated as theft -> 401 + family nuke.
        refresh(r0).andExpect(status().isUnauthorized());

        // The family sweep revoked r1 too, so the legitimate-looking r1 is now dead.
        refresh(r1).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutThenRefresh_returns401() throws Exception {
        String email = uniqueEmail();
        signup(email, "password123", "Logout User");
        String refreshToken = loginJson(email, "password123").get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TokenBody(refreshToken))))
                .andExpect(status().isNoContent());

        refresh(refreshToken).andExpect(status().isUnauthorized());
    }

    // --- helpers ---

    private JsonNode loginJson(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginBody(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result);
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new TokenBody(refreshToken))));
    }

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private record TokenBody(String refreshToken) {
    }
}
