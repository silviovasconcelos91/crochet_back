ALTER TABLE pattern
    ADD COLUMN visibility VARCHAR(10) NOT NULL DEFAULT 'PRIVATE',
    ADD CONSTRAINT ck_pattern_visibility CHECK (visibility IN ('PRIVATE', 'PUBLIC'));

-- the public catalogue lists PUBLIC patterns sorted like this
CREATE INDEX ix_pattern_public_title ON pattern (lower(title), id) WHERE visibility = 'PUBLIC';
