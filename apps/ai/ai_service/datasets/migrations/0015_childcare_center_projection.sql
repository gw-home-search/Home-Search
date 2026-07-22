CREATE OR REPLACE VIEW reference_read.childcare_center_fact AS
SELECT fact.fact_id AS center_id,
       fact.name AS center_name,
       fact.subcategory AS center_type,
       fact.status AS operating_status,
       fact.road_address,
       fact.lot_address,
       fact.attributes ->> 'address' AS address,
       (fact.attributes ->> 'capacity')::integer AS capacity,
       fact.region_code,
       fact.region_name,
       fact.latitude,
       fact.longitude,
       fact.row_reference_date AS reference_date,
       fact.dataset_observed_at AS observed_at,
       fact.dataset_version,
       fact.published_at,
       fact.freshness_days
FROM reference_read.facility_point_fact fact
WHERE fact.source_id = 'childcare.center'
  AND fact.category = 'CHILDCARE';

REVOKE ALL ON reference_read.childcare_center_fact FROM PUBLIC;
GRANT SELECT ON reference_read.childcare_center_fact TO home_search_ai_runtime;
