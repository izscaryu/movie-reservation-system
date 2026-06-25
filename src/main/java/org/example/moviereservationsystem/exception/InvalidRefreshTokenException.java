package org.example.moviereservationsystem.exception;

/**
 * Thrown when a presented refresh token is unknown, expired, or already revoked.
 * Mapped to 401 by {@link GlobalExceptionHandler}. The message is intentionally
 * generic so it never reveals whether a token existed.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Invalid or expired refresh token");
    }
}
