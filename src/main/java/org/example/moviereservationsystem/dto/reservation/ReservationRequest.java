package org.example.moviereservationsystem.dto.reservation;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Hold request: a showtime plus the showtime-seat ids to hold. These are
 * showtimeSeatIds (the ids the seat map exposes), NOT physical seat ids — they
 * already pin both the seat and the showtime. The service dedups them and
 * rejects any id that does not belong to the given showtime.
 */
public record ReservationRequest(
        @NotNull Long showtimeId,
        @NotNull @NotEmpty List<Long> showtimeSeatIds) {
}
