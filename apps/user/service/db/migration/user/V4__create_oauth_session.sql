CREATE TABLE users.oauth_session (primary_id CHAR(36) PRIMARY KEY, session_id CHAR(36) NOT NULL UNIQUE, creation_time BIGINT NOT NULL, last_access_time BIGINT NOT NULL, max_inactive_interval INTEGER NOT NULL, expiry_time BIGINT NOT NULL, principal_name VARCHAR(100));
CREATE INDEX oauth_session_ix1 ON users.oauth_session(last_access_time);
CREATE INDEX oauth_session_ix2 ON users.oauth_session(expiry_time);
CREATE INDEX oauth_session_ix3 ON users.oauth_session(principal_name);
CREATE TABLE users.oauth_session_attributes (session_primary_id CHAR(36) NOT NULL REFERENCES users.oauth_session(primary_id) ON DELETE CASCADE, attribute_name VARCHAR(200) NOT NULL, attribute_bytes BYTEA NOT NULL, PRIMARY KEY(session_primary_id,attribute_name));
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='home_search_user_runtime') THEN
  GRANT USAGE ON SCHEMA users TO home_search_user_runtime;
  GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA users TO home_search_user_runtime;
  GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA users TO home_search_user_runtime;
  ALTER DEFAULT PRIVILEGES IN SCHEMA users GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO home_search_user_runtime;
  ALTER DEFAULT PRIVILEGES IN SCHEMA users GRANT USAGE,SELECT ON SEQUENCES TO home_search_user_runtime;
  REVOKE ALL ON TABLE users.flyway_schema_history FROM home_search_user_runtime;
 END IF;
END $$;
