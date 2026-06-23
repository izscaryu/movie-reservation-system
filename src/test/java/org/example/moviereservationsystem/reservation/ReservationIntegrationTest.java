package org.example.moviereservationsystem.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.example.moviereservationsystem.entity.Reservation;
import org.example.moviereservationsystem.job.ReservationExpiryJob;
import org.example.moviereservationsystem.repository.ReservationRepository;
import org.example.moviereservationsystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 5 acceptance coverage for the reservation flow. These tests are
 * intentionally NOT @Transactional (inherited from the base): the overbooking
 * guarantee depends on real commits racing, so nothing here may roll back behind
 * the scenes. Per-test isolation comes from the base-class truncate, and each
 * showtime gets a unique day-spaced start so same-room overlap never trips.
 */
class ReservationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationExpiryJob expiryJob;

    @Test
    void happyPath_holdThenConfirm() throws Exception {
        long showtimeId = newShowtime(120, new BigDecimal("10.00"));
        List<Long> seatIds = availableSeatIds(showtimeId, 2);
        String user = userToken();

        JsonNode held = read(holdOk(user, showtimeId, seatIds));
        long reservationId = held.get("id").asLong();
        assertThat(held.get("status").asText()).isEqualTo("PENDING");
        assertThat(held.get("expiresAt").isNull()).isFalse();
        assertThat(held.get("totalPrice").asDouble()).isEqualTo(20.0); // 2 x 10.00
        assertThat(held.get("seats")).hasSize(2);
        assertThat(seatStatus(showtimeId, seatIds.get(0))).isEqualTo("HELD");

        JsonNode confirmed = read(mockMvc.perform(post("/api/reservations/" + reservationId + "/confirm")
                        .header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("expiresAt").isNull()).isTrue();
        assertThat(seatStatus(showtimeId, seatIds.get(0))).isEqualTo("BOOKED");
        assertThat(seatStatus(showtimeId, seatIds.get(1))).isEqualTo("BOOKED");
    }

    @Test
    void holdAlreadyHeldSeat_returns409() throws Exception {
        long showtimeId = newShowtime(100, new BigDecimal("10.00"));
        Long seat = availableSeatIds(showtimeId, 1).get(0);

        holdOk(userToken(), showtimeId, List.of(seat));
        // A different user grabs the same seat -> conflict.
        assertThat(holdStatus(userToken(), showtimeId, List.of(seat))).isEqualTo(409);
    }

    @Test
    void multiSeatHoldIsAtomic_partialConflictLeavesOthersAvailable() throws Exception {
        long showtimeId = newShowtime(100, new BigDecimal("10.00"));
        List<Long> seats = availableSeatIds(showtimeId, 5);
        List<Long> aSeats = seats.subList(0, 3);          // s1,s2,s3
        List<Long> bSeats = List.of(seats.get(2), seats.get(3), seats.get(4)); // s3,s4,s5 (s3 shared)

        holdOk(userToken(), showtimeId, aSeats);
        // B collides on s3 -> whole request rejected...
        assertThat(holdStatus(userToken(), showtimeId, bSeats)).isEqualTo(409);
        // ...and B's other two seats must NOT have been left HELD.
        assertThat(seatStatus(showtimeId, seats.get(3))).isEqualTo("AVAILABLE");
        assertThat(seatStatus(showtimeId, seats.get(4))).isEqualTo("AVAILABLE");
        // s3 stays held by A.
        assertThat(seatStatus(showtimeId, seats.get(2))).isEqualTo("HELD");
    }

    @Test
    void concurrentHoldsOnSameSeat_exactlyOneWins() throws Exception {
        long showtimeId = newShowtime(100, new BigDecimal("10.00"));
        Long seat = availableSeatIds(showtimeId, 1).get(0);
        String userA = userToken();
        String userB = userToken();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> holdA = racingHold(start, userA, showtimeId, seat);
        Callable<Integer> holdB = racingHold(start, userB, showtimeId, seat);
        Future<Integer> fa = pool.submit(holdA);
        Future<Integer> fb = pool.submit(holdB);
        start.countDown(); // fire both at once
        int sa = fa.get();
        int sb = fb.get();
        pool.shutdown();

        // Exactly one 201 and one 409 — no double-book, no 500.
        assertThat(List.of(sa, sb)).containsExactlyInAnyOrder(201, 409);
        // And the seat ended up held exactly once.
        assertThat(seatStatus(showtimeId, seat)).isEqualTo("HELD");
    }

    @Test
    void expiryJob_marksExpiredAndReleasesSeats() throws Exception {
        long showtimeId = newShowtime(100, new BigDecimal("10.00"));
        Long seat = availableSeatIds(showtimeId, 1).get(0);
        long reservationId = read(holdOk(userToken(), showtimeId, List.of(seat))).get("id").asLong();

        backdateHold(reservationId);
        expiryJob.runOnce();

        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus().name())
                .isEqualTo("EXPIRED");
        assertThat(seatStatus(showtimeId, seat)).isEqualTo("AVAILABLE");
    }

    @Test
    void confirmAfterExpiry_returns409() throws Exception {
        long showtimeId = newShowtime(100, new BigDecimal("10.00"));
        Long seat = availableSeatIds(showtimeId, 1).get(0);
        String user = userToken();
        long reservationId = read(holdOk(user, showtimeId, List.of(seat))).get("id").asLong();

        backdateHold(reservationId);
        expiryJob.runOnce();

        mockMvc.perform(post("/api/reservations/" + reservationId + "/confirm")
                        .header("Authorization", "Bearer " + user))
                .andExpect(status().isConflict());
    }

    @Test
    void actingOnSomeoneElsesReservation_returns404() throws Exception {
        long showtimeId = newShowtime(100, new BigDecimal("10.00"));
        Long seat = availableSeatIds(showtimeId, 1).get(0);
        long reservationId = read(holdOk(userToken(), showtimeId, List.of(seat))).get("id").asLong();
        String otherUser = userToken();

        mockMvc.perform(post("/api/reservations/" + reservationId + "/confirm")
                        .header("Authorization", "Bearer " + otherUser))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + otherUser))
                .andExpect(status().isNotFound());
    }

    @Test
    void crossShowtimeSeatId_returns400() throws Exception {
        long showtimeA = newShowtime(100, new BigDecimal("10.00"));
        long showtimeB = newShowtime(100, new BigDecimal("10.00"));
        Long seatFromB = availableSeatIds(showtimeB, 1).get(0);

        // Posting a seat id that belongs to showtime B against showtime A -> 400.
        assertThat(holdStatus(userToken(), showtimeA, List.of(seatFromB))).isEqualTo(400);
    }

    @Test
    void noToken_returns401_andValidationReturns400() throws Exception {
        long showtimeId = newShowtime(100, new BigDecimal("10.00"));
        Long seat = availableSeatIds(showtimeId, 1).get(0);

        // No token.
        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HoldBody(showtimeId, List.of(seat)))))
                .andExpect(status().isUnauthorized());

        // Empty seat list -> validation 400.
        mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HoldBody(showtimeId, List.of()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mine_isPaginatedAndFiltered() throws Exception {
        long showtimeId = newShowtime(100, new BigDecimal("10.00"));
        List<Long> seats = availableSeatIds(showtimeId, 5);
        String user = userToken();
        // Five separate single-seat reservations for the one user.
        for (Long seat : seats) {
            holdOk(user, showtimeId, List.of(seat));
        }

        JsonNode page0 = read(mockMvc.perform(get("/api/reservations/me")
                        .header("Authorization", "Bearer " + user)
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk()).andReturn());
        assertThat(page0.get("totalElements").asLong()).isEqualTo(5);
        assertThat(page0.get("totalPages").asInt()).isEqualTo(3);
        assertThat(page0.get("content")).hasSize(2);
        assertThat(page0.get("first").asBoolean()).isTrue();

        JsonNode page2 = read(mockMvc.perform(get("/api/reservations/me")
                        .header("Authorization", "Bearer " + user)
                        .param("page", "2").param("size", "2"))
                .andExpect(status().isOk()).andReturn());
        assertThat(page2.get("content")).hasSize(1); // 5 = 2 + 2 + 1
        assertThat(page2.get("last").asBoolean()).isTrue();

        // Filter is applied at the DB level: every showtime is in the future, so
        // "upcoming" keeps all five and "past" matches none.
        long upcoming = read(mockMvc.perform(get("/api/reservations/me")
                        .header("Authorization", "Bearer " + user)
                        .param("filter", "upcoming"))
                .andExpect(status().isOk()).andReturn()).get("totalElements").asLong();
        assertThat(upcoming).isEqualTo(5);

        long past = read(mockMvc.perform(get("/api/reservations/me")
                        .header("Authorization", "Bearer " + user)
                        .param("filter", "past"))
                .andExpect(status().isOk()).andReturn()).get("totalElements").asLong();
        assertThat(past).isZero();
    }

    // --- reservation-specific helpers ---

    private Callable<Integer> racingHold(CountDownLatch start, String token, long showtimeId, Long seat) {
        return () -> {
            start.await();
            return holdStatus(token, showtimeId, List.of(seat));
        };
    }

    private void backdateHold(long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        reservation.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        reservationRepository.save(reservation);
    }

    private long newShowtime(int durationMinutes, BigDecimal price) throws Exception {
        String admin = adminToken();
        long movieId = createMovie(admin, durationMinutes);
        return createShowtime(admin, movieId, roomId("Room 1"), nextFutureSlot(), price);
    }

    private MvcResult holdOk(String token, long showtimeId, List<Long> seatIds) throws Exception {
        return mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HoldBody(showtimeId, seatIds))))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private int holdStatus(String token, long showtimeId, List<Long> seatIds) throws Exception {
        return mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new HoldBody(showtimeId, seatIds))))
                .andReturn().getResponse().getStatus();
    }
}
