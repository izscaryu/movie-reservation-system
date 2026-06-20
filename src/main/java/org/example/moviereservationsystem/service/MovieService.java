package org.example.moviereservationsystem.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.example.moviereservationsystem.dto.movie.MovieRequest;
import org.example.moviereservationsystem.dto.movie.MovieResponse;
import org.example.moviereservationsystem.entity.Genre;
import org.example.moviereservationsystem.entity.Movie;
import org.example.moviereservationsystem.exception.ResourceNotFoundException;
import org.example.moviereservationsystem.repository.GenreRepository;
import org.example.moviereservationsystem.repository.MovieRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MovieService {

    private final MovieRepository movieRepository;
    private final GenreRepository genreRepository;

    @Transactional
    public MovieResponse create(MovieRequest request) {
        Movie movie = new Movie();
        applyRequest(movie, request);
        return MovieResponse.fromEntity(movieRepository.save(movie));
    }

    /**
     * Full-replace update. 404 if the movie does not exist or is already
     * soft-deleted (looked up via findByIdAndDeletedAtIsNull) — a deleted movie
     * is not silently re-edited.
     */
    @Transactional
    public MovieResponse update(Long id, MovieRequest request) {
        Movie movie = requireActive(id);
        applyRequest(movie, request);
        return MovieResponse.fromEntity(movieRepository.save(movie));
    }

    /**
     * Soft delete: stamp deleted_at = now. 404 if the movie does not exist or
     * is already soft-deleted, so a second DELETE does not silently re-delete.
     * Existing showtimes/reservations keep referencing the row.
     */
    @Transactional
    public void softDelete(Long id) {
        Movie movie = requireActive(id);
        movie.setDeletedAt(LocalDateTime.now());
        movieRepository.save(movie);
    }

    @Transactional(readOnly = true)
    public List<MovieResponse> list(String genre) {
        List<Movie> movies;
        if (genre == null || genre.isBlank()) {
            movies = movieRepository.findAllActiveWithGenres();
        } else {
            // Two-step so the genre filter never truncates each movie's genre
            // set: find matching IDs, then fetch those IDs with their full
            // genres. Empty match short-circuits (avoids an `IN ()` query).
            List<Long> ids = movieRepository.findIdsByGenreName(genre.trim());
            movies = ids.isEmpty() ? List.of() : movieRepository.findByIdsWithGenres(ids);
        }
        return movies.stream().map(MovieResponse::fromEntity).toList();
    }

    @Transactional(readOnly = true)
    public MovieResponse get(Long id) {
        return MovieResponse.fromEntity(requireActive(id));
    }

    private Movie requireActive(Long id) {
        return movieRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("No movie with id: " + id));
    }

    private void applyRequest(Movie movie, MovieRequest request) {
        movie.setTitle(request.title());
        movie.setDescription(request.description());
        movie.setPosterUrl(request.posterUrl());
        movie.setDurationMinutes(request.durationMinutes());
        movie.setGenres(resolveGenres(request.genres()));
    }

    /**
     * Get-or-create genres from names. Dedups within the request by lowercased
     * (trimmed) name, then for each resolves an existing row case-insensitively
     * or inserts a new one. Blank/null names are skipped.
     */
    private Set<Genre> resolveGenres(List<String> names) {
        Set<Genre> resolved = new HashSet<>();
        if (names == null) {
            return resolved;
        }
        Map<String, String> byLower = new LinkedHashMap<>();
        for (String raw : names) {
            if (raw == null) {
                continue;
            }
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                byLower.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
            }
        }
        for (String name : byLower.values()) {
            Genre genre = genreRepository.findByNameIgnoreCase(name)
                    .orElseGet(() -> {
                        Genre created = new Genre();
                        created.setName(name);
                        return genreRepository.save(created);
                    });
            resolved.add(genre);
        }
        return resolved;
    }
}
