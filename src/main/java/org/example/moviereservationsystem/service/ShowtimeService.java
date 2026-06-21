package org.example.moviereservationsystem.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.moviereservationsystem.dto.showtime.SeatMapEntry;
import org.example.moviereservationsystem.dto.showtime.SeatMapResponse;
import org.example.moviereservationsystem.dto.showtime.ShowtimeRequest;
import org.example.moviereservationsystem.dto.showtime.ShowtimeResponse;
import org.example.moviereservationsystem.entity.Movie;
import org.example.moviereservationsystem.entity.Seat;
import org.example.moviereservationsystem.entity.SeatStatus;
import org.example.moviereservationsystem.entity.Showtime;
import org.example.moviereservationsystem.entity.ShowtimeSeat;
import org.example.moviereservationsystem.entity.TheaterRoom;
import org.example.moviereservationsystem.exception.ResourceNotFoundException;
import org.example.moviereservationsystem.exception.ShowtimeConflictException;
import org.example.moviereservationsystem.repository.MovieRepository;
import org.example.moviereservationsystem.repository.SeatRepository;
import org.example.moviereservationsystem.repository.ShowtimeRepository;
import org.example.moviereservationsystem.repository.ShowtimeSeatRepository;
import org.example.moviereservationsystem.repository.TheaterRoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShowtimeService {

    private final ShowtimeRepository showtimeRepository;
    private final ShowtimeSeatRepository showtimeSeatRepository;
    private final MovieRepository movieRepository;
    private final TheaterRoomRepository theaterRoomRepository;
    private final SeatRepository seatRepository;

    /**
     * Creates a showtime and, in the SAME transaction, generates one
     * ShowtimeSeat (status AVAILABLE) per physical seat in the room. The single
     * @Transactional makes this atomic: if seat generation fails, the showtime
     * insert rolls back too, so a showtime can never exist with a missing or
     * partial seat map.
     *
     * <p>endTime is computed as startTime + movie.durationMinutes (stored, not
     * client-supplied). A soft-deleted/missing movie -> 404; a missing room ->
     * 404; an overlapping showtime in the same room -> 409.
     */
    @Transactional
    public ShowtimeResponse create(ShowtimeRequest request) {
        // 404 if the movie does not exist OR is soft-deleted: no scheduling
        // showtimes for a movie that is not in the public catalogue.
        Movie movie = movieRepository.findByIdAndDeletedAtIsNull(request.movieId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No movie with id: " + request.movieId()));

        TheaterRoom room = theaterRoomRepository.findById(request.theaterRoomId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No theater room with id: " + request.theaterRoomId()));

        List<Seat> seats = seatRepository.findByTheaterRoomId(room.getId());
        if (seats.isEmpty()) {
            // A seeded room always has seats; an empty room is a config error,
            // not a client error, but we refuse rather than create a seatless
            // showtime.
            throw new ResourceNotFoundException(
                    "Theater room has no seats: " + room.getId());
        }

        LocalDateTime endTime = request.startTime().plusMinutes(movie.getDurationMinutes());

        // Interval overlap (strict < / >, so back-to-back is allowed). This is a
        // read-then-write check — see the repository note on the concurrency
        // limitation.
        boolean overlaps = showtimeRepository
                .existsByTheaterRoom_IdAndStartTimeLessThanAndEndTimeGreaterThan(
                        room.getId(), endTime, request.startTime());
        if (overlaps) {
            throw new ShowtimeConflictException(
                    "Showtime overlaps an existing showtime in room " + room.getName());
        }

        Showtime showtime = new Showtime();
        showtime.setMovie(movie);
        showtime.setTheaterRoom(room);
        showtime.setStartTime(request.startTime());
        showtime.setEndTime(endTime);
        showtime.setPrice(request.price());
        showtimeRepository.save(showtime);

        List<ShowtimeSeat> showtimeSeats = seats.stream()
                .map(seat -> newShowtimeSeat(showtime, seat))
                .toList();
        showtimeSeatRepository.saveAll(showtimeSeats);

        return ShowtimeResponse.fromEntity(showtime);
    }

    /**
     * Public list of a movie's showtimes, optionally filtered to a single date.
     * Hides showtimes of a soft-deleted/missing movie (404), consistent with the
     * Phase 3 public movie reads.
     */
    @Transactional(readOnly = true)
    public List<ShowtimeResponse> listByMovie(Long movieId, LocalDate date) {
        requireActiveMovie(movieId);
        List<Showtime> showtimes;
        if (date == null) {
            showtimes = showtimeRepository.findByMovieIdWithDetails(movieId);
        } else {
            LocalDateTime dayStart = date.atStartOfDay();
            LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
            showtimes = showtimeRepository
                    .findByMovieIdAndDayWithDetails(movieId, dayStart, dayEnd);
        }
        return showtimes.stream().map(ShowtimeResponse::fromEntity).toList();
    }

    /**
     * Public seat map for a showtime. Hides (404) a showtime whose movie has
     * been soft-deleted, same visibility rule as the catalogue.
     */
    @Transactional(readOnly = true)
    public SeatMapResponse getSeatMap(Long showtimeId) {
        Showtime showtime = showtimeRepository.findByIdWithMovie(showtimeId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No showtime with id: " + showtimeId));
        if (showtime.getMovie().getDeletedAt() != null) {
            throw new ResourceNotFoundException("No showtime with id: " + showtimeId);
        }

        List<SeatMapEntry> seats = showtimeSeatRepository
                .findByShowtimeIdWithSeat(showtimeId).stream()
                .map(SeatMapEntry::fromEntity)
                .toList();
        return new SeatMapResponse(showtimeId, seats);
    }

    private void requireActiveMovie(Long movieId) {
        movieRepository.findByIdAndDeletedAtIsNull(movieId)
                .orElseThrow(() -> new ResourceNotFoundException("No movie with id: " + movieId));
    }

    private ShowtimeSeat newShowtimeSeat(Showtime showtime, Seat seat) {
        ShowtimeSeat showtimeSeat = new ShowtimeSeat();
        showtimeSeat.setShowtime(showtime);
        showtimeSeat.setSeat(seat);
        showtimeSeat.setStatus(SeatStatus.AVAILABLE);
        return showtimeSeat;
    }
}
