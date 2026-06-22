package org.example.moviereservationsystem.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.example.moviereservationsystem.repository.TheaterRoomRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 6 acceptance coverage for the admin reports, against the real (Docker)
 * MySQL. The DB is shared across test classes and runs persist, so NO assertion
 * here uses a global absolute total (another class's CONFIRMED reservations would
 * make that flaky). Instead each assertion is scoped to data THIS test owns: the
 * by-movie slice for a movie it created, a before/after delta, relative ordering
 * of its own movies, or a date window nothing falls in. (This is the same
 * isolation gap flagged for Phase 7; aggregates are where it bites hardest.)
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TheaterRoomRepository theaterRoomRepository;

    @Value("${app.admin.email}")
    private String adminEmail;

    @Value("${app.admin.password}")
    private String adminPassword;

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

        JsonNode popular = getJson(admin, "/api/admin/reports/popular-movies?limit=100");
        // Relative ordering of OUR movies (owned): more tickets ranks higher.
        assertThat(indexOfMovie(popular, high)).isLessThan(indexOfMovie(popular, low));
        // Soft-deleted movie is EXCLUDED from the "promote now" list.
        assertThat(indexOfMovie(popular, deleted)).isEqualTo(-1);

        // ...but its revenue is still counted (the money was real).
        JsonNode byMovie = getJson(admin, "/api/admin/reports/revenue/by-movie");
        assertThat(movieRevenue(byMovie, deleted)).isEqualTo(20.0); // 2 x 10.00
    }

    @Test
    void zeroData_pastWindow_yieldsZeroAndEmpty_notNull() throws Exception {
        String admin = adminToken();
        // Nothing has a createdAt this old (created_at is stamped at booking time),
        // so this window is genuinely empty across the whole shared DB.
        String window = "from=2000-01-01&to=2000-01-02";

        JsonNode revenue = getJson(admin, "/api/admin/reports/revenue?" + window);
        assertThat(revenue.get("totalRevenue").asDouble()).isEqualTo(0.0); // SUM over no rows -> 0, not null
        assertThat(revenue.get("confirmedReservations").asLong()).isEqualTo(0);

        assertThat(getJson(admin, "/api/admin/reports/revenue/by-movie?" + window)).isEmpty();
        assertThat(getJson(admin, "/api/admin/reports/popular-movies?" + window)).isEmpty();
    }

    @Test
    void badParams_return400() throws Exception {
        String admin = adminToken();
        // from after to.
        mockMvc.perform(get("/api/admin/reports/revenue?from=2026-06-22&to=2026-06-01")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
        // limit below 1.
        mockMvc.perform(get("/api/admin/reports/popular-movies?limit=0")
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

    // --- report helpers ---

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

    // --- flow helpers ---

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

    private List<Long> availableSeatIds(long showtimeId, int n) throws Exception {
        JsonNode map = read(mockMvc.perform(get("/api/showtimes/" + showtimeId + "/seats"))
                .andExpect(status().isOk())
                .andReturn());
        List<Long> ids = new ArrayList<>();
        for (JsonNode seat : map.get("seats")) {
            if ("AVAILABLE".equals(seat.get("status").asText())) {
                ids.add(seat.get("showtimeSeatId").asLong());
                if (ids.size() == n) {
                    break;
                }
            }
        }
        assertThat(ids).hasSize(n);
        return ids;
    }

    private long createMovie(String admin, int durationMinutes) throws Exception {
        MovieBody body = new MovieBody("Movie " + uid(), null, null, durationMinutes, List.of());
        return read(mockMvc.perform(post("/api/admin/movies")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asLong();
    }

    private void softDeleteMovie(String admin, long movieId) throws Exception {
        mockMvc.perform(delete("/api/admin/movies/" + movieId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
    }

    private long newShowtime(String admin, long movieId, BigDecimal price) throws Exception {
        long roomId = theaterRoomRepository.findByName("Room 1").orElseThrow().getId();
        return read(mockMvc.perform(post("/api/admin/showtimes")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ShowtimeBody(movieId, roomId, uniqueFutureStart(), price))))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asLong();
    }

    private LocalDateTime uniqueFutureStart() {
        // Far future + large random offset so same-room overlap never trips
        // creation across tests/classes/repeated runs in the shared DB.
        return LocalDateTime.now().plusYears(1).withSecond(0).withNano(0)
                .plusMinutes(ThreadLocalRandom.current().nextLong(0, 5_000_000));
    }

    // --- http / auth helpers ---

    private JsonNode getJson(String token, String path) throws Exception {
        return read(mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
    }

    private String adminToken() throws Exception {
        return login(adminEmail, adminPassword);
    }

    private String userToken() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SignupBody(email, "password123", "User"))))
                .andExpect(status().isCreated());
        return login(email, "password123");
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginBody(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).get("token").asText();
    }

    private String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record HoldBody(Long showtimeId, List<Long> showtimeSeatIds) {
    }

    private record ShowtimeBody(
            Long movieId, Long theaterRoomId, LocalDateTime startTime, BigDecimal price) {
    }

    private record MovieBody(
            String title, String description, String posterUrl,
            Integer durationMinutes, List<String> genres) {
    }

    private record SignupBody(String email, String password, String name) {
    }

    private record LoginBody(String email, String password) {
    }
}
