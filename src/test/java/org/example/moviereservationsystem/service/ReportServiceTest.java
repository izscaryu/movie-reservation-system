package org.example.moviereservationsystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import org.example.moviereservationsystem.dto.report.OccupancyReport;
import org.example.moviereservationsystem.entity.Movie;
import org.example.moviereservationsystem.entity.SeatStatus;
import org.example.moviereservationsystem.entity.Showtime;
import org.example.moviereservationsystem.exception.ResourceNotFoundException;
import org.example.moviereservationsystem.repository.ReservationRepository;
import org.example.moviereservationsystem.repository.ReservationSeatRepository;
import org.example.moviereservationsystem.repository.ShowtimeRepository;
import org.example.moviereservationsystem.repository.ShowtimeSeatRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the only PURE-JAVA part of ReportService: the occupancy-rate
 * computation (percentage, divide-by-zero guard, 2-decimal HALF_UP rounding).
 *
 * <p>The revenue / by-movie / popular-movies figures are aggregated DB-side
 * (SUM / COUNT / GROUP BY) and are NOT unit-tested here — a mock cannot reproduce
 * the aggregation, so those stay covered by ReportIntegrationTest. Here the seat
 * counts are mocked and we assert the arithmetic the service does on them.
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationSeatRepository reservationSeatRepository;
    @Mock
    private ShowtimeSeatRepository showtimeSeatRepository;
    @Mock
    private ShowtimeRepository showtimeRepository;

    @InjectMocks
    private ReportService service;

    private static final long SHOWTIME_ID = 100L;

    @Test
    void occupancy_computesPercentage_withTwoDecimals() {
        stubShowtime();
        when(showtimeSeatRepository.countByShowtime_Id(SHOWTIME_ID)).thenReturn(40L);
        when(showtimeSeatRepository.countByShowtime_IdAndStatus(SHOWTIME_ID, SeatStatus.BOOKED))
                .thenReturn(2L);

        OccupancyReport report = service.occupancy(SHOWTIME_ID);

        assertThat(report.totalSeats()).isEqualTo(40);
        assertThat(report.bookedSeats()).isEqualTo(2);
        // 2 / 40 = 5%. Money/ratio asserted by value, never equals (5.00 vs 5).
        assertThat(report.occupancyRate()).isEqualByComparingTo("5.00");
    }

    @Test
    void occupancy_roundsHalfUp_toTwoDecimals() {
        stubShowtime();
        when(showtimeSeatRepository.countByShowtime_Id(SHOWTIME_ID)).thenReturn(3L);
        when(showtimeSeatRepository.countByShowtime_IdAndStatus(SHOWTIME_ID, SeatStatus.BOOKED))
                .thenReturn(2L);

        OccupancyReport report = service.occupancy(SHOWTIME_ID);

        // 2 / 3 = 66.666... -> 66.67 at 2dp HALF_UP.
        assertThat(report.occupancyRate()).isEqualByComparingTo("66.67");
    }

    @Test
    void occupancy_zeroTotalSeats_doesNotDivideByZero() {
        stubShowtime();
        when(showtimeSeatRepository.countByShowtime_Id(SHOWTIME_ID)).thenReturn(0L);
        when(showtimeSeatRepository.countByShowtime_IdAndStatus(SHOWTIME_ID, SeatStatus.BOOKED))
                .thenReturn(0L);

        OccupancyReport report = service.occupancy(SHOWTIME_ID);

        // Guarded: returns 0, never throws ArithmeticException.
        assertThat(report.occupancyRate()).isEqualByComparingTo("0.00");
    }

    @Test
    void occupancy_unknownShowtime_throws404() {
        when(showtimeRepository.findByIdWithMovie(SHOWTIME_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.occupancy(SHOWTIME_ID));
    }

    private void stubShowtime() {
        Movie movie = new Movie();
        movie.setTitle("Test Movie");
        Showtime showtime = new Showtime();
        showtime.setId(SHOWTIME_ID);
        showtime.setMovie(movie);
        showtime.setStartTime(LocalDateTime.of(2026, 6, 1, 18, 0));
        when(showtimeRepository.findByIdWithMovie(SHOWTIME_ID)).thenReturn(Optional.of(showtime));
    }
}
