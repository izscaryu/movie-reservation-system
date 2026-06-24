package org.example.moviereservationsystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.example.moviereservationsystem.dto.showtime.ShowtimeRequest;
import org.example.moviereservationsystem.dto.showtime.ShowtimeResponse;
import org.example.moviereservationsystem.entity.Movie;
import org.example.moviereservationsystem.entity.Seat;
import org.example.moviereservationsystem.entity.Showtime;
import org.example.moviereservationsystem.entity.TheaterRoom;
import org.example.moviereservationsystem.repository.MovieRepository;
import org.example.moviereservationsystem.repository.SeatRepository;
import org.example.moviereservationsystem.repository.ShowtimeRepository;
import org.example.moviereservationsystem.repository.ShowtimeSeatRepository;
import org.example.moviereservationsystem.repository.TheaterRoomRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for the one piece of PURE ARITHMETIC in ShowtimeService:
 * {@code endTime = startTime + movie.durationMinutes}, computed server-side and
 * stored (never client-supplied).
 *
 * <p>The overlap check is a DB interval query
 * ({@code existsByTheaterRoom_IdAndStartTimeLessThanAndEndTimeGreaterThan}), not
 * Java logic — it is stubbed to "no overlap" here and left to
 * ShowtimeIntegrationTest to prove. Likewise the atomic seat-map generation is a
 * persistence behaviour covered by integration.
 */
@ExtendWith(MockitoExtension.class)
class ShowtimeServiceTest {

    @Mock
    private ShowtimeRepository showtimeRepository;
    @Mock
    private ShowtimeSeatRepository showtimeSeatRepository;
    @Mock
    private MovieRepository movieRepository;
    @Mock
    private TheaterRoomRepository theaterRoomRepository;
    @Mock
    private SeatRepository seatRepository;

    @InjectMocks
    private ShowtimeService service;

    private static final long MOVIE_ID = 1L;
    private static final long ROOM_ID = 2L;

    @Test
    void create_computesEndTime_asStartPlusMovieDuration() {
        int durationMinutes = 135;
        LocalDateTime start = LocalDateTime.of(2026, 6, 1, 18, 0);

        Movie movie = movie(durationMinutes);
        TheaterRoom room = room();
        when(movieRepository.findByIdAndDeletedAtIsNull(MOVIE_ID)).thenReturn(Optional.of(movie));
        when(theaterRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(seatRepository.findByTheaterRoomId(ROOM_ID)).thenReturn(List.of(seat(room)));
        // Overlap is a DB query; stub the no-conflict case so we reach the endTime
        // computation. (The overlap behaviour itself is an integration concern.)
        when(showtimeRepository.existsByTheaterRoom_IdAndStartTimeLessThanAndEndTimeGreaterThan(
                anyLong(), any(), any())).thenReturn(false);

        ShowtimeResponse response = service.create(
                new ShowtimeRequest(MOVIE_ID, ROOM_ID, start, new BigDecimal("12.00")));

        // The persisted entity carries endTime = start + 135 minutes.
        ArgumentCaptor<Showtime> saved = ArgumentCaptor.forClass(Showtime.class);
        verify(showtimeRepository).save(saved.capture());
        assertThat(saved.getValue().getEndTime()).isEqualTo(start.plusMinutes(durationMinutes));
        // ...and so does the response view returned to the caller.
        assertThat(response.startTime()).isEqualTo(start);
        assertThat(response.endTime()).isEqualTo(start.plusMinutes(durationMinutes));
    }

    private static Movie movie(int durationMinutes) {
        Movie movie = new Movie();
        movie.setId(MOVIE_ID);
        movie.setTitle("Test Movie");
        movie.setDurationMinutes(durationMinutes);
        return movie;
    }

    private static TheaterRoom room() {
        TheaterRoom room = new TheaterRoom();
        room.setId(ROOM_ID);
        room.setName("Room 1");
        return room;
    }

    private static Seat seat(TheaterRoom room) {
        Seat seat = new Seat();
        seat.setTheaterRoom(room);
        seat.setRowLabel("A");
        seat.setSeatNumber(1);
        return seat;
    }
}
