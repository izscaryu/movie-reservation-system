package org.example.moviereservationsystem.controller;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.moviereservationsystem.dto.report.MovieRevenue;
import org.example.moviereservationsystem.dto.report.OccupancyReport;
import org.example.moviereservationsystem.dto.report.PopularMovie;
import org.example.moviereservationsystem.dto.report.RevenueReport;
import org.example.moviereservationsystem.service.ReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only reporting. The path lives under /api/admin/** which the
 * SecurityFilterChain restricts to ROLE_ADMIN — USER -> 403, no token -> 401 —
 * so no SecurityConfig change was needed (same tier as the admin movie/showtime
 * endpoints). All responses are DTOs; entities are never exposed. Date params
 * are ISO yyyy-MM-dd; both bounds optional and inclusive (from > to -> 400).
 */
@RestController
@RequestMapping("/api/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {

    private final ReportService reportService;

    @GetMapping("/revenue")
    public ResponseEntity<RevenueReport> revenue(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.revenue(from, to));
    }

    @GetMapping("/revenue/by-movie")
    public ResponseEntity<List<MovieRevenue>> revenueByMovie(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.revenueByMovie(from, to));
    }

    @GetMapping("/occupancy")
    public ResponseEntity<OccupancyReport> occupancy(@RequestParam Long showtimeId) {
        return ResponseEntity.ok(reportService.occupancy(showtimeId));
    }

    @GetMapping("/popular-movies")
    public ResponseEntity<List<PopularMovie>> popularMovies(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(reportService.popularMovies(from, to, limit));
    }
}
