# Movie Reservation System — Full Build Guide

**Stack:** Java 21 + Spring Boot 3 + MySQL 8 + Spring Security (JWT) + Docker
**Type:** Backend-only REST API (tested via Postman)
**Tool-assisted build:** Claude Code

---

## How to use this document

This is your spec and roadmap. Treat it as the single source of truth for the project.

When working with Claude Code:
1. Don't paste the whole document in at once and say "build this." You'll get a wall of code you don't understand and can't debug.
2. Work **phase by phase** (this doc is split into 9 phases). Give Claude Code one phase at a time, read every file it creates, and ask it to explain anything unfamiliar before moving on.
3. After each phase, **run the app and test it yourself** in Postman before moving to the next phase. If phase 3 is broken, phase 4 will be built on a broken foundation.
4. Keep a `PROGRESS.md` file in your repo root and ask Claude Code to update it after each phase — this becomes useful documentation later and helps Claude Code (and you) remember where you are if the conversation resets.

Suggested first message to Claude Code in your project folder:

> "I'm building a Movie Reservation System backend. I have a full spec in `movie-reservation-system-guide.md`. Read it fully first. We're going to build this in phases — don't skip ahead. Start by setting up Phase 0 (project scaffolding) and explain each piece of the generated project structure to me before we continue."

---

## Why this stack

| Choice | Reasoning |
|---|---|
| **Java 21** | LTS version, modern enough to use records, virtual threads if you want to experiment later. |
| **Spring Boot 3** | Industry-standard Java backend framework. Handles dependency injection, web layer, security, and ORM integration with minimal boilerplate. |
| **MySQL** | Your choice — relational DB, good fit since this domain is relationship-heavy (users, movies, showtimes, seats, reservations). |
| **Spring Data JPA (Hibernate)** | ORM layer — maps Java objects to MySQL tables, avoids hand-writing repetitive SQL for basic operations. |
| **Spring Security + JWT** | Industry-standard auth approach for stateless REST APIs. No sessions stored server-side — scales better and is what most companies use. |
| **Docker / Docker Compose** | Lets you run MySQL without installing it locally, and makes the whole project runnable with one command — a real CV plus. |
| **Flyway** (DB migrations) | Version-controls your database schema changes instead of relying on Hibernate to auto-generate tables — this is how real production systems manage schemas, and it's a good habit to build now. |

---

## High-Level Architecture

```
Client (Postman)
      │
      ▼
[Controller Layer]   — REST endpoints, request/response DTOs
      │
      ▼
[Service Layer]      — business logic (seat locking, booking rules, reporting)
      │
      ▼
[Repository Layer]   — Spring Data JPA interfaces (DB access)
      │
      ▼
[MySQL Database]
```

Cross-cutting:
- **Security filter chain** — intercepts every request, validates JWT, sets authentication context
- **Exception handling layer** — centralized error responses (`@ControllerAdvice`)
- **DTOs** — never expose your JPA entities directly in API responses; always map to DTOs

---

## Data Model

This is the core of the project. Get this right before writing any code.

### Entities

**User**
| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| email | String | unique, used for login |
| password | String | bcrypt-hashed, never stored plain |
| name | String | |
| role | Enum (`USER`, `ADMIN`) | |
| createdAt | Timestamp | |

**Movie**
| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| title | String | |
| description | Text | |
| posterUrl | String | store URL/path, not the binary image |
| genre | String or separate Genre entity (see note below) | |
| durationMinutes | Integer | needed to calculate showtime end times |
| createdAt | Timestamp | |

> **Design decision point:** genre as a plain string column, or a many-to-many `Genre` entity? A string is simpler and fine for v1. A separate entity is more "correct" relationally and is a good stretch goal. Decide explicitly and note your reasoning in your README — this is exactly the kind of decision an interviewer might ask about.

**Showtime**
| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| movie | FK → Movie | |
| theaterRoom | FK → TheaterRoom (or just a roomName string for v1) | |
| startTime | Timestamp | |
| endTime | Timestamp | calculated from movie duration, or stored explicitly |
| price | BigDecimal | **never use float/double for money** |

