package org.example.moviereservationsystem.repository;

import java.util.List;
import org.example.moviereservationsystem.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByTheaterRoomId(Long theaterRoomId);

    boolean existsByTheaterRoomId(Long theaterRoomId);
}
