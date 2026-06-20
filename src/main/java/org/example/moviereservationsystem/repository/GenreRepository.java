package org.example.moviereservationsystem.repository;

import java.util.Optional;
import org.example.moviereservationsystem.entity.Genre;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenreRepository extends JpaRepository<Genre, Long> {

    Optional<Genre> findByName(String name);
}
