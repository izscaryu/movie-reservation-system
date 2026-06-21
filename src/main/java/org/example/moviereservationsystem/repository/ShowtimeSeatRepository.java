package org.example.moviereservationsystem.repository;

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
}
