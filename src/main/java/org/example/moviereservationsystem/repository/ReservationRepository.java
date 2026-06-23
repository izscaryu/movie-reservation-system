package org.example.moviereservationsystem.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.example.moviereservationsystem.dto.report.MovieRevenue;
import org.example.moviereservationsystem.entity.Reservation;
import org.example.moviereservationsystem.entity.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    // Ownership-scoped lookup: a reservation the caller does not own resolves to
    // empty -> 404 (we never reveal that someone else's id exists).
    Optional<Reservation> findByIdAndUserId(Long id, Long userId);

    // A page of the caller's reservations with showtime + movie fetched (no N+1
    // when mapping; seats are loaded separately to avoid fetching two collections
    // at once). Pageable is safe here: only @ManyToOne associations are fetch-
    // joined (no to-many fetch), so this paginates in the database, not in memory.
    // The optional after/before bounds implement the upcoming/past filter at the
    // DB level so paging counts the filtered set. Explicit countQuery (the main
    // query fetch-joins). (createdAt, id) order for a deterministic page boundary.
    @Query(value = "SELECT r FROM Reservation r JOIN FETCH r.showtime s JOIN FETCH s.movie "
            + "WHERE r.user.id = :userId "
            + "AND (:after IS NULL OR s.startTime > :after) "
            + "AND (:before IS NULL OR s.startTime <= :before) "
            + "ORDER BY r.createdAt DESC, r.id DESC",
            countQuery = "SELECT COUNT(r) FROM Reservation r JOIN r.showtime s "
            + "WHERE r.user.id = :userId "
            + "AND (:after IS NULL OR s.startTime > :after) "
            + "AND (:before IS NULL OR s.startTime <= :before)")
    Page<Reservation> findByUserIdWithShowtime(
            @Param("userId") Long userId,
            @Param("after") LocalDateTime after,
            @Param("before") LocalDateTime before,
            Pageable pageable);

    // Admin listing: a page of reservations filtered by optional status + an
    // optional created_at range, with user / showtime / movie fetched for the
    // admin view. No ORDER BY here on purpose — the caller passes a Pageable whose
    // Sort is built from a server-side WHITELIST (AdminReservationService), so the
    // client can never sort on arbitrary entity properties. Only @ManyToOne are
    // fetch-joined (no to-many), so this pages in the database. Explicit countQuery
    // because the main query fetch-joins.
    @Query(value = "SELECT r FROM Reservation r "
            + "JOIN FETCH r.user u JOIN FETCH r.showtime s JOIN FETCH s.movie m "
            + "WHERE (:status IS NULL OR r.status = :status) "
            + "AND (:from IS NULL OR r.createdAt >= :from) "
            + "AND (:to IS NULL OR r.createdAt < :to)",
            countQuery = "SELECT COUNT(r) FROM Reservation r "
            + "WHERE (:status IS NULL OR r.status = :status) "
            + "AND (:from IS NULL OR r.createdAt >= :from) "
            + "AND (:to IS NULL OR r.createdAt < :to)")
    Page<Reservation> findForAdmin(
            @Param("status") ReservationStatus status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    // Ids of PENDING holds whose deadline has passed — the expiry job's worklist.
    // Hits idx_reservations_status_expires (status, expires_at).
    @Query("SELECT r.id FROM Reservation r "
            + "WHERE r.status = :status AND r.expiresAt < :cutoff")
    List<Long> findIdsByStatusAndExpiresAtBefore(
            @Param("status") ReservationStatus status, @Param("cutoff") LocalDateTime cutoff);

    /**
     * Guarded status transition: flips status (and clears the hold deadline) only
     * if the row is still in the expected {@code from} state, returning the rows
     * affected. This is the serialization point for the confirm-vs-expire race —
     * the UPDATE takes a row lock, so of two racing transitions exactly one sees
     * 1 row (it won) and the other sees 0 (already changed). Avoids needing a
     * @Version column on Reservation. flush+clear keep the persistence context
     * consistent with the bulk update.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Reservation r SET r.status = :to, r.expiresAt = NULL "
            + "WHERE r.id = :id AND r.status = :from")
    int compareAndSetStatus(
            @Param("id") Long id,
            @Param("from") ReservationStatus from,
            @Param("to") ReservationStatus to);

    // As above but accepts a set of source states (cancel may act on PENDING or
    // CONFIRMED).
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Reservation r SET r.status = :to, r.expiresAt = NULL "
            + "WHERE r.id = :id AND r.status IN :fromStates")
    int compareAndSetStatusFromAnyOf(
            @Param("id") Long id,
            @Param("fromStates") Collection<ReservationStatus> fromStates,
            @Param("to") ReservationStatus to);

    // --- Phase 6 reporting (read-only aggregation, DB-side) ---

    // Total revenue, date-bounded on created_at (the booking instant — see
    // ReportService for why createdAt is the axis). COALESCE so "no matching
    // rows" yields 0, not a NULL that would NPE on unboxing. Null from/to leaves
    // that bound open. Rides idx_reservations_status_created (status, created_at).
    @Query("SELECT COALESCE(SUM(r.totalPrice), 0) FROM Reservation r "
            + "WHERE r.status = :status "
            + "AND (:from IS NULL OR r.createdAt >= :from) "
            + "AND (:to IS NULL OR r.createdAt < :to)")
    BigDecimal sumRevenue(
            @Param("status") ReservationStatus status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    // Count of matching reservations for the same window (paired with sumRevenue).
    @Query("SELECT COUNT(r) FROM Reservation r "
            + "WHERE r.status = :status "
            + "AND (:from IS NULL OR r.createdAt >= :from) "
            + "AND (:to IS NULL OR r.createdAt < :to)")
    long countInRange(
            @Param("status") ReservationStatus status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    // Revenue grouped by movie, highest first. Sums r.totalPrice with NO seat
    // join, so revenue is never multiplied by the seat count. Soft-deleted movies
    // are INCLUDED (the money was real — historical accounting sees everything).
    @Query("SELECT new org.example.moviereservationsystem.dto.report.MovieRevenue("
            + "m.id, m.title, COALESCE(SUM(r.totalPrice), 0)) "
            + "FROM Reservation r JOIN r.showtime s JOIN s.movie m "
            + "WHERE r.status = :status "
            + "AND (:from IS NULL OR r.createdAt >= :from) "
            + "AND (:to IS NULL OR r.createdAt < :to) "
            + "GROUP BY m.id, m.title "
            + "ORDER BY COALESCE(SUM(r.totalPrice), 0) DESC")
    List<MovieRevenue> revenueByMovie(
            @Param("status") ReservationStatus status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
