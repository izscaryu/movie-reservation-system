package org.example.moviereservationsystem.dto.report;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Seat occupancy for one showtime: BOOKED seats over the showtime's total seats.
 * {@code occupancyRate} is a percentage in [0, 100] with 2-decimal scale. HELD
 * (transient holds) are not counted — only confirmed BOOKED seats. Reported even
 * when the movie is soft-deleted (an admin report sees everything).
 */
public record OccupancyReport(
        Long showtimeId,
        String movieTitle,
        LocalDateTime startTime,
        long totalSeats,
        long bookedSeats,
        BigDecimal occupancyRate) {
}
