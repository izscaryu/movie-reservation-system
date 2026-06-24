# Build Progress

Tracking the Movie Reservation System build, phase by phase.
Spec: `movie-reservation-system-guide.md`.

| Phase | Title | Status |
|-------|-------|--------|
| 0 | Project Scaffolding & Environment | ✅ Done |
| 1 | Data Model & Migrations | ✅ Done |
| 2 | Authentication & Authorization | ✅ Done |
| 3 | Movie Management | ✅ Done |
| 4 | Showtime & Seat Setup | ✅ Done |
| 5 | Reservation Flow (core) | ✅ Done |
| 6 | Admin Reporting | ✅ Done |
| 7 | Polish & Cross-Cutting Concerns | ✅ Done |
| 8 | Testing | ✅ Done |
| 9 | Containerization & Documentation | ⬜ Not started |

## Phase 0 — notes / decisions

- **Stack confirmed:** Spring Boot 3.5.15, Java 21 (project source/target level; runtime
  JDK on this machine/ is 25, backward-compatible), Maven via `mvnw` wrapper, MySQL 8.4 in Docker.
- **Dependencies:** All Phase 0 deps were already present from the IntelliJ/Spring Initializr
  setup (Web, Data JPA, MySQL driver, Security, Validation, Flyway core + flyway-mysql, Lombok).
  No pom changes needed.
- **Config format:** Converted `application.properties` → `application.yml` (deleted the old file).
- **Secrets:** All DB credentials come from env vars with local-dev defaults; `.env` is gitignored,
  `.env.example` is the committed template.
- **Schema ownership:** Flyway owns the schema; `spring.jpa.hibernate.ddl-auto=validate` so Hibernate
  never mutates tables.
- **MySQL:** Runs via `docker-compose.yml` (named volume for persistence, healthcheck for Phase 9).
- **Local port note:** host 3306 is taken by a native MySQL80 Windows service, so Docker MySQL
  is published on **3307** (`.env` → `DB_PORT=3307`). The Spring app reads `DB_PORT`, so it
  connects to 3307 too.

## Phase 1 — notes / decisions

- **Design decisions:** Genre is a separate entity, many-to-many with Movie via the `movie_genre`
  join table. TheaterRoom is configurable (`num_rows` × `seats_per_row`); seats are generated from
  those dimensions — but the actual room/seat *seeding* is deferred to Phase 4 per the spec.
  Phase 1 is tables + entities + repositories + admin seed only.
- **Migration:** single `V1__init_schema.sql` creates all 11 tables (incl. `flyway_schema_history`)
  in FK order, utf8mb4/InnoDB. Indexes on `showtime_seats.showtime_id`, `reservations.user_id`,
  etc. `@Version` column on `showtime_seats` for optimistic locking. Overbooking safety net =
  `UNIQUE(showtime_seat_id)` on `reservation_seats`.
- **Reserved word:** `rows` is reserved in MySQL → column named `num_rows` (mapped in `TheaterRoom`).
- **Enums** stored as `VARCHAR(20)` via `@Enumerated(STRING)`. **Money** as `DECIMAL(10,2)` → `BigDecimal`.
- **Entities:** all `@ManyToOne` are `LAZY`; Lombok `@Getter/@Setter/@NoArgsConstructor` (no `@Data`
  on entities). `created_at` via Hibernate `@CreationTimestamp`.
- **Admin seed:** `AdminUserInitializer` (`CommandLineRunner`) creates one ADMIN user on startup,
  idempotent on email, password bcrypt-hashed at runtime (no hash committed). Credentials from
  `ADMIN_EMAIL`/`ADMIN_PASSWORD`/`ADMIN_NAME` env vars (defaults in `application.yml`). A
  `PasswordEncoder` bean lives in `SecurityBeansConfig`; full `SecurityFilterChain` waits for Phase 2.
- **No SQL seed migrations** — all seeding is via Java `CommandLineRunner`.
- **Verified:** `V1` applies, Hibernate `validate` passes (entities match schema), admin row exists,
  `./mvnw test` green.

## Phase 2 — notes / decisions

- **JWT lib:** JJWT 0.12.6 (`jjwt-api` + runtime `jjwt-impl`/`jjwt-jackson`). HS256.
- **Token claims:** `sub` = user id, plus `email` and `role`; `iat`/`exp`. Secret from
  `JWT_SECRET` (dev-only insecure default in `application.yml`, must override in prod; ≥32 bytes
  for HS256). Lifetime from `JWT_EXPIRATION_MS` (default 1h).
- **Stateless principal (intentional trade-off):** `JwtAuthenticationFilter` rebuilds
  `UserPrincipal` straight from the token claims on every request — **no DB lookup**. Consequence:
  a role change (e.g. `/promote`) does **not** affect an already-issued token; the user must
  **re-login** (or wait for expiry) to get a token carrying the new role. This is accepted standard
  JWT behavior, chosen for true statelessness. To make promotions take effect immediately we'd
  switch the filter to a per-request DB load — deliberately not doing that.
