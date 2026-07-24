SET LOCAL lock_timeout = '5s';

DO $precondition$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'home_search_property_runtime') THEN
        RAISE EXCEPTION
            'V12 requires the home_search_property_runtime role to be bootstrapped before migration';
    END IF;
END
$precondition$;

GRANT SELECT, INSERT, UPDATE ON TABLE
    public.building_register_collection_campaign,
    public.building_register_collection_target,
    public.building_register_endpoint_snapshot,
    public.building_register_raw_page,
    public.building_register_record_snapshot,
    public.building_register_complex_match,
    public.building_register_match_evidence,
    public.building_ratio_candidate,
    public.building_ratio_candidate_input,
    public.building_ratio_projection
TO home_search_property_runtime;

GRANT USAGE, SELECT ON SEQUENCE
    public.building_register_collection_target_id_seq,
    public.building_register_endpoint_snapshot_id_seq,
    public.building_register_raw_page_id_seq,
    public.building_register_record_snapshot_id_seq,
    public.building_register_complex_match_id_seq,
    public.building_register_match_evidence_id_seq,
    public.building_ratio_candidate_id_seq,
    public.building_ratio_projection_id_seq
TO home_search_property_runtime;
