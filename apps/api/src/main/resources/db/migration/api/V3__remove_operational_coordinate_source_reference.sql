DO $$
DECLARE
    source_table text;
    has_rows boolean;
    occupied_table text;
BEGIN
    FOR source_table IN
        SELECT table_name
        FROM (
            VALUES
                ('reference.parcel_coordinate_snapshot'),
                ('reference.parcel_coordinate_snapshot_stage'),
                ('reference.parcel_coordinate_snapshot_publish'),
                ('reference.coordinate_snapshot_run'),
                ('reference.coordinate_snapshot_region_checkpoint'),
                ('reference.coordinate_snapshot_stage_chunk_checkpoint'),
                ('reference.coordinate_snapshot_publish_checkpoint'),
                ('reference.coordinate_snapshot_publish_chunk_checkpoint')
        ) AS source_tables(table_name)
    LOOP
        IF to_regclass(source_table) IS NOT NULL THEN
            EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s LIMIT 1)', source_table)
            INTO has_rows;
            IF has_rows THEN
                occupied_table = concat_ws(', ', occupied_table, source_table);
            END IF;
        END IF;
    END LOOP;

    IF occupied_table IS NOT NULL THEN
        RAISE EXCEPTION
            'Refusing to drop operational coordinate source tables with rows: %',
            occupied_table;
    END IF;
END $$;

DROP TABLE IF EXISTS reference.coordinate_snapshot_publish_chunk_checkpoint;
DROP TABLE IF EXISTS reference.coordinate_snapshot_publish_checkpoint;
DROP TABLE IF EXISTS reference.parcel_coordinate_snapshot_publish;
DROP TABLE IF EXISTS reference.coordinate_snapshot_stage_chunk_checkpoint;
DROP TABLE IF EXISTS reference.parcel_coordinate_snapshot_stage;
DROP TABLE IF EXISTS reference.coordinate_snapshot_region_checkpoint;
DROP TABLE IF EXISTS reference.parcel_coordinate_snapshot;
DROP TABLE IF EXISTS reference.coordinate_snapshot_run;
DROP SCHEMA IF EXISTS reference;
