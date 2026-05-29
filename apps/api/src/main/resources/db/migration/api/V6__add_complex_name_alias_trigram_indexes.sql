CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX ix_complex_name_alias_normalized_name_trgm
    ON complex_name_alias USING GIN (normalized_name gin_trgm_ops);

CREATE INDEX ix_complex_name_alias_alias_name_lower_trgm
    ON complex_name_alias USING GIN (lower(alias_name) gin_trgm_ops);
