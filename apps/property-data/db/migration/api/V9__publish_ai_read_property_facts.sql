SET LOCAL lock_timeout = '5s';

DO $precondition$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'home_search_ai_reader') THEN
        RAISE EXCEPTION
            'V9 requires the home_search_ai_reader role to be bootstrapped before migration';
    END IF;
END
$precondition$;

CREATE SCHEMA IF NOT EXISTS ai_read;

REVOKE ALL ON SCHEMA ai_read FROM PUBLIC;

CREATE VIEW ai_read.complex_fact
WITH (security_barrier = true)
AS
SELECT
    complex.id AS complex_id,
    complex.parcel_id,
    complex.complex_pk,
    complex.apt_seq,
    complex.name,
    complex.trade_name,
    complex.display_name,
    region.code AS region_code,
    region.name AS region_name,
    parcel.address,
    complex.dong_cnt AS building_count,
    complex.unit_cnt AS unit_count,
    complex.use_date,
    COALESCE(display_coordinate.latitude, parcel.latitude) AS latitude,
    COALESCE(display_coordinate.longitude, parcel.longitude) AS longitude,
    COALESCE(display_coordinate.coordinate_source, 'PARCEL_FALLBACK') AS coordinate_source,
    display_coordinate.confidence AS coordinate_confidence,
    COALESCE(
        COALESCE(display_coordinate.latitude, parcel.latitude) BETWEEN 33 AND 39
        AND COALESCE(display_coordinate.longitude, parcel.longitude) BETWEEN 124 AND 132,
        false
    ) AS marker_safe,
    GREATEST(
        complex.updated_at,
        parcel.updated_at,
        COALESCE(display_coordinate.updated_at, '-infinity'::timestamptz)
    ) AS data_updated_at
FROM public.complex complex
JOIN public.parcel parcel ON parcel.id = complex.parcel_id
LEFT JOIN public.region region ON region.id = complex.region_id
LEFT JOIN public.complex_display_coordinate display_coordinate
    ON display_coordinate.complex_id = complex.id
   AND (
       display_coordinate.coordinate_source <> 'BUILDING_FOOTPRINT'
       OR display_coordinate.confidence >= 80
   );

CREATE VIEW ai_read.trade_fact
WITH (security_barrier = true)
AS
SELECT
    trade.id AS trade_id,
    trade.complex_id,
    trade.complex_pk,
    trade.apt_seq,
    trade.deal_date,
    trade.deal_amount AS deal_amount_ten_thousand_krw,
    trade.excl_area AS exclusive_area_square_meters,
    trade.floor,
    trade.apt_dong,
    trade.source,
    trade.source_key,
    trade.created_at AS normalized_at
FROM public.trade trade
WHERE trade.deleted_at IS NULL;

COMMENT ON VIEW ai_read.complex_fact IS
    'A-grade apartment identity and marker-safe coordinate facts for the AI service';
COMMENT ON VIEW ai_read.trade_fact IS
    'A-grade active normalized apartment trade facts for the AI service; amounts are 10,000 KRW';

REVOKE ALL ON ALL TABLES IN SCHEMA public, reference, batch FROM home_search_ai_reader;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public, reference, batch FROM home_search_ai_reader;
REVOKE ALL ON SCHEMA public, reference, batch FROM home_search_ai_reader;
REVOKE ALL ON ALL TABLES IN SCHEMA ai_read FROM PUBLIC, home_search_ai_reader;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA ai_read FROM PUBLIC, home_search_ai_reader;

GRANT USAGE ON SCHEMA ai_read TO home_search_ai_reader;
GRANT SELECT ON ai_read.complex_fact, ai_read.trade_fact TO home_search_ai_reader;
