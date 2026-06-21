package org.example.moviereservationsystem.config;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.moviereservationsystem.entity.Seat;
import org.example.moviereservationsystem.entity.TheaterRoom;
import org.example.moviereservationsystem.repository.SeatRepository;
import org.example.moviereservationsystem.repository.TheaterRoomRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the theater rooms and their physical seats on startup, consistent with
 * the {@link AdminUserInitializer} admin seed (CommandLineRunner, not a SQL
 * migration). Idempotent PER ROOM: each room is seeded only if no room with
 * that name already exists, so adding a new room here later seeds just that one
 * without touching the others. Seats are generated from the room dimensions
 * (rows x seatsPerRow) as "A1".."<rows>last", e.g. an 8x10 room yields 80 seats
 * A1..H10.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RoomSeatInitializer implements CommandLineRunner {

    private final TheaterRoomRepository theaterRoomRepository;
    private final SeatRepository seatRepository;

    /** Rooms to seed: name, rows, seatsPerRow. */
    private static final List<RoomSpec> ROOMS = List.of(
            new RoomSpec("Room 1", 5, 8),
            new RoomSpec("Room 2", 8, 10),
            new RoomSpec("Room 3", 6, 9));

    @Override
    @Transactional
    public void run(String... args) {
        for (RoomSpec spec : ROOMS) {
            seedRoomIfAbsent(spec);
        }
    }

    private void seedRoomIfAbsent(RoomSpec spec) {
        if (theaterRoomRepository.findByName(spec.name()).isPresent()) {
            log.info("Theater room '{}' already exists; skipping seed.", spec.name());
            return;
        }

        TheaterRoom room = new TheaterRoom();
        room.setName(spec.name());
        room.setRows(spec.rows());
        room.setSeatsPerRow(spec.seatsPerRow());
        theaterRoomRepository.save(room);

        List<Seat> seats = new ArrayList<>();
        for (int r = 0; r < spec.rows(); r++) {
            String rowLabel = String.valueOf((char) ('A' + r));
            for (int n = 1; n <= spec.seatsPerRow(); n++) {
                Seat seat = new Seat();
                seat.setTheaterRoom(room);
                seat.setRowLabel(rowLabel);
                seat.setSeatNumber(n);
                seats.add(seat);
            }
        }
        seatRepository.saveAll(seats);

        log.info("Seeded theater room '{}' with {} seats ({}x{}).",
                spec.name(), seats.size(), spec.rows(), spec.seatsPerRow());
    }

    private record RoomSpec(String name, int rows, int seatsPerRow) {
    }
}
