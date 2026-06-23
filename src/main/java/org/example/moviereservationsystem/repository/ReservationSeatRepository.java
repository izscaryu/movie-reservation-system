package org.example.moviereservationsystem.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.example.moviereservationsystem.dto.report.PopularMovie;
import org.example.moviereservationsystem.entity.ReservationSeat;
import org.example.moviereservationsystem.entity.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationSeatRepository extends JpaRepository<ReservationSeat, Long> {

    // A reservation's seat links with the showtime seat + physical seat loaded.
    // Used both to render seat labels and, on cancel/expiry, to flip those
    // showtime seats back to AVAILABLE (managed entities, so @Version applies)
    // before deleting the link rows.
    @Query("SELECT rs FROM ReservationSeat rs "
            + "JOIN FETCH rs.showtimeSeat ss JOIN FETCH ss.seat "
            + "WHERE rs.reservation.id = :reservationId")
    List<ReservationSeat> findByReservationIdWithSeat(@Param("reservationId") Long reservationId);

    // Batch variant for list views: seats for many reservations in one query,
    // grouped by reservation in the service (avoids N+1 over a user's list).
    @Query("SELECT rs FROM ReservationSeat rs "
            + "JOIN FETCH rs.showtimeSeat ss JOIN FETCH ss.seat "
            + "WHERE rs.reservation.id IN :reservationIds")
    List<ReservationSeat> findByReservationIdInWithSeat(
            @Param("reservationIds") Collection<Long> reservationIds);

    void deleteByReservationId(Long reservationId);

    // --- Phase 6 reporting ---

    // Top movies by tickets sold (= reservation_seats linked to CONFIRMED
    // reservations), highest first. The status filter is essential: cancelled /
    // expired holds already dropped their link rows in Phase 5, but PENDING holds
    // still have rows, so CONFIRMED is enforced explicitly. Soft-deleted movies
    // are EXCLUDED (a "what to promote now" list). Date-bounded on the
    // reservation's created_at; null from/to leaves that bound open. m.id breaks
    // ticket-count ties so the page order is deterministic across pages.
    // COUNT(DISTINCT m.id) for the page total (rows are grouped by movie).
    @Query(value = "SELECT new org.example.moviereservationsystem.dto.report.PopularMovie("
            + "m.id, m.title, COUNT(rs.id)) "
            + "FROM ReservationSeat rs JOIN rs.reservation r JOIN r.showtime s JOIN s.movie m "
            + "WHERE r.status = :status AND m.deletedAt IS NULL "
            + "AND (:from IS NULL OR r.createdAt >= :from) "
            + "AND (:to IS NULL OR r.createdAt < :to) "
            + "GROUP BY m.id, m.title "
            + "ORDER BY COUNT(rs.id) DESC, m.id ASC",
            countQuery = "SELECT COUNT(DISTINCT m.id) "
            + "FROM ReservationSeat rs JOIN rs.reservation r JOIN r.showtime s JOIN s.movie m "
            + "WHERE r.status = :status AND m.deletedAt IS NULL "
            + "AND (:from IS NULL OR r.createdAt >= :from) "
            + "AND (:to IS NULL OR r.createdAt < :to)")
    Page<PopularMovie> popularMovies(
            @Param("status") ReservationStatus status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);
}
