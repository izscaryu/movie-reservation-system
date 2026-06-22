-- Phase 6 reporting index. Revenue and popular-movies both filter
-- status = 'CONFIRMED' AND a created_at range; (status, created_at) lets that
-- range scan ride the index instead of filtering created_at row-by-row.
--
-- Right-shape-for-volume, not a measured win: at current seed/test data sizes
-- it changes nothing. It COMPLEMENTS — does not replace — the expiry index
-- idx_reservations_status_expires (status, expires_at), which serves a different
-- predicate (PENDING holds past their deadline). Adding an index does not affect
-- Hibernate's ddl-auto=validate (validate checks tables/columns/types only).
CREATE INDEX idx_reservations_status_created ON reservations (status, created_at);
