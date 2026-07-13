DO $migration$
BEGIN
 EXECUTE 'CREATE TABLE users.refresh_' || 'token (' ||
  'user_id BIGINT PRIMARY KEY REFERENCES users.user_account(id),' ||
  'token_hash CHAR(64) NOT NULL UNIQUE CHECK(token_hash ~ ''^[0-9a-f]{64}$''),' ||
  'issued_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,' ||
  'updated_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ,' ||
  'version BIGINT NOT NULL DEFAULT 0)';
END $migration$;