- **Login auth:** Spring Security auto-configures a `DaoAuthenticationProvider` from the
  `UserDetailsServiceImpl` (loads by email) + `BCryptPasswordEncoder` beans; `AuthService.login`
  calls the global `AuthenticationManager`. No explicit provider bean (an earlier redundant
  `@Bean DaoAuthenticationProvider` was removed — it triggered a "UserDetailsService beans will
  not be used…" WARN and the `.authenticationProvider()` call on HttpSecurity was dead code, since
  login goes through the global manager). `UserPrincipal` carries the user id for Phase 5 ownership.
- **Signup:** `POST /api/auth/signup` returns **201 with no token** (role always USER); login is a
  separate call. `POST /api/auth/login` returns `{token, tokenType:"Bearer", expiresInMs}`.
- **Endpoint tiers:** public = `POST /api/auth/**` and `GET /api/movies/**`, `GET /api/showtimes/**`;
  admin-only = `/api/admin/**` (`hasRole("ADMIN")`); everything else authenticated. Stateless
  session, CSRF disabled. 401 via `RestAuthenticationEntryPoint`, 403 via `RestAccessDeniedHandler`.
- **Promote:** `POST /api/admin/users/{id}/promote` (admin-only) sets role ADMIN, 404 if missing.
- **Exception handling:** minimal `GlobalExceptionHandler` (`@RestControllerAdvice`) for validation
  (400), email-taken (409), bad credentials (401), not-found (404). Full version is Phase 7.
- **Resolved WARN:** the "Global AuthenticationManager configured with an AuthenticationProvider
  bean…" message was removed by deleting the redundant provider bean (see Login auth above).
- **Verified:** integration test (`AuthIntegrationTest`) covers all four cases —
  signup→login→token, no-token→401, USER token on `/api/admin/**`→403, ADMIN token→200. All green.

## Phase 3 — notes / decisions

- **Endpoints:** admin (ADMIN-only via the existing `/api/admin/**` tier): `POST /api/admin/movies`
  → 201, `PUT /api/admin/movies/{id}` → 200, `DELETE /api/admin/movies/{id}` → 204. Public (the
  existing `GET /api/movies/**` permitAll tier): `GET /api/movies?genre=...` and
  `GET /api/movies/{id}`. **No `SecurityConfig` change needed** — Phase 2 tiers already cover both
  path prefixes (public reads under `/api/movies`, admin writes under `/api/admin/movies`).
- **DTOs (entity never exposed):** `dto/movie/MovieRequest` — validated `title` @NotBlank
  @Size(255), `durationMinutes` @NotNull @Positive, `description` ≤5000, `posterUrl` ≤512, each
  genre name @NotBlank @Size(100). `MovieResponse` returns `genres` as a **sorted (case-insensitive)
  list of names**. The service maps entity→DTO inside the open transaction (genres always
  initialised), so controllers only ever see DTOs.
- **Genre = NAMES, get-or-create (confirmed design):** `MovieService.resolveGenres` dedups within a
  request by lowercased+trimmed name, then `findByNameIgnoreCase` else insert. First-seen casing is
  the stored casing; `"Action"`/`"action"` resolve to one row. No separate Genre admin API. Unused
  `GenreRepository.findByName` was replaced by `findByNameIgnoreCase` (nothing referenced it).
- **Soft delete (confirmed design):** `V2__add_movie_soft_delete.sql` adds nullable
  `deleted_at DATETIME(6)` (+ `idx_movies_deleted_at`) to `movies`; matching `Movie.deletedAt` field
  keeps `ddl-auto=validate` happy. DELETE stamps `now()` → 204. **PUT/DELETE on an already
  soft-deleted movie → 404** (both look up via `findByIdAndDeletedAtIsNull`, so a deleted row is
  never silently re-edited/re-deleted). Every public read filters `deleted_at IS NULL`. Existing
  showtimes/reservations keep referencing the row.
- **Migration version:** the next free Flyway version was **V2** — the Phase 1 admin seed was a Java
  `CommandLineRunner`, not a SQL migration, so the spec's `V2__seed_admin.sql` was never consumed.
- **N+1 + genre-filter truncation (the tricky bit):** single fetch `findByIdAndDeletedAtIsNull` uses
  `@EntityGraph("genres")`. List queries use JPQL `SELECT DISTINCT m … LEFT JOIN FETCH m.genres …
  ORDER BY m.title` rather than a derived `@EntityGraph` list method — a to-many `@EntityGraph` list
  returns duplicate roots and cannot carry `DISTINCT` or the explicit `ORDER BY title`. The genre
  filter is **two-step**: `findIdsByGenreName` (case-insensitive) finds matching movie IDs, then
  `findByIdsWithGenres` fetches those IDs with their **full** genre set (no genre predicate in the
  fetch, so the collection is never truncated). Empty match short-circuits to avoid an `IN ()` query.
- **Verified:** `MovieIntegrationTest` (7 tests, MockMvc, real MySQL) — create→sorted genres; genre
  filter returns each movie's **full** genre set (the truncation guard); case-insensitive
  get-or-create reuse; update replaces fields+genres; soft-delete hides from single+list reads and a
  2nd delete/update → 404; USER→403 / no-token→401; validation (blank title, null/zero duration) →
  400. Full suite green: **12 tests, 0 failures**.

## Phase 4 — notes / decisions

- **No new DDL (no V3).** All Phase 4 tables already existed in `V1__init_schema.sql`:
  `theater_rooms`, `seats`, `showtimes` (incl. **`end_time NOT NULL`**), `showtime_seats` (incl. the
  **`@Version`** column). So `endTime` being a stored column was effectively pre-decided by the
  schema; Phase 4 adds no migration, only Java.
