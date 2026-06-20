package org.example.moviereservationsystem.repository;

import java.util.Optional;
import org.example.moviereservationsystem.entity.Genre;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenreRepository extends JpaRepository<Genre, Long> {

    // Case-insensitive lookup backs the get-or-create in MovieService, so
    // "Action"/"action" resolve to one genre row.
    Optional<Genre> findByNameIgnoreCase(String name);
}
