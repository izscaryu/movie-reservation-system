-- Phase 3 soft delete for movies. A non-null deleted_at marks a movie as
-- removed: it disappears from every public read (all of which filter on
-- deleted_at IS NULL) while existing showtimes/reservations keep referencing
-- the row. DELETE on the admin endpoint sets this timestamp; rows are never
-- physically removed.
ALTER TABLE movies
    ADD COLUMN deleted_at DATETIME(6) NULL;

-- Public reads constantly filter on deleted_at IS NULL; index keeps it cheap.
CREATE INDEX idx_movies_deleted_at ON movies (deleted_at);
