-- Phase 10 refresh tokens. Long-lived, rotating, revocable sessions that back
-- the short-lived access JWT.
--
-- We store the SHA-256 HASH of the token, never the raw value: the raw token is
-- a high-entropy (256-bit) SecureRandom value handed to the client once and kept
-- only there. SHA-256 (not bcrypt) is the right choice here — bcrypt is a slow
-- KDF for low-entropy passwords; for a random 256-bit token a fast hash is both
-- sufficient and avoids a per-refresh CPU cost. token_hash is a 64-char hex digest.
CREATE TABLE refresh_tokens (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- token_hash is the refresh lookup key (the UNIQUE above already indexes it).
-- user_id is indexed for the reuse-detection "revoke the whole family" sweep.
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
