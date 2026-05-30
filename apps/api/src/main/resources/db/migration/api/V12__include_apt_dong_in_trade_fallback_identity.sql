DROP INDEX IF EXISTS uq_trade_fallback_identity;

-- apt_dong is a discriminator only when both compared rows have a value.
-- Missing apt_dong is handled as unknown by JdbcNormalizedTradeRepository before insert.
CREATE UNIQUE INDEX uq_trade_fallback_identity
    ON trade (complex_id, deal_date, floor, excl_area, deal_amount, apt_dong) NULLS NOT DISTINCT;
