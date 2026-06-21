package org.example.moviereservationsystem.controller;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.moviereservationsystem.dto.reservation.ReservationRequest;
import org.example.moviereservationsystem.dto.reservation.ReservationResponse;
import org.example.moviereservationsystem.security.UserPrincipal;
import org.example.moviereservationsystem.service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated reservation flow. /api/reservations/** is not matched by any
 * public or admin matcher, so it falls under .anyRequest().authenticated() — no
 * token -> 401. Every action is owner-scoped in the service via the principal's
 * user id (a reservation the caller does not own resolves to 404).
 */
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    public ResponseEntity<ReservationResponse> hold(
            @Valid @RequestBody ReservationRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        ReservationResponse response = reservationService.hold(request, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<ReservationResponse> confirm(
            @PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(reservationService.confirm(id, principal.getId()));
    }

    @GetMapping("/me")
    public ResponseEntity<List<ReservationResponse>> mine(
            @RequestParam(required = false) String filter,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(reservationService.listMine(principal.getId(), filter));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(
            @PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        reservationService.cancel(id, principal.getId());
        return ResponseEntity.noContent().build();
    }
}
