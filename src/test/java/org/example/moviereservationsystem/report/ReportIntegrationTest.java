package org.example.moviereservationsystem.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import org.example.moviereservationsystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 6 acceptance coverage for the admin reports. Assertions stay scoped to
 * data each test owns (by-movie slice, before/after delta, relative ordering, an
 * empty date window) — robust both before and after the base-class per-test
 * truncate, and a guard against any accidental reliance on global absolute totals.
 */
class ReportIntegrationTest extends AbstractIntegrationTest {

    @Test
    void revenue_countsConfirmedOnly() throws Exception {
        String admin = adminToken();
        String user = userToken();
        long movieId = createMovie(admin, 120);
        long showtimeId = newShowtime(admin, movieId, new BigDecimal("10.00"));
        List<Long> seats = availableSeatIds(showtimeId, 4);

        double before = revenueTotal(admin);

        holdAndConfirm(user, showtimeId, seats.subList(0, 2)); // CONFIRMED: 2 x 10 = 20.00
        hold(user, showtimeId, List.of(seats.get(2)));         // PENDING: earns nothing
        holdThenCancel(user, showtimeId, List.of(seats.get(3))); // CANCELLED: earns nothing

        // Owned slice: only the CONFIRMED reservation counts toward this movie.
        JsonNode byMovie = getJson(admin, "/api/admin/reports/revenue/by-movie");
        assertThat(movieRevenue(byMovie, movieId)).isEqualTo(20.0);

        // Owned delta on the global total: PENDING + CANCELLED added 0.
        double after = revenueTotal(admin);
        assertThat(after - before).isEqualTo(20.0);
    }

    @Test
    void occupancy_knownRatio_excludesHeldSeats() throws Exception {
        String admin = adminToken();
        String user = userToken();
        long movieId = createMovie(admin, 120);
        long showtimeId = newShowtime(admin, movieId, new BigDecimal("10.00")); // Room 1 = 40 seats
        List<Long> seats = availableSeatIds(showtimeId, 3);

        holdAndConfirm(user, showtimeId, seats.subList(0, 2)); // 2 BOOKED
        hold(user, showtimeId, List.of(seats.get(2)));         // 1 HELD -> must NOT count

        JsonNode occ = getJson(admin, "/api/admin/reports/occupancy?showtimeId=" + showtimeId);
        assertThat(occ.get("totalSeats").asLong()).isEqualTo(40);
        assertThat(occ.get("bookedSeats").asLong()).isEqualTo(2);
        assertThat(occ.get("occupancyRate").asDouble()).isEqualTo(5.0); // 2/40 = 5.00%
    }

