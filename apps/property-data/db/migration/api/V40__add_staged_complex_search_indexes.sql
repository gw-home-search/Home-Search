SET LOCAL lock_timeout = '5s';

CREATE INDEX ix_complex_display_name_lower_prefix
    ON public.complex (lower(display_name) text_pattern_ops);

CREATE INDEX ix_complex_name_lower_prefix
    ON public.complex (lower(name) text_pattern_ops);

CREATE INDEX ix_complex_trade_name_lower_prefix
    ON public.complex (lower(trade_name) text_pattern_ops);

CREATE INDEX ix_complex_search_name_prefix
    ON public.complex (search_name text_pattern_ops);

CREATE INDEX ix_complex_name_alias_alias_name_lower_prefix
    ON public.complex_name_alias (lower(alias_name) text_pattern_ops);

CREATE INDEX ix_complex_name_alias_normalized_name_prefix
    ON public.complex_name_alias (normalized_name text_pattern_ops);

CREATE INDEX ix_parcel_address_simple_fts
    ON public.parcel
    USING gin (to_tsvector('simple', lower(COALESCE(address, ''))));
