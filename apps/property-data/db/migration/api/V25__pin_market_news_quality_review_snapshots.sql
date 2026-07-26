ALTER TABLE public.market_news_quality_review_set
    ADD COLUMN source_snapshot_captured_at timestamptz,
    ADD COLUMN source_snapshot_count integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_market_news_quality_source_snapshot_count
        CHECK (source_snapshot_count BETWEEN 0 AND 18);

CREATE TABLE public.market_news_quality_review_snapshot (
    review_set_id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    scope_type varchar(16) NOT NULL,
    region_code varchar(16),
    PRIMARY KEY (review_set_id, snapshot_id),
    CONSTRAINT fk_market_news_quality_snapshot_review_set
        FOREIGN KEY (review_set_id)
        REFERENCES public.market_news_quality_review_set(review_set_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_market_news_quality_snapshot
        FOREIGN KEY (snapshot_id)
        REFERENCES public.market_news_snapshot(snapshot_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_market_news_quality_snapshot_scope
        CHECK ((scope_type = 'NATIONWIDE' AND region_code IS NULL)
            OR (scope_type = 'SIDO' AND region_code IS NOT NULL))
);

CREATE UNIQUE INDEX uq_market_news_quality_review_snapshot_scope
    ON public.market_news_quality_review_snapshot (
        review_set_id, scope_type, COALESCE(region_code, '')
    );

GRANT SELECT, INSERT, UPDATE, DELETE
ON TABLE public.market_news_quality_review_snapshot
TO home_search_property_runtime;
