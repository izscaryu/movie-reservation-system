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
     * Revokes every still-active token for a user in one statement — used by
     * reuse detection to invalidate the whole token family when a revoked token
     * is replayed. Returns the number of rows revoked.
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now "
            + "WHERE r.user.id = :userId AND r.revokedAt IS NULL")
    int revokeAllActiveForUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
