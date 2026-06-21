package org.example.moviereservationsystem.showtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.example.moviereservationsystem.entity.TheaterRoom;
import org.example.moviereservationsystem.repository.SeatRepository;
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
 * Phase 4 acceptance coverage for showtime + seat setup. Runs against the real
 * (Docker) MySQL like the Phase 2/3 tests. Relies on the rooms seeded by
 * RoomSeatInitializer (Room 1/2/3); start times are pushed well into the future
 * and spaced per test so unrelated runs in the shared DB never collide.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShowtimeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TheaterRoomRepository theaterRoomRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Value("${app.admin.email}")
    private String adminEmail;

    @Value("${app.admin.password}")
    private String adminPassword;

    @Test
    void create_autoGeneratesOneAvailableSeatPerRoomSeat() throws Exception {
        String admin = adminToken();
        long movieId = createMovie(admin, 120);
        // Room 2 is seeded 8x10 = 80 seats.
        TheaterRoom room2 = room("Room 2");
        long expectedSeats = seatRepository.findByTheaterRoomId(room2.getId()).size();
        assertThat(expectedSeats).isEqualTo(80);

        long showtimeId = createShowtime(
                admin, movieId, room2.getId(), futureSlot(), new BigDecimal("12.50"));

        JsonNode seatMap = read(mockMvc.perform(get("/api/showtimes/" + showtimeId + "/seats"))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode seats = seatMap.get("seats");
        // One ShowtimeSeat per physical seat, matching the room dimensions...
        assertThat(seats).hasSize((int) expectedSeats);
        // ...and every generated seat starts AVAILABLE.
        for (JsonNode seat : seats) {
            assertThat(seat.get("status").asText()).isEqualTo("AVAILABLE");
        }
        // First entry is A1 (ordered layout, label derived from row+number).
        assertThat(seats.get(0).get("label").asText()).isEqualTo("A1");
    }

    @Test
    void overlap_sameRoomRejected_differentRoomOrNonOverlapAllowed() throws Exception {
        String admin = adminToken();
        long movieId = createMovie(admin, 120); // endTime = start + 120 min
        TheaterRoom room1 = room("Room 1");
        TheaterRoom room3 = room("Room 3");
        LocalDateTime start = futureSlot();

        // Baseline showtime in Room 1: [start, start+120m).
        createShowtime(admin, movieId, room1.getId(), start, new BigDecimal("10.00"));

        // Genuinely overlapping in the SAME room (starts 60m in) -> 409.
        expectCreateStatus(admin, movieId, room1.getId(), start.plusMinutes(60), 409);

        // Same overlapping window but a DIFFERENT room -> allowed (201).
        expectCreateStatus(admin, movieId, room3.getId(), start.plusMinutes(60), 201);
    }

    @Test
    void overlapBoundary_backToBackAllowed_touchingIsNotOverlap() throws Exception {
        String admin = adminToken();
        long movieId = createMovie(admin, 120);
        TheaterRoom room1 = room("Room 1");
        // Place this block a day after the other overlap test's slot to avoid
        // colliding with it in the shared DB.
        LocalDateTime start = futureSlot().plusDays(1);

        // First showtime occupies [start, start+120m).
        createShowtime(admin, movieId, room1.getId(), start, new BigDecimal("10.00"));

        // Next starts EXACTLY when the first ends (start+120m). Strict < / >
        // boundary => touching is not an overlap => allowed (201).
        expectCreateStatus(admin, movieId, room1.getId(), start.plusMinutes(120), 201);

        // One minute earlier genuinely overlaps the first => rejected (409).
        expectCreateStatus(admin, movieId, room1.getId(), start.plusMinutes(119), 409);
    }

    @Test
    void create_forSoftDeletedMovie_returns404() throws Exception {
        String admin = adminToken();
        long movieId = createMovie(admin, 100);
        mockMvc.perform(delete("/api/admin/movies/" + movieId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        expectCreateStatus(admin, movieId, room("Room 1").getId(), futureSlot(), 404);
    }

    @Test
    void publicReads_workWithoutToken_andHideSoftDeletedMovie() throws Exception {
        String admin = adminToken();
        long movieId = createMovie(admin, 110);
        long showtimeId = createShowtime(
                admin, movieId, room("Room 2").getId(), futureSlot().plusDays(2),
                new BigDecimal("9.00"));

        // Public list + seat map work with NO token.
        JsonNode list = read(mockMvc.perform(get("/api/movies/" + movieId + "/showtimes"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(list).hasSize(1);
        mockMvc.perform(get("/api/showtimes/" + showtimeId + "/seats"))
                .andExpect(status().isOk());

        // Soft-delete the movie: both reads now hide it (404).
        mockMvc.perform(delete("/api/admin/movies/" + movieId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/movies/" + movieId + "/showtimes"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/showtimes/" + showtimeId + "/seats"))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminCreate_userToken403_noToken401() throws Exception {
        String user = userToken();
        String body = objectMapper.writeValueAsString(new ShowtimeBody(
                1L, 1L, futureSlot(), new BigDecimal("10.00")));

        mockMvc.perform(post("/api/admin/showtimes")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/showtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidRequests_return400() throws Exception {
        String admin = adminToken();
        long roomId = room("Room 1").getId();
        long movieId = createMovie(admin, 100);

        // Missing movieId.
        expectBadRequest(admin, new ShowtimeBody(null, roomId, futureSlot(), new BigDecimal("10.00")));
        // Missing startTime.
        expectBadRequest(admin, new ShowtimeBody(movieId, roomId, null, new BigDecimal("10.00")));
        // Non-positive price.
        expectBadRequest(admin, new ShowtimeBody(movieId, roomId, futureSlot(), new BigDecimal("0.00")));
        // Past start time (@Future).
        expectBadRequest(admin, new ShowtimeBody(
                movieId, roomId, LocalDateTime.now().minusDays(1), new BigDecimal("10.00")));
    }

    // --- helpers ---

    private LocalDateTime futureSlot() {
        // Far future + minute precision so each run's window is isolated.
        return LocalDateTime.now().plusYears(1).withSecond(0).withNano(0);
    }

    private TheaterRoom room(String name) {
        return theaterRoomRepository.findByName(name).orElseThrow();
    }

    private long createMovie(String adminToken, int durationMinutes) throws Exception {
        MovieBody body = new MovieBody(
                "Movie " + uid(), null, null, durationMinutes, List.of());
        MvcResult result = mockMvc.perform(post("/api/admin/movies")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result).get("id").asLong();
    }

    private long createShowtime(
            String adminToken, long movieId, long roomId, LocalDateTime start, BigDecimal price)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/showtimes")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ShowtimeBody(movieId, roomId, start, price))))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result).get("id").asLong();
    }

    private void expectCreateStatus(
            String adminToken, long movieId, long roomId, LocalDateTime start, int expectedStatus)
            throws Exception {
        mockMvc.perform(post("/api/admin/showtimes")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ShowtimeBody(movieId, roomId, start, new BigDecimal("10.00")))))
                .andExpect(status().is(expectedStatus));
    }

    private void expectBadRequest(String adminToken, ShowtimeBody body) throws Exception {
        mockMvc.perform(post("/api/admin/showtimes")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    private String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String adminToken() throws Exception {
        return login(adminEmail, adminPassword);
    }

    private String userToken() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        String body = objectMapper.writeValueAsString(new SignupBody(email, "password123", "User"));
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        return login(email, "password123");
    }

    private String login(String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginBody(email, password));
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).get("token").asText();
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
