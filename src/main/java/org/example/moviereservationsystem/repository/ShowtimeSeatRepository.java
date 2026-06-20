package org.example.moviereservationsystem.repository;

import org.example.moviereservationsystem.entity.ShowtimeSeat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowtimeSeatRepository extends JpaRepository<ShowtimeSeat, Long> {
}
