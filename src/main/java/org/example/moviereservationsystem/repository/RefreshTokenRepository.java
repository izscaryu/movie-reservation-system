package org.example.moviereservationsystem.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import org.example.moviereservationsystem.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Atomic compare-and-consume: revokes the token identified by {@code hash} only
     * if it is still active (not revoked, not expired), in one statement. Returns 1
     * if this caller consumed it, 0 otherwise.
     *
     * <p>This closes the rotation TOCTOU window. The {@code WHERE revoked_at IS NULL}
     * predicate is re-evaluated by InnoDB as a current read under the row's X-lock,
     * so of N concurrent callers that all read the token as active, exactly one gets
     * a row match — mirroring the rows-affected serialization used for reservation
     * state transitions (compareAndSetStatus).
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now "
            + "WHERE r.tokenHash = :hash AND r.revokedAt IS NULL AND r.expiresAt >= :now")
    int consumeIfActive(@Param("hash") String hash, @Param("now") LocalDateTime now);

    /**
     * Revokes every still-active token for a user in one statement — used by
     * reuse detection to invalidate the whole token family when a revoked token
     * is replayed. Returns the number of rows revoked.
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now "
            + "WHERE r.user.id = :userId AND r.revokedAt IS NULL")
    int revokeAllActiveForUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
