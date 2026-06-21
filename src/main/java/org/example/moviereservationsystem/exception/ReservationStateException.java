package org.example.moviereservationsystem.exception;

/**
 * Thrown when a reservation cannot make a requested transition from its current
 * state — e.g. confirming a hold that already EXPIRED (confirm-vs-expire race),
 * double-confirming, or cancelling after the showtime has started. Mapped to
 * 409 Conflict.
 */
public class ReservationStateException extends RuntimeException {

    public ReservationStateException(String message) {
        super(message);
    }
}