- **Room/Seat model (confirmed design):** kept **configurable** rooms (`num_rows × seats_per_row`),
  seats generated from those dimensions. Seeded via a Java **`RoomSeatInitializer` CommandLineRunner**
  (consistent with `AdminUserInitializer`; the project seeds in Java, not SQL). **Idempotent per room
  by name** — each room is seeded only if absent, so adding a room later seeds just that one. Seeds 3
  rooms: **Room 1 = 5×8 (40 seats), Room 2 = 8×10 (80), Room 3 = 6×9 (54)**. Row labels A, B, C…;
  seat label = `rowLabel + seatNumber` (e.g. `A5`).
- **endTime (confirmed design):** **stored**, computed server-side at creation as
  `startTime + movie.durationMinutes` (client never sends it). Two reasons: the overlap check does
  interval math in SQL so `end_time` must be a real column; and storing it **snapshots** the duration
  — later editing a movie's `durationMinutes` does not retroactively shift already-scheduled showtimes.
- **Auto seat-map generation (THE key step):** `POST /api/admin/showtimes` creates the showtime and,
  in the **same `@Transactional`**, inserts one `ShowtimeSeat` (status `AVAILABLE`) per `Seat` in the
  room. The single transaction makes it **atomic** — if seat generation fails the showtime insert
  rolls back too, so a showtime can never exist with a missing/partial seat map. `@Version` rows are
  populated now (value 0) but unused until Phase 5's reservation/concurrency flow.
- **Overlap validation:** rejected with **409 Conflict** (`ShowtimeConflictException` → new handler).
  Uses interval logic `existing.startTime < newEnd AND existing.endTime > newStart` (derived query
  `existsByTheaterRoom_IdAndStartTimeLessThanAndEndTimeGreaterThan`), **not** start-time equality.
  Strict `<` / `>` means **back-to-back showtimes are allowed** (one ending exactly as the next starts
  is not an overlap); a test pins this boundary (touching = 201, 1 min into the window = 409).
- **KNOWN LIMITATION (concurrency):** the overlap check is **read-then-write**, so two simultaneous
  admin creates could both pass it and double-book a room — same class of race as the Phase 3 genre
  get-or-create. **Accepted at admin scale**, not engineered around. If it ever mattered: a DB-level
  guard (MySQL has no exclusion constraint, so a locking/serialized check) would be the fix.
- **Soft-deleted movie (confirmed design):**
  - **Create** a showtime for a soft-deleted/missing movie → **404** (looked up via
    `findByIdAndDeletedAtIsNull`).
  - **Public reads hide them:** `GET /api/movies/{movieId}/showtimes` for a deleted/missing movie →
    404; `GET /api/showtimes/{id}/seats` whose movie is soft-deleted → 404. Consistent with Phase 3
    (every public movie read filters `deleted_at IS NULL`). Showtime rows stay in the DB (integrity
    for past reservations); they are just not surfaced publicly.
- **Endpoints:** admin (`/api/admin/**` tier): `POST /api/admin/showtimes` → 201. Public
  (already-permitAll GET tiers): `GET /api/movies/{movieId}/showtimes?date=YYYY-MM-DD` (date optional;
  omitted = all) under `/api/movies/**`, and `GET /api/showtimes/{id}/seats` (seat map) under
  `/api/showtimes/**`. **No `SecurityConfig` change** — both prefixes were opened for GET back in
  **Phase 2** (not Phase 3), so `/api/showtimes/**` was already public.
- **DTOs (entity never exposed):** `ShowtimeRequest` (`movieId`/`theaterRoomId` @NotNull,
  `startTime` @NotNull @Future, `price` @NotNull @Positive — no `endTime`), `ShowtimeResponse`,
  `SeatMapResponse` + `SeatMapEntry` (exposes **`showtimeSeatId`** — the id Phase 5 will lock — not
  the physical seat id). Repository fetches `JOIN FETCH` movie/room/seat to avoid N+1 when mapping.
- **Tooling note (not a code bug):** Spring does **not** read `.env` (only docker-compose does).
  Running `./mvnw test` from a bare shell fell back to the `application.yml` default `DB_PORT:3306`
  (the native MySQL80 service, which has no `movieuser`) → `Access denied`. Fix: export `.env` into
  the shell (or use the IDE run config) so `DB_PORT=3307` (Docker MySQL) is used.
- **Verified:** `ShowtimeIntegrationTest` (7 tests, MockMvc, real MySQL) — auto-gen seat count ==
  room dims (Room 2 = **80 seats**) and all `AVAILABLE`; overlap same room → 409, non-overlap /
  different room → 201; back-to-back boundary (touching 201 / overlapping 409); soft-deleted movie
  create → 404; public reads work with no token and hide a soft-deleted movie (404); USER → 403 /
  no-token → 401; validation (missing movieId/startTime, non-positive price, past start) → 400. Full
  suite green: **19 tests, 0 failures**.

## Phase 5 — notes / decisions

