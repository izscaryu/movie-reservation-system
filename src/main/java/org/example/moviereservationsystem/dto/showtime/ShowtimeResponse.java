package org.example.moviereservationsystem.dto.showtime;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.example.moviereservationsystem.entity.Showtime;

/**
 * Output view of a showtime. Never expose the entity. fromEntity must be called
 * while movie and theaterRoom are initialised (the service fetches them in the
 * same transaction).
 */
public record ShowtimeResponse(
        Long id,
        Long movieId,
        String movieTitle,
        Long theaterRoomId,
        String roomName,
        LocalDateTime startTime,
        LocalDateTime endTime,
        BigDecimal price) {

    public static ShowtimeResponse fromEntity(Showtime showtime) {
        return new ShowtimeResponse(
                showtime.getId(),
                showtime.getMovie().getId(),
                showtime.getMovie().getTitle(),
                showtime.getTheaterRoom().getId(),
                showtime.getTheaterRoom().getName(),
                showtime.getStartTime(),
                showtime.getEndTime(),
                showtime.getPrice());
    }
}
