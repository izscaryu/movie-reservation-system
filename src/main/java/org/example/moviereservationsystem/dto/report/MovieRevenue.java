package org.example.moviereservationsystem.dto.report;

import java.math.BigDecimal;

/**
 * Revenue for a single movie (CONFIRMED reservations only). Populated directly
 * by a JPQL constructor expression — all components are object types so the
 * aggregate results (Long id, BigDecimal sum) bind without primitive coercion.
 * Soft-deleted movies are included here: the money was real (historical view).
 */
public record MovieRevenue(
        Long movieId,
        String movieTitle,
        BigDecimal revenue) {
}
