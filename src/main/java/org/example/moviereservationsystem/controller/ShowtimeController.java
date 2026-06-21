package org.example.moviereservationsystem.controller;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.moviereservationsystem.dto.showtime.SeatMapResponse;
import org.example.moviereservationsystem.dto.showtime.ShowtimeResponse;
import org.example.moviereservationsystem.service.ShowtimeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public showtime browsing. Both paths are permitAll for GET in the
 * SecurityFilterChain ("/api/movies/**" and "/api/showtimes/**" were both opened
 * in Phase 2), so no security change is needed. No class-level @RequestMapping:
 * the two reads live under different prefixes by design (one nested under the
 * movie, one under the showtime).
 */
@RestController
@RequiredArgsConstructor
public class ShowtimeController {

    private final ShowtimeService showtimeService;

    @GetMapping("/api/movies/{movieId}/showtimes")
    public ResponseEntity<List<ShowtimeResponse>> listByMovie(
            @PathVariable Long movieId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(showtimeService.listByMovie(movieId, date));
    }

    @GetMapping("/api/showtimes/{id}/seats")
    public ResponseEntity<SeatMapResponse> seatMap(@PathVariable Long id) {
        return ResponseEntity.ok(showtimeService.getSeatMap(id));
    }
}
