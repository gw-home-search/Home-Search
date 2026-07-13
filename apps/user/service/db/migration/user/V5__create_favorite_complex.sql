CREATE TABLE users.favorite_complex (
    user_id BIGINT NOT NULL
        REFERENCES users.user_account(id) ON DELETE CASCADE,
    complex_id BIGINT NOT NULL CHECK (complex_id > 0),
    saved_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, complex_id)
);

CREATE INDEX favorite_complex_user_saved_idx
    ON users.favorite_complex(user_id, saved_at DESC, complex_id DESC);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'home_search_user_runtime') THEN
        REVOKE ALL ON TABLE users.favorite_complex FROM home_search_user_runtime;
        GRANT SELECT, INSERT, DELETE ON TABLE users.favorite_complex TO home_search_user_runtime;
    END IF;
END $$;
