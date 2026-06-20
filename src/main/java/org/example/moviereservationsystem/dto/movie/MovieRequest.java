package org.example.moviereservationsystem.dto.movie;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Create/update input for a movie. Genres are carried as NAMES; the service
 * does get-or-create (case-insensitive) and dedups within the request, so
 * there is no separate Genre admin API. A null/empty genres list is allowed
 * (movie with no genres). Each element is constrained to the genres.name
 * column width.
 */
public record MovieRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 5000) String description,
        @Size(max = 512) String posterUrl,
        @NotNull @Positive Integer durationMinutes,
        List<@NotBlank @Size(max = 100) String> genres) {
}
