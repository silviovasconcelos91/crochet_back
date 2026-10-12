CREATE TABLE pattern_progress
(
    pattern_id    BIGINT PRIMARY KEY REFERENCES pattern (id) ON DELETE CASCADE,
    status        VARCHAR(20) NOT NULL,
    step_position INT         NOT NULL,
    row_position  INT         NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    finished_at   TIMESTAMPTZ,
    CONSTRAINT ck_progress_status CHECK (status IN ('IN_PROGRESS', 'FINISHED')),
    CONSTRAINT ck_progress_step_position CHECK (step_position >= 0),
    CONSTRAINT ck_progress_row_position CHECK (row_position >= 0),
    CONSTRAINT ck_progress_finished_at CHECK ((status = 'FINISHED') = (finished_at IS NOT NULL))
);
