package org.example.moviereservationsystem.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Covers the four Phase 2 authorization cases:
 *   1. signup -> login -> token is issued
 *   2. no token on a protected endpoint -> 401
 *   3. USER token on an admin endpoint -> 403
 *   4. ADMIN token on an admin endpoint -> 200
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.admin.email}")
    private String adminEmail;

    @Value("${app.admin.password}")
    private String adminPassword;

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

        String adminToken = login(adminEmail, adminPassword);

        mockMvc.perform(post("/api/admin/users/" + targetId + "/promote")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    // --- helpers ---

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private long signup(String email, String password, String name) throws Exception {
        String body = objectMapper.writeValueAsString(
                new SignupBody(email, password, name));
        MvcResult result = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("id").asLong();
    }

    private String login(String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginBody(email, password));
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("token").asText();
    }

    private record SignupBody(String email, String password, String name) {
    }

    private record LoginBody(String email, String password) {
    }
}
