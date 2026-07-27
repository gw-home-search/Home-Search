WITH complex_base AS (
    SELECT
        p.id AS parcel_id,
        p.latitude AS parcel_lat,
        p.longitude AS parcel_lng,
        c.id AS complex_id,
        c.name AS complex_name,
        c.unit_cnt,
        c.use_date,
        COALESCE(c.bc_rat, profile.building_coverage_rate) AS effective_bc_rat,
        COALESCE(c.vl_rat, profile.floor_area_ratio) AS effective_vl_rat,
        coordinate_case.status AS coordinate_case_status,
        coordinate_case.relation_type,
        coordinate_case.relation_confidence,
        COALESCE(display_coordinate.latitude, p.latitude) AS lat,
        COALESCE(display_coordinate.longitude, p.longitude) AS lng,
        display_coordinate.coordinate_source,
        display_coordinate.confidence,
        latest_complex_trade.deal_date AS latest_complex_deal_date,
        latest_complex_trade.deal_amount AS latest_complex_deal_amount,
        latest_complex_trade.excl_area AS latest_complex_excl_area,
        first_complex_trade.deal_date AS first_deal
    FROM public.parcel p
    JOIN public.complex c ON c.parcel_id = p.id
    LEFT JOIN public.complex_display_coordinate display_coordinate
      ON display_coordinate.complex_id = c.id
     AND (
         display_coordinate.coordinate_source <> :buildingFootprintSource
         OR display_coordinate.confidence >= :trustedBuildingCoordinateConfidence
    )
    LEFT JOIN public.complex_coordinate_case coordinate_case
      ON coordinate_case.parcel_id = p.id
    LEFT JOIN public.building_register_profile_publication publication
      ON publication.status = 'PUBLISHED'
    LEFT JOIN public.complex_building_register_profile_summary profile
      ON profile.publication_id = publication.publication_id
     AND profile.complex_id = c.id
     AND profile.ratio_scope = 'PARCEL'
     AND profile.ratio_quality = 'PNU_FALLBACK'
    LEFT JOIN LATERAL (
        SELECT trade.deal_date, trade.deal_amount, trade.excl_area
        FROM public.trade
        WHERE trade.complex_id = c.id
          AND trade.deleted_at IS NULL
        ORDER BY trade.deal_date DESC, trade.id DESC
        LIMIT 1
    ) latest_complex_trade ON true
    LEFT JOIN LATERAL (
        SELECT trade.deal_date
        FROM public.trade
        WHERE trade.complex_id = c.id
          AND trade.deleted_at IS NULL
          AND EXISTS (
              SELECT 1
              FROM public.complex_coordinate_case first_trade_case
              WHERE first_trade_case.parcel_id = p.id
                AND first_trade_case.relation_type = 'REDEVELOPED'
                AND first_trade_case.relation_confidence = 'HIGH'
          )
        ORDER BY trade.deal_date ASC, trade.id ASC
        LIMIT 1
    ) first_complex_trade ON true
    GROUP BY
        p.id,
        p.latitude,
        p.longitude,
        c.id,
        c.name,
        c.unit_cnt,
        c.use_date,
        c.bc_rat,
        c.vl_rat,
        profile.building_coverage_rate,
        profile.floor_area_ratio,
        coordinate_case.status,
        coordinate_case.relation_type,
        coordinate_case.relation_confidence,
        display_coordinate.latitude,
        display_coordinate.longitude,
        display_coordinate.coordinate_source,
        display_coordinate.confidence,
        latest_complex_trade.deal_date,
        latest_complex_trade.deal_amount,
        latest_complex_trade.excl_area,
        first_complex_trade.deal_date
),
parcel_flags AS (
    SELECT
        parcel_id,
        count(*) AS complex_count,
        count(*) FILTER (
            WHERE coordinate_source = :buildingFootprintSource
        ) AS trusted_building_coordinate_count,
        COALESCE(bool_or(
            coordinate_case_status = 'RESOLVED'
            AND relation_type = 'CONCURRENT'
            AND relation_confidence = 'HIGH'
        ), false) AS is_concurrent,
        COALESCE(bool_or(
            relation_type = 'REDEVELOPED'
            AND relation_confidence = 'HIGH'
        ), false) AS is_redeveloped,
        COALESCE(bool_or(relation_type = 'REDEVELOPED'), false) AS is_redevelopment_candidate
    FROM complex_base
    GROUP BY parcel_id
),
current_generation AS (
    SELECT DISTINCT ON (parcel_id)
        parcel_id,
        complex_id
    FROM complex_base
    ORDER BY
        parcel_id,
        use_date DESC NULLS LAST,
        latest_complex_deal_date DESC NULLS LAST,
        first_deal DESC NULLS LAST,
        complex_id DESC
),
split_complex_marker AS (
    SELECT
        base.parcel_id,
        base.complex_id,
        base.complex_name,
        base.lat,
        base.lng,
        base.latest_complex_deal_amount AS latest_deal_amount,
        base.latest_complex_excl_area AS excl_area,
        base.unit_cnt::bigint AS unit_cnt_sum,
        CASE
            WHEN base.use_date IS NULL THEN NULL
            ELSE EXTRACT(YEAR FROM age(CURRENT_DATE, base.use_date))::integer
        END AS building_age
    FROM complex_base base
    JOIN parcel_flags flags ON flags.parcel_id = base.parcel_id
    LEFT JOIN current_generation current_generation
      ON current_generation.parcel_id = base.parcel_id
    WHERE (
        NOT flags.is_redeveloped
        AND (
            flags.is_concurrent
            OR (
                NOT flags.is_redevelopment_candidate
                AND flags.trusted_building_coordinate_count > 0
            )
        )
        AND flags.complex_count > 1
        AND base.coordinate_source = :buildingFootprintSource
    )
       OR (
           flags.is_redeveloped
           AND base.complex_id = current_generation.complex_id
       )
),
representative_coordinate AS (
    SELECT DISTINCT ON (base.parcel_id)
        base.parcel_id,
        base.complex_id,
        base.lat,
        base.lng
    FROM complex_base base
    ORDER BY
        base.parcel_id,
        CASE base.coordinate_source
            WHEN :buildingFootprintSource THEN 0
            ELSE 1
        END,
        base.confidence DESC NULLS LAST,
        base.latest_complex_deal_date DESC NULLS LAST,
        base.complex_id DESC
),
parcel_marker_base AS (
    SELECT base.*
    FROM complex_base base
    JOIN parcel_flags flags ON flags.parcel_id = base.parcel_id
    WHERE NOT flags.is_redeveloped
      AND (
          NOT (
              (
                  flags.is_concurrent
                  OR (
                      NOT flags.is_redevelopment_candidate
                      AND flags.trusted_building_coordinate_count > 0
                  )
              )
              AND flags.complex_count > 1
          )
          OR base.coordinate_source IS DISTINCT FROM :buildingFootprintSource
      )
),
parcel_marker AS (
    SELECT
        base.parcel_id,
        CASE
            WHEN flags.complex_count = 1 THEN representative_coordinate.complex_id
            ELSE NULL
        END AS complex_id,
        CASE
            WHEN flags.complex_count = 1 THEN representative_coordinate.lat
            ELSE MAX(base.parcel_lat)
        END AS lat,
        CASE
            WHEN flags.complex_count = 1 THEN representative_coordinate.lng
            ELSE MAX(base.parcel_lng)
        END AS lng,
        SUM(base.unit_cnt)::bigint AS unit_cnt_sum,
        MAX(
            CASE
                WHEN base.use_date IS NULL THEN NULL
                ELSE EXTRACT(YEAR FROM age(CURRENT_DATE, base.use_date))::integer
            END
        ) AS building_age
    FROM parcel_marker_base base
    JOIN representative_coordinate
      ON representative_coordinate.parcel_id = base.parcel_id
    JOIN parcel_flags flags ON flags.parcel_id = base.parcel_id
    GROUP BY
        base.parcel_id,
        flags.complex_count,
        representative_coordinate.complex_id,
        representative_coordinate.lat,
        representative_coordinate.lng
),
latest_parcel_trade AS (
    SELECT DISTINCT ON (base.parcel_id)
        base.parcel_id,
        base.latest_complex_deal_amount AS latest_deal_amount,
        base.latest_complex_excl_area AS excl_area,
        base.complex_name
    FROM complex_base base
    JOIN parcel_marker_base marker_base
      ON marker_base.parcel_id = base.parcel_id
     AND marker_base.complex_id = base.complex_id
    WHERE base.latest_complex_deal_date IS NOT NULL
    ORDER BY base.parcel_id, base.latest_complex_deal_date DESC, base.complex_id DESC
),
markers AS (
    SELECT
        split.parcel_id,
        split.complex_id,
        split.complex_name,
        split.lat,
        split.lng,
        split.latest_deal_amount,
        split.excl_area,
        split.unit_cnt_sum,
        split.building_age
    FROM split_complex_marker split
    UNION ALL
    SELECT
        parcel_marker.parcel_id,
        parcel_marker.complex_id,
        latest_parcel_trade.complex_name,
        parcel_marker.lat,
        parcel_marker.lng,
        latest_parcel_trade.latest_deal_amount,
        latest_parcel_trade.excl_area,
        parcel_marker.unit_cnt_sum,
        parcel_marker.building_age
    FROM parcel_marker
    LEFT JOIN latest_parcel_trade
      ON latest_parcel_trade.parcel_id = parcel_marker.parcel_id
)
INSERT INTO public.map_complex_marker_projection (
    generation_id,
    marker_key,
    parcel_id,
    complex_id,
    complex_name,
    lat,
    lng,
    point,
    latest_deal_amount,
    latest_excl_area,
    unit_cnt_sum,
    building_age,
    ratio_members
)
SELECT
    :generationId,
    CASE
        WHEN markers.complex_id IS NULL THEN 'parcel:' || markers.parcel_id::text
        ELSE 'complex:' || markers.complex_id::text
    END,
    markers.parcel_id,
    markers.complex_id,
    markers.complex_name,
    markers.lat,
    markers.lng,
    ST_SetSRID(ST_MakePoint(markers.lng, markers.lat), 4326),
    markers.latest_deal_amount,
    markers.excl_area,
    markers.unit_cnt_sum,
    markers.building_age,
    COALESCE((
        SELECT jsonb_agg(
            jsonb_build_object(
                'bcRat', ratio.effective_bc_rat,
                'vlRat', ratio.effective_vl_rat
            )
            ORDER BY ratio.complex_id
        )
        FROM complex_base ratio
        WHERE ratio.parcel_id = markers.parcel_id
          AND (markers.complex_id IS NULL OR ratio.complex_id = markers.complex_id)
    ), '[]'::jsonb)
FROM markers
WHERE markers.lat BETWEEN 33 AND 39
  AND markers.lng BETWEEN 124 AND 132
  AND markers.unit_cnt_sum IS NOT NULL
ORDER BY markers.parcel_id, markers.complex_id
