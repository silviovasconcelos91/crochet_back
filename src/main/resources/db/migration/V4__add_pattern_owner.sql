-- Development patterns have no owner and are discarded (accounts spec, section 2).
DELETE FROM pattern;

ALTER TABLE pattern
    ADD COLUMN owner_id BIGINT NOT NULL REFERENCES account (id) ON DELETE CASCADE;

CREATE INDEX ix_pattern_owner_id ON pattern (owner_id);
