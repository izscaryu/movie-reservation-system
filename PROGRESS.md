# Build Progress

Tracking the Movie Reservation System build, phase by phase.
Spec: `movie-reservation-system-guide.md`.

| Phase | Title | Status |
|-------|-------|--------|
| 0 | Project Scaffolding & Environment | ✅ Done |
| 1 | Data Model & Migrations | ✅ Done |
| 2 | Authentication & Authorization | ✅ Done |
| 3 | Movie Management | ✅ Done |
| 4 | Showtime & Seat Setup | ⬜ Not started |
| 5 | Reservation Flow (core) | ⬜ Not started |
| 6 | Admin Reporting | ⬜ Not started |
| 7 | Polish & Cross-Cutting Concerns | ⬜ Not started |
| 8 | Testing | ⬜ Not started |
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
