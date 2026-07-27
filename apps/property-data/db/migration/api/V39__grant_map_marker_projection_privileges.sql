GRANT SELECT, INSERT, UPDATE, DELETE
ON TABLE public.map_marker_generation
TO home_search_property_runtime;

GRANT SELECT, INSERT, UPDATE, DELETE
ON TABLE
    public.map_complex_marker_projection,
    public.map_region_marker_projection,
    public.map_marker_active_generation
TO home_search_property_runtime;

GRANT USAGE, SELECT
ON SEQUENCE public.map_marker_generation_id_seq
TO home_search_property_runtime;

GRANT EXECUTE
ON FUNCTION public.activate_map_marker_generation()
TO home_search_property_runtime;
