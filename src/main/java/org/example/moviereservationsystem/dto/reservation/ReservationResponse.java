package org.example.moviereservationsystem.dto.reservation;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.example.moviereservationsystem.entity.Reservation;
import org.example.moviereservationsystem.entity.ReservationSeat;
import org.example.moviereservationsystem.entity.Seat;

/**
 * Output view of a reservation. Never expose the entity. Built while showtime,
 * movie and the seat links are initialised (the service loads them in the same
 * transaction).
 */
public record ReservationResponse(
        Long id,
        Long showtimeId,
        String movieTitle,
        LocalDateTime startTime,
        String status,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        BigDecimal totalPrice,
        List<String> seats) {

    public static ReservationResponse of(Reservation reservation, List<ReservationSeat> seatLinks) {
        List<String> seatLabels = seatLinks.stream()
                .map(rs -> rs.getShowtimeSeat().getSeat())
                .sorted(Comparator.comparing(Seat::getRowLabel).thenComparing(Seat::getSeatNumber))
                .map(seat -> seat.getRowLabel() + seat.getSeatNumber())
                .toList();
        return new ReservationResponse(
                reservation.getId(),
                reservation.getShowtime().getId(),
                reservation.getShowtime().getMovie().getTitle(),
                reservation.getShowtime().getStartTime(),
                reservation.getStatus().name(),
                reservation.getCreatedAt(),
                reservation.getExpiresAt(),
                reservation.getTotalPrice(),
                seatLabels);
    }
}
