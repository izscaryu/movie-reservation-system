package org.example.moviereservationsystem.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.example.moviereservationsystem.entity.TheaterRoom;
import org.example.moviereservationsystem.repository.TheaterRoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;

/**
 * Shared base for every integration test. It provides:
 *
 * <ul>
 *   <li>A single throwaway MySQL (Testcontainers) started once per JVM and reused
 *       by every test class, so the Spring context is built once and cached.
 *   <li>A per-test wipe of the transactional tables, leaving the seeded data
 *       (admin user, theater rooms, seats) intact.
 *   <li>Deterministic, collision-free future showtime slots.
 *   <li>The token / movie / showtime / seat helpers that previously lived,
 *       duplicated, in each test class.
 * </ul>
 *
 * <p>Deliberately NOT {@code @Transactional}: the concurrency tests rely on real
 * commits racing, so nothing here may roll back behind the scenes. Isolation
 * comes from the per-test truncate instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    // One container for the whole suite. Started in a static initializer (before
    // Spring), stopped by Testcontainers' Ryuk at JVM exit. The image matches
    // production (mysql:8.4) and docker-compose.
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("movie_reservation")
                    .withUsername("movieuser")
                    .withPassword("moviepass");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /**
     * Transactional tables, in FK-safe (child -> parent) order. The seed tables
     * (users, theater_rooms, seats) are deliberately excluded: login, showtime
     * creation and the expiry job all depend on them, and the CommandLineRunner
     * seeders populate them once at context start.
     */
    private static final List<String> TRANSACTIONAL_TABLES = List.of(
            "reservation_seats",
            "reservations",
            "showtime_seats",
            "showtimes",
            "movie_genre",
            "movies",
            "genres");

    // Deterministic, monotonically increasing far-future slots a full day apart.
    // Replaces the old random-offset hack: per-test truncate removes any chance of
    // cross-test collision, and the day spacing keeps successive same-room
    // showtimes from overlapping within a test.
    private static final LocalDateTime TIME_BASE =
            LocalDateTime.now().plusYears(1).truncatedTo(ChronoUnit.MINUTES);
    private static final AtomicLong SLOT_COUNTER = new AtomicLong();

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected TheaterRoomRepository theaterRoomRepository;

    @Value("${app.admin.email}")
    protected String adminEmail;

    @Value("${app.admin.password}")
    protected String adminPassword;

    @BeforeEach
    void wipeTransactionalTables() {
        // All statements on ONE connection: FOREIGN_KEY_CHECKS is session-scoped,
        // and pooled JdbcTemplate calls could otherwise toggle it on a different
        // connection than the TRUNCATEs run on.
        jdbcTemplate.execute((Connection con) -> {
            try (Statement st = con.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (String table : TRANSACTIONAL_TABLES) {
                    st.execute("TRUNCATE TABLE " + table);
                }
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
    }

    // --- time ---

    protected LocalDateTime nextFutureSlot() {
        return TIME_BASE.plusDays(SLOT_COUNTER.getAndIncrement());
    }

    // --- rooms ---

    protected TheaterRoom room(String name) {
        return theaterRoomRepository.findByName(name).orElseThrow();
    }

    protected long roomId(String name) {
        return room(name).getId();
    }

    // --- auth ---

    protected String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected long signup(String email, String password, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SignupBody(email, password, name))))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result).get("id").asLong();
    }

    protected String adminToken() throws Exception {
        return login(adminEmail, adminPassword);
    }

    protected String userToken() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        signup(email, "password123", "User");
        return login(email, "password123");
    }

    protected String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginBody(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).get("token").asText();
    }

    // --- movies / showtimes ---

    protected long createMovie(String adminToken, int durationMinutes) throws Exception {
        return createMovie(adminToken,
                new MovieBody("Movie " + uid(), null, null, durationMinutes, List.of()));
    }

    protected long createMovie(String adminToken, MovieBody body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/movies")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result).get("id").asLong();
    }

    protected long createShowtime(
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

    // --- seats ---

    protected List<Long> availableSeatIds(long showtimeId, int n) throws Exception {
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

    protected String seatStatus(long showtimeId, Long showtimeSeatId) throws Exception {
        JsonNode map = read(mockMvc.perform(get("/api/showtimes/" + showtimeId + "/seats"))
                .andExpect(status().isOk())
                .andReturn());
        for (JsonNode seat : map.get("seats")) {
            if (seat.get("showtimeSeatId").asLong() == showtimeSeatId) {
                return seat.get("status").asText();
            }
        }
        throw new AssertionError("seat " + showtimeSeatId + " not in map");
    }

    // --- json ---

    protected JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // --- shared request bodies (public so subclasses in other packages can construct them) ---

    public record SignupBody(String email, String password, String name) {
    }

    public record LoginBody(String email, String password) {
    }

    public record MovieBody(
            String title, String description, String posterUrl,
            Integer durationMinutes, List<String> genres) {
    }

    public record ShowtimeBody(
            Long movieId, Long theaterRoomId, LocalDateTime startTime, BigDecimal price) {
    }

    public record HoldBody(Long showtimeId, List<Long> showtimeSeatIds) {
    }
}
