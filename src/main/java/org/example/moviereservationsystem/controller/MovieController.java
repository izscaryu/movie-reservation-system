package org.example.moviereservationsystem.controller;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.moviereservationsystem.dto.movie.MovieResponse;
import org.example.moviereservationsystem.service.MovieService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public movie browsing. GET /api/movies/** is permitAll in the
 * SecurityFilterChain. Soft-deleted movies are excluded from every read.
 */
@RestController
@RequestMapping("/api/movies")
@RequiredArgsConstructor
public class MovieController {

    private final MovieService movieService;

    @GetMapping
    public ResponseEntity<List<MovieResponse>> list(
            @RequestParam(required = false) String genre) {
        return ResponseEntity.ok(movieService.list(genre));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MovieResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(movieService.get(id));
    }
}
