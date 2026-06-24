package org.example.moviereservationsystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.example.moviereservationsystem.dto.reservation.ReservationRequest;
import org.example.moviereservationsystem.dto.reservation.ReservationResponse;
import org.example.moviereservationsystem.entity.Movie;
import org.example.moviereservationsystem.entity.Reservation;
import org.example.moviereservationsystem.entity.ReservationSeat;
import org.example.moviereservationsystem.entity.ReservationStatus;
import org.example.moviereservationsystem.entity.Seat;
import org.example.moviereservationsystem.entity.SeatStatus;
import org.example.moviereservationsystem.entity.Showtime;
import org.example.moviereservationsystem.entity.ShowtimeSeat;
import org.example.moviereservationsystem.exception.BadRequestException;
import org.example.moviereservationsystem.exception.ReservationStateException;
import org.example.moviereservationsystem.exception.ResourceNotFoundException;
import org.example.moviereservationsystem.exception.SeatsUnavailableException;
import org.example.moviereservationsystem.repository.ReservationRepository;
import org.example.moviereservationsystem.repository.ReservationSeatRepository;
import org.example.moviereservationsystem.repository.ShowtimeRepository;
import org.example.moviereservationsystem.repository.ShowtimeSeatRepository;
import org.example.moviereservationsystem.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Fast, isolated unit tests for {@link ReservationService}'s business LOGIC: the
 * branching the integration suite is slow or tedious to drive end-to-end — every
 * illegal state transition, every validation/ownership guard, the price snapshot,
 * and the expiry-decision idempotency.
 *
 * <p><b>GUARDRAIL — why there is no concurrency test here:</b> concurrency /
 * overbooking is proven by the Phase 5 integration test (real DB, {@code @Version},
 * CountDownLatch); it CANNOT be unit-tested because the guarantee lives in DB
 * row-locking, not service branching. Do not add a mocked "concurrency" test — it
 * would prove nothing and give false confidence.
 *
 * <p><b>Scope boundary (deliberate):</b> these tests do NOT and CANNOT prove the
 * overbooking guarantee. That guarantee lives in DB behaviours a Mockito mock
 * cannot reproduce — the {@code @Version} optimistic lock, the flush-ordering
 * deadlock fix, and the {@code compareAndSetStatus} rows-affected serialization.
 * Here the repositories are mocked, so we assert the service's REACTION to a given
 * repository result, not any concurrency outcome. The one test that touches
 * optimistic locking ({@link #optimisticLockException_isTranslatedTo409}) only
 * verifies the catch-branch translation (native exception -> clean 409), not that
 * a race is detected.
 */
@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationSeatRepository reservationSeatRepository;
    @Mock
    private ShowtimeRepository showtimeRepository;
    @Mock
    private ShowtimeSeatRepository showtimeSeatRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private ReservationService service;

    @Captor
    private ArgumentCaptor<Reservation> reservationCaptor;

    private static final long USER_ID = 7L;
    private static final long SHOWTIME_ID = 100L;
    private static final long HOLD_MINUTES = 10L;

    @BeforeEach
    void wireFieldDependencies() {
        // @Value field is not populated without a Spring context; set the
        // configured hold window explicitly so expiresAt is computed from a known
        // value rather than the long default of 0.
        ReflectionTestUtils.setField(service, "holdMinutes", HOLD_MINUTES);
        // The EntityManager is a @PersistenceContext FIELD, not a constructor arg;
        // @InjectMocks injects the repos via the constructor and does not field-
        // inject it, so wire the mock in explicitly.
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
    }

    // ------------------------------------------------------------------
    // hold() — guards, validation, price snapshot
    // ------------------------------------------------------------------

    @Test
    void hold_unknownShowtime_throws404() {
        when(showtimeRepository.findByIdWithMovie(SHOWTIME_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.hold(request(SHOWTIME_ID, 1L), USER_ID));
    }

    @Test
    void hold_showtimeOfSoftDeletedMovie_throws404() {
        // The movie row exists but is soft-deleted: its showtimes are hidden, so a
        // hold is rejected as not-found (never reveals the deleted showtime).
        Showtime showtime = showtime(SHOWTIME_ID, future(), price("10.00"), /* movieDeleted */ true);
        when(showtimeRepository.findByIdWithMovie(SHOWTIME_ID)).thenReturn(Optional.of(showtime));

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.hold(request(SHOWTIME_ID, 1L), USER_ID));
    }

    @Test
    void hold_showtimeAlreadyStarted_throws409() {
        Showtime showtime = showtime(SHOWTIME_ID, past(), price("10.00"), false);
        when(showtimeRepository.findByIdWithMovie(SHOWTIME_ID)).thenReturn(Optional.of(showtime));

        assertThatExceptionOfType(ReservationStateException.class)
                .isThrownBy(() -> service.hold(request(SHOWTIME_ID, 1L), USER_ID));
    }

    @Test
    void hold_unknownOrCrossShowtimeSeatId_throws400() {
        Showtime showtime = showtime(SHOWTIME_ID, future(), price("10.00"), false);
        when(showtimeRepository.findByIdWithMovie(SHOWTIME_ID)).thenReturn(Optional.of(showtime));
        // Two distinct ids requested, but only one genuinely belongs to the
        // showtime: the size mismatch means an unknown / cross-showtime id was
        // posted -> 400.
        when(showtimeSeatRepository.findByShowtimeIdAndIdInWithSeat(eq(SHOWTIME_ID), any()))
                .thenReturn(List.of(seat(1L, SeatStatus.AVAILABLE, "A", 1, showtime)));

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> service.hold(request(SHOWTIME_ID, 1L, 2L), USER_ID));
    }

    @Test
    void hold_dedupsRequestedSeatIds_beforeComparingCounts() {
        // The same id posted twice is one distinct seat: the count check must pass
        // (1 requested == 1 returned), proving the service de-duplicates rather
        // than rejecting a duplicated id as a phantom extra seat.
        Showtime showtime = showtime(SHOWTIME_ID, future(), price("10.00"), false);
        ShowtimeSeat s1 = seat(1L, SeatStatus.AVAILABLE, "A", 1, showtime);
        when(showtimeRepository.findByIdWithMovie(SHOWTIME_ID)).thenReturn(Optional.of(showtime));
        when(showtimeSeatRepository.findByShowtimeIdAndIdInWithSeat(eq(SHOWTIME_ID), any()))
                .thenReturn(List.of(s1));

        ReservationResponse response = service.hold(request(SHOWTIME_ID, 1L, 1L), USER_ID);

        assertThat(response.seats()).containsExactly("A1");
        // Price reflects ONE seat, not two: dedup happened before the snapshot.
        assertThat(response.totalPrice()).isEqualByComparingTo("10.00");
    }

    @Test
    void hold_seatNotAvailable_throws409_andNamesOffendingSeatsSorted() {
        Showtime showtime = showtime(SHOWTIME_ID, future(), price("10.00"), false);
        when(showtimeRepository.findByIdWithMovie(SHOWTIME_ID)).thenReturn(Optional.of(showtime));
        // Two already-taken seats, returned in a non-sorted order: the message must
        // list them sorted by label (A1 before B3).
        when(showtimeSeatRepository.findByShowtimeIdAndIdInWithSeat(eq(SHOWTIME_ID), any()))
                .thenReturn(List.of(
                        seat(1L, SeatStatus.HELD, "B", 3, showtime),
                        seat(2L, SeatStatus.BOOKED, "A", 1, showtime)));

        assertThatExceptionOfType(SeatsUnavailableException.class)
                .isThrownBy(() -> service.hold(request(SHOWTIME_ID, 1L, 2L), USER_ID))
                .withMessageContaining("A1, B3");
        // Rejected before any seat was flipped or flushed.
        verifyNoInteractions(entityManager);
    }

    @Test
    void optimisticLockException_isTranslatedTo409() {
        // A TRANSLATION test, not a race test: it pins the contract that a native
        // jakarta.persistence.OptimisticLockException thrown by flush() is caught
        // and rethrown as SeatsUnavailableException (-> 409 via GlobalExceptionHandler)
        // instead of escaping as a 500. This is pure catch-block branching, fully
        // determined by the single mocked throw below — it does NOT detect or prove
        // a concurrent race (that is the Phase 5 integration test's job).
        Showtime showtime = showtime(SHOWTIME_ID, future(), price("10.00"), false);
        when(showtimeRepository.findByIdWithMovie(SHOWTIME_ID)).thenReturn(Optional.of(showtime));
        when(showtimeSeatRepository.findByShowtimeIdAndIdInWithSeat(eq(SHOWTIME_ID), any()))
                .thenReturn(List.of(seat(1L, SeatStatus.AVAILABLE, "A", 1, showtime)));
        doThrowOnFlush();

        // Assert the OUTCOME: the translated exception type (which the handler maps
        // to 409), not merely that the catch block executed.
        assertThatExceptionOfType(SeatsUnavailableException.class)
                .isThrownBy(() -> service.hold(request(SHOWTIME_ID, 1L), USER_ID));
        // And the losing hold must not go on to persist a reservation.
        verifyNoInteractions(reservationRepository);
    }

    @Test
    void hold_success_buildsPendingReservation_snapshotsPrice_andHoldsSeats() {
        Showtime showtime = showtime(SHOWTIME_ID, future(), price("10.50"), false);
        ShowtimeSeat a1 = seat(1L, SeatStatus.AVAILABLE, "A", 1, showtime);
        ShowtimeSeat a2 = seat(2L, SeatStatus.AVAILABLE, "A", 2, showtime);
        ShowtimeSeat a3 = seat(3L, SeatStatus.AVAILABLE, "A", 3, showtime);
        when(showtimeRepository.findByIdWithMovie(SHOWTIME_ID)).thenReturn(Optional.of(showtime));
        when(showtimeSeatRepository.findByShowtimeIdAndIdInWithSeat(eq(SHOWTIME_ID), any()))
                .thenReturn(List.of(a1, a2, a3));

        LocalDateTime before = LocalDateTime.now();
        ReservationResponse response = service.hold(request(SHOWTIME_ID, 1L, 2L, 3L), USER_ID);
        LocalDateTime after = LocalDateTime.now();

        // The reservation the service built and saved.
        verify(reservationRepository).save(reservationCaptor.capture());
        Reservation saved = reservationCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ReservationStatus.PENDING);
        // Price snapshot = unit price x seat count, asserted by VALUE (compareTo),
        // never equals() — 10.50 x 3 = 31.50, and 31.5 vs 31.50 would fail equals.
        assertThat(saved.getTotalPrice()).isEqualByComparingTo("31.50");
        // Hold deadline is now + configured hold-minutes (within a tolerance, since
        // now() is real). Assert it sits inside [before+hold, after+hold].
        assertThat(saved.getExpiresAt())
                .isAfterOrEqualTo(before.plusMinutes(HOLD_MINUTES))
                .isBeforeOrEqualTo(after.plusMinutes(HOLD_MINUTES));

        // Every requested seat was flipped AVAILABLE -> HELD before the flush.
        assertThat(List.of(a1, a2, a3))
                .allMatch(s -> s.getStatus() == SeatStatus.HELD);
        verify(entityManager).flush();

        // Response view is consistent with the snapshot.
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.totalPrice()).isEqualByComparingTo("31.50");
        assertThat(response.seats()).containsExactly("A1", "A2", "A3");
    }

    // ------------------------------------------------------------------
    // confirm() — ownership + state guard
    // ------------------------------------------------------------------

    @Test
    void confirm_notOwnedOrMissing_throws404() {
        // Owner-scoped lookup is empty: someone else's id and a non-existent id are
        // indistinguishable (both 404) so we never reveal another user's id exists.
        when(reservationRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.confirm(1L, USER_ID));
    }

    @Test
    void confirm_notPending_throws409() {
        // Owned, but the guarded PENDING -> CONFIRMED update matches 0 rows: it was
        // already confirmed/expired/cancelled (e.g. the confirm-after-expiry race).
        when(reservationRepository.findByIdAndUserId(1L, USER_ID))
                .thenReturn(Optional.of(new Reservation()));
        when(reservationRepository.compareAndSetStatus(
                1L, ReservationStatus.PENDING, ReservationStatus.CONFIRMED)).thenReturn(0);

        assertThatExceptionOfType(ReservationStateException.class)
                .isThrownBy(() -> service.confirm(1L, USER_ID));
    }

    // ------------------------------------------------------------------
    // cancel() — ownership + guards + release
    // ------------------------------------------------------------------

    @Test
    void cancel_notOwnedOrMissing_throws404() {
        when(reservationRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.cancel(1L, USER_ID));
    }

    @Test
    void cancel_afterShowtimeStarted_throws409() {
        Reservation reservation = reservationFor(showtime(SHOWTIME_ID, past(), price("10.00"), false));
        when(reservationRepository.findByIdAndUserId(1L, USER_ID))
                .thenReturn(Optional.of(reservation));

        assertThatExceptionOfType(ReservationStateException.class)
                .isThrownBy(() -> service.cancel(1L, USER_ID));
        // Guard fires before any state transition is attempted.
        verify(reservationRepository, org.mockito.Mockito.never())
                .compareAndSetStatusFromAnyOf(anyLong(), any(), any());
    }

    @Test
    void cancel_notInCancellableState_throws409() {
        Reservation reservation = reservationFor(showtime(SHOWTIME_ID, future(), price("10.00"), false));
        when(reservationRepository.findByIdAndUserId(1L, USER_ID))
                .thenReturn(Optional.of(reservation));
        when(reservationRepository.compareAndSetStatusFromAnyOf(eq(1L), any(), eq(ReservationStatus.CANCELLED)))
                .thenReturn(0);

        assertThatExceptionOfType(ReservationStateException.class)
                .isThrownBy(() -> service.cancel(1L, USER_ID));
    }

    @Test
    void cancel_success_cancelsFromPendingOrConfirmed_andReleasesSeats() {
        Showtime showtime = showtime(SHOWTIME_ID, future(), price("10.00"), false);
        Reservation reservation = reservationFor(showtime);
        ShowtimeSeat booked = seat(1L, SeatStatus.BOOKED, "A", 1, showtime);
        when(reservationRepository.findByIdAndUserId(1L, USER_ID))
                .thenReturn(Optional.of(reservation));
        when(reservationRepository.compareAndSetStatusFromAnyOf(eq(1L), any(), eq(ReservationStatus.CANCELLED)))
                .thenReturn(1);
        when(reservationSeatRepository.findByReservationIdWithSeat(1L))
                .thenReturn(List.of(link(reservation, booked)));

        service.cancel(1L, USER_ID);

        // CONFIRMED is a cancellable source state (integration only cancels PENDING
        // holds): the guarded transition must allow PENDING or CONFIRMED.
        ArgumentCaptor<List<ReservationStatus>> fromStates = captorForList();
        verify(reservationRepository)
                .compareAndSetStatusFromAnyOf(eq(1L), fromStates.capture(), eq(ReservationStatus.CANCELLED));
        assertThat(fromStates.getValue())
                .containsExactly(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);
        // Seats released: flipped back to AVAILABLE and the link rows deleted (frees
        // the unique key so the seat can be re-held).
        assertThat(booked.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        verify(reservationSeatRepository).deleteByReservationId(1L);
    }

    // ------------------------------------------------------------------
    // expireOne() — idempotent expiry decision
    // ------------------------------------------------------------------

    @Test
    void expireOne_stillPending_expiresAndReleasesSeats() {
        Showtime showtime = showtime(SHOWTIME_ID, future(), price("10.00"), false);
        ShowtimeSeat held = seat(1L, SeatStatus.HELD, "A", 1, showtime);
        when(reservationRepository.compareAndSetStatus(
                1L, ReservationStatus.PENDING, ReservationStatus.EXPIRED)).thenReturn(1);
        when(reservationSeatRepository.findByReservationIdWithSeat(1L))
                .thenReturn(List.of(link(new Reservation(), held)));

        service.expireOne(1L);

        assertThat(held.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        verify(reservationSeatRepository).deleteByReservationId(1L);
    }

    @Test
    void expireOne_alreadyNonPending_isIdempotentNoOp() {
        // A confirm/cancel already moved it off PENDING: the guarded update matches
        // 0 rows and expiry must NOT touch the seats (no double-release).
        when(reservationRepository.compareAndSetStatus(
                1L, ReservationStatus.PENDING, ReservationStatus.EXPIRED)).thenReturn(0);

        service.expireOne(1L);

        verifyNoInteractions(reservationSeatRepository);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static ReservationRequest request(long showtimeId, Long... seatIds) {
        return new ReservationRequest(showtimeId, List.of(seatIds));
    }

    private static LocalDateTime future() {
        return LocalDateTime.now().plusDays(1);
    }

    private static LocalDateTime past() {
        return LocalDateTime.now().minusMinutes(1);
    }

    private static BigDecimal price(String value) {
        return new BigDecimal(value);
    }

    private static Showtime showtime(long id, LocalDateTime startTime, BigDecimal price, boolean movieDeleted) {
        Movie movie = new Movie();
        movie.setTitle("Test Movie");
        movie.setDeletedAt(movieDeleted ? LocalDateTime.now() : null);
        Showtime showtime = new Showtime();
        showtime.setId(id);
        showtime.setMovie(movie);
        showtime.setStartTime(startTime);
        showtime.setPrice(price);
        return showtime;
    }

    private static ShowtimeSeat seat(long id, SeatStatus status, String rowLabel, int number, Showtime showtime) {
        Seat seat = new Seat();
        seat.setRowLabel(rowLabel);
        seat.setSeatNumber(number);
        ShowtimeSeat showtimeSeat = new ShowtimeSeat();
        showtimeSeat.setId(id);
        showtimeSeat.setSeat(seat);
        showtimeSeat.setStatus(status);
        showtimeSeat.setShowtime(showtime);
        return showtimeSeat;
    }

    private static Reservation reservationFor(Showtime showtime) {
        Reservation reservation = new Reservation();
        reservation.setId(1L);
        reservation.setShowtime(showtime);
        reservation.setStatus(ReservationStatus.PENDING);
        return reservation;
    }

    private static ReservationSeat link(Reservation reservation, ShowtimeSeat showtimeSeat) {
        ReservationSeat link = new ReservationSeat();
        link.setReservation(reservation);
        link.setShowtimeSeat(showtimeSeat);
        return link;
    }

    private void doThrowOnFlush() {
        org.mockito.Mockito.doThrow(new OptimisticLockException("stale version"))
                .when(entityManager).flush();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<ReservationStatus>> captorForList() {
        return ArgumentCaptor.forClass(List.class);
    }
}
