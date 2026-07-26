GRANT SELECT, INSERT, UPDATE
ON TABLE
    public.building_register_profile_publication,
    public.building_register_profile_site,
    public.building_register_profile_building,
    public.building_register_profile_hierarchy,
    public.building_register_profile_field_evidence,
    public.complex_building_register_profile_summary
TO home_search_property_runtime;

REVOKE DELETE, TRUNCATE
ON TABLE
    public.building_register_profile_publication,
    public.building_register_profile_site,
    public.building_register_profile_building,
    public.building_register_profile_hierarchy,
    public.building_register_profile_field_evidence,
    public.complex_building_register_profile_summary
FROM home_search_property_runtime;

GRANT EXECUTE ON FUNCTION public.publish_building_register_profile(uuid)
TO home_search_property_runtime;

GRANT EXECUTE ON FUNCTION public.backfill_building_register_profile_operational_columns(uuid)
TO home_search_property_runtime;