    @Test
    void occupancy_unknownShowtime_returns404() throws Exception {
        mockMvc.perform(get("/api/admin/reports/occupancy?showtimeId=999999999")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void popularMovies_orderingAndSoftDeletePolicy() throws Exception {
        String admin = adminToken();
        String user = userToken();

        long high = createMovie(admin, 120);
        long highShowtime = newShowtime(admin, high, new BigDecimal("10.00"));
        holdAndConfirm(user, highShowtime, availableSeatIds(highShowtime, 3)); // 3 tickets

        long low = createMovie(admin, 120);
        long lowShowtime = newShowtime(admin, low, new BigDecimal("10.00"));
        holdAndConfirm(user, lowShowtime, availableSeatIds(lowShowtime, 1)); // 1 ticket

        long deleted = createMovie(admin, 120);
        long deletedShowtime = newShowtime(admin, deleted, new BigDecimal("10.00"));
        holdAndConfirm(user, deletedShowtime, availableSeatIds(deletedShowtime, 2)); // 2 tickets
        softDeleteMovie(admin, deleted);

        JsonNode popular = getJson(admin, "/api/admin/reports/popular-movies?size=100").get("content");
        // Relative ordering of OUR movies (owned): more tickets ranks higher.
        assertThat(indexOfMovie(popular, high)).isLessThan(indexOfMovie(popular, low));
        // Soft-deleted movie is EXCLUDED from the "promote now" list.
        assertThat(indexOfMovie(popular, deleted)).isEqualTo(-1);

        // ...but its revenue is still counted (the money was real).
        JsonNode byMovie = getJson(admin, "/api/admin/reports/revenue/by-movie");
        assertThat(movieRevenue(byMovie, deleted)).isEqualTo(20.0); // 2 x 10.00
    }

    @Test
    void popularMovies_isPaginated() throws Exception {
        String admin = adminToken();
        String user = userToken();
        // Five movies with strictly descending ticket counts 5..1.
        for (int tickets = 5; tickets >= 1; tickets--) {
            long movieId = createMovie(admin, 120);
            long showtimeId = newShowtime(admin, movieId, new BigDecimal("10.00"));
            holdAndConfirm(user, showtimeId, availableSeatIds(showtimeId, tickets));
        }

        JsonNode page0 = getJson(admin, "/api/admin/reports/popular-movies?page=0&size=2");
        assertThat(page0.get("totalElements").asLong()).isEqualTo(5);
        assertThat(page0.get("totalPages").asInt()).isEqualTo(3);
        assertThat(page0.get("content")).hasSize(2);
        // Highest ticket count first.
        assertThat(page0.get("content").get(0).get("ticketsSold").asLong()).isEqualTo(5);
        assertThat(page0.get("content").get(1).get("ticketsSold").asLong()).isEqualTo(4);

        JsonNode page1 = getJson(admin, "/api/admin/reports/popular-movies?page=1&size=2");
        // Order holds across the boundary: page 1 starts no higher than page 0 ended.
        assertThat(page1.get("content").get(0).get("ticketsSold").asLong())
                .isLessThanOrEqualTo(page0.get("content").get(1).get("ticketsSold").asLong());

        JsonNode page2 = getJson(admin, "/api/admin/reports/popular-movies?page=2&size=2");
        assertThat(page2.get("content")).hasSize(1);
        assertThat(page2.get("last").asBoolean()).isTrue();
    }

    @Test
    void zeroData_pastWindow_yieldsZeroAndEmpty_notNull() throws Exception {
        String admin = adminToken();
        // Nothing has a createdAt this old (created_at is stamped at booking time),
        // so this window is genuinely empty.
        String window = "from=2000-01-01&to=2000-01-02";

        JsonNode revenue = getJson(admin, "/api/admin/reports/revenue?" + window);
        assertThat(revenue.get("totalRevenue").asDouble()).isEqualTo(0.0); // SUM over no rows -> 0, not null
        assertThat(revenue.get("confirmedReservations").asLong()).isEqualTo(0);

        assertThat(getJson(admin, "/api/admin/reports/revenue/by-movie?" + window)).isEmpty();
        assertThat(getJson(admin, "/api/admin/reports/popular-movies?" + window).get("content")).isEmpty();
    }

    @Test
    void badParams_return400() throws Exception {
        String admin = adminToken();
        // from after to.
        mockMvc.perform(get("/api/admin/reports/revenue?from=2026-06-22&to=2026-06-01")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
        // page size below 1.
        mockMvc.perform(get("/api/admin/reports/popular-movies?size=0")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void user_forbidden_noToken_unauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/reports/revenue")
                        .header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/reports/revenue"))
                .andExpect(status().isUnauthorized());
    }

    // --- report-specific helpers ---

    private double revenueTotal(String admin) throws Exception {
        return getJson(admin, "/api/admin/reports/revenue").get("totalRevenue").asDouble();
    }

    // Revenue for a specific movie from a by-movie list, or 0.0 if absent.
    private double movieRevenue(JsonNode byMovie, long movieId) {
        for (JsonNode row : byMovie) {
            if (row.get("movieId").asLong() == movieId) {
                return row.get("revenue").asDouble();
            }
        }
        return 0.0;
    }

    private int indexOfMovie(JsonNode list, long movieId) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).get("movieId").asLong() == movieId) {
                return i;
            }
        }
        return -1;
    }

    private void holdAndConfirm(String user, long showtimeId, List<Long> seatIds) throws Exception {
        long id = read(hold(user, showtimeId, seatIds)).get("id").asLong();
        mockMvc.perform(post("/api/reservations/" + id + "/confirm")
                        .header("Authorization", "Bearer " + user))
                .andExpect(status().isOk());
    }

    private void holdThenCancel(String user, long showtimeId, List<Long> seatIds) throws Exception {
        long id = read(hold(user, showtimeId, seatIds)).get("id").asLong();
        mockMvc.perform(delete("/api/reservations/" + id)
                        .header("Authorization", "Bearer " + user))
                .andExpect(status().isNoContent());
    }

    private MvcResult hold(String token, long showtimeId, List<Long> seatIds) throws Exception {
        return mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HoldBody(showtimeId, seatIds))))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private void softDeleteMovie(String admin, long movieId) throws Exception {
        mockMvc.perform(delete("/api/admin/movies/" + movieId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
    }

    private long newShowtime(String admin, long movieId, BigDecimal price) throws Exception {
        return createShowtime(admin, movieId, roomId("Room 1"), nextFutureSlot(), price);
    }

    private JsonNode getJson(String token, String path) throws Exception {
        return read(mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
    }
}
