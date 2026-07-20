CREATE INDEX registry_fact_academy_region_summary_idx
    ON reference_projection.registry_fact (
        (attributes ->> 'educationOfficeName'),
        region_name,
        publication_id,
        (attributes ->> 'educationOfficeCode'),
        subcategory,
        status
    )
    WHERE source_id = 'edu.academy-registry';
