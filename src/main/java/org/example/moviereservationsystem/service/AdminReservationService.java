package org.example.moviereservationsystem.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.example.moviereservationsystem.dto.PageResponse;
import org.example.moviereservationsystem.dto.reservation.AdminReservationView;
import org.example.moviereservationsystem.entity.Reservation;
import org.example.moviereservationsystem.entity.ReservationStatus;
import org.example.moviereservationsystem.exception.BadRequestException;
import org.example.moviereservationsystem.repository.ReservationRepository;
import org.example.moviereservationsystem.util.DateRanges;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin reservation browsing: paginated, filterable by status and a created_at
 * range (the booking axis, reusing the shared {@link DateRanges} helper), with a
 * whitelisted sort.
 */
@Service
@RequiredArgsConstructor
public class AdminReservationService {

    private final ReservationRepository reservationRepository;

    // Allowed sort fields -> the entity property they map to. Built server-side so
    // the client can NEVER sort on an arbitrary entity property (which raw
    // Pageable-sort passthrough would permit).
    private static final Map<String, String> SORTABLE = Map.of(
            "createdAt", "createdAt",
            "totalPrice", "totalPrice",
            "status", "status",
            "id", "id");

    @Transactional(readOnly = true)
    public PageResponse<AdminReservationView> list(
            ReservationStatus status, LocalDate from, LocalDate to,
            int page, int size, String sort, String direction) {
        DateRanges.validateRange(from, to);
        Sort sortSpec = resolveSort(sort, direction);
        Page<Reservation> result = reservationRepository.findForAdmin(
                status, DateRanges.startOfDay(from), DateRanges.startOfDayAfter(to),
                PageRequest.of(page, size, sortSpec));
        List<AdminReservationView> content =
                result.getContent().stream().map(AdminReservationView::of).toList();
        return PageResponse.of(content, result);
    }

    private Sort resolveSort(String sort, String direction) {
        String property = SORTABLE.get(sort);
        if (property == null) {
            throw new BadRequestException(
                    "Unsupported sort field '" + sort + "'; allowed: " + SORTABLE.keySet());
        }
        Sort.Direction dir;
        try {
            dir = Sort.Direction.fromString(direction);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(
                    "Invalid sort direction '" + direction + "'; use 'asc' or 'desc'");
        }
        Sort base = Sort.by(dir, property);
        // Append id as a stable tiebreaker so pages don't shuffle when the primary
        // sort field ties (e.g. many rows share a status or created_at).
        return property.equals("id") ? base : base.and(Sort.by(Sort.Direction.ASC, "id"));
    }
}
