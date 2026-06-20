package org.example.moviereservationsystem.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.example.moviereservationsystem.entity.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Issues and validates HS256 JWTs. Tokens are self-contained: subject = user
 * id, plus email and role claims, so the authentication filter can rebuild the
 * principal without a database lookup.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public String generateToken(UserPrincipal principal) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(String.valueOf(principal.getId()))
                .claim("email", principal.getUsername())
                .claim("role", principal.getRole().name())
                .issuedAt(now)
                .expiration(expiry)
                // Pin HS256 explicitly: signWith(key) would otherwise auto-select
                // the HMAC variant from key length (a >=48-byte key yields HS384).
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Parses and verifies the token (signature + expiry), returning the
     * principal built from its claims. Throws {@link io.jsonwebtoken.JwtException}
     * on any invalid/expired token.
     */
    public UserPrincipal parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Long userId = Long.valueOf(claims.getSubject());
        String email = claims.get("email", String.class);
        Role role = Role.valueOf(claims.get("role", String.class));
        return new UserPrincipal(userId, email, null, role);
    }
}