**TheaterRoom** (can be simplified for v1 — see note)
| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| name | String | e.g. "Room 1" |
| rows | Integer | |
| seatsPerRow | Integer | |

> **Simplification option:** if you want to move faster, hardcode a fixed seat layout (e.g. always 8 rows × 10 seats) instead of modeling configurable rooms. Note this as a deliberate scope decision, not laziness — that distinction matters when you explain it later.

**Seat**
| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| theaterRoom | FK → TheaterRoom | |
| rowLabel | String | e.g. "A" |
| seatNumber | Integer | e.g. 5 → seat "A5" |

**Reservation**
| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| user | FK → User | |
| showtime | FK → Showtime | |
| status | Enum (`PENDING`, `CONFIRMED`, `CANCELLED`, `EXPIRED`) | |
| createdAt | Timestamp | |
| expiresAt | Timestamp | for the seat-hold mechanism, see Phase 5 |
| totalPrice | BigDecimal | |

**ReservationSeat** (join table — a reservation can include multiple seats)
| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| reservation | FK → Reservation | |
| showtimeSeat | FK → ShowtimeSeat | see below |

**ShowtimeSeat** (this is the critical table — see "The Overbooking Problem" below)
| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| showtime | FK → Showtime | |
| seat | FK → Seat | |
| status | Enum (`AVAILABLE`, `HELD`, `BOOKED`) | |
| version | Integer | **for optimistic locking — explained below** |

### Why `ShowtimeSeat` exists (this is the key design insight)

A common mistake is to only have `Seat` (physical seats in a room) and `Reservation`, then try to figure out availability by querying "which seats are NOT in a confirmed reservation for this showtime." This works but becomes a fragile, slow query and makes locking much harder.

Instead: when a showtime is created, **generate a `ShowtimeSeat` row for every physical seat in that room, for that specific showtime.** Now availability is just: `SELECT * FROM showtime_seat WHERE showtime_id = ? AND status = 'AVAILABLE'`. And locking a seat means locking a single row in this table. This is the same pattern real ticketing systems (cinemas, airlines, concerts) use.

### Entity Relationship Diagram (text form)

```
User 1───* Reservation
Movie 1───* Showtime
TheaterRoom 1───* Seat
TheaterRoom 1───* Showtime
Showtime 1───* ShowtimeSeat ───1 Seat
Reservation 1───* ReservationSeat ───1 ShowtimeSeat
```

---

## The Overbooking Problem (read this before Phase 5)

This is the single most important technical decision in the whole project. Two users can click "reserve seat A5" for the same showtime within milliseconds of each other. Without protection, both requests can read "seat is available," both proceed, and you've sold the same seat twice.

You have three realistic options. Pick **one**, implement it, and be ready to explain why you didn't pick the others.

### Option 1: Optimistic Locking (recommended for this project)
Add a `@Version` column to `ShowtimeSeat`. When two transactions try to update the same row, the second one to commit fails with an `OptimisticLockException` because the version number it read is stale. Your service layer catches this and returns a "seat no longer available" response.

- **Pros:** Simple to implement with JPA (`@Version` annotation does most of the work), good performance under low contention, doesn't hold DB locks.
- **Cons:** Under very high contention (many people fighting for the same seat) you get more failed requests that may need a retry.
- **Why recommended:** It's simple enough to actually finish, but real enough to demonstrate the concept properly.

### Option 2: Pessimistic Locking
Use `SELECT ... FOR UPDATE` to lock the `ShowtimeSeat` row the moment a user starts reserving it. Other transactions trying to read/update that row block until the first transaction commits or rolls back.

- **Pros:** Guarantees no double-booking, conceptually simpler to reason about.
- **Cons:** Can cause performance issues / deadlocks under high load if not careful with lock ordering.

### Option 3: Database Unique Constraint as a Safety Net
Regardless of which of the above you pick, also add a **unique constraint** on `(showtime_seat_id)` in the `reservation_seat` table or similar, so that even if your application logic has a bug, the database itself physically cannot store two confirmed bookings for the same seat. This is a "defense in depth" measure — do this either way.

**Your job:** implement Option 1, add the Option 3 safety net, and write a concurrent test (Phase 8) that fires multiple simultaneous booking requests at the same seat and proves only one succeeds.

