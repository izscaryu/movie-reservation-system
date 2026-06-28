package org.example.moviereservationsystem.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.example.moviereservationsystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 *   9. N concurrent refreshes of one token -> exactly one rotates (TOCTOU proof)
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

    /**
     * THE refresh-rotation TOCTOU proof. {@code threads} requests present the SAME
     * refresh token at the same instant (a {@link CountDownLatch} releases them
     * together) against the real endpoint and a real MySQL. The window between
     * "check the token is active" and "revoke + rotate it" is closed by an atomic
     * compare-and-consume ({@code consumeIfActive}) — the auth analogue of the seat
     * race's {@code compareAndSetStatus}.
     *
     * <p>Crisp assertions: exactly <b>1</b> rotation succeeds (200), exactly
     * <b>threads-1</b> are rejected (401), <b>no other status</b> (a 500 would mean a
     * leaked race). The data-level guarantee is the real one — the token is consumed
     * <b>exactly once</b>, so the user has exactly two rows (the original + the single
     * winner's replacement), never a token rotated twice. Fail-safe reuse handling
     * then leaves <b>zero</b> active tokens: a lost-race presentation is treated as
     * theft, so the family sweep revokes the winner's fresh token too (deterministic
     * re-login). Parameterized over a small and a larger fan-out.
     *
     * <p>Intentionally an integration test against real MySQL: the guarantee lives in
     * InnoDB row-locking (current-read re-evaluation under the X-lock), not service
     * branching, so a mocked repository could not reproduce it.
     */
    @ParameterizedTest(name = "{0} concurrent refreshes of one token -> 1 success, {0}-1 rejected")
    @ValueSource(ints = {2, 8})
    void concurrentRefreshOfSameToken_rotatesExactlyOnce(int threads) throws Exception {
        String email = uniqueEmail();
        long userId = signup(email, "password123", "Race User");
        String refreshToken = loginJson(email, "password123").get("refreshToken").asText();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(racingRefresh(start, refreshToken)));
        }
        start.countDown(); // fire them all at once

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get());
        }
        pool.shutdown();

        long successes = statuses.stream().filter(s -> s == 200).count();
        long rejected = statuses.stream().filter(s -> s == 401).count();
        System.out.printf(
                "[refresh TOCTOU proof] threads=%d -> 200 x%d, 401 x%d (statuses=%s)%n",
                threads, successes, rejected, statuses);

        // Exactly one rotation wins; everyone else a clean 401; nothing else (no 500).
        assertThat(successes).isEqualTo(1);
        assertThat(rejected).isEqualTo(threads - 1L);
        assertThat(statuses).allMatch(s -> s == 200 || s == 401);

        // Consumed EXACTLY once: original + the single winner's replacement = 2 rows.
        // (A double-rotation would leave 3+.) This is what actually proves the fix,
        // not the response codes.
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
        assertThat(total).isEqualTo(2);

        // Fail-safe: a lost-race presentation is reuse, so the family sweep revokes the
        // winner's fresh token too -> no active tokens remain, the user must re-login.
        Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? "
                        + "AND revoked_at IS NULL AND expires_at > ?",
                Integer.class, userId, Timestamp.valueOf(LocalDateTime.now()));
        assertThat(active).isZero();
    }

    // --- helpers ---

    private Callable<Integer> racingRefresh(CountDownLatch start, String refreshToken) {
        return () -> {
            start.await();
            return refresh(refreshToken).andReturn().getResponse().getStatus();
        };
    }

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
