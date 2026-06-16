CREATE TABLE IF NOT EXISTS appointments (
    id               VARCHAR(36)  PRIMARY KEY,
    title            VARCHAR(255) NOT NULL,
    description      TEXT         NOT NULL,
    scheduled_at     VARCHAR(50)  NOT NULL,
    duration_minutes INTEGER      NOT NULL,
    attendee         VARCHAR(255) NOT NULL
);
