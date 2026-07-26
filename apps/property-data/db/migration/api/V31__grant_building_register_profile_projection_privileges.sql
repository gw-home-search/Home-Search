GRANT SELECT, INSERT, UPDATE
ON TABLE
    public.building_register_profile_projection_run,
    public.complex_building_register_profile,
    public.complex_building_register_building,
    public.building_register_profile_projected_quality
TO home_search_property_runtime;

GRANT SELECT
ON TABLE public.building_register_profile_archive_manifest
TO home_search_property_runtime;
