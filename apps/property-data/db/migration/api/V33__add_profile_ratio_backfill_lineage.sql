CREATE TABLE public.building_ratio_profile_backfill_import (
    import_id uuid PRIMARY KEY,
    analysis_run_id uuid NOT NULL REFERENCES public.building_register_profile_analysis_run(analysis_run_id),
    archive_id uuid NOT NULL REFERENCES public.building_register_profile_archive_manifest(archive_id),
    rules_version character varying(80) NOT NULL,
    source_file_sha256 character varying(64) NOT NULL,
    candidate_count integer DEFAULT 0 NOT NULL,
    status character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    failure_reason text,
    CONSTRAINT uq_brpbi_source UNIQUE (analysis_run_id, archive_id, rules_version),
    CONSTRAINT ck_brpbi_rules CHECK (btrim(rules_version) <> ''),
    CONSTRAINT ck_brpbi_hash CHECK (source_file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_brpbi_count CHECK (candidate_count >= 0),
    CONSTRAINT ck_brpbi_status CHECK (status IN ('IMPORTING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_brpbi_completion CHECK (
        (status='COMPLETED' AND completed_at IS NOT NULL AND failure_reason IS NULL)
        OR (status='FAILED' AND completed_at IS NULL AND failure_reason IS NOT NULL)
        OR (status='IMPORTING' AND completed_at IS NULL AND failure_reason IS NULL)
    )
);

CREATE TABLE public.building_ratio_profile_candidate_lineage (
    candidate_id bigint PRIMARY KEY REFERENCES public.building_ratio_candidate(id),
    import_id uuid NOT NULL REFERENCES public.building_ratio_profile_backfill_import(import_id),
    pnu_scope_hash character varying(64) NOT NULL,
    comparison_status character varying(32) NOT NULL,
    recap_value numeric(18,8) NOT NULL,
    title_value numeric(30,12) NOT NULL,
    difference numeric(30,12) NOT NULL,
    contributor_count integer NOT NULL,
    expected_contributor_count integer NOT NULL,
    evidence_sha256 character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_brpcl_evidence UNIQUE (import_id, pnu_scope_hash, candidate_id),
    CONSTRAINT ck_brpcl_scope_hash CHECK (pnu_scope_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_brpcl_evidence_hash CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_brpcl_safe_comparison CHECK (
        comparison_status IN ('MATCH', 'WITHIN_TOLERANCE')
        AND difference BETWEEN 0 AND 0.01
    ),
    CONSTRAINT ck_brpcl_complete_contributors CHECK (
        contributor_count > 0
        AND contributor_count = expected_contributor_count
    ),
    CONSTRAINT ck_brpcl_ratio_values CHECK (recap_value > 0 AND title_value > 0)
);

CREATE INDEX ix_brpcl_import ON public.building_ratio_profile_candidate_lineage(import_id, candidate_id);

REVOKE ALL ON TABLE public.building_ratio_profile_backfill_import
    FROM home_search_property_runtime;
REVOKE ALL ON TABLE public.building_ratio_profile_candidate_lineage
    FROM home_search_property_runtime;
