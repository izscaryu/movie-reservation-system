package org.example.moviereservationsystem.exception;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Single source of truth for the API's error responses. Every handler emits the
 * same JSON shape via {@link #build}: {timestamp, status, error, message, path}.
 *
 * <p>Two rules govern the messages:
 * <ul>
 *   <li>Domain/validation handlers expose a useful, caller-facing message.
 *   <li>The catch-all 500 (and any unrecognised integrity error) returns a
 *       generic message and logs the real exception server-side — internals
 *       (stack traces, SQL, class names) must never leak to the client.
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Explicit name of the UNIQUE(showtime_seat_id) backstop in V1 — see V1__init_schema.sql. */
    private static final String SEAT_UNIQUE_CONSTRAINT = "uq_reservation_seats_showtime_seat";

    // --- Validation / bad input -> 400 -----------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    /** Constraint violations on method params (e.g. @RequestParam bounds on a @Validated controller). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    /** Malformed/unparseable request body. Keep the message generic so Jackson internals don't leak. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Malformed JSON request", request);
    }

    /** A path/query param could not be coerced to the target type (e.g. /movies/abc for a Long id). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = ex.getName() + ": invalid value '" + ex.getValue() + "'";
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    /** A required query param was absent. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST,
                ex.getParameterName() + ": required parameter is missing", request);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            BadRequestException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    // --- Auth -> 401 -----------------------------------------------------

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(
            BadCredentialsException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "Invalid email or password", request);
    }

    // --- Not found / routing -> 404, 405 ---------------------------------

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    /**
     * Unknown path. Boot 3.5 routes unmatched requests to the resource handler,
     * which throws NoResourceFoundException; NoHandlerFoundException is mapped too
     * in case resource handling is ever disabled. Either way -> a clean 404.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNoEndpoint(
            Exception ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "No endpoint found for the requested path", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage(), request);
    }

    // --- Domain conflicts -> 409 -----------------------------------------

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleEmailExists(
            EmailAlreadyExistsException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
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
     * {@code @Version} optimistic-lock failure when two transactions both flip
     * the same seat, and a DB row-lock deadlock under heavy contention. Both mean
     * the seat was taken concurrently -> 409, so the loser retries, not a 500.
     * (The UNIQUE backstop is handled separately by {@link #handleDataIntegrity}.)
     */
    @ExceptionHandler({
            ObjectOptimisticLockingFailureException.class,
            OptimisticLockException.class,
            CannotAcquireLockException.class})
    public ResponseEntity<Map<String, Object>> handleConcurrentSeatConflict(
            RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "One or more seats were just taken; please refresh", request);
    }

    /**
     * Narrow the broad DataIntegrityViolationException: only the UNIQUE seat
     * backstop firing is a concurrency 409. Any other integrity error (FK, NOT
     * NULL, an unrelated unique key) is a real server fault — mislabelling it as
     * a seat conflict would hide bugs, so it falls through to a logged 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        if (isSeatUniqueViolation(ex)) {
            return build(HttpStatus.CONFLICT,
                    "One or more seats were just taken; please refresh", request);
        }
        log.error("Unhandled data integrity violation on {} {}",
                request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request);
    }

    // --- Catch-all -> 500 (no leak) --------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}",
                request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request);
    }

    // --- helpers ---------------------------------------------------------

    /** True if the seat-unique backstop is anywhere in the cause chain. The constraint is
     *  explicitly named in V1, so a name match is reliable (not a guessed auto-name). */
    private boolean isSeatUniqueViolation(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.toLowerCase().contains(SEAT_UNIQUE_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private ResponseEntity<Map<String, Object>> build(
            HttpStatus status, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
