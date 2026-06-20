package org.example.moviereservationsystem.dto.movie;

import java.time.LocalDateTime;
import java.util.List;
import org.example.moviereservationsystem.entity.Genre;
import org.example.moviereservationsystem.entity.Movie;

/**
 * Output view of a movie. Never expose the entity. Genres are returned as a
 * sorted (case-insensitive) list of names. fromEntity must be called while the
 * genres collection is initialised (it always is: the service maps inside an
 * open transaction after a fetch that eagerly loads genres).
 */
public record MovieResponse(
        Long id,
        String title,
        String description,
        String posterUrl,
        Integer durationMinutes,
        List<String> genres,
        LocalDateTime createdAt) {

    public static MovieResponse fromEntity(Movie movie) {
        List<String> genreNames = movie.getGenres().stream()
                .map(Genre::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return new MovieResponse(
                movie.getId(),
                movie.getTitle(),
                movie.getDescription(),
                movie.getPosterUrl(),
                movie.getDurationMinutes(),
                genreNames,
                movie.getCreatedAt());
    }
}
