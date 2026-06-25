package org.example.moviereservationsystem.dto.auth;

/**
 * Login / refresh response. {@code accessToken} is the short-lived bearer JWT;
 * {@code refreshToken} is the long-lived rotating session token. {@code token}
 * is a backward-compatible alias of {@code accessToken} (same value) kept so
 * existing clients of the pre-Phase-10 contract keep working — new clients
 * should read {@code accessToken}.
 */
public record AuthResponse(
        String accessToken,
        String token,
        String tokenType,
        long expiresInMs,
        String refreshToken) {

    public static AuthResponse of(String accessToken, long expiresInMs, String refreshToken) {
        return new AuthResponse(accessToken, accessToken, "Bearer", expiresInMs, refreshToken);
    }
}
