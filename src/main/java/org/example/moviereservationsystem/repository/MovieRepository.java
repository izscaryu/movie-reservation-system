package org.example.moviereservationsystem.repository;

import org.example.moviereservationsystem.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MovieRepository extends JpaRepository<Movie, Long> {
}
