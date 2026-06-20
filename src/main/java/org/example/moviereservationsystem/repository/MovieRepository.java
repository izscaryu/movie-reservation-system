package org.example.moviereservationsystem.repository;

import java.util.List;
import java.util.Optional;
import org.example.moviereservationsystem.entity.Movie;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MovieRepository extends JpaRepository<Movie, Long> {

    // Single active movie with its genres eagerly loaded (one query, no N+1).
    // A single root is unaffected by the to-many fetch duplicate-row issue.
    @EntityGraph(attributePaths = "genres")
    Optional<Movie> findByIdAndDeletedAtIsNull(Long id);

    // List all active movies with their FULL genre set, deterministic order.
    // DISTINCT + LEFT JOIN FETCH dedups the rows a to-many fetch produces while
    // still including genre-less movies. (A derived findByDeletedAtIsNull +
    // @EntityGraph cannot express DISTINCT or ORDER BY and would return a movie
    // once per genre.)
    @Query("SELECT DISTINCT m FROM Movie m LEFT JOIN FETCH m.genres "
            + "WHERE m.deletedAt IS NULL ORDER BY m.title")
    List<Movie> findAllActiveWithGenres();

    // Step 1 of the genre filter: IDs of active movies that carry the named
    // genre (case-insensitive). Kept separate from the fetch so the genre WHERE
    // never truncates the fetched genre collection.
    @Query("SELECT m.id FROM Movie m JOIN m.genres g "
            + "WHERE m.deletedAt IS NULL AND LOWER(g.name) = LOWER(:genre)")
    List<Long> findIdsByGenreName(@Param("genre") String genre);

    // Step 2: fetch those movies with their FULL genre set, same deterministic
    // order. No genre predicate here, so every genre of each movie is returned.
    @Query("SELECT DISTINCT m FROM Movie m LEFT JOIN FETCH m.genres "
            + "WHERE m.id IN :ids ORDER BY m.title")
    List<Movie> findByIdsWithGenres(@Param("ids") List<Long> ids);
}
