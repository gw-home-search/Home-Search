CREATE INDEX IF NOT EXISTS ix_trade_complex_latest_active_covering
    ON trade (complex_id, deal_date DESC, id DESC)
    INCLUDE (deal_amount, excl_area)
    WHERE deleted_at IS NULL;
