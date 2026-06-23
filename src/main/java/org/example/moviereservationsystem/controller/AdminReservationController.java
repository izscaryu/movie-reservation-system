package org.example.moviereservationsystem.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.example.moviereservationsystem.dto.PageResponse;
import org.example.moviereservationsystem.dto.reservation.AdminReservationView;
import org.example.moviereservationsystem.entity.ReservationStatus;
import org.example.moviereservationsystem.service.AdminReservationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin reservation listing. Under /api/admin/** so the existing
 * SecurityFilterChain already restricts it to ROLE_ADMIN (USER -> 403, no token
 * -> 401); no security change needed. Paginated and filterable by status and a
 * created_at range; sort is whitelisted in the service. An unknown status value
 * fails enum binding -> 400; from > to -> 400; page/size bounds -> 400.
 */
@RestController
@RequestMapping("/api/admin/reservations")
@RequiredArgsConstructor
@Validated
public class AdminReservationController {

    private final AdminReservationService adminReservationService;

    @GetMapping
    public ResponseEntity<PageResponse<AdminReservationView>> list(
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageResponse.MAX_PAGE_SIZE) int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return ResponseEntity.ok(
                adminReservationService.list(status, from, to, page, size, sort, direction));
    }
}
