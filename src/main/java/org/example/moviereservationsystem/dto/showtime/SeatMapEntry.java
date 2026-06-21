package org.example.moviereservationsystem.dto.showtime;

import org.example.moviereservationsystem.entity.ShowtimeSeat;

/**
 * One seat in a showtime's seat map. showtimeSeatId is the id the Phase 5
 * reservation flow will lock — NOT the physical seat id. label is the
 * human-readable seat name, e.g. "A5".
 */
public record SeatMapEntry(
        Long showtimeSeatId,
        String rowLabel,
        Integer seatNumber,
        String label,
        String status) {

    public static SeatMapEntry fromEntity(ShowtimeSeat showtimeSeat) {
        String rowLabel = showtimeSeat.getSeat().getRowLabel();
        Integer seatNumber = showtimeSeat.getSeat().getSeatNumber();
        return new SeatMapEntry(
                showtimeSeat.getId(),
                rowLabel,
                seatNumber,
                rowLabel + seatNumber,
                showtimeSeat.getStatus().name());
    }
}
