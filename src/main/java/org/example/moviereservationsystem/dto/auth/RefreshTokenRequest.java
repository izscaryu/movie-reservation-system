package org.example.moviereservationsystem.dto.auth;

import jakarta.validation.constraints.NotBlank;

/** Body for both /api/auth/refresh and /api/auth/logout. */
public record RefreshTokenRequest(@NotBlank String refreshToken) {
}
