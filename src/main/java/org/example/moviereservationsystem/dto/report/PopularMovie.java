package org.example.moviereservationsystem.dto.report;

/**
 * A movie ranked by tickets sold (= reservation_seats linked to CONFIRMED
 * reservations). Populated by a JPQL constructor expression; {@code ticketsSold}
 * is a {@code Long} (never null from COUNT) so it binds without primitive
 * coercion. Soft-deleted movies are excluded — this is a "what to promote now"
 * list.
 */
public record PopularMovie(
        Long movieId,
        String movieTitle,
        Long ticketsSold) {
}
