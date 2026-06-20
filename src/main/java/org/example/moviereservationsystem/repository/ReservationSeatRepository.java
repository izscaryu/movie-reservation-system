package org.example.moviereservationsystem.repository;

import org.example.moviereservationsystem.entity.ReservationSeat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationSeatRepository extends JpaRepository<ReservationSeat, Long> {
}
