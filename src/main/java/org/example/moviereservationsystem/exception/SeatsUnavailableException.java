package org.example.moviereservationsystem.exception;

/**
 * Thrown when one or more requested seats cannot be held because they are no
 * longer AVAILABLE (already HELD/BOOKED, or grabbed by a racing request). The
 * message names the offending seats so the client can refresh its seat map.
 * Mapped to 409 Conflict.
 */
public class SeatsUnavailableException extends RuntimeException {

    public SeatsUnavailableException(String message) {
        super(message);
    }
}
