ALTER TABLE public.complex
    ADD COLUMN bld_mgm_bld_rgst_pk character varying(255);

ALTER TABLE public.complex
    ADD CONSTRAINT ck_complex_bld_mgm_bld_rgst_pk_not_blank CHECK (
        bld_mgm_bld_rgst_pk IS NULL OR btrim(bld_mgm_bld_rgst_pk) <> ''
    );

CREATE UNIQUE INDEX uq_complex_bld_mgm_bld_rgst_pk
    ON public.complex (bld_mgm_bld_rgst_pk)
    WHERE bld_mgm_bld_rgst_pk IS NOT NULL;

ALTER TABLE public.complex_metadata_enrichment_attempt
    ADD COLUMN request_id uuid,
    ADD COLUMN projection_applied boolean DEFAULT false NOT NULL;

CREATE INDEX ix_cmea_request_complex
    ON public.complex_metadata_enrichment_attempt (request_id, complex_id)
    WHERE request_id IS NOT NULL;
