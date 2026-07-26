\set ON_ERROR_STOP on

SELECT publication_id,status,expected_site_count,site_count,expected_building_count,building_count,
       expected_hierarchy_count,hierarchy_count,expected_evidence_count,evidence_count,
       expected_summary_count,summary_count,content_sha256,published_at
FROM building_register_profile_publication
WHERE publication_id=CAST(:'publication_id' AS uuid);

SELECT 'site' AS relation,count(*) AS row_count
FROM building_register_profile_site WHERE publication_id=CAST(:'publication_id' AS uuid)
UNION ALL
SELECT 'building',count(*) FROM building_register_profile_building
WHERE publication_id=CAST(:'publication_id' AS uuid)
UNION ALL
SELECT 'hierarchy',count(*) FROM building_register_profile_hierarchy
WHERE publication_id=CAST(:'publication_id' AS uuid)
UNION ALL
SELECT 'evidence',count(*) FROM building_register_profile_field_evidence
WHERE publication_id=CAST(:'publication_id' AS uuid)
UNION ALL
SELECT 'summary',count(*) FROM complex_building_register_profile_summary
WHERE publication_id=CAST(:'publication_id' AS uuid);

SELECT scope,count(DISTINCT field_id) AS field_identifier_count
FROM building_register_profile_field_evidence
WHERE publication_id=CAST(:'publication_id' AS uuid)
GROUP BY scope ORDER BY scope;

SELECT count(*) FILTER (WHERE ratio_quality='PNU_FALLBACK') AS pnu_ratio_fallback_count,
       count(*) FILTER (WHERE ratio_quality='PARTIAL') AS partial_ratio_count,
       count(*) FILTER (WHERE ratio_quality IS NOT NULL
                         AND building_coverage_rate IS NULL AND floor_area_ratio IS NULL) AS empty_ratio_section_count
FROM complex_building_register_profile_summary
WHERE publication_id=CAST(:'publication_id' AS uuid);

SELECT count(*) AS incomplete_sum_exposed_count
FROM building_register_profile_field_evidence
WHERE publication_id=CAST(:'publication_id' AS uuid)
  AND aggregation_method='SUM' AND conflict_status='INCOMPLETE'
  AND (public_scope IS NOT NULL OR quality IS NOT NULL);
