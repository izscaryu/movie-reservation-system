package org.example.moviereservationsystem.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.moviereservationsystem.dto.PageResponse;
import org.example.moviereservationsystem.dto.reservation.ReservationRequest;
import org.example.moviereservationsystem.dto.reservation.ReservationResponse;
import org.example.moviereservationsystem.entity.Reservation;
import org.example.moviereservationsystem.entity.ReservationSeat;
import org.example.moviereservationsystem.entity.ReservationStatus;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final ShowtimeRepository showtimeRepository;
    private final ShowtimeSeatRepository showtimeSeatRepository;
    private final UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${app.reservation.hold-minutes:10}")
    private long holdMinutes;

    private static final String NO_RESERVATION = "No reservation with id: ";

    /**
     * Holds the requested seats for the caller as a single atomic unit: either
     * all seats move AVAILABLE -> HELD and a PENDING reservation is created, or
     * the whole thing rolls back (no seat is left HELD). Concurrency-safe via the
     * {@code @Version} on ShowtimeSeat: the explicit status check rejects the
     * common already-taken case, and the forced flush surfaces the optimistic-lock
     * / unique-constraint failure of a true race as a clean 409 here rather than
     * at commit.
     */
    @Transactional
    public ReservationResponse hold(ReservationRequest request, Long userId) {
        List<Long> seatIds = request.showtimeSeatIds().stream().distinct().toList();

        Showtime showtime = showtimeRepository.findByIdWithMovie(request.showtimeId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No showtime with id: " + request.showtimeId()));
        // Soft-deleted movie's showtimes are not bookable (hidden, like the
        // public reads).
        if (showtime.getMovie().getDeletedAt() != null) {
            throw new ResourceNotFoundException("No showtime with id: " + request.showtimeId());
        }
        if (!showtime.getStartTime().isAfter(LocalDateTime.now())) {
            throw new ReservationStateException("Showtime has already started");
        }

        // Only seats that genuinely belong to this showtime come back; a smaller
        // count means an unknown or cross-showtime id was posted.
        List<ShowtimeSeat> seats =
                showtimeSeatRepository.findByShowtimeIdAndIdInWithSeat(showtime.getId(), seatIds);
        if (seats.size() != seatIds.size()) {
            throw new BadRequestException(
                    "One or more seat ids do not belong to showtime " + showtime.getId());
        }

        List<String> unavailable = seats.stream()
                .filter(seat -> seat.getStatus() != SeatStatus.AVAILABLE)
                .map(this::label)
                .sorted()
                .toList();
        if (!unavailable.isEmpty()) {
            log.warn("Seat-lock conflict on showtime {} for user {}: seats already taken {}",
                    showtime.getId(), userId, unavailable);
            throw new SeatsUnavailableException("Seats not available: " + String.join(", ", unavailable));
        }

        // Flip to HELD and flush NOW, BEFORE inserting any reservation rows.
        // Hibernate orders INSERTs before UPDATEs within a flush, so without this
        // the reservation_seats insert would take the UNIQUE(showtime_seat_id)
        // index lock before the seat-row UPDATE — two concurrent holds would then
        // acquire locks in opposite orders and deadlock. Flushing the versioned
        // UPDATE first gives every hold the same lock order (seat row, then the
        // unique key), so the loser of a race gets a clean optimistic-lock
        // failure instead. @Version increments on this flush.
        seats.forEach(seat -> seat.setStatus(SeatStatus.HELD));
        try {
            entityManager.flush();
        } catch (OptimisticLockException e) {
            // A concurrent winner already bumped the version: our versioned
            // UPDATE matched 0 rows. flush() throws the native JPA exception (not
            // Spring's translated type), so we catch it here and report a clean
            // 409 instead of letting it surface as a 500.
            log.warn("Seat-lock conflict (optimistic) on showtime {} for user {}: lost the race",
                    showtime.getId(), userId);
            throw new SeatsUnavailableException(
                    "One or more seats were just taken; please refresh and retry");
        }

        Reservation reservation = new Reservation();
        reservation.setUser(userRepository.getReferenceById(userId));
        reservation.setShowtime(showtime);
        reservation.setStatus(ReservationStatus.PENDING);
        reservation.setExpiresAt(LocalDateTime.now().plusMinutes(holdMinutes));
        reservation.setTotalPrice(showtime.getPrice().multiply(BigDecimal.valueOf(seats.size())));
        reservationRepository.save(reservation);

        List<ReservationSeat> links = seats.stream()
                .map(seat -> newLink(reservation, seat))
                .toList();
        reservationSeatRepository.saveAll(links);
        // The reservation + link INSERTs flush at commit. A UNIQUE(showtime_seat_id)
        // violation there (the defence-in-depth backstop the optimistic lock should
        // already prevent) is translated by Spring to DataIntegrityViolationException
        // and mapped to 409 by the handler.
        log.info("Reservation {} created (PENDING): user={}, showtime={}, seats={}, expiresAt={}",
                reservation.getId(), userId, showtime.getId(), seats.size(), reservation.getExpiresAt());
        return ReservationResponse.of(reservation, links);
    }

    /**
     * PENDING -> CONFIRMED, seats HELD -> BOOKED. Owner-scoped (404 otherwise).
     * The guarded compareAndSetStatus is the serialization point against the
     * expiry job: if the job already EXPIRED this hold, 0 rows match -> 409.
     */
    @Transactional
    public ReservationResponse confirm(Long reservationId, Long userId) {
        // Existence + ownership: not owned / not found are indistinguishable (404).
        reservationRepository.findByIdAndUserId(reservationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        NO_RESERVATION + reservationId));

        int claimed = reservationRepository.compareAndSetStatus(
                reservationId, ReservationStatus.PENDING, ReservationStatus.CONFIRMED);
        if (claimed == 0) {
            throw new ReservationStateException("Reservation " + reservationId
                    + " is not pending (already confirmed, expired or cancelled)");
        }

        List<ReservationSeat> links =
                reservationSeatRepository.findByReservationIdWithSeat(reservationId);
        // Managed dirty update -> version-guarded, so an exact-instant race with
        // expiry resolves at the seat level too.
        links.forEach(link -> link.getShowtimeSeat().setStatus(SeatStatus.BOOKED));

        Reservation confirmed = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        NO_RESERVATION + reservationId));
        return ReservationResponse.of(confirmed, links);
    }

    /**
     * Cancels a PENDING or CONFIRMED reservation and releases its seats, only if
     * the showtime has not started. Owner-scoped (404 otherwise).
     */
    @Transactional
    public void cancel(Long reservationId, Long userId) {
        Reservation reservation = reservationRepository.findByIdAndUserId(reservationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        NO_RESERVATION + reservationId));
        if (!reservation.getShowtime().getStartTime().isAfter(LocalDateTime.now())) {
            throw new ReservationStateException("Cannot cancel after the showtime has started");
        }

        int claimed = reservationRepository.compareAndSetStatusFromAnyOf(
                reservationId,
                List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED),
                ReservationStatus.CANCELLED);
        if (claimed == 0) {
            throw new ReservationStateException("Reservation " + reservationId
                    + " cannot be cancelled in its current state");
        }
        releaseSeats(reservationId);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReservationResponse> listMine(Long userId, String filter, int page, int size) {
        // The upcoming/past filter is pushed into the query as start-time bounds
        // so the page is computed over the filtered set (filtering in memory after
        // paging would silently drop rows from each page).
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime after = "upcoming".equalsIgnoreCase(filter) ? now : null;
        LocalDateTime before = "past".equalsIgnoreCase(filter) ? now : null;

        Page<Reservation> resPage = reservationRepository.findByUserIdWithShowtime(
                userId, after, before, PageRequest.of(page, size));
        List<Reservation> reservations = resPage.getContent();
        if (reservations.isEmpty()) {
            return PageResponse.of(List.of(), resPage);
        }

        List<Long> ids = reservations.stream().map(Reservation::getId).toList();
        Map<Long, List<ReservationSeat>> seatsByReservation =
                reservationSeatRepository.findByReservationIdInWithSeat(ids).stream()
                        .collect(Collectors.groupingBy(rs -> rs.getReservation().getId()));
        List<ReservationResponse> content = reservations.stream()
                .map(r -> ReservationResponse.of(
                        r, seatsByReservation.getOrDefault(r.getId(), List.of())))
                .toList();
        return PageResponse.of(content, resPage);
    }

    // --- expiry (called per-reservation THROUGH the proxy by ReservationExpiryJob) ---

    @Transactional(readOnly = true)
    public List<Long> findOverdueHoldIds() {
        return reservationRepository.findIdsByStatusAndExpiresAtBefore(
                ReservationStatus.PENDING, LocalDateTime.now());
    }

    /**
     * Expires one overdue hold in its own transaction: PENDING -> EXPIRED and
     * seats back to AVAILABLE. Idempotent — if a confirm/cancel already moved it
     * off PENDING, the guarded update matches 0 rows and we no-op (so a
     * confirm-vs-expire race never double-acts).
     */
    @Transactional
    public void expireOne(Long reservationId) {
        int claimed = reservationRepository.compareAndSetStatus(
                reservationId, ReservationStatus.PENDING, ReservationStatus.EXPIRED);
        if (claimed == 0) {
            return;
        }
        releaseSeats(reservationId);
    }

    private void releaseSeats(Long reservationId) {
        List<ReservationSeat> links =
                reservationSeatRepository.findByReservationIdWithSeat(reservationId);
        links.forEach(link -> link.getShowtimeSeat().setStatus(SeatStatus.AVAILABLE));
        // Deleting the links frees UNIQUE(showtime_seat_id) so the seat can be
        // re-held later.
        reservationSeatRepository.deleteByReservationId(reservationId);
    }

    private ReservationSeat newLink(Reservation reservation, ShowtimeSeat showtimeSeat) {
        ReservationSeat link = new ReservationSeat();
        link.setReservation(reservation);
        link.setShowtimeSeat(showtimeSeat);
        return link;
    }

    private String label(ShowtimeSeat showtimeSeat) {
        return showtimeSeat.getSeat().getRowLabel() + showtimeSeat.getSeat().getSeatNumber();
    }
}
