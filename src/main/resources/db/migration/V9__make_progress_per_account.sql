-- Progress belongs to (pattern, account): several accounts can follow one public pattern
-- (public patterns spec, section 2). V7 emptied the table, nothing to carry over.
DROP TABLE pattern_progress;

CREATE TABLE pattern_progress
(
    pattern_id    BIGINT      NOT NULL REFERENCES pattern (id) ON DELETE CASCADE,
    account_id    BIGINT      NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    status        VARCHAR(20) NOT NULL,
    step_position INT         NOT NULL,
    row_position  INT         NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    finished_at   TIMESTAMPTZ,
    CONSTRAINT pk_pattern_progress PRIMARY KEY (pattern_id, account_id),
    CONSTRAINT ck_progress_status CHECK (status IN ('IN_PROGRESS', 'FINISHED')),
    CONSTRAINT ck_progress_step_position CHECK (step_position >= 0),
    CONSTRAINT ck_progress_row_position CHECK (row_position >= 0),
    CONSTRAINT ck_progress_finished_at CHECK ((status = 'FINISHED') = (finished_at IS NOT NULL))
);

CREATE INDEX ix_pattern_progress_account_id ON pattern_progress (account_id);
