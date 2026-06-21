package org.example.moviereservationsystem.dto.showtime;

import java.util.List;

/**
 * Full seat map for a showtime: every seat with its current status so a client
 * can render a seat picker.
 */
public record SeatMapResponse(
        Long showtimeId,
        List<SeatMapEntry> seats) {
}
