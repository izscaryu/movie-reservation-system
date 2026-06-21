package org.example.moviereservationsystem.dto.showtime;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Admin input for creating a showtime. endTime is NOT accepted here — it is
 * computed server-side from the movie's duration (see ShowtimeService), so it
 * always stays consistent with the movie and cannot be spoofed by the client.
 */
public record ShowtimeRequest(
        @NotNull Long movieId,
        @NotNull Long theaterRoomId,
        @NotNull @Future LocalDateTime startTime,
        @NotNull @Positive BigDecimal price) {
}