- **No new DDL (no V3).** `reservations` + `reservation_seats` (with FKs, `idx_reservations_user`,
  `idx_reservations_status_expires (status, expires_at)`, and the `UNIQUE(showtime_seat_id)`
  overbooking safety net) already existed in `V1`. The `@Version` on `showtime_seats` is finally used.
- **No `SecurityConfig` change.** `/api/reservations/**` matches no public/admin matcher, so it falls
  under `.anyRequest().authenticated()` → any logged-in role; no token → 401. Every action is
  **owner-scoped in the service** by the principal's user id (not by the URL).
- **Needed addition:** `@EnableScheduling` on the app class — it was absent, so without it the
  `@Scheduled` expiry job is created but never fires.
- **Concurrency = optimistic locking (confirmed, the headline decision).** Hold reads each
  `ShowtimeSeat`, checks `AVAILABLE`, sets `HELD`; the versioned `UPDATE … WHERE version=?` makes the
  loser of a race match 0 rows → 409. Three layers: (a) the status read rejects the common
  already-taken case (with the offending seat labels); (b) `@Version` catches the true read-both race;
  (c) `UNIQUE(showtime_seat_id)` is the last-resort backstop. Chosen over pessimistic
  (`SELECT … FOR UPDATE`) — no held DB locks, better under the low contention that's realistic, and
  the spec's recommended option.
- **THE deadlock fix (subtle, important).** Hibernate flushes **INSERTs before UPDATEs** within a
  flush, so naively the `reservation_seats` INSERT took the unique-key lock *before* the
  `showtime_seats` versioned UPDATE — two concurrent holds then grabbed locks in opposite orders and
  MySQL killed one with a **`Deadlock found`** (surfaced as 500, not 409). Fix: in `hold()`, set seats
  `HELD` and **`entityManager.flush()` BEFORE** creating the reservation/links, so every hold takes
  the seat row lock first (one consistent order). The loser now blocks then gets a clean
  `OptimisticLockException` → 409. Also note `flush()` throws the **native**
  `jakarta.persistence.OptimisticLockException`, not Spring's translated type (manual flush bypasses
  Spring's `@Repository`/commit translation) — caught explicitly in the service; the handler maps the
  native + Spring optimistic, `DataIntegrityViolationException`, and `CannotAcquireLockException` all
  to 409 as backstops.
- **Atomic multi-seat holds.** Whole hold is one `@Transactional`; any failure rolls back every
  tentative `HELD` — a partial hold can never leak. (Test: A holds {s1,s2,s3}; B's {s3,s4,s5} → 409
  and s4,s5 stay AVAILABLE.)
- **Seat ↔ showtime validation + dedup.** Requested ids are de-duplicated, then loaded
  `WHERE showtime_id=? AND id IN (?)`; if fewer come back than distinct requested, an unknown /
  cross-showtime id was posted → **400**.
- **State machine.** Seats `AVAILABLE→HELD→BOOKED`, back to `AVAILABLE` on expiry/cancel. Reservations
  `PENDING→{CONFIRMED, EXPIRED, CANCELLED}`, `CONFIRMED→CANCELLED`. Releasing a seat flips the
  `ShowtimeSeat` to AVAILABLE (managed update, version-guarded) **and deletes the `reservation_seats`
  link** (frees the unique key so the seat can be re-held). `expiresAt` is nulled on leaving PENDING
  (per the entity contract).
- **Reservation-level transitions are guarded conditional updates.** `compareAndSetStatus(id, from,
  to)` = `UPDATE … WHERE id=? AND status=:from`, checking rows-affected. This is the serialization
  point for **confirm-vs-expire**: the row lock means exactly one of the two sees 1 row (wins) and the
  other sees 0 → 409. Avoids needing a `@Version` column on `Reservation` (so no DDL). Confirm's
  `HELD→BOOKED` seat flip is a *managed* update (not bulk JPQL), so `@Version` guards it too — the
  exact-instant race resolves at the seat level as well.
- **Expiry job — the Spring proxy gotcha (folded in).** `ReservationExpiryJob` is a **separate bean**;
  it calls `reservationService.expireOne(id)` per overdue hold, **crossing the bean boundary** so each
  gets its own `@Transactional`. A `this.expireOne(...)` loop inside the service would be
  self-invocation and bypass the proxy (the per-reservation transaction would silently not apply).
  `expireOne` is idempotent (guarded update no-ops if already non-PENDING). `runOnce()` is exposed so
  the test drives one sweep deterministically. Cadence: `@Scheduled(fixedRate = 60000)`; hold window
  10 min (`app.reservation.hold-minutes`).
- **Decided codes (confirmed):** acting on someone else's reservation → **404** (don't reveal the id
  exists); confirm-after-expiry / double-confirm / cancel-after-start → **409**; cross-showtime seat →
  **400**. Extra guards: holding a soft-deleted movie's showtime → **404**, an already-started showtime
  → **409**.
- **KNOWN LIMITATION (carried, not new):** the showtime overlap check is still read-then-write (admin
  scale). Unrelated to the reservation concurrency, which is fully guarded above.
- **Endpoints:** `POST /api/reservations` (hold → 201 PENDING + expiresAt), `POST
  /api/reservations/{id}/confirm` (→ 200 CONFIRMED), `GET /api/reservations/me?filter=upcoming|past`,
  `DELETE /api/reservations/{id}` (→ 204). DTOs only; request takes **`showtimeSeatIds`** (the ids the
  seat map exposes).
