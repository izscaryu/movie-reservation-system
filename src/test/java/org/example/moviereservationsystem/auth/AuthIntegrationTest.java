package org.example.moviereservationsystem.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.example.moviereservationsystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Covers the four Phase 2 authorization cases:
 *   1. signup -> login -> token is issued
 *   2. no token on a protected endpoint -> 401
 *   3. USER token on an admin endpoint -> 403
 *   4. ADMIN token on an admin endpoint -> 200
 */
class AuthIntegrationTest extends AbstractIntegrationTest {

    @Test
    void signupThenLogin_issuesToken() throws Exception {
        String email = uniqueEmail();
        long userId = signup(email, "password123", "Test User");
        assertThat(userId).isPositive();

        String token = login(email, "password123");
        assertThat(token).isNotBlank();
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
        // A fresh USER to be the promotion target.
        String email = uniqueEmail();
        long targetId = signup(email, "password123", "Promote Me");

        String adminToken = adminToken();

        mockMvc.perform(post("/api/admin/users/" + targetId + "/promote")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
