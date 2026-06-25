package org.example.moviereservationsystem.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import org.example.moviereservationsystem.entity.RefreshToken;
import org.example.moviereservationsystem.entity.User;
import org.example.moviereservationsystem.exception.InvalidRefreshTokenException;
import org.example.moviereservationsystem.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Issues, rotates, and revokes refresh tokens. Only the SHA-256 hash of a token
 * is ever stored; the raw value (returned to the client) is a 256-bit
 * SecureRandom string. All methods run inside the caller's transaction
 * (AuthService), so managed-entity mutations flush on commit.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokenRepository refreshTokenRepository;
    private final long refreshExpirationMs;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            @Value("${app.jwt.refresh-expiration-ms}") long refreshExpirationMs) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    /** Creates and persists a new refresh token for the user; returns the RAW token. */
    public String issueFor(User user) {
        String raw = generateRawToken();
        RefreshToken entity = new RefreshToken();
        entity.setUser(user);
        entity.setTokenHash(hash(raw));
        entity.setExpiresAt(LocalDateTime.now().plus(refreshExpirationMs, ChronoUnit.MILLIS));
        refreshTokenRepository.save(entity);
        return raw;
    }

    /**
     * Validates a presented refresh token and CONSUMES it (marks it revoked) so it
     * can be rotated for a fresh one. Returns the matched row (its user is needed
     * to mint the new tokens).
     *
     * <p>Reuse detection: presenting an ALREADY-revoked token means a spent token
     * was replayed — treated as theft, so every active token in that user's family
     * is revoked, forcing a full re-login. Unknown / expired / revoked all surface
     * as a generic 401 ({@link InvalidRefreshTokenException}).
     */
    public RefreshToken verifyAndConsume(String rawToken) {
        LocalDateTime now = LocalDateTime.now();
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (token.isRevoked()) {
            int revoked = refreshTokenRepository.revokeAllActiveForUser(token.getUser().getId(), now);
            log.warn("Refresh-token reuse detected for user {}; revoked {} active token(s)",
                    token.getUser().getId(), revoked);
            throw new InvalidRefreshTokenException();
        }
        if (token.isExpired(now)) {
            throw new InvalidRefreshTokenException();
        }

        token.setRevokedAt(now); // rotation: this token is now spent
        return token;
    }

    /** Logout: revoke the presented token if it exists and is still active. Idempotent. */
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken)).ifPresent(token -> {
            if (!token.isRevoked()) {
                token.setRevokedAt(LocalDateTime.now());
            }
        });
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32]; // 256 bits of entropy
        RANDOM.nextBytes(bytes);
        return BASE64_URL.encodeToString(bytes);
    }

    /** SHA-256 hex digest. Fast hash is correct for a high-entropy random token (bcrypt is not). */
    private String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
        }
    }
}
