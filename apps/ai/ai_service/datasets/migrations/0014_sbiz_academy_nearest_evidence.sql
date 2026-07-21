CREATE OR REPLACE VIEW reference_read.sbiz_academy_exact_match AS
SELECT match.sbiz_publication_id,
       match.sbiz_fact_id,
       match.registry_fact_id,
       registry.name AS registry_academy_name,
       registry.status AS registry_status,
       registry_publication.dataset_version AS registry_dataset_version,
       registry_publication.observed_at AS registry_observed_at
FROM reference_projection.academy_exact_match match
JOIN reference_read.active_source_metadata sbiz_metadata
  ON sbiz_metadata.publication_id = match.sbiz_publication_id
 AND sbiz_metadata.source_id = 'place.sbiz-academy'
JOIN reference_projection.registry_fact registry
  ON registry.publication_id = match.registry_publication_id
 AND registry.fact_id = match.registry_fact_id
JOIN dataset_publication registry_publication
  ON registry_publication.publication_id = match.registry_publication_id;

REVOKE ALL ON reference_read.sbiz_academy_exact_match FROM PUBLIC;
GRANT SELECT ON reference_read.sbiz_academy_exact_match TO home_search_ai_runtime;
