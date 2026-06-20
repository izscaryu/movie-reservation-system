-- Phase 1 schema. Flyway owns the schema; Hibernate validates against it.
-- Tables are created in foreign-key dependency order.

CREATE TABLE users (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    email      VARCHAR(255) NOT NULL,
    password   VARCHAR(255) NOT NULL,
    name       VARCHAR(255) NOT NULL,
    role       VARCHAR(20)  NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE genres (
    id   BIGINT       NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_genres_name UNIQUE (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE movies (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    title            VARCHAR(255) NOT NULL,
    description      TEXT,
    poster_url       VARCHAR(512),
    duration_minutes INT          NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- Many-to-many join between movies and genres.
CREATE TABLE movie_genre (
    movie_id BIGINT NOT NULL,
    genre_id BIGINT NOT NULL,
    PRIMARY KEY (movie_id, genre_id),
    CONSTRAINT fk_movie_genre_movie FOREIGN KEY (movie_id) REFERENCES movies (id) ON DELETE CASCADE,
    CONSTRAINT fk_movie_genre_genre FOREIGN KEY (genre_id) REFERENCES genres (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- "rows" is a reserved word in MySQL, so the column is named num_rows.
CREATE TABLE theater_rooms (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    name           VARCHAR(100) NOT NULL,
    num_rows       INT          NOT NULL,
    seats_per_row  INT          NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE seats (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    theater_room_id BIGINT      NOT NULL,
    row_label       VARCHAR(8)  NOT NULL,
    seat_number     INT         NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_seats_room FOREIGN KEY (theater_room_id) REFERENCES theater_rooms (id),
    CONSTRAINT uq_seats_room_row_number UNIQUE (theater_room_id, row_label, seat_number)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_seats_room ON seats (theater_room_id);

CREATE TABLE showtimes (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    movie_id        BIGINT         NOT NULL,
    theater_room_id BIGINT         NOT NULL,
    start_time      DATETIME(6)    NOT NULL,
    end_time        DATETIME(6)    NOT NULL,
    price           DECIMAL(10, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_showtimes_movie FOREIGN KEY (movie_id) REFERENCES movies (id),
    CONSTRAINT fk_showtimes_room FOREIGN KEY (theater_room_id) REFERENCES theater_rooms (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_showtimes_movie ON showtimes (movie_id);
CREATE INDEX idx_showtimes_room_start ON showtimes (theater_room_id, start_time);

CREATE TABLE showtime_seats (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    showtime_id BIGINT      NOT NULL,
    seat_id     BIGINT      NOT NULL,
    status      VARCHAR(20) NOT NULL,
    version     INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_showtime_seats_showtime FOREIGN KEY (showtime_id) REFERENCES showtimes (id),
    CONSTRAINT fk_showtime_seats_seat FOREIGN KEY (seat_id) REFERENCES seats (id),
    CONSTRAINT uq_showtime_seats_showtime_seat UNIQUE (showtime_id, seat_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_showtime_seats_showtime ON showtime_seats (showtime_id);

CREATE TABLE reservations (
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    user_id     BIGINT         NOT NULL,
    showtime_id BIGINT         NOT NULL,
    status      VARCHAR(20)    NOT NULL,
    created_at  DATETIME(6)    NOT NULL,
    expires_at  DATETIME(6),
    total_price DECIMAL(10, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_reservations_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_reservations_showtime FOREIGN KEY (showtime_id) REFERENCES showtimes (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_reservations_user ON reservations (user_id);
CREATE INDEX idx_reservations_status_expires ON reservations (status, expires_at);

-- UNIQUE(showtime_seat_id) is the overbooking safety net (defence in depth):
-- the database physically cannot link one showtime seat to two reservations.
-- On hold expiry/cancel (Phase 5) these rows are deleted to release the seat.
CREATE TABLE reservation_seats (
    id               BIGINT NOT NULL AUTO_INCREMENT,
    reservation_id   BIGINT NOT NULL,
    showtime_seat_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_reservation_seats_reservation FOREIGN KEY (reservation_id) REFERENCES reservations (id) ON DELETE CASCADE,
    CONSTRAINT fk_reservation_seats_showtime_seat FOREIGN KEY (showtime_seat_id) REFERENCES showtime_seats (id),
    CONSTRAINT uq_reservation_seats_showtime_seat UNIQUE (showtime_seat_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