---

## Seat Hold / Expiry Mechanism

Real booking systems (cinemas, flights, concerts) don't let you "reserve" forever just by selecting a seat — they hold it temporarily (commonly 5–15 minutes) while you complete checkout, then release it if you don't confirm.

For this project:
1. When a user selects seats, create a `Reservation` with status `PENDING` and `expiresAt = now + 10 minutes`. Mark the relevant `ShowtimeSeat` rows as `HELD`.
2. A background scheduled job runs every minute, finds `PENDING` reservations past their `expiresAt`, marks them `EXPIRED`, and flips their seats back to `AVAILABLE`.
3. "Confirming" a reservation (simulate payment — no real payment needed) flips status to `CONFIRMED` and seats to `BOOKED`.

This is implemented with Spring's `@Scheduled` annotation — covered in Phase 5.

---

## Phase-by-Phase Build Plan

### Phase 0 — Project Scaffolding & Environment

**What you're doing:** Setting up the empty Spring Boot project with the right dependencies, Docker for MySQL, and confirming everything boots before writing any real logic.

Steps:
1. Generate a Spring Boot project via [start.spring.io](https://start.spring.io) (or have Claude Code do it) with these dependencies:
   - Spring Web
   - Spring Data JPA
   - MySQL Driver
   - Spring Security
   - Validation
   - Flyway Migration
   - Lombok
2. Set up `docker-compose.yml` with a MySQL service so you don't need to install MySQL locally.
3. Configure `application.yml` with DB connection settings, using environment variables (not hardcoded passwords).
4. Confirm the app boots with `./mvnw spring-boot:run` and connects to MySQL with zero errors.
5. Initialize a git repo, add a `.gitignore` (Spring Boot template), make your first commit.

**Done when:** app starts cleanly, connects to MySQL, no entities/tables exist yet — that's expected.

---

### Phase 1 — Data Model & Migrations

**What you're doing:** Creating all JPA entities and the Flyway migration scripts that define your actual MySQL schema.

Steps:
1. Write Flyway migration `V1__init_schema.sql` creating all tables from the Data Model section above, with proper foreign keys, indexes (especially on `showtime_seat.showtime_id` and `reservation.user_id` — you'll query by these constantly), and the unique constraint safety net mentioned above.
2. Create JPA entity classes matching the schema exactly (`User`, `Movie`, `Showtime`, `TheaterRoom`, `Seat`, `ShowtimeSeat`, `Reservation`, `ReservationSeat`).
3. Add the `@Version` field to `ShowtimeSeat` for optimistic locking.
4. Create Spring Data JPA repository interfaces for each entity (`UserRepository`, `MovieRepository`, etc.).
5. Write a seed migration `V2__seed_admin.sql` that inserts one admin user (bcrypt-hash the password ahead of time, or do it via a `CommandLineRunner` in Java instead of raw SQL — better practice, since you can hash dynamically).

**Done when:** running the app applies migrations automatically, tables exist in MySQL (verify with a DB client or `docker exec` + `mysql` CLI), and one admin row exists.

---

### Phase 2 — Authentication & Authorization

**What you're doing:** Sign up, log in, JWT issuing/validation, and role-based access control.

Steps:
1. Add password hashing via `BCryptPasswordEncoder`.
2. Build `POST /api/auth/signup` — creates a `User` with role `USER` by default.
3. Build `POST /api/auth/login` — validates credentials, issues a signed JWT containing user id + role.
4. Implement a JWT filter (`OncePerRequestFilter`) that reads the `Authorization: Bearer <token>` header, validates the token, and sets the Spring Security context.
5. Configure `SecurityFilterChain`:
   - Public: `/api/auth/**`, browsing movies/showtimes (GET endpoints)
   - Authenticated only: making reservations
   - Admin only: movie/showtime management, promoting users, reports
6. Build `POST /api/admin/users/{id}/promote` — admin-only, sets a user's role to `ADMIN`.

**Done when:** you can sign up, log in and get a token, hit a protected endpoint successfully with the token and get a 401/403 without it or with the wrong role.

---

### Phase 3 — Movie Management

**What you're doing:** Admin CRUD for movies.

Steps:
1. DTOs: `MovieRequestDto` (for create/update input), `MovieResponseDto` (for output — never return the entity directly).
2. `POST /api/admin/movies` — create movie (admin only)
3. `PUT /api/admin/movies/{id}` — update movie (admin only)
4. `DELETE /api/admin/movies/{id}` — delete movie (admin only) — consider: what happens to existing showtimes/reservations for a deleted movie? Decide and document (e.g. soft-delete instead of hard delete is often safer).
5. `GET /api/movies` — public list, with optional genre filter via query param
6. `GET /api/movies/{id}` — public single movie detail
7. Add validation (`@NotBlank`, `@Size`, etc.) on the request DTO and a global exception handler that turns validation errors into clean 400 responses.

**Done when:** full CRUD works via Postman, regular users get 403 on admin endpoints, validation errors return clean messages instead of stack traces.

---

### Phase 4 — Showtime & Seat Setup

**What you're doing:** Admin creates showtimes; the system auto-generates the seat map for that showtime.

Steps:
1. Decide and implement your `TheaterRoom`/`Seat` setup (configurable rooms, or simplified fixed layout — see note in Data Model section). Seed at least 2-3 rooms with seats via migration or a `CommandLineRunner`.
2. `POST /api/admin/showtimes` — admin creates a showtime for a movie + room + start time. **On creation, automatically generate one `ShowtimeSeat` row per `Seat` in that room, status `AVAILABLE`.** This is the key step — don't skip it.
3. Validation: prevent overlapping showtimes in the same room (a new showtime's time range can't conflict with an existing one in the same room — you'll need a query checking for time overlap).
4. `GET /api/movies/{movieId}/showtimes?date=YYYY-MM-DD` — public, returns showtimes for a movie on a given date.
5. `GET /api/showtimes/{id}/seats` — public, returns the full seat map for a showtime with each seat's current status (`AVAILABLE`/`HELD`/`BOOKED`) so the client can render a seat picker.

**Done when:** creating a showtime auto-populates its seat map correctly, overlapping showtimes are rejected, and you can fetch a seat map showing correct availability.

---

### Phase 5 — Reservation Flow (the core of the project)

**What you're doing:** The actual booking logic, including the concurrency-safe seat locking and the hold/expiry mechanism.

Steps:
1. `POST /api/reservations` — body: `showtimeId`, list of `seatId`s.
   - In a single transaction: re-check each requested `ShowtimeSeat` is `AVAILABLE`, flip to `HELD`, create the `Reservation` (status `PENDING`, `expiresAt` = now + 10 min), create `ReservationSeat` rows linking them.
   - Rely on the `@Version` field — if another transaction already grabbed one of these seats, this update will throw `OptimisticLockException`. Catch it, roll back, return a clear "seat(s) no longer available" response (409 Conflict), telling the user **which** seats failed so the client can refresh the seat map.
2. `POST /api/reservations/{id}/confirm` — flips `PENDING` → `CONFIRMED`, seats `HELD` → `BOOKED`. (No real payment integration needed — this simulates "checkout completed.")
3. `GET /api/reservations/me` — user's own reservations, with filters for upcoming/past.
4. `DELETE /api/reservations/{id}` — cancel, only allowed if status is `PENDING`/`CONFIRMED` **and** the showtime hasn't started yet. Releases seats back to `AVAILABLE`.
5. Implement the expiry job: `@Scheduled(fixedRate = 60000)` method that finds `PENDING` reservations where `expiresAt < now`, sets them to `EXPIRED`, and releases their seats.
6. Make sure a user can't see or cancel another user's reservation (check ownership in the service layer, not just rely on the URL).

**Done when:** the full flow works (browse → pick seats → reserve → confirm → see in "my reservations" → cancel), expired holds release seats automatically, and ownership checks are enforced.

---

### Phase 6 — Admin Reporting

**What you're doing:** Aggregate queries for admin dashboards.

Steps:
1. `GET /api/admin/reservations` — all reservations, paginated, filterable by date range/status.
2. `GET /api/admin/reports/revenue?from=...&to=...` — total revenue from `CONFIRMED` reservations in a date range.
3. `GET /api/admin/reports/occupancy?showtimeId=...` — booked seats vs. total seats for a showtime (capacity %).
4. `GET /api/admin/reports/popular-movies` — e.g. movies ranked by number of confirmed reservations or revenue, in a date range.

Use JPQL aggregate queries (`SUM`, `COUNT`, `GROUP BY`) or, if you want a stronger CV detail, native SQL queries for the more complex reports — and explain why in your README (e.g. "JPQL became unreadable for this multi-join aggregation, so I used a native query").

**Done when:** all 4 endpoints return correct numbers you can manually verify against your test data.

---

### Phase 7 — Polish & Cross-Cutting Concerns

Steps:
1. Global exception handler (`@RestControllerAdvice`) — consistent JSON error shape across the whole API (e.g. `{ "timestamp", "status", "error", "message", "path" }`).
2. Request validation everywhere (don't trust client input on any endpoint).
3. Pagination on all "list" endpoints (`Pageable` from Spring Data).
4. Logging — add meaningful log statements at key points (reservation created, seat lock conflict, expiry job run).
5. API documentation via springdoc-openapi (Swagger UI) — gives you a browsable API doc for free and is an easy CV mention.

**Done when:** errors are consistent and readable, Swagger UI loads and shows all endpoints correctly.

---

### Phase 8 — Testing (this is where the project becomes provably good, not just functional)

Steps:
1. **Unit tests** for service-layer business logic (e.g. reservation service, expiry logic) using JUnit 5 + Mockito.
2. **Integration tests** using `@SpringBootTest` + Testcontainers (spins up a real throwaway MySQL in Docker for tests — more realistic than H2 in-memory DB).
3. **The concurrency test (the most important one):** write a test that fires N concurrent threads, all trying to reserve the *same* seat for the same showtime, and asserts that exactly 1 succeeds and N-1 get a conflict response. This is your proof that the overbooking protection actually works — not just claimed in a README.
4. Test the expiry job logic directly (you can manipulate `expiresAt` in test data to simulate time passing without actually waiting).

**Done when:** you have a test suite that passes, and specifically a passing concurrency test you could screen-record or paste into your README as evidence.

---

### Phase 9 — Containerization & Documentation

Steps:
1. Write a `Dockerfile` for the Spring Boot app (multi-stage build: build with Maven, run with a slim JRE image).
2. Extend `docker-compose.yml` to run both the app and MySQL together — `docker compose up` should be all that's needed to run the entire project from a clean clone.
3. Write a strong `README.md`:
   - What the project is and why
   - Architecture diagram (you can reuse/adapt the one above)
   - **The overbooking problem and how you solved it** — this is your headline technical talking point, give it real space
   - How to run it locally
   - API documentation link (Swagger)
   - What you'd add with more time (payment integration, email notifications, etc.)
4. Push to GitHub with a clean commit history (not "wip" x40 — squash if needed).

**Done when:** someone with zero context could clone your repo, run one command, and have the API running.

---

## Stretch Goals (only after everything above works)

- Email notifications (booking confirmation, reminder before showtime) via Spring Mail + a free SMTP provider
- Rate limiting on auth endpoints (prevent brute-force login attempts)
- Refresh tokens (currently your JWT setup can use short-lived access tokens only — refresh tokens are a nice add-on)
- A simple seat-map visualization endpoint that returns data shaped for an easy frontend rendering
- Caching showtime/seat availability with Redis to reduce DB load on hot endpoints

---

## A Note on Working With Claude Code

- Always ask it to **explain unfamiliar Spring concepts** as it builds (e.g. "explain what `@Transactional` is doing here and why it matters for the seat locking") — you're learning Spring Boot through this project, don't let it become copy-paste.
- After each phase, ask it to **write or update tests** for what was just built, rather than leaving all testing for Phase 8 — catching bugs early is much cheaper.
- If something feels overcomplicated, ask "is there a simpler way to do this that's still correct?" — Claude Code (like any LLM) can over-engineer if not checked.
- Keep commits small and tied to each phase, so your git history itself tells the story of how the project was built — another small thing that looks good to anyone reviewing your repo.
