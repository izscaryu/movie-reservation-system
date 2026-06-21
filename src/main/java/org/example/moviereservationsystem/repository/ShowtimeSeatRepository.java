package org.example.moviereservationsystem.repository;

import java.util.Collection;
import java.util.List;
import org.example.moviereservationsystem.entity.ShowtimeSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShowtimeSeatRepository extends JpaRepository<ShowtimeSeat, Long> {

    // Seat map for a showtime: each showtime seat with its physical seat loaded
    // (one query, no N+1), ordered for a stable seat-picker layout (A1, A2, ...).
    @Query("SELECT ss FROM ShowtimeSeat ss JOIN FETCH ss.seat seat "
            + "WHERE ss.showtime.id = :showtimeId ORDER BY seat.rowLabel, seat.seatNumber")
    List<ShowtimeSeat> findByShowtimeIdWithSeat(@Param("showtimeId") Long showtimeId);

    // Loads only the requested seats that genuinely belong to the given showtime,
    // with the physical seat fetched. The service compares the result size to the
    // distinct requested count to reject unknown / cross-showtime ids (400).
    // ORDER BY id gives a deterministic row-lock order for concurrent multi-seat
    // holds.
    @Query("SELECT ss FROM ShowtimeSeat ss JOIN FETCH ss.seat "
            + "WHERE ss.showtime.id = :showtimeId AND ss.id IN :ids ORDER BY ss.id")
    List<ShowtimeSeat> findByShowtimeIdAndIdInWithSeat(
            @Param("showtimeId") Long showtimeId, @Param("ids") Collection<Long> ids);
}
