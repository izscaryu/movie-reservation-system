package org.example.moviereservationsystem.repository;

import java.util.List;
import java.util.Optional;
import org.example.moviereservationsystem.entity.Movie;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MovieRepository extends JpaRepository<Movie, Long> {

    // Single active movie with its genres eagerly loaded (one query, no N+1).
    // A single root is unaffected by the to-many fetch duplicate-row issue.
    @EntityGraph(attributePaths = "genres")
    Optional<Movie> findByIdAndDeletedAtIsNull(Long id);

    // Step 1 (unfiltered list): a page of active movie IDs in deterministic
    // (title, id) order. Paging IDs — not the fetch — sidesteps HHH000104: a
    // collection fetch join cannot be paginated in the database, so Hibernate
    // would otherwise pull every row and paginate in memory. Explicit countQuery
    // because the projection is a scalar, not the root entity.
    @Query(value = "SELECT m.id FROM Movie m WHERE m.deletedAt IS NULL ORDER BY m.title, m.id",
            countQuery = "SELECT COUNT(m) FROM Movie m WHERE m.deletedAt IS NULL")
    Page<Long> findActiveIds(Pageable pageable);

    // Step 1 (genre filter): a page of matching active movie IDs (case-insensitive),
    // same (title, id) order. Kept separate from the fetch so the genre WHERE never
    // truncates the fetched genre collection. COUNT(DISTINCT m.id) so the total is
    // a movie count, not a movie-genre row count.
    @Query(value = "SELECT m.id FROM Movie m JOIN m.genres g "
            + "WHERE m.deletedAt IS NULL AND LOWER(g.name) = LOWER(:genre) "
            + "ORDER BY m.title, m.id",
            countQuery = "SELECT COUNT(DISTINCT m.id) FROM Movie m JOIN m.genres g "
            + "WHERE m.deletedAt IS NULL AND LOWER(g.name) = LOWER(:genre)")
    Page<Long> findIdsByGenreName(@Param("genre") String genre, Pageable pageable);

    // Step 2: fetch those movies with their FULL genre set. The ORDER BY MUST be
    // re-applied here — an IN (...) does not preserve the order of the id page, so
    // without it the page would come back shuffled. (title, id) is the same
    // deterministic key used in step 1, the id breaking title ties.
    @Query("SELECT DISTINCT m FROM Movie m LEFT JOIN FETCH m.genres "
            + "WHERE m.id IN :ids ORDER BY m.title, m.id")
    List<Movie> findByIdsWithGenres(@Param("ids") List<Long> ids);
}
