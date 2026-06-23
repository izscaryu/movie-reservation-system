package org.example.moviereservationsystem.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.example.moviereservationsystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Phase 7 coverage for GET /api/admin/reservations: pagination, the status and
 * created_at filters (reusing the shared date helper), the whitelisted sort, and
 * the admin tier (USER -> 403, no token -> 401).
 */
class AdminReservationIntegrationTest extends AbstractIntegrationTest {

    @Test
    void list_isPaginated_andIncludesUser() throws Exception {
        String admin = adminToken();
        String user = userToken();
        long showtimeId = freshShowtime(admin, new BigDecimal("10.00"));
        List<Long> seats = availableSeatIds(showtimeId, 5);
        confirm(user, hold(user, showtimeId, List.of(seats.get(0))));
        hold(user, showtimeId, List.of(seats.get(1)));          // PENDING
        cancel(user, hold(user, showtimeId, List.of(seats.get(2)))); // CANCELLED

        JsonNode page = list(admin, "?page=0&size=2");
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page.get("content")).hasSize(2);
        // Admin view exposes the owning user (the point of this endpoint).
        JsonNode row = page.get("content").get(0);
        assertThat(row.get("userId").asLong()).isPositive();
        assertThat(row.get("userEmail").asText()).contains("@");
        assertThat(row.get("userName").isNull()).isFalse();
    }

    @Test
    void filterByStatus_returnsOnlyThatStatus() throws Exception {
        String admin = adminToken();
        String user = userToken();
        long showtimeId = freshShowtime(admin, new BigDecimal("10.00"));
        List<Long> seats = availableSeatIds(showtimeId, 3);
        confirm(user, hold(user, showtimeId, List.of(seats.get(0))));
        hold(user, showtimeId, List.of(seats.get(1)));          // PENDING
        cancel(user, hold(user, showtimeId, List.of(seats.get(2)))); // CANCELLED

        JsonNode confirmed = list(admin, "?status=CONFIRMED");
        assertThat(confirmed.get("totalElements").asLong()).isEqualTo(1);
        assertThat(confirmed.get("content").get(0).get("status").asText()).isEqualTo("CONFIRMED");

        assertThat(list(admin, "?status=PENDING").get("totalElements").asLong()).isEqualTo(1);
        assertThat(list(admin, "?status=CANCELLED").get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void filterByCreatedAtRange_inclusiveToday_emptyPastWindow_invertedIs400() throws Exception {
        String admin = adminToken();
        String user = userToken();
        long showtimeId = freshShowtime(admin, new BigDecimal("10.00"));
        confirm(user, hold(user, showtimeId, availableSeatIds(showtimeId, 1)));

        // created_at is stamped "now": a window covering today includes it...
        LocalDate today = LocalDate.now();
        assertThat(list(admin, "?from=" + today + "&to=" + today).get("totalElements").asLong())
                .isEqualTo(1);
        // ...a long-past window is genuinely empty...
        assertThat(list(admin, "?from=2000-01-01&to=2000-01-02").get("totalElements").asLong())
                .isZero();
        // ...and an inverted range is a 400 (shared date helper).
        mockMvc.perform(get("/api/admin/reservations?from=2026-06-22&to=2026-06-01")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sort_isWhitelisted() throws Exception {
        String admin = adminToken();
        String user = userToken();
        long showtimeId = freshShowtime(admin, new BigDecimal("10.00"));
        List<Long> seats = availableSeatIds(showtimeId, 6);
        // totalPrice = 10 * seatCount -> 10, 30, 20.
        hold(user, showtimeId, List.of(seats.get(0)));                                  // 10.00
        hold(user, showtimeId, List.of(seats.get(1), seats.get(2), seats.get(3)));      // 30.00
        hold(user, showtimeId, List.of(seats.get(4), seats.get(5)));                    // 20.00

        JsonNode asc = list(admin, "?sort=totalPrice&direction=asc");
        JsonNode content = asc.get("content");
        assertThat(content.get(0).get("totalPrice").asDouble()).isEqualTo(10.0);
        assertThat(content.get(1).get("totalPrice").asDouble()).isEqualTo(20.0);
        assertThat(content.get(2).get("totalPrice").asDouble()).isEqualTo(30.0);

        // A non-whitelisted field is rejected, not silently passed to the query.
        mockMvc.perform(get("/api/admin/reservations?sort=user.password")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
        // A bad direction is rejected too.
        mockMvc.perform(get("/api/admin/reservations?direction=sideways")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidStatusValue_returns400() throws Exception {
        mockMvc.perform(get("/api/admin/reservations?status=BOGUS")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void user_forbidden_noToken_unauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/reservations")
                        .header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/reservations"))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ---

    private JsonNode list(String admin, String query) throws Exception {
        return read(mockMvc.perform(get("/api/admin/reservations" + query)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn());
    }

    private long freshShowtime(String admin, BigDecimal price) throws Exception {
        long movieId = createMovie(admin, 120);
        return createShowtime(admin, movieId, roomId("Room 1"), nextFutureSlot(), price);
    }

    private long hold(String token, long showtimeId, List<Long> seatIds) throws Exception {
        return read(mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HoldBody(showtimeId, seatIds))))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asLong();
    }

    private void confirm(String token, long reservationId) throws Exception {
        mockMvc.perform(post("/api/reservations/" + reservationId + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void cancel(String token, long reservationId) throws Exception {
        mockMvc.perform(delete("/api/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }
}
