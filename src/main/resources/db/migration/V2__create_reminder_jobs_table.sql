CREATE TABLE IF NOT EXISTS reminder_jobs (
    id             VARCHAR(36)  PRIMARY KEY,
    appointment_id VARCHAR(36)  NOT NULL REFERENCES appointments(id) ON DELETE CASCADE,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts       INTEGER      NOT NULL DEFAULT 0,
    scheduled_for  VARCHAR(50)  NOT NULL,
    locked_at      VARCHAR(50),
    last_error     TEXT,
    created_at     VARCHAR(50)  NOT NULL,
    updated_at     VARCHAR(50)  NOT NULL
);

-- Supports the worker's claim query: filter by status, order by scheduled_for.
CREATE INDEX IF NOT EXISTS idx_reminder_jobs_claimable
    ON reminder_jobs (status, scheduled_for);
