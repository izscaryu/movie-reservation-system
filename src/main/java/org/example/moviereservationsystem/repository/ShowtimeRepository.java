package org.example.moviereservationsystem.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.example.moviereservationsystem.entity.Showtime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShowtimeRepository extends JpaRepository<Showtime, Long> {

    /**
     * Interval overlap in the same room: an existing showtime conflicts when it
     * starts before the new one ends AND ends after the new one starts. Strict
     * &lt; / &gt; deliberately allows back-to-back showtimes (one ending exactly
     * as the next starts is NOT an overlap). NOTE: this is a read-then-write
     * check in the service, so two concurrent admin creates could both pass it
     * (see PROGRESS Phase 4 — accepted at admin scale).
     */
    boolean existsByTheaterRoom_IdAndStartTimeLessThanAndEndTimeGreaterThan(
            Long theaterRoomId, LocalDateTime newEnd, LocalDateTime newStart);

    // Showtime with movie loaded, to check the movie's soft-delete state before
    // exposing a seat map (single root, so the fetch is duplicate-safe).
    @Query("SELECT s FROM Showtime s JOIN FETCH s.movie WHERE s.id = :id")
    Optional<Showtime> findByIdWithMovie(@Param("id") Long id);

    // All showtimes for a movie, movie + room fetched (no N+1 when mapping to
    // the response), deterministic order. The service has already confirmed the
    // movie is active before calling this.
    @Query("SELECT s FROM Showtime s JOIN FETCH s.movie JOIN FETCH s.theaterRoom "
            + "WHERE s.movie.id = :movieId ORDER BY s.startTime")
    List<Showtime> findByMovieIdWithDetails(@Param("movieId") Long movieId);

    // Same as above, bounded to a single day [dayStart, dayEnd).
    @Query("SELECT s FROM Showtime s JOIN FETCH s.movie JOIN FETCH s.theaterRoom "
            + "WHERE s.movie.id = :movieId AND s.startTime >= :dayStart AND s.startTime < :dayEnd "
            + "ORDER BY s.startTime")
    List<Showtime> findByMovieIdAndDayWithDetails(
            @Param("movieId") Long movieId,
            @Param("dayStart") LocalDateTime dayStart,
            @Param("dayEnd") LocalDateTime dayEnd);
}