- **Test isolation:** integration tests use real MySQL and are **not** `@Transactional` (the race must
  commit for real). Showtime start times now carry a large **random** far-future offset so showtimes
  never collide (same room/minute) across test classes or repeated runs in the shared DB — this also
  hardened the Phase 4 `ShowtimeIntegrationTest` start times.
- **Verified:** `ReservationIntegrationTest` (9 tests incl. the concurrency test, re-run 3× to rule
  out flakiness) — happy path, already-held → 409, multi-seat atomicity, expiry, confirm-after-expiry
  → 409, someone-else's → 404, cross-showtime → 400, no-token → 401, validation → 400. Full suite
  green: **28 tests, 0 failures**.

## Phase 6 — notes / decisions

- **Scope: read-only aggregation, no new domain logic.** Three report endpoints under
  `/api/admin/reports`; revenue/popular are date-bounded, occupancy is per-showtime.
- **V3 migration (index only).** `V3__add_reservations_status_created_idx.sql` adds
  `idx_reservations_status_created (status, created_at)`. Revenue + popular both filter
  `status = CONFIRMED` AND a `created_at` range; the existing `idx_reservations_status_expires
  (status, expires_at)` only narrows by status (its range is on `expires_at`), so the created_at
  range couldn't ride it. **Right-shape-for-volume, not a measured win** — invisible at current
  seed/test data sizes. It **complements, does not replace,** the expiry index (different predicate:
  PENDING holds past their deadline). Adding an index does **not** affect `ddl-auto=validate`
  (validate checks tables/columns/types, not indexes), so V3 is a safe additive migration with no
  entity change.
- **No `SecurityConfig` change.** `/api/admin/reports/**` matches the existing
  `/api/admin/**` → `hasRole("ADMIN")` tier (same as admin movie/showtime endpoints). USER → 403,
  no token → 401. Confirmed by tests.
- **What counts (the semantic call):** **revenue = `SUM(reservations.total_price)` WHERE
  `status = CONFIRMED`** only. PENDING/EXPIRED/CANCELLED earn nothing (the `CONFIRMED` filter is
  explicit in every query). Sums the **stored** `total_price` (snapshot at hold = price × seats), not
  a recompute from `showtime.price`, so a later price edit can't rewrite history. Seat-level
  aggregates: EXPIRED/CANCELLED already dropped their `reservation_seats` rows in Phase 5 so they
  vanish naturally, **but PENDING holds still have rows**, so popular-movies still filters
  `CONFIRMED` explicitly (can't rely on row-deletion alone).
- **Revenue date axis = `created_at` (confirmed).** It's the booking instant, not the true
  confirm/sale instant — which this project does **not** persist. Accepted proxy here; documented in
  `ReportService` that production would add a `confirmedAt` stamped on confirm and report on that.
  **Not added now.** Date params are ISO `yyyy-MM-dd`, both **optional**, both **inclusive**:
  internally `created_at >= from 00:00` and `created_at < (to + 1 day) 00:00` (half-open under the
  hood, inclusive to the caller). `from > to` → **400** via `BadRequestException`. `popular-movies`
  `limit` defaults to 10, capped at 100, `< 1` → 400.
- **DB-side aggregation, never in-Java summation.** JPQL `SUM`/`COUNT`/`GROUP BY`. Grouped results
  (`MovieRevenue`, `PopularMovie`) use **constructor expressions** with object-typed components
  (`Long` counts, `BigDecimal` money) so aggregates bind without primitive coercion. Scalar totals
  use **`COALESCE(SUM(...), 0)`** so the **empty/zero state** ("no reservations yet") returns `0`,
  never a NULL that would NPE into a 500. Grouped queries return an empty list naturally.
- **Occupancy:** numerator = `showtime_seats` with `status = BOOKED`; denominator = total
  `showtime_seats` for the showtime (both ride `idx_showtime_seats_showtime`). HELD (transient holds)
  are **not** counted. `occupancyRate` = percentage in [0, 100], 2-dp, computed in the service (guards
  divide-by-zero even though a showtime always auto-generates ≥1 seat). 404 only if the showtime row
  **genuinely doesn't exist** — **never** because its movie is soft-deleted (an admin report sees
  everything, same as revenue).
- **Soft-deleted movies (confirmed policy):** revenue **total**, revenue **by-movie**, and
  **occupancy** all **include** them (the money was real / admin sees everything — historical view).
  **popular-movies excludes** them (`deleted_at IS NULL`) — it's a "what to promote now" list. Pinned
  by a test: a soft-deleted movie still shows its revenue in by-movie but is absent from popular.
