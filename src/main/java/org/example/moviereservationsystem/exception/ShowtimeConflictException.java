package org.example.moviereservationsystem.exception;

/**
 * Thrown when a showtime cannot be created because it overlaps an existing
 * showtime in the same room. Mapped to 409 Conflict by GlobalExceptionHandler.
 */
public class ShowtimeConflictException extends RuntimeException {

    public ShowtimeConflictException(String message) {
        super(message);
    }
}
