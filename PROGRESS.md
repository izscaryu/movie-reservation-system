# Build Progress

Tracking the Movie Reservation System build, phase by phase.
Spec: `movie-reservation-system-guide.md`.

| Phase | Title | Status |
|-------|-------|--------|
| 0 | Project Scaffolding & Environment | ✅ Done |
| 1 | Data Model & Migrations | ⬜ Not started |
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
  JDK on this machine is 25, backward-compatible), Maven via `mvnw` wrapper, MySQL 8.4 in Docker.
- **Dependencies:** All Phase 0 deps were already present from the IntelliJ/Spring Initializr
  setup (Web, Data JPA, MySQL driver, Security, Validation, Flyway core + flyway-mysql, Lombok).
  No pom changes needed.
- **Config format:** Converted `application.properties` → `application.yml` (deleted the old file).
- **Secrets:** All DB credentials come from env vars with local-dev defaults; `.env` is gitignored,
  `.env.example` is the committed template.
- **Schema ownership:** Flyway owns the schema; `spring.jpa.hibernate.ddl-auto=validate` so Hibernate
  never mutates tables.
- **MySQL:** Runs via `docker-compose.yml` (named volume for persistence, healthcheck for Phase 9).
