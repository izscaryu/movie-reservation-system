# Build Progress

Tracking the Movie Reservation System build, phase by phase.
Spec: `movie-reservation-system-guide.md`.

| Phase | Title | Status |
|-------|-------|--------|
| 0 | Project Scaffolding & Environment | ✅ Done |
| 1 | Data Model & Migrations | ✅ Done |
| 2 | Authentication & Authorization | ⬜ Not started |
| 3 | Movie Management | ⬜ Not started |
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
