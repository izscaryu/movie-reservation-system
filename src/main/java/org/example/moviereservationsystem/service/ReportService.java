package org.example.moviereservationsystem.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.moviereservationsystem.dto.PageResponse;
import org.example.moviereservationsystem.dto.report.MovieRevenue;
import org.example.moviereservationsystem.dto.report.OccupancyReport;
import org.example.moviereservationsystem.dto.report.PopularMovie;
import org.example.moviereservationsystem.dto.report.RevenueReport;
import org.example.moviereservationsystem.entity.ReservationStatus;
import org.example.moviereservationsystem.entity.SeatStatus;
import org.example.moviereservationsystem.entity.Showtime;
import org.example.moviereservationsystem.exception.ResourceNotFoundException;
import org.example.moviereservationsystem.repository.ReservationRepository;
import org.example.moviereservationsystem.repository.ReservationSeatRepository;
import org.example.moviereservationsystem.repository.ShowtimeRepository;
import org.example.moviereservationsystem.repository.ShowtimeSeatRepository;
import org.example.moviereservationsystem.util.DateRanges;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only admin reporting (Phase 6). Every figure is aggregated DB-side via
 * SUM / COUNT / GROUP BY — reservations are never loaded into memory and summed
 * in Java.
 *
 * <p>Revenue counts <b>CONFIRMED reservations only</b>: PENDING, EXPIRED and
 * CANCELLED earn nothing. The revenue date axis is the reservation's
 * {@code createdAt} (booking time). The true sale instant is the confirm call,
 * which this project does not persist; in production you would add a
 * {@code confirmedAt} stamped on confirm and report on that instead. createdAt
 * is the correct proxy here.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final ShowtimeSeatRepository showtimeSeatRepository;
    private final ShowtimeRepository showtimeRepository;

    @Transactional(readOnly = true)
    public RevenueReport revenue(LocalDate from, LocalDate to) {
        DateRanges.validateRange(from, to);
        LocalDateTime fromInstant = DateRanges.startOfDay(from);
        LocalDateTime toInstant = DateRanges.startOfDayAfter(to);
        BigDecimal total =
                reservationRepository.sumRevenue(ReservationStatus.CONFIRMED, fromInstant, toInstant);
        long count =
                reservationRepository.countInRange(ReservationStatus.CONFIRMED, fromInstant, toInstant);
        return new RevenueReport(from, to, total, count);
    }

    @Transactional(readOnly = true)
    public List<MovieRevenue> revenueByMovie(LocalDate from, LocalDate to) {
        DateRanges.validateRange(from, to);
        return reservationRepository.revenueByMovie(
                ReservationStatus.CONFIRMED, DateRanges.startOfDay(from), DateRanges.startOfDayAfter(to));
    }

    @Transactional(readOnly = true)
    public OccupancyReport occupancy(Long showtimeId) {
        // No soft-delete filter: an admin report must still show a showtime whose
        // movie was soft-deleted (consistent with revenue seeing everything). A
        // 404 means the showtime row genuinely does not exist — never because
        // deleted_at is set on its movie.
        Showtime showtime = showtimeRepository.findByIdWithMovie(showtimeId)
                .orElseThrow(() -> new ResourceNotFoundException("No showtime with id: " + showtimeId));
        long totalSeats = showtimeSeatRepository.countByShowtime_Id(showtimeId);
        long bookedSeats =
                showtimeSeatRepository.countByShowtime_IdAndStatus(showtimeId, SeatStatus.BOOKED);
        return new OccupancyReport(
                showtimeId,
                showtime.getMovie().getTitle(),
                showtime.getStartTime(),
                totalSeats,
                bookedSeats,
                occupancyPercent(bookedSeats, totalSeats));
    }

    @Transactional(readOnly = true)
    public PageResponse<PopularMovie> popularMovies(LocalDate from, LocalDate to, int page, int size) {
        DateRanges.validateRange(from, to);
        // page/size bounds (size >= 1, <= MAX_PAGE_SIZE) are enforced at the
        // controller via @Validated, so no extra limit check is needed here.
        Page<PopularMovie> result = reservationSeatRepository.popularMovies(
                ReservationStatus.CONFIRMED, DateRanges.startOfDay(from), DateRanges.startOfDayAfter(to),
                PageRequest.of(page, size));
        return PageResponse.from(result);
    }

    // --- helpers ---

    // Occupancy as a percentage in [0, 100], 2-decimal scale. Guards total == 0
    // (cannot happen — a showtime always auto-generates its seat map — but never
    // divide by zero).
    private BigDecimal occupancyPercent(long booked, long total) {
        if (total == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(booked)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }
}
