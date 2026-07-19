CREATE OR REPLACE VIEW reference_read.academy_registry_summary AS
SELECT registry.attributes ->> 'educationOfficeCode' AS education_office_code,
       registry.attributes ->> 'educationOfficeName' AS education_office_name,
       registry.region_name AS district_name,
       registry.subcategory AS academy_type,
       registry.status,
       count(*)::bigint AS registry_count,
       metadata.dataset_version,
       metadata.observed_at,
       metadata.published_at,
       metadata.freshness_days
FROM reference_projection.registry_fact registry
JOIN reference_read.active_source_metadata metadata
  ON metadata.publication_id = registry.publication_id
WHERE registry.source_id = 'edu.academy-registry'
GROUP BY registry.attributes ->> 'educationOfficeCode',
         registry.attributes ->> 'educationOfficeName',
         registry.region_name, registry.subcategory, registry.status,
         metadata.dataset_version, metadata.observed_at,
         metadata.published_at, metadata.freshness_days;

REVOKE ALL ON reference_read.academy_registry_summary FROM PUBLIC;
GRANT SELECT ON reference_read.academy_registry_summary TO home_search_ai_runtime;
