package org.example.moviereservationsystem.repository;

import java.util.Collection;
import java.util.List;
import org.example.moviereservationsystem.entity.ReservationSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationSeatRepository extends JpaRepository<ReservationSeat, Long> {

    // A reservation's seat links with the showtime seat + physical seat loaded.
    // Used both to render seat labels and, on cancel/expiry, to flip those
    // showtime seats back to AVAILABLE (managed entities, so @Version applies)
    // before deleting the link rows.
    @Query("SELECT rs FROM ReservationSeat rs "
            + "JOIN FETCH rs.showtimeSeat ss JOIN FETCH ss.seat "
            + "WHERE rs.reservation.id = :reservationId")
    List<ReservationSeat> findByReservationIdWithSeat(@Param("reservationId") Long reservationId);

    // Batch variant for list views: seats for many reservations in one query,
    // grouped by reservation in the service (avoids N+1 over a user's list).
    @Query("SELECT rs FROM ReservationSeat rs "
            + "JOIN FETCH rs.showtimeSeat ss JOIN FETCH ss.seat "
            + "WHERE rs.reservation.id IN :reservationIds")
    List<ReservationSeat> findByReservationIdInWithSeat(
            @Param("reservationIds") Collection<Long> reservationIds);

    void deleteByReservationId(Long reservationId);
}
