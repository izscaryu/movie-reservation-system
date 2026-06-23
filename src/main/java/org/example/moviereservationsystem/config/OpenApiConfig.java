package org.example.moviereservationsystem.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger UI metadata. The {@link SecurityScheme} registers the JWT
 * bearer scheme so the Swagger UI shows an "Authorize" button: paste a token from
 * POST /api/auth/login and it is sent as {@code Authorization: Bearer <token>} on
 * subsequent calls. The matching {@code @SecurityRequirement} applies it globally
 * (public endpoints simply ignore the header).
 *
 * <p>Docs UI lives at {@code /swagger-ui.html}; the raw spec at
 * {@code /v3/api-docs}. Both are permitted in the SecurityFilterChain.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Movie Reservation System API",
                version = "v1",
                description = "Backend REST API for browsing movies/showtimes, reserving seats "
                        + "(with overbooking protection), and admin management + reporting."),
        security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfig {
}
