package org.example.moviereservationsystem.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.example.moviereservationsystem.entity.Reservation;
import org.example.moviereservationsystem.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    // Ownership-scoped lookup: a reservation the caller does not own resolves to
    // empty -> 404 (we never reveal that someone else's id exists).
    Optional<Reservation> findByIdAndUserId(Long id, Long userId);

    // A caller's reservations with showtime + movie fetched (no N+1 when mapping;
    // seats are loaded separately to avoid fetching two collections at once).
    @Query("SELECT r FROM Reservation r JOIN FETCH r.showtime s JOIN FETCH s.movie "
            + "WHERE r.user.id = :userId ORDER BY r.createdAt DESC")
    List<Reservation> findByUserIdWithShowtime(@Param("userId") Long userId);

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
}
