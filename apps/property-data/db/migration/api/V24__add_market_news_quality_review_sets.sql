CREATE TABLE public.market_news_quality_review_set (
    review_set_id uuid PRIMARY KEY,
    policy_version varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    sampled_at timestamptz NOT NULL DEFAULT now(),
    total_sample_count integer NOT NULL,
    minimum_category_count integer NOT NULL,
    covered_sido_count integer NOT NULL,
    direct_complex_count integer NOT NULL,
    same_dong_count integer NOT NULL,
    same_sigungu_count integer NOT NULL,
    complex_challenge_count integer NOT NULL,
    url_sample_count integer NOT NULL,
    CONSTRAINT ck_market_news_quality_review_status
        CHECK (status IN ('READY', 'INSUFFICIENT_SAMPLE')),
    CONSTRAINT ck_market_news_quality_review_counts
        CHECK (total_sample_count >= 0
            AND minimum_category_count >= 0
            AND covered_sido_count BETWEEN 0 AND 17
            AND direct_complex_count >= 0
            AND same_dong_count >= 0
            AND same_sigungu_count >= 0
            AND complex_challenge_count >= 0
            AND url_sample_count >= 0)
);

INSERT INTO public.market_news_quality_review_set (
    review_set_id,
    policy_version,
    status,
    sampled_at,
    total_sample_count,
    minimum_category_count,
    covered_sido_count,
    direct_complex_count,
    same_dong_count,
    same_sigungu_count,
    complex_challenge_count,
    url_sample_count
)
SELECT DISTINCT
    review_set_id,
    'LEGACY',
    'INSUFFICIENT_SAMPLE',
    min(sampled_at) OVER (PARTITION BY review_set_id),
    count(*) OVER (PARTITION BY review_set_id),
    0,
    0,
    0,
    0,
    0,
    0,
    0
FROM public.market_news_quality_label
ON CONFLICT (review_set_id) DO NOTHING;

ALTER TABLE public.market_news_quality_label
    ADD CONSTRAINT fk_market_news_quality_review_set
        FOREIGN KEY (review_set_id)
        REFERENCES public.market_news_quality_review_set(review_set_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_market_news_quality_article
        FOREIGN KEY (article_id)
        REFERENCES public.market_news_article(article_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_market_news_quality_relation
        FOREIGN KEY (relation_id)
        REFERENCES public.market_news_relation(relation_id)
        ON DELETE RESTRICT;

CREATE INDEX ix_market_news_quality_review_sampled
    ON public.market_news_quality_review_set (sampled_at, review_set_id);

GRANT SELECT, INSERT, UPDATE, DELETE
ON TABLE public.market_news_quality_review_set
TO home_search_property_runtime;
