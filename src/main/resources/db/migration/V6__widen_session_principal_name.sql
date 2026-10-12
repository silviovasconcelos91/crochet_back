-- account.email allows 254 characters; the Spring Session schema (V3) only allowed 100
ALTER TABLE spring_session ALTER COLUMN principal_name TYPE VARCHAR(254);
