CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX ix_complex_name_lower_trgm
    ON complex USING GIN (lower(name) gin_trgm_ops);

CREATE INDEX ix_complex_trade_name_lower_trgm
    ON complex USING GIN (lower(COALESCE(trade_name, '')) gin_trgm_ops);

CREATE INDEX ix_parcel_address_lower_trgm
    ON parcel USING GIN (lower(COALESCE(address, '')) gin_trgm_ops);
