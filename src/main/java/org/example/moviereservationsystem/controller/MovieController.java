package org.example.moviereservationsystem.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.example.moviereservationsystem.dto.PageResponse;
import org.example.moviereservationsystem.dto.movie.MovieResponse;
import org.example.moviereservationsystem.service.MovieService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public movie browsing. GET /api/movies/** is permitAll in the
 * SecurityFilterChain. Soft-deleted movies are excluded from every read. The
 * list is paginated; ordering is fixed server-side (title, id), so no client sort
 * is accepted. {@code @Validated} enables the page/size bound checks.
 */
@RestController
@RequestMapping("/api/movies")
@RequiredArgsConstructor
@Validated
public class MovieController {

    private final MovieService movieService;

    @GetMapping
    public ResponseEntity<PageResponse<MovieResponse>> list(
            @RequestParam(required = false) String genre,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageResponse.MAX_PAGE_SIZE) int size) {
        return ResponseEntity.ok(movieService.list(genre, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MovieResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(movieService.get(id));
    }
}
