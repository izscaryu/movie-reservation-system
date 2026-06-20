package org.example.moviereservationsystem.repository;

import org.example.moviereservationsystem.entity.TheaterRoom;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TheaterRoomRepository extends JpaRepository<TheaterRoom, Long> {
}
