CREATE TABLE pattern
(
    id           BIGSERIAL PRIMARY KEY,
    title        VARCHAR(200) NOT NULL,
    description  TEXT,
    hook_size_mm NUMERIC(4, 2),
    yarn         VARCHAR(200),
    source_url   VARCHAR(500),
    image_url    VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);

CREATE TABLE step
(
    id         BIGSERIAL PRIMARY KEY,
    pattern_id BIGINT       NOT NULL REFERENCES pattern (id) ON DELETE CASCADE,
    position   INT          NOT NULL,
    title      VARCHAR(200) NOT NULL,
    CONSTRAINT uk_step_pattern_position UNIQUE (pattern_id, position)
);

CREATE TABLE pattern_row
(
    id       BIGSERIAL PRIMARY KEY,
    step_id  BIGINT      NOT NULL REFERENCES step (id) ON DELETE CASCADE,
    position INT         NOT NULL,
    number   VARCHAR(20) NOT NULL,
    value    TEXT        NOT NULL,
    CONSTRAINT uk_pattern_row_step_position UNIQUE (step_id, position)
);
