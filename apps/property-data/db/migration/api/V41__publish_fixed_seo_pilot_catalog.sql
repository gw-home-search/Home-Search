CREATE TABLE public.seo_pilot_complex_catalog (
    complex_id bigint PRIMARY KEY,
    region_id bigint,
    pilot_rank integer NOT NULL UNIQUE,
    published_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_seo_pilot_rank CHECK (pilot_rank BETWEEN 1 AND 1000)
);

CREATE FUNCTION public.refresh_seo_pilot_complex_catalog()
RETURNS integer
LANGUAGE plpgsql
AS $function$
DECLARE
    published_count integer;
BEGIN
    TRUNCATE TABLE public.seo_pilot_complex_catalog;

    INSERT INTO public.seo_pilot_complex_catalog (complex_id, region_id, pilot_rank)
    WITH redeveloped_parcel AS (
        SELECT parcel_id
        FROM public.complex_coordinate_case
        WHERE relation_type = 'REDEVELOPED'
          AND relation_confidence = 'HIGH'
    ), superseded_complex AS (
        SELECT c.id AS complex_id
        FROM public.complex c
        JOIN redeveloped_parcel rp ON rp.parcel_id = c.parcel_id
        WHERE c.id <> (
            SELECT c2.id
            FROM public.complex c2
            LEFT JOIN public.trade t2
              ON t2.complex_id = c2.id
             AND t2.deleted_at IS NULL
            WHERE c2.parcel_id = c.parcel_id
            GROUP BY c2.id
            ORDER BY c2.use_date DESC NULLS LAST,
                     MAX(t2.deal_date) DESC NULLS LAST,
                     MIN(t2.deal_date) DESC NULLS LAST,
                     c2.id DESC
            LIMIT 1
        )
    ), recent_trade_stats AS (
        SELECT complex_id,
               COUNT(*) AS trade_count_24m,
               MAX(deal_date) AS latest_trade_date
        FROM public.trade
        WHERE deleted_at IS NULL
          AND deal_date >= CURRENT_DATE - INTERVAL '24 months'
        GROUP BY complex_id
    ), ranked AS (
        SELECT c.id AS complex_id,
               COALESCE(c.region_id, p.region_id) AS region_id,
               ROW_NUMBER() OVER (
                   ORDER BY COALESCE(recent_trade.trade_count_24m, 0) DESC,
                            recent_trade.latest_trade_date DESC NULLS LAST,
                            c.id
               ) AS pilot_rank
        FROM public.complex c
        JOIN public.parcel p ON p.id = c.parcel_id
        LEFT JOIN recent_trade_stats recent_trade ON recent_trade.complex_id = c.id
        LEFT JOIN superseded_complex sc ON sc.complex_id = c.id
        WHERE sc.complex_id IS NULL
          AND COALESCE(NULLIF(BTRIM(c.trade_name), ''), NULLIF(BTRIM(c.name), '')) IS NOT NULL
          AND NULLIF(BTRIM(p.address), '') IS NOT NULL
          AND (c.dong_cnt IS NOT NULL
               OR c.unit_cnt IS NOT NULL
               OR c.use_date IS NOT NULL
               OR EXISTS (
                   SELECT 1
                   FROM public.complex_building_register_profile_summary summary
                   JOIN public.building_register_profile_publication publication USING (publication_id)
                   WHERE summary.complex_id = c.id
                     AND publication.status = 'PUBLISHED'
               )
               OR EXISTS (
                   SELECT 1
                   FROM public.trade active_trade
                   WHERE active_trade.complex_id = c.id
                     AND active_trade.deleted_at IS NULL
               ))
    )
    SELECT complex_id, region_id, pilot_rank::integer
    FROM ranked
    WHERE pilot_rank <= 1000;

    GET DIAGNOSTICS published_count = ROW_COUNT;
    RETURN published_count;
END
$function$;

REVOKE ALL
ON FUNCTION public.refresh_seo_pilot_complex_catalog()
FROM PUBLIC;

SELECT public.refresh_seo_pilot_complex_catalog();

GRANT SELECT
ON TABLE public.seo_pilot_complex_catalog
TO home_search_property_runtime;
