CREATE TABLE public.market_news_collection_execution (
    execution_id uuid PRIMARY KEY,
    request_id varchar(100) NOT NULL UNIQUE,
    execution_type varchar(32) NOT NULL,
    policy_version varchar(32) NOT NULL,
    scheduled_at timestamptz NOT NULL,
    overlap_cutoff timestamptz,
    state varchar(16) NOT NULL,
    call_budget integer NOT NULL DEFAULT 4000,
    call_count integer NOT NULL DEFAULT 0,
    planned_work_unit_count integer NOT NULL DEFAULT 0,
    completed_work_unit_count integer NOT NULL DEFAULT 0,
    truncated_work_unit_count integer NOT NULL DEFAULT 0,
    failed_work_unit_count integer NOT NULL DEFAULT 0,
    skipped_budget_work_unit_count integer NOT NULL DEFAULT 0,
    raw_item_count integer NOT NULL DEFAULT 0,
    article_count integer NOT NULL DEFAULT 0,
    relation_count integer NOT NULL DEFAULT 0,
    bootstrap_truncated boolean NOT NULL DEFAULT false,
    started_at timestamptz,
    completed_at timestamptz,
    failure_kind varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_market_news_execution_type
        CHECK (execution_type IN ('GENERAL', 'MAJOR_COMPLEX', 'BOOTSTRAP', 'RETENTION')),
    CONSTRAINT ck_market_news_execution_state
        CHECK (state IN ('PLANNED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_market_news_execution_budget
        CHECK (call_budget BETWEEN 1 AND 4000 AND call_count BETWEEN 0 AND call_budget),
    CONSTRAINT ck_market_news_execution_counts
        CHECK (planned_work_unit_count >= 0
            AND completed_work_unit_count >= 0
            AND truncated_work_unit_count >= 0
            AND failed_work_unit_count >= 0
            AND skipped_budget_work_unit_count >= 0
            AND raw_item_count >= 0
            AND article_count >= 0
            AND relation_count >= 0),
    CONSTRAINT ck_market_news_execution_terminal
        CHECK ((state = 'PLANNED' AND started_at IS NULL AND completed_at IS NULL)
            OR (state = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL)
            OR (state IN ('COMPLETED', 'PARTIAL', 'FAILED')
                AND started_at IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE INDEX ix_market_news_execution_scheduled
    ON public.market_news_collection_execution (scheduled_at DESC, created_at DESC);

CREATE TABLE public.market_news_collection_work_unit (
    work_unit_id uuid PRIMARY KEY,
    execution_id uuid NOT NULL,
    unit_order integer NOT NULL,
    scope_kind varchar(32) NOT NULL,
    scope_type varchar(16) NOT NULL,
    region_code varchar(16),
    complex_id bigint,
    category varchar(32),
    query_text varchar(500) NOT NULL,
    cutoff_at timestamptz,
    oldest_provided_at timestamptz,
    last_provider_start integer NOT NULL DEFAULT 0,
    cutoff_reached boolean NOT NULL DEFAULT false,
    state varchar(32) NOT NULL,
    call_count integer NOT NULL DEFAULT 0,
    raw_item_count integer NOT NULL DEFAULT 0,
    failure_kind varchar(64),
    started_at timestamptz,
    completed_at timestamptz,
    CONSTRAINT fk_market_news_work_unit_execution
        FOREIGN KEY (execution_id)
        REFERENCES public.market_news_collection_execution(execution_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_market_news_work_unit_complex
        FOREIGN KEY (complex_id)
        REFERENCES public.complex(id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_market_news_work_unit_order UNIQUE (execution_id, unit_order),
    CONSTRAINT ck_market_news_work_unit_scope_kind
        CHECK (scope_kind IN ('NATIONAL_CATEGORY', 'SIDO', 'MAJOR_COMPLEX')),
    CONSTRAINT ck_market_news_work_unit_scope
        CHECK ((scope_type = 'NATIONWIDE' AND region_code IS NULL)
            OR (scope_type = 'SIDO' AND region_code IS NOT NULL)),
    CONSTRAINT ck_market_news_work_unit_complex_scope
        CHECK ((scope_kind = 'MAJOR_COMPLEX' AND complex_id IS NOT NULL)
            OR (scope_kind <> 'MAJOR_COMPLEX' AND complex_id IS NULL)),
    CONSTRAINT ck_market_news_work_unit_category
        CHECK (category IS NULL OR category IN (
            'POLICY', 'FINANCE_LOAN', 'SUPPLY_SALE', 'REDEVELOPMENT',
            'TRANSACTION_PRICE', 'TRANSPORT_DEVELOPMENT'
        )),
    CONSTRAINT ck_market_news_work_unit_state
        CHECK (state IN ('PLANNED', 'RUNNING', 'COMPLETED', 'TRUNCATED', 'FAILED', 'SKIPPED_BUDGET')),
    CONSTRAINT ck_market_news_work_unit_progress
        CHECK (unit_order > 0
            AND last_provider_start BETWEEN 0 AND 1000
            AND call_count >= 0
            AND raw_item_count >= 0),
    CONSTRAINT ck_market_news_work_unit_terminal
        CHECK ((state = 'PLANNED' AND started_at IS NULL AND completed_at IS NULL)
            OR (state = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL)
            OR (state IN ('COMPLETED', 'TRUNCATED', 'FAILED', 'SKIPPED_BUDGET')
                AND completed_at IS NOT NULL))
);

CREATE INDEX ix_market_news_work_unit_execution_state
    ON public.market_news_collection_work_unit (execution_id, state, unit_order);

CREATE TABLE public.market_news_article (
    article_id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    provider varchar(16) NOT NULL,
    canonical_url_hash char(64) NOT NULL,
    public_url text NOT NULL,
    title varchar(500) NOT NULL,
    provided_at timestamptz NOT NULL,
    first_seen_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_market_news_article_identity UNIQUE (provider, canonical_url_hash),
    CONSTRAINT ck_market_news_article_provider CHECK (provider = 'NAVER'),
    CONSTRAINT ck_market_news_article_url_length CHECK (length(public_url) BETWEEN 8 AND 4000),
    CONSTRAINT ck_market_news_article_title CHECK (length(btrim(title)) BETWEEN 1 AND 500),
    CONSTRAINT ck_market_news_article_seen CHECK (first_seen_at <= last_seen_at)
);

CREATE INDEX ix_market_news_article_provided
    ON public.market_news_article (provided_at DESC, article_id DESC);

CREATE TABLE public.market_news_raw_item (
    work_unit_id uuid NOT NULL,
    provider_start integer NOT NULL,
    provider_rank integer NOT NULL,
    title_raw text,
    original_link_raw text,
    link_raw text,
    description_raw text,
    pub_date_raw text,
    received_at timestamptz NOT NULL DEFAULT now(),
    rejection_reason varchar(64),
    article_id bigint,
    PRIMARY KEY (work_unit_id, provider_start, provider_rank),
    CONSTRAINT fk_market_news_raw_item_work_unit
        FOREIGN KEY (work_unit_id)
        REFERENCES public.market_news_collection_work_unit(work_unit_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_market_news_raw_item_article
        FOREIGN KEY (article_id)
        REFERENCES public.market_news_article(article_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_market_news_raw_item_position
        CHECK (provider_start BETWEEN 1 AND 1000 AND provider_rank BETWEEN 1 AND 100),
    CONSTRAINT ck_market_news_raw_item_outcome
        CHECK (NOT (rejection_reason IS NOT NULL AND article_id IS NOT NULL)),
    CONSTRAINT ck_market_news_raw_item_rejection
        CHECK (rejection_reason IS NULL OR rejection_reason IN (
            'MISSING_REQUIRED_FIELD', 'INVALID_URL', 'INVALID_PROVIDED_AT',
            'OUTSIDE_RETENTION_WINDOW', 'NOT_REAL_ESTATE_RELEVANT',
            'REGION_AMBIGUOUS', 'COMPLEX_AMBIGUOUS', 'DUPLICATE_ARTICLE'
        ))
);

CREATE INDEX ix_market_news_raw_item_received
    ON public.market_news_raw_item (received_at, work_unit_id);

CREATE TABLE public.market_news_relation (
    relation_id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    article_id bigint NOT NULL,
    policy_version varchar(32) NOT NULL,
    category varchar(32) NOT NULL,
    relation_type varchar(32) NOT NULL,
    region_code varchar(16),
    complex_id bigint,
    matched_tokens text[] NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_market_news_relation_article
        FOREIGN KEY (article_id)
        REFERENCES public.market_news_article(article_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_market_news_relation_complex
        FOREIGN KEY (complex_id)
        REFERENCES public.complex(id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_market_news_relation_category
        CHECK (category IN (
            'POLICY', 'FINANCE_LOAN', 'SUPPLY_SALE', 'REDEVELOPMENT',
            'TRANSACTION_PRICE', 'TRANSPORT_DEVELOPMENT'
        )),
    CONSTRAINT ck_market_news_relation_type
        CHECK (relation_type IN (
            'NATIONWIDE', 'SAME_SIDO', 'SAME_SIGUNGU', 'SAME_DONG', 'DIRECT_COMPLEX'
        )),
    CONSTRAINT ck_market_news_relation_shape
        CHECK ((relation_type = 'NATIONWIDE' AND region_code IS NULL AND complex_id IS NULL)
            OR (relation_type IN ('SAME_SIDO', 'SAME_SIGUNGU', 'SAME_DONG')
                AND region_code IS NOT NULL AND complex_id IS NULL)
            OR (relation_type = 'DIRECT_COMPLEX'
                AND region_code IS NOT NULL AND complex_id IS NOT NULL))
);

CREATE UNIQUE INDEX uq_market_news_relation_identity
    ON public.market_news_relation (
        article_id, policy_version, relation_type, COALESCE(region_code, ''), COALESCE(complex_id, 0)
    );

CREATE INDEX ix_market_news_relation_complex
    ON public.market_news_relation (complex_id, relation_type, article_id)
    WHERE complex_id IS NOT NULL;

CREATE INDEX ix_market_news_relation_region
    ON public.market_news_relation (region_code, category, article_id)
    WHERE region_code IS NOT NULL;

CREATE TABLE public.market_news_snapshot (
    snapshot_id uuid PRIMARY KEY,
    execution_id uuid NOT NULL,
    policy_version varchar(32) NOT NULL,
    scope_type varchar(16) NOT NULL,
    region_code varchar(16),
    build_status varchar(16) NOT NULL,
    generated_at timestamptz NOT NULL DEFAULT now(),
    data_cutoff timestamptz NOT NULL,
    superseded_by_snapshot_id uuid,
    withdrawn_reason varchar(64),
    item_count integer NOT NULL DEFAULT 0,
    CONSTRAINT fk_market_news_snapshot_execution
        FOREIGN KEY (execution_id)
        REFERENCES public.market_news_collection_execution(execution_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_market_news_snapshot_superseded
        FOREIGN KEY (superseded_by_snapshot_id)
        REFERENCES public.market_news_snapshot(snapshot_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_market_news_snapshot_scope
        CHECK ((scope_type = 'NATIONWIDE' AND region_code IS NULL)
            OR (scope_type = 'SIDO' AND region_code IS NOT NULL)),
    CONSTRAINT ck_market_news_snapshot_status
        CHECK (build_status IN ('BUILDING', 'PUBLISHED', 'REJECTED', 'SUPERSEDED', 'WITHDRAWN')),
    CONSTRAINT ck_market_news_snapshot_superseded_link
        CHECK ((build_status = 'SUPERSEDED' AND superseded_by_snapshot_id IS NOT NULL)
            OR (build_status <> 'SUPERSEDED' AND superseded_by_snapshot_id IS NULL)),
    CONSTRAINT ck_market_news_snapshot_withdrawn_reason
        CHECK ((build_status = 'WITHDRAWN' AND withdrawn_reason IS NOT NULL)
            OR (build_status <> 'WITHDRAWN' AND withdrawn_reason IS NULL)),
    CONSTRAINT ck_market_news_snapshot_item_count CHECK (item_count >= 0)
);

CREATE UNIQUE INDEX uq_market_news_snapshot_current_scope
    ON public.market_news_snapshot (scope_type, COALESCE(region_code, ''))
    WHERE build_status = 'PUBLISHED';

CREATE INDEX ix_market_news_snapshot_last_good
    ON public.market_news_snapshot (scope_type, region_code, generated_at DESC)
    WHERE build_status IN ('PUBLISHED', 'SUPERSEDED');

CREATE TABLE public.market_news_snapshot_item (
    snapshot_id uuid NOT NULL,
    article_id bigint NOT NULL,
    relation_id bigint NOT NULL,
    category varchar(32) NOT NULL,
    sort_rank integer NOT NULL,
    provider_rank integer NOT NULL,
    PRIMARY KEY (snapshot_id, article_id),
    CONSTRAINT fk_market_news_snapshot_item_snapshot
        FOREIGN KEY (snapshot_id)
        REFERENCES public.market_news_snapshot(snapshot_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_market_news_snapshot_item_article
        FOREIGN KEY (article_id)
        REFERENCES public.market_news_article(article_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_market_news_snapshot_item_relation
        FOREIGN KEY (relation_id)
        REFERENCES public.market_news_relation(relation_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_market_news_snapshot_item_category
        CHECK (category IN (
            'POLICY', 'FINANCE_LOAN', 'SUPPLY_SALE', 'REDEVELOPMENT',
            'TRANSACTION_PRICE', 'TRANSPORT_DEVELOPMENT'
        )),
    CONSTRAINT ck_market_news_snapshot_item_order
        CHECK (sort_rank > 0 AND provider_rank BETWEEN 1 AND 100)
);

CREATE UNIQUE INDEX uq_market_news_snapshot_item_rank
    ON public.market_news_snapshot_item (snapshot_id, sort_rank);

CREATE TABLE public.market_news_major_complex_selection (
    selection_week date NOT NULL,
    rank integer NOT NULL,
    complex_id bigint NOT NULL,
    region_code varchar(16) NOT NULL,
    trade_count_90d integer NOT NULL,
    unit_cnt integer,
    selected_at timestamptz NOT NULL DEFAULT now(),
    selection_status varchar(16) NOT NULL DEFAULT 'PUBLISHED',
    PRIMARY KEY (selection_week, rank),
    CONSTRAINT fk_market_news_major_complex
        FOREIGN KEY (complex_id)
        REFERENCES public.complex(id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_market_news_major_complex_week UNIQUE (selection_week, complex_id),
    CONSTRAINT ck_market_news_major_complex_rank CHECK (rank BETWEEN 1 AND 200),
    CONSTRAINT ck_market_news_major_complex_count CHECK (trade_count_90d > 0),
    CONSTRAINT ck_market_news_major_complex_status CHECK (selection_status IN ('BUILDING', 'PUBLISHED', 'REJECTED'))
);

CREATE INDEX ix_market_news_major_complex_current
    ON public.market_news_major_complex_selection (selection_week DESC, rank)
    WHERE selection_status = 'PUBLISHED';

CREATE TABLE public.market_news_quality_label (
    review_set_id uuid NOT NULL,
    article_id bigint NOT NULL,
    relation_id bigint NOT NULL,
    sample_stratum varchar(64) NOT NULL,
    relevance_correct boolean,
    category_correct boolean,
    relation_correct boolean,
    url_opened boolean,
    sampled_at timestamptz NOT NULL DEFAULT now(),
    reviewed_at timestamptz,
    reviewer_ref varchar(100),
    note varchar(500),
    PRIMARY KEY (review_set_id, article_id, relation_id)
);

CREATE INDEX ix_market_news_quality_review
    ON public.market_news_quality_label (review_set_id, sample_stratum, reviewed_at);
