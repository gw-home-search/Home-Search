CREATE TABLE reference_projection.academy_exact_match (
    sbiz_publication_id uuid NOT NULL REFERENCES dataset_publication(publication_id) ON DELETE RESTRICT,
    sbiz_fact_id text NOT NULL,
    registry_publication_id uuid NOT NULL REFERENCES dataset_publication(publication_id) ON DELETE RESTRICT,
    registry_fact_id text NOT NULL,
    PRIMARY KEY (sbiz_publication_id, sbiz_fact_id),
    FOREIGN KEY (sbiz_publication_id, sbiz_fact_id)
        REFERENCES reference_projection.facility_point(publication_id, fact_id) ON DELETE RESTRICT,
    FOREIGN KEY (registry_publication_id, registry_fact_id)
        REFERENCES reference_projection.registry_fact(publication_id, fact_id) ON DELETE RESTRICT
);

CREATE TRIGGER academy_exact_match_immutable
    BEFORE UPDATE OR DELETE ON reference_projection.academy_exact_match
    FOR EACH ROW EXECUTE FUNCTION reject_publication_mutation();

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
       CASE WHEN match.registry_fact_id IS NULL THEN 'UNMATCHED' ELSE 'EXACT' END AS registry_match
FROM reference_read.facility_point_fact fact
LEFT JOIN reference_projection.academy_exact_match match
  ON match.sbiz_publication_id = fact.publication_id
 AND match.sbiz_fact_id = fact.fact_id
WHERE fact.source_id = 'place.sbiz-academy';

GRANT SELECT, INSERT ON reference_projection.academy_exact_match TO home_search_ai_importer;
REVOKE ALL ON reference_projection.academy_exact_match FROM home_search_ai_runtime;
GRANT SELECT ON reference_read.sbiz_academy_fact TO home_search_ai_runtime;
