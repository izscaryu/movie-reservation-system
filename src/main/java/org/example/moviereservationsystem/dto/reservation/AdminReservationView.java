package org.example.moviereservationsystem.dto.reservation;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.example.moviereservationsystem.entity.Reservation;

/**
 * Admin-facing view of a reservation. Unlike the owner-scoped
 * {@link ReservationResponse}, this includes the owning user (the whole point of
 * the admin list) plus the showtime/movie context. Seat-level detail is omitted
 * to keep the list query free of a to-many fetch (which would break DB-side
 * paging); the per-reservation detail is available elsewhere if needed.
 *
 * <p>Built from a Reservation whose user / showtime / movie are already fetched.
 */
public record AdminReservationView(
        Long id,
        Long userId,
        String userEmail,
        String userName,
        Long showtimeId,
        String movieTitle,
        LocalDateTime showtimeStartTime,
        String status,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        BigDecimal totalPrice) {

    public static AdminReservationView of(Reservation r) {
        return new AdminReservationView(
                r.getId(),
                r.getUser().getId(),
                r.getUser().getEmail(),
                r.getUser().getName(),
                r.getShowtime().getId(),
                r.getShowtime().getMovie().getTitle(),
                r.getShowtime().getStartTime(),
                r.getStatus().name(),
                r.getCreatedAt(),
                r.getExpiresAt(),
                r.getTotalPrice());
    }
}