- **DEFERRED to Phase 7 (don't lose):** `GET /api/admin/reservations` (paginated, filterable by
  date range/status) from the spec's Phase 6 list — it's fundamentally a paginated list, and Phase 7
  owns pagination across all list endpoints. Likewise the **popular-movies top-N becomes a `Page`**
  in Phase 7 (today it's `List` + a `Pageable` LIMIT).
- **Test isolation (the gotcha aggregates expose).** The DB is shared across test classes and runs
  persist, so **no assertion uses a global absolute total** — another class's CONFIRMED reservations
  would make that flaky. Each assertion is scoped to data the test owns: the **by-movie slice** for a
  movie it created, a **before/after delta** on the total, **relative ordering** of its own movies, or
  a **date window nothing falls in** (revenue/popular over `2000-01-01..02` are genuinely empty since
  every `created_at` is "now"). Same isolation gap flagged for Phase 7; reporting is where it bites
  hardest.
- **Endpoints:** `GET /api/admin/reports/revenue?from&to` → `RevenueReport`; `…/revenue/by-movie
  ?from&to` → `List<MovieRevenue>` (revenue desc); `…/occupancy?showtimeId` → `OccupancyReport`;
  `…/popular-movies?from&to&limit` → `List<PopularMovie>` (tickets-sold desc). DTOs only; entities
  never exposed.
- **Verified:** `ReportIntegrationTest` (7 tests) — revenue counts CONFIRMED only (PENDING/CANCELLED
  excluded, owned by-movie slice + delta); occupancy known 2/40 = 5.00% ratio with HELD excluded;
  occupancy unknown showtime → 404; popular-movies ordering (owned relative) + soft-delete excluded
  from popular yet included in revenue; zero-data past window → `0`/empty (the NULL-SUM guard);
  `from > to` and `limit=0` → 400; USER → 403 / no-token → 401. Full suite green: **35 tests, 0
  failures**.

## Phase 7 — notes / decisions

- **Scope:** error-shape completion, the test-isolation overhaul (Testcontainers), pagination
  across list endpoints, the deferred `GET /api/admin/reservations`, observability logging, and
  Swagger — built in 7 small slices with a review checkpoint after the Testcontainers migration.
- **Error contract (slice 2).** `GlobalExceptionHandler` already emitted the target
  `{timestamp,status,error,message,path}` shape, so this **added** the missing handlers, it didn't
  rewrite: catch-all `Exception` → **500** with a generic `"Internal server error"` body (the real
  exception is **logged**, never echoed — no internals leak); malformed JSON
  (`HttpMessageNotReadableException`), param **type mismatch**, **missing param**, and bean
  `ConstraintViolationException` → **400**; unknown path → **404**; unsupported method → **405**.
  The 405/415/missing-param mappings are explicit so the new catch-all can't regress those standard
  MVC errors into 500. Timestamps switched to **`Instant`** (UTC ISO-8601), including the 401/403
  security-filter bodies, so the whole API is uniform.
- **Boot 3.5 no-handler (verified, not assumed).** An unknown path throws **`NoResourceFoundException`**
  (not `NoHandlerFoundException`); it reaches `@RestControllerAdvice` and returns our shape. Both are
  mapped defensively → 404; no `throw-exception-if-no-handler-found` needed. Pinned by
  `ErrorHandlingIntegrationTest`.
- **409 narrowing (slice 2).** `DataIntegrityViolationException` was split out of the unconditional
  concurrency group. Only the UNIQUE seat backstop firing — matched on its **explicit V1 constraint
  name** `uq_reservation_seats_showtime_seat` anywhere in the cause chain (reliable; not a guessed
  auto-name) — stays **409**; any other integrity error now falls through to a **logged 500** instead
  of being mislabelled a seat conflict. Optimistic-lock + deadlock exceptions remain unconditional 409.
- **Test foundation = Testcontainers (slice 3, THE risky one).** One throwaway `mysql:8.4` started
  once per JVM and reused by every class (context built once, cached); datasource via
  `@DynamicPropertySource` — **no `.env` / port 3307 dependency** in tests any more. A shared
  `AbstractIntegrationTest` base holds the container, a **per-test wipe**, the deterministic time
  source, and the token/movie/showtime/seat helpers that were duplicated across all five classes.
  - **Cleanup (explicit decision):** per-test single-connection `TRUNCATE` of the 7 **transactional**
    tables (`reservation_seats, reservations, showtime_seats, showtimes, movie_genre, movies, genres`)
    in FK-safe order — single connection because `FOREIGN_KEY_CHECKS` is session-scoped. The **seed**
    tables (`users, theater_rooms, seats`) are **preserved** (populated once by the CommandLineRunners
    at context start; login / showtime-creation / expiry depend on them). Seed survival is *proven*,
    not assumed: `ShowtimeIntegrationTest` logs in as the seeded admin and reads the seeded Room 2's
    80 seats after a truncate.
  - **Deterministic data:** the random far-future-offset hack is gone, replaced by a static,
    monotonic, day-spaced `nextFutureSlot()`. Truncate removes cross-test collisions; day-spacing
    prevents same-room overlap within a test.
  - **Parallelism guard:** the shared container + truncate-all assume **sequential** execution;
    `junit-platform.properties` pins `parallel.enabled=false` and the base class documents why.
- **Scheduling gated at the TRIGGER, not the job (slice 3).** `ReservationExpiryJob.runOnce()` stays
  unconditional (tests drive it); the `@Scheduled` trigger moved to `ReservationExpiryScheduler`,
  gated by `@ConditionalOnProperty(app.scheduling.enabled, matchIfMissing=true)`. Default on in prod;
  the **test profile** sets it `false` so the job never fires mid-suite.
- **Pagination via `PageResponse<T>` (slice 4).** A stable envelope
  (`content,page,size,totalElements,totalPages,first,last`) — Spring's `Page`/`PageImpl` JSON is
  **never** serialized (not contract-stable, violates DTOs-only). `MAX_PAGE_SIZE=100`; page/size
  bounds enforced at the controllers via `@Validated` + `@Min/@Max` → 400.
  - **Movies** (both modes) page the **IDs** first then fetch — never a collection fetch (avoids
    HHH000104 in-memory pagination). Explicit `countQuery` (`COUNT(m)` unfiltered,
    `COUNT(DISTINCT m.id)` for the genre filter); the second fetch **re-applies `ORDER BY m.title,
    m.id`** because an `IN(...)` doesn't preserve order. The genre two-step already re-sorted but
    lacked the **`m.id` tiebreaker** — a latent ordering bug under duplicate titles (a row could land
    on two pages or none); **fixed**.
  - **reservations/me** → `Page`; the upcoming/past filter, previously applied **in memory**
    (incompatible with paging), is pushed into the query as start-time bounds. Safe to page directly:
    only `@ManyToOne` showtime/movie are fetch-joined.
  - **popular-movies** → `Page` (+ `COUNT(DISTINCT m.id)` count query, `m.id` tiebreaker). The old
    `?limit` top-N became `?page/?size`.
- **`GET /api/admin/reservations` (slice 5).** Paginated; filter by **status** (enum-bound; bad value
  → 400) + **created_at** range. **Reuse, not reimplement:** the Phase 6 date helper was extracted to
  `util/DateRanges` (inclusive both ends, half-open internally, `from > to` → 400) and both
  `ReportService` and the new `AdminReservationService` route through it. **Sort is whitelisted**
  (`createdAt,totalPrice,status,id`; unknown field / bad direction → 400) and built server-side with an
  id tiebreaker — the client's raw `Pageable` sort is **never** passed through. New
  `AdminReservationView` DTO includes the owning **user** + showtime/movie context; seat detail is
  omitted to keep the query free of a to-many fetch. Lands under the existing `/api/admin/**` tier
  (no security change).
- **Observability (slice 6).** Logs at **reservation created** (PENDING, with user/showtime/seat
  count/expiry), **seat-lock conflict** on both 409 paths (explicit already-taken + optimistic-lock
  race), and the **expiry sweep** (debug per run, info on actual expirations).
- **API docs (slice 6).** `springdoc-openapi-starter-webmvc-ui` **2.8.9** (version pinned — not in the
  Boot BOM). Swagger UI at `/swagger-ui.html`, spec at `/v3/api-docs`; both **permitted** in the
  SecurityFilterChain (else they'd 401 under `anyRequest().authenticated()`). `OpenApiConfig` declares
  the JWT **bearer `@SecurityScheme`** + a global requirement, so the UI's Authorize button sends
  `Authorization: Bearer <token>`.
- **Housekeeping (slices 1, 7).** Removed the untracked `autentificationSuccess.txt` scratch JWT log
  (+ gitignore). `.gitattributes` gained `* text=auto` (silences the CRLF warnings). `src/.http` is now
  committed as living API docs (Phase 4–7 request sections) alongside the **non-secret**
  `src/http-client.env.json`; `adminPassword` moved to the **gitignored**
  `src/http-client.private.env.json`.
- **Out of scope (noted, not built):** CORS, a `confirmedAt` column, and resolving the native-3306 vs
  Docker-3307 ambiguity (Testcontainers makes the test suite independent of it regardless).
- **Verified:** full suite green on Testcontainers, stable across repeated runs — **53 tests, 0
  failures** (the 35 Phase-6 tests migrated, plus error-shape, movie/reservation/report pagination,
  admin-reservations, and OpenAPI coverage). New test classes: `ErrorHandlingIntegrationTest`,
  `MoviePaginationTest`, `AdminReservationIntegrationTest`, `OpenApiIntegrationTest`, and the shared
  `AbstractIntegrationTest` base.

## Phase 8 — notes / decisions

- **Scope reconciliation (most of the spec's Phase 8 list was already built per-phase).**
  Integration tests + Testcontainers (spec item 2), the concurrency test (item 3) and the
  expiry-job test (item 4) all landed in earlier phases. The actual gap was **spec item 1**:
  there was **no JUnit 5 + Mockito unit-test layer** — every existing test booted the full
  Spring context against real MySQL. Phase 8 adds fast, isolated **service-layer unit tests**
  for the business logic that was only exercised end-to-end. **No new dependencies** —
  `spring-boot-starter-test` already brings JUnit 5, Mockito, mockito-junit-jupiter and AssertJ.
- **The unit/integration split (the headline decision).** Unit tests (`@ExtendWith(MockitoExtension)`,
  no Spring context, no DB, strict stubs) target the **branching** that is tedious/slow to drive
  through the full stack — every illegal state transition, every validation/ownership guard, the
  price snapshot, pure arithmetic and the pure date logic. They assert the service's **reaction**
  to a mocked repository result (returned DTO / thrown exception), never a value a mock merely
  echoes back. Integration tests keep proving everything that depends on real DB behaviour
  (queries, transactions, FK/UNIQUE constraints, optimistic locking, the seat-map generation).
- **Concurrency STAYS integration — encoded as a guardrail, not just a convention.** The
  overbooking guarantee lives in DB behaviours a Mockito mock cannot reproduce: the `@Version`
  optimistic lock on `showtime_seats`, the flush-ordering deadlock fix, and the
  `compareAndSetStatus` rows-affected serialization. A "concurrency unit test" with mocked repos
  would prove nothing and give **false confidence**, so it is deliberately absent. A guardrail
  comment at the top of `ReservationServiceTest` records *why*, so nobody adds one later. The one
  unit test that touches optimistic locking (`optimisticLockException_isTranslatedTo409`) is a
  **translation test** — it pins "native `jakarta.persistence.OptimisticLockException` caught at
  flush → `SeatsUnavailableException` (409, not 500)", which is pure catch-block branching — and
  explicitly does **not** claim to detect a race.
- **New unit-test classes (27 tests):**
  - `ReservationServiceTest` (16) — the crown jewel. hold guards (unknown showtime / soft-deleted
    movie's showtime → 404, started showtime → 409, unknown/cross-showtime seat id → 400,
    unavailable seat → 409 with offending labels listed **sorted**, seat-id dedup), hold success
    (PENDING, **price snapshot** price×seats, `expiresAt = now + hold-minutes`, seats → HELD),
    confirm (ownership 404 / guarded PENDING→CONFIRMED claiming 0 rows → 409), cancel (ownership
    404, after-start guard 409, non-cancellable state 409, and the **CONFIRMED→CANCELLED** path +
    seat release the integration suite does not cover), `expireOne` (expire+release vs the
    **idempotent no-op** when already non-PENDING), and the optimistic-lock translation test above.
  - `ReportServiceTest` (4) — **only the pure-Java occupancy-rate computation**: percentage, 2-dp
    HALF_UP rounding (2/3 → 66.67), the divide-by-zero guard (0 seats → 0.00), unknown showtime →
    404. The SUM/COUNT/GROUP BY revenue aggregation stays integration (a mock can't reproduce it).
  - `ShowtimeServiceTest` (1) — `endTime = startTime + movie.durationMinutes` (pure arithmetic).
    The overlap check is a DB interval query — stubbed to no-conflict and left to integration.
  - `MovieServiceTest` (1) — genre dedup by lowercased+trimmed name, skipping null/blank, keeping
    first-seen casing (five entries collapse to one resolved genre). The get-or-create round-trip
    stays integration.
  - `DateRangesTest` (5) — pure: inverted range → 400; ascending/equal/open accepted; `startOfDay`
    inclusive lower bound; `startOfDayAfter` makes the inclusive `to` half-open so a single-day
    range brackets the whole `to` day.
- **Tested through the public service API only** — private helpers (`resolveGenres`,
  `occupancyPercent`, the `label`/`releaseSeats` internals) are exercised via the public methods,
  never called directly. `@Value holdMinutes` and the `@PersistenceContext` `EntityManager` (a
  field, so `@InjectMocks` does not wire it) are set via `ReflectionTestUtils` in `@BeforeEach`.
- **BigDecimal discipline:** every money/ratio assertion uses `isEqualByComparingTo` (never
  `equals`, which fails on scale — 31.5 vs 31.50, 5 vs 5.00).
- **Concurrency showcase polished (slice 4, not rewritten).** The existing Phase 5 same-seat race
  was generalized from a hardcoded 2 threads to a `@ParameterizedTest` over `{2, 8}` with crisp,
  README-ready assertions: exactly **1** success (201), exactly **threads-1** conflicts (409),
  **no other status** (a 500 would mean a deadlock/leak), the seat ends HELD by the single winner,
  and `reservation_seats` holds **exactly one** link for it (no double-book at the data level). It
  prints a one-line summary per fan-out (`threads=2 → 1×201/1×409`, `threads=8 → 1×201/7×409`).
  Same CountDownLatch + ExecutorService + `racingHold` structure; stays an integration test on
  purpose.
- **Phase 7 carry-overs closed (slice 1).**
  - **Beyond-last-page:** added `MoviePaginationTest.pageBeyondLast_...` — page 99 of a 3-page
    result is a well-formed **empty page** with correct `totalElements`/`totalPages`
    (`first=false`, `last=true`), not an error (MovieService short-circuits the ID query on an
    empty page).
  - **Non-whitelisted sort → 400:** already pinned by `AdminReservationIntegrationTest.sort_isWhitelisted`
    (`?sort=user.password` and a bad direction both 400). `/api/admin/reservations` is the only
    endpoint taking a client-supplied `sort`; the contract is **reject, not silently ignore**.
  - **Swagger:** `OpenApiIntegrationTest` already proves the UI loads without a token
    (`GET /swagger-ui/index.html` → 200) and that `/v3/api-docs` advertises the JWT `bearerAuth`
    scheme (drives the Authorize button) — no new code needed.
- **Test execution stays sequential** (the shared Testcontainers MySQL + truncate-all model from
  Phase 7 assumes it; `junit-platform.properties` pins parallelism off). The new unit tests need
  no container and run in milliseconds.
- **Out of scope (noted, not done):** Clock refactor (expiry tests construct already-overdue
  reservations / assert the hold window within a tolerance instead), any new test dependency,
  parallel execution, and the Phase 9 README/Dockerfile work.
- **Verified:** full suite green — **82 tests, 0 failures** (**27 unit** across the 5 new classes
  + **55 integration**); `./mvnw clean package` builds the jar.
