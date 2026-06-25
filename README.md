# Movie Reservation System

A backend-only cinema seat-reservation REST API, built in Java 21 / Spring Boot to
production standards. The interesting part isn't CRUD — it's **not selling the same seat
twice when two people click "reserve A5" in the same millisecond.** That problem, and the
layered solution proven by a real concurrency test, is the headline of this project (see
[The overbooking problem](#the-overbooking-problem-the-headline)).

It's a learning / CV project, and honest about its scope: there is **no frontend** (the API
is exercised via Swagger UI, an IntelliJ `.http` file, or Postman) and **no real payment**
("confirming" a reservation simulates a completed checkout). Everything else — auth, schema
migrations, seat locking, the expiry job, reporting, pagination, error handling, and the test
suite — is built the way a real service would be.

---

## Table of contents

- [What it is and why](#what-it-is-and-why)
- [Tech stack](#tech-stack)
- [Architecture](#architecture)
- [The overbooking problem (the headline)](#the-overbooking-problem-the-headline)
- [How to run it](#how-to-run-it)
- [API documentation](#api-documentation)
- [API surface](#api-surface)
- [Testing](#testing)
- [What I'd add with more time](#what-id-add-with-more-time)

---

## What it is and why

A user can browse movies and showtimes, see a live seat map, hold seats, confirm, and view
their reservations; an admin manages movies and showtimes and reads revenue/occupancy/popularity
reports. Auth is stateless JWT with role-based access (`USER` / `ADMIN`).

The goal was to build something small enough to finish but real enough to demonstrate the
decisions an interviewer actually asks about: how do you stop a double-booking under concurrency,
how do you manage a schema over time, how do you keep entities out of your API contract, and how
do you *prove* the hard part works rather than just claiming it. Each of those has a deliberate,
documented answer below and in [`PROGRESS.md`](PROGRESS.md), which records the per-phase build
decisions.

---

## Tech stack

| Area | Choice |
|---|---|
| Language / runtime | **Java 21** (LTS; `source`/`target` 21) |
| Framework | **Spring Boot 3.5.15** (Web, Data JPA, Security, Validation, Scheduling) |
| Database | **MySQL 8.4** |
| Persistence | **Spring Data JPA / Hibernate**, `ddl-auto=validate` (Hibernate never mutates the schema) |
| Schema migrations | **Flyway** (`V1`–`V3`; Flyway owns the schema) |
| Auth | **Spring Security + JWT (JJWT, HS256)**, stateless, BCrypt password hashing |
| API docs | **springdoc-openapi / Swagger UI** |
| Boilerplate | **Lombok** |
| Containerization | **Docker** multi-stage build + **Docker Compose** (app + DB, one command) |
| Testing | **JUnit 5 + Mockito** (unit) and **Testcontainers** (real throwaway MySQL for integration) |

No new runtime dependency was added for containerization — the image and compose stack use only
what the application already needs.

---

## Architecture

A conventional layered REST architecture; entities are never exposed — every request and response
crosses a DTO boundary.

```
Client (Swagger UI / .http / Postman)
      │  HTTP + JSON, Bearer JWT
      ▼
[Security filter chain]   — validates the JWT, rebuilds the principal from token claims
      │                     (no per-request DB lookup), sets the SecurityContext
      ▼
[Controller layer]        — REST endpoints, request/response DTOs, bean validation
      │
      ▼
[Service layer]           — business logic: seat locking, hold/expiry, booking rules, reporting
      │                     (@Transactional boundaries live here)
      ▼
[Repository layer]        — Spring Data JPA interfaces, JPQL aggregate queries
      │
      ▼
[MySQL]                   — schema owned by Flyway; @Version optimistic lock + UNIQUE backstop
```

Cross-cutting:

- **Security filter chain** — a `OncePerRequestFilter` validates `Authorization: Bearer <token>`
  and sets authentication. The principal is rebuilt straight from the token claims (a deliberate
  statelessness trade-off: a role change takes effect on the next login, not mid-token).
- **Global exception handler** — a single `@RestControllerAdvice` produces one consistent JSON
  error shape (`{timestamp, status, error, message, path}`) for the whole API. Internal exceptions
  are logged, never echoed to the client.
- **DTOs-only rule** — JPA entities never leave the service layer; controllers see DTOs exclusively.

### Domain model

The schema is relationship-heavy, which is why MySQL/JPA is a good fit:

```
User 1───* Reservation
Movie *───* Genre            (many-to-many; genre is an entity, not a string column)
Movie 1───* Showtime
TheaterRoom 1───* Seat
TheaterRoom 1───* Showtime
Showtime 1───* ShowtimeSeat ───1 Seat
Reservation 1───* ReservationSeat ───1 ShowtimeSeat
```

The pivotal table is **`ShowtimeSeat`** — explained next.

---

## The overbooking problem (the headline)

Two users click **"reserve seat A5"** for the same showtime within milliseconds. Without
protection, both transactions read the seat as available, both proceed, and the seat is sold
twice. This is solved here with **one structural choice plus three layers, proven by a test.**

### The structural choice — `ShowtimeSeat`

Instead of computing availability by scanning "which seats aren't in a confirmed reservation"
(a fragile, slow query that makes locking hard), the system generates **one `ShowtimeSeat` row
per physical seat when a showtime is created.** Availability becomes a single indexed row lookup,
and reserving a seat becomes **locking a single row** — the same pattern real cinemas, airlines,
and concert systems use.

### Layer 1 — optimistic locking

`ShowtimeSeat` carries a JPA `@Version` column. A hold checks each seat is `AVAILABLE` and flips
it to `HELD`; the versioned `UPDATE ... WHERE id = ? AND version = ?` matches **zero rows** if a
competing transaction already moved it, raising `OptimisticLockException` → a clean **409** that
names exactly which seats failed (so the client can refresh its seat map). Chosen over pessimistic
`SELECT ... FOR UPDATE` deliberately: no database locks are held across a reservation's think-time,
which is the right trade-off under realistic contention.

### Layer 2 — the deadlock fix (the subtle one)

Hibernate flushes **INSERTs before UPDATEs**, so the `reservation_seats` INSERT was taking its
unique-key lock *before* the `showtime_seats` versioned UPDATE — two concurrent holds could grab
the same locks in **opposite order** and MySQL would kill one with a deadlock (a **500**, not a
clean 409). The fix: **flush the seats-`HELD` update *before* inserting the reservation rows**, so
every hold contends on the seat row first, in one consistent lock order. The loser now blocks, then
loses the version check cleanly → **409**.

> A related subtlety: a manual `flush()` throws the **native** `jakarta.persistence.OptimisticLockException`,
> not Spring's translated type. The handler maps the native one, Spring's translated one, the narrowed
> unique-constraint violation, and the lock-acquisition exception **all to 409.**

### Layer 3 — the database backstop

`UNIQUE(showtime_seat_id)` on `reservation_seats` means that even if every application-logic layer
had a bug, the database **physically cannot** record one seat in two reservations. Defense in depth.

### The proof

The concurrency **integration test** fires N threads at the same seat on a **real MySQL (via
Testcontainers)** and asserts: exactly **one 201**, **N−1 × 409**, **no 500** (a 500 would mean a
deadlock or a leaked partial hold), and **exactly one** `reservation_seats` link for the seat.
It runs parameterized at `threads = {2, 8}`:

```
threads=2 → 1×201 / 1×409
threads=8 → 1×201 / 7×409
```

every run. This is the difference between *claiming* the protection works and *proving* it.

### Hold / expiry

A hold isn't forever. A reservation starts `PENDING` with `expiresAt = now + 10 minutes` and its
seats `HELD`. A `@Scheduled` job sweeps every 60s, marks overdue holds `EXPIRED`, and releases their
seats back to `AVAILABLE` — **each in its own transaction via a cross-bean call**, which avoids
Spring's self-invocation proxy gotcha (a `this.method()` loop would bypass the proxy and silently
run without the per-reservation transaction). Confirming a reservation flips it to `CONFIRMED` and
its seats to `BOOKED`. The confirm-vs-expire race is itself serialized by a guarded conditional
update (`UPDATE ... WHERE id = ? AND status = 'PENDING'`, checking rows-affected).

---

## How to run it

**Prerequisite: Docker.** That's the only thing you need installed — no local Java, Maven, or MySQL.

```bash
git clone <this-repo>
cd movie-reservation-system
docker compose up --build
```

`docker compose up` builds the app image (multi-stage: Maven build → slim JRE runtime, run as a
non-root user) and starts MySQL alongside it. The app waits for MySQL to pass its healthcheck before
booting, Flyway applies migrations `V1`–`V3` on startup, and `CommandLineRunner`s seed an admin
user plus three theater rooms with their seats (idempotently — only on first boot).

When it's up:

- API base: **http://localhost:8080**
- Swagger UI: **http://localhost:8080/swagger-ui/index.html**
- Seeded admin (dev default): **`admin@example.com`** / **`admin123`**

No `.env` file is required — the compose file ships safe development defaults for everything and
they can be overridden via the environment or an optional `.env` (see `.env.example`).

> **Security note:** the default `JWT_SECRET` and admin password are **insecure dev defaults**,
> clearly labelled as such. Override `JWT_SECRET` (≥ 32 bytes for HS256) and the admin credentials
> for any real deployment. This project is not hardened for production exposure.

### Local IDE development (optional)

To run the Spring app on your host against just the dockerized DB:

```bash
cp .env.example .env        # defaults are fine
docker compose up -d mysql  # DB only, published on host port 3307
./mvnw spring-boot:run
```

The host publish defaults to **3307** because host 3306 is commonly taken by a native MySQL install;
inside the compose network the app always reaches MySQL on its internal **3306**. (Decoupling those
two is why `docker compose up` "just works" regardless of what's on your host 3306.)

---

## API documentation

Interactive docs are generated from the code by **springdoc-openapi**:

- **Swagger UI:** http://localhost:8080/swagger-ui/index.html
- **OpenAPI spec:** http://localhost:8080/v3/api-docs

To call authenticated endpoints from Swagger UI: `POST /api/auth/login`, copy the returned `token`,
click the **Authorize** button (top right), and paste it. The UI then sends
`Authorization: Bearer <token>` on every request. Both the Swagger UI and the spec are public
(permitted in the security filter chain); everything else follows the access tiers below.

---

## API surface

| Method & path | Access | Purpose |
|---|---|---|
| `POST /api/auth/signup` | public | Register a `USER` (201, no token returned) |
| `POST /api/auth/login` | public | Authenticate → JWT |
| `GET /api/movies?genre=` | public | List movies (paginated, optional genre filter) |
| `GET /api/movies/{id}` | public | Movie detail |
| `GET /api/movies/{movieId}/showtimes?date=` | public | Showtimes for a movie |
| `GET /api/showtimes/{id}/seats` | public | Live seat map for a showtime |
| `POST /api/reservations` | user | Hold seats → `PENDING` reservation + `expiresAt` |
| `POST /api/reservations/{id}/confirm` | user | `PENDING` → `CONFIRMED`, seats → `BOOKED` |
| `GET /api/reservations/me?filter=upcoming\|past` | user | Own reservations (paginated) |
| `DELETE /api/reservations/{id}` | user | Cancel (owner-checked, before showtime start) |
| `POST /api/admin/movies` · `PUT /{id}` · `DELETE /{id}` | admin | Movie management (soft delete) |
| `POST /api/admin/showtimes` | admin | Create showtime + auto-generate its seat map |
| `POST /api/admin/users/{id}/promote` | admin | Grant `ADMIN` |
| `GET /api/admin/reservations` | admin | All reservations (paginated, filterable, sorted) |
| `GET /api/admin/reports/revenue` · `/revenue/by-movie` · `/occupancy` · `/popular-movies` | admin | Aggregate reports |

Reservation ownership is enforced in the **service layer** by the authenticated principal's id, not
by the URL — acting on someone else's reservation returns **404** (it doesn't reveal the id exists).

---

## Testing

**82 tests, 0 failures** — a deliberate split between fast unit tests and realistic integration tests:

- **27 unit tests** (JUnit 5 + Mockito, no Spring context, no DB) cover service-layer branching:
  every illegal state transition, validation/ownership guard, the price snapshot, and pure
  arithmetic/date logic. They assert the service's *reaction* to a mocked repository result, never a
  value a mock merely echoes back.
- **55 integration tests** (`@SpringBootTest` + **Testcontainers** spinning up a real throwaway
  MySQL 8.4) cover everything that depends on actual database behaviour: queries, transactions,
  FK/UNIQUE constraints, optimistic locking, and seat-map generation. The suite has no dependency on
  a local `.env` or a particular host port.

The **concurrency test is a real, passing integration test, not a README claim** — it commits real
transactions on a real MySQL and asserts exactly one winner out of N racing threads (see
[The proof](#the-proof)). The overbooking guarantee lives in database behaviours a mock cannot
reproduce, so it stays integration on purpose — a "concurrency unit test" with mocked repositories
would prove nothing.

```bash
./mvnw test          # needs Docker running (Testcontainers starts MySQL)
```

---

## What I'd add with more time

In rough priority order — these are deliberately **out of scope** for this build, not oversights:

- **Payment integration** — confirming a reservation currently simulates a completed checkout; a real
  flow would integrate a payment provider and stamp a true `confirmedAt` (reports currently use
  `created_at` as a documented proxy for the sale instant).
- **Email notifications** — booking confirmation and a pre-showtime reminder, via Spring Mail + an
  SMTP provider.
- **Refresh tokens** — the JWT setup issues short-lived access tokens only; refresh tokens would let
  access tokens be even shorter without forcing frequent re-login.
- **Rate limiting on auth endpoints** — to blunt brute-force login attempts.
- **Redis caching** — for hot read paths like showtime/seat availability, to cut DB load.
- **A SPA frontend** — which would force adding **CORS** configuration (intentionally omitted now,
  since this is a backend-only API with no browser origin to allow).

---

*Built phase by phase; the commit history and [`PROGRESS.md`](PROGRESS.md) document the decisions
behind each phase.*
