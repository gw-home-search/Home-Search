CREATE OR REPLACE VIEW reference_read.sbiz_academy_fact AS
SELECT fact.fact_id AS sbiz_fact_id,
       fact.name,
       fact.subcategory AS small_category_code,
       fact.status,
       fact.road_address,
       fact.lot_address,
       fact.region_code,
       fact.latitude,
       fact.longitude,
       fact.position,
       fact.dataset_version,
       fact.dataset_observed_at AS observed_at,
       match.registry_fact_id,
       CASE WHEN match.registry_fact_id IS NULL THEN 'UNMATCHED' ELSE 'EXACT' END AS registry_match,
       registry.name AS registry_academy_name,
       registry.status AS registry_status,
       registry_publication.dataset_version AS registry_dataset_version,
       registry_publication.observed_at AS registry_observed_at
FROM reference_read.facility_point_fact fact
LEFT JOIN reference_projection.academy_exact_match match
  ON match.sbiz_publication_id = fact.publication_id
 AND match.sbiz_fact_id = fact.fact_id
LEFT JOIN reference_projection.registry_fact registry
  ON registry.publication_id = match.registry_publication_id
 AND registry.fact_id = match.registry_fact_id
LEFT JOIN dataset_publication registry_publication
  ON registry_publication.publication_id = match.registry_publication_id
WHERE fact.source_id = 'place.sbiz-academy';

REVOKE ALL ON reference_read.sbiz_academy_fact FROM PUBLIC;
GRANT SELECT ON reference_read.sbiz_academy_fact TO home_search_ai_runtime;
