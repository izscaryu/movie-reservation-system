package org.example.moviereservationsystem.exception;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Minimal, consistent JSON error responses. Phase 7 expands this to cover the
 * whole API; for Phase 2 it covers auth and validation paths.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleEmailExists(
            EmailAlreadyExistsException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(
            BadCredentialsException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "Invalid email or password", request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            BadRequestException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(ShowtimeConflictException.class)
    public ResponseEntity<Map<String, Object>> handleShowtimeConflict(
            ShowtimeConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler({SeatsUnavailableException.class, ReservationStateException.class})
    public ResponseEntity<Map<String, Object>> handleReservationConflict(
            RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /**
     * Backstops for the overbooking race that bypass the explicit checks: the
     * @Version optimistic-lock failure when two transactions both flip the same
     * seat, the UNIQUE(showtime_seat_id) violation if even that slips through,
     * and a DB row-lock deadlock under heavy contention. All mean the seat was
     * taken concurrently -> 409, so the loser retries rather than seeing a 500.
     */
    @ExceptionHandler({
            ObjectOptimisticLockingFailureException.class,
            OptimisticLockException.class,
            DataIntegrityViolationException.class,
            CannotAcquireLockException.class})
    public ResponseEntity<Map<String, Object>> handleConcurrentSeatConflict(
            RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "One or more seats were just taken; please refresh", request);
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private ResponseEntity<Map<String, Object>> build(
            HttpStatus status, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
