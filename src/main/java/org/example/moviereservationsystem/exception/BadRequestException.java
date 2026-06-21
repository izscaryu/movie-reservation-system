package org.example.moviereservationsystem.exception;

/**
 * Thrown for a semantically invalid request that bean validation cannot express
 * — e.g. a showtimeSeatId that does not belong to the given showtime. Mapped to
 * 400 Bad Request.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
