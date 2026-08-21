ALTER TABLE users
    ADD COLUMN nickname VARCHAR(10) NULL,
    ADD COLUMN service_terms_version VARCHAR(64) NULL,
    ADD COLUMN service_terms_agreed_at TIMESTAMP(6) NULL,
    ADD COLUMN privacy_terms_version VARCHAR(64) NULL,
    ADD COLUMN privacy_terms_agreed_at TIMESTAMP(6) NULL,
    ADD CONSTRAINT uk_users_nickname UNIQUE (nickname);

-- Existing rows predate versioned consent capture, so these columns remain nullable during migration.
-- New sign-ups always write all five fields. A later audited data migration may enforce NOT NULL.
