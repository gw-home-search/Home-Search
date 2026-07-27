SET LOCAL lock_timeout = '5s';

DO $precondition$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'home_search_ai_reader') THEN
        RAISE EXCEPTION
            'V36 requires the home_search_ai_reader role to be bootstrapped before migration';
    END IF;
END
$precondition$;

CREATE VIEW ai_read.complex_search_fact
WITH (security_barrier = true)
AS
SELECT
    complex.id AS complex_id,
    complex.display_name,
    complex.name AS canonical_name,
    complex.trade_name,
    regexp_replace(
        hs_normalize_complex_search_name(
            COALESCE(NULLIF(btrim(complex.trade_name), ''), complex.name)
        ),
        '(아파트|apt)$',
        '',
        'i'
    ) AS canonical_search_name,
    COALESCE(alias.names, ARRAY[]::text[]) AS aliases,
    COALESCE(alias.normalized_names, ARRAY[]::text[]) AS alias_search_names,
    region.code AS region_code,
    region.name AS region_name,
    parcel.address,
    lower(concat_ws(
        ' ',
        hs_normalize_complex_search_name(complex.display_name),
        hs_normalize_complex_search_name(complex.name),
        hs_normalize_complex_search_name(COALESCE(complex.trade_name, complex.name)),
        array_to_string(COALESCE(alias.normalized_names, ARRAY[]::text[]), ' '),
        hs_normalize_complex_search_name(parcel.address),
        hs_normalize_complex_search_name(COALESCE(region.name, ''))
    )) AS search_document,
    complex.unit_cnt AS unit_count,
    complex.use_date,
    COALESCE(
        COALESCE(display_coordinate.latitude, parcel.latitude) BETWEEN 33 AND 39
        AND COALESCE(display_coordinate.longitude, parcel.longitude) BETWEEN 124 AND 132,
        false
    ) AS marker_safe,
    GREATEST(
        complex.updated_at,
        parcel.updated_at,
        COALESCE(display_coordinate.updated_at, '-infinity'::timestamptz),
        COALESCE(alias.updated_at, '-infinity'::timestamptz)
    ) AS data_updated_at
FROM public.complex complex
JOIN public.parcel parcel ON parcel.id = complex.parcel_id
LEFT JOIN public.region region ON region.id = complex.region_id
LEFT JOIN public.complex_display_coordinate display_coordinate
    ON display_coordinate.complex_id = complex.id
   AND (
       display_coordinate.coordinate_source <> 'BUILDING_FOOTPRINT'
       OR display_coordinate.confidence >= 80
   )
LEFT JOIN LATERAL (
    SELECT
        array_agg(value.alias_name ORDER BY value.alias_name, value.id) AS names,
        array_agg(
            regexp_replace(value.normalized_name, '(아파트|apt)$', '', 'i')
            ORDER BY value.alias_name, value.id
        ) AS normalized_names,
        max(value.updated_at) AS updated_at
    FROM public.complex_name_alias value
    WHERE value.complex_id = complex.id
) alias ON true;

CREATE VIEW ai_read.complex_profile_fact
WITH (security_barrier = true)
AS
SELECT
    summary.complex_id,
    summary.ratio_scope,
    summary.ratio_quality,
    summary.building_coverage_rate,
    summary.floor_area_ratio,
    summary.site_area_m2,
    summary.building_area_m2,
    summary.total_floor_area_m2,
    summary.household_scope,
    summary.household_quality,
    summary.household_count,
    summary.family_count,
    summary.unit_count,
    summary.parking_scope,
    summary.parking_quality,
    summary.total_parking_count,
    summary.parking_per_household,
    summary.building_scope,
    summary.building_quality,
    summary.main_building_count,
    summary.attached_building_count,
    summary.max_ground_floor_count,
    summary.max_underground_floor_count,
    summary.max_height_m,
    summary.elevator_scope,
    summary.elevator_quality,
    summary.ride_elevator_count,
    summary.emergency_elevator_count,
    summary.safety_scope,
    summary.safety_quality,
    summary.seismic_design_status,
    summary.seismic_abilities,
    summary.date_scope,
    summary.date_quality,
    summary.permit_date,
    summary.construction_start_date,
    summary.use_approval_date,
    summary.address_scope,
    summary.address_quality,
    summary.parcel_address,
    summary.road_address,
    summary.energy_scope,
    summary.energy_quality,
    summary.energy_efficiency_grades,
    summary.energy_saving_rate_min,
    summary.energy_saving_rate_max,
    summary.energy_epi_min,
    summary.energy_epi_max,
    summary.green_building_grades,
    summary.green_cert_score_min,
    summary.green_cert_score_max,
    summary.intelligent_building_grades,
    summary.intelligent_cert_score_min,
    summary.intelligent_cert_score_max,
    summary.created_at AS data_updated_at
FROM public.complex_building_register_profile_summary summary
JOIN public.building_register_profile_publication publication
  ON publication.publication_id = summary.publication_id
 AND publication.status = 'PUBLISHED';

COMMENT ON VIEW ai_read.complex_search_fact IS
    'A-grade bounded apartment identity search facts without source audit identifiers';
COMMENT ON VIEW ai_read.complex_profile_fact IS
    'Published building-register profile summary facts without provider keys or PNU';

REVOKE ALL ON ai_read.complex_search_fact, ai_read.complex_profile_fact
FROM PUBLIC, home_search_ai_reader;
