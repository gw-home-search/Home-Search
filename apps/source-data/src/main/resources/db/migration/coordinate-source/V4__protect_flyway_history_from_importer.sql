DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'home_search_coordinate_importer') THEN
        REVOKE INSERT, UPDATE, DELETE, TRUNCATE
            ON TABLE reference.flyway_schema_history
            FROM home_search_coordinate_importer;
        GRANT SELECT
            ON TABLE reference.flyway_schema_history
            TO home_search_coordinate_importer;
    END IF;
END $$;
