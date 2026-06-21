package org.example.moviereservationsystem.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.moviereservationsystem.dto.showtime.ShowtimeRequest;
import org.example.moviereservationsystem.dto.showtime.ShowtimeResponse;
import org.example.moviereservationsystem.service.ShowtimeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only showtime management. Lives under /api/admin/** which the
 * SecurityFilterChain restricts to ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/admin/showtimes")
@RequiredArgsConstructor
public class AdminShowtimeController {

    private final ShowtimeService showtimeService;

    @PostMapping
    public ResponseEntity<ShowtimeResponse> create(@Valid @RequestBody ShowtimeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(showtimeService.create(request));
    }
}
