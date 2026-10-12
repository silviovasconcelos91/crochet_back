-- Accounts now log in by username: the existing accounts (and with them every pattern, step, row and
-- progress) are discarded, as are the sessions whose principal was an email (public patterns spec, section 2).
TRUNCATE account, pattern, spring_session RESTART IDENTITY CASCADE;

ALTER TABLE account
    ADD COLUMN username     VARCHAR(30) NOT NULL,
    -- computed in Java (Usernames.key); lowercasing can lengthen a few letters, hence 60
    ADD COLUMN username_key VARCHAR(60) NOT NULL,
    ADD CONSTRAINT uk_account_username_key UNIQUE (username_key);
