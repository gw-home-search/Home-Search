SET LOCAL lock_timeout = '5s';

CREATE TEMP TABLE hs_v8_complex_identity_before ON COMMIT DROP AS
SELECT
    count(*) AS row_count,
    md5(string_agg(concat_ws('|', id, name, trade_name), E'\n' ORDER BY id)) AS name_checksum,
    md5(string_agg(concat_ws('|', id, complex_pk, apt_seq), E'\n' ORDER BY id)) AS identity_checksum
FROM complex;

ALTER TABLE complex
    ADD COLUMN display_name varchar(255);

CREATE OR REPLACE FUNCTION hs_normalize_complex_search_name(value text)
RETURNS text
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $function$
    SELECT regexp_replace(lower(value), '[[:space:][:punct:]]+', '', 'g')
$function$;

CREATE OR REPLACE FUNCTION hs_escape_like_pattern(value text)
RETURNS text
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $function$
    SELECT replace(replace(replace(value, '\', '\\'), '%', '\%'), '_', '\_')
$function$;

CREATE OR REPLACE FUNCTION hs_complex_display_name(
    source_name text,
    source_trade_name text,
    source_region_id bigint
)
RETURNS text
LANGUAGE plpgsql
STABLE
AS $function$
DECLARE
    base_name text := COALESCE(NULLIF(btrim(source_trade_name), ''), btrim(source_name));
    locality text;
    name_without_locality text;
BEGIN
    SELECT btrim(region.name)
    INTO locality
    FROM region
    WHERE region.id = source_region_id
      AND region.region_type = 'eup-myeon-dong'
      AND NULLIF(btrim(region.name), '') IS NOT NULL;

    IF locality IS NULL THEN
        RETURN base_name;
    END IF;

    IF strpos(lower(base_name), lower(locality)) = 1 THEN
        name_without_locality := btrim(substring(base_name FROM char_length(locality) + 1));
        IF name_without_locality = '' THEN
            RETURN locality;
        END IF;
        RETURN locality || ' ' || name_without_locality;
    END IF;

    RETURN locality || ' ' || base_name;
END
$function$;

CREATE OR REPLACE FUNCTION hs_sync_complex_display_name()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    NEW.display_name := hs_complex_display_name(NEW.name, NEW.trade_name, NEW.region_id);
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_complex_display_name_sync
BEFORE INSERT OR UPDATE OF name, trade_name, region_id
ON complex
FOR EACH ROW
EXECUTE FUNCTION hs_sync_complex_display_name();

UPDATE complex
SET display_name = hs_complex_display_name(name, trade_name, region_id);

ALTER TABLE complex
    ADD COLUMN search_name varchar(255)
    GENERATED ALWAYS AS (hs_normalize_complex_search_name(display_name)) STORED;

CREATE OR REPLACE FUNCTION hs_sync_region_complex_display_names()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.name IS DISTINCT FROM OLD.name THEN
        UPDATE complex
        SET display_name = hs_complex_display_name(name, trade_name, region_id)
        WHERE region_id = NEW.id;
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_region_complex_display_name_sync
AFTER UPDATE OF name
ON region
FOR EACH ROW
EXECUTE FUNCTION hs_sync_region_complex_display_names();

DO $validation$
DECLARE
    before_state record;
    current_row_count bigint;
    current_name_checksum text;
    current_identity_checksum text;
    invalid_projection_count bigint;
BEGIN
    SELECT * INTO before_state FROM hs_v8_complex_identity_before;
    SELECT
        count(*),
        md5(string_agg(concat_ws('|', id, name, trade_name), E'\n' ORDER BY id)),
        md5(string_agg(concat_ws('|', id, complex_pk, apt_seq), E'\n' ORDER BY id))
    INTO current_row_count, current_name_checksum, current_identity_checksum
    FROM complex;

    SELECT count(*)
    INTO invalid_projection_count
    FROM complex
    WHERE display_name IS NULL
       OR btrim(display_name) = ''
       OR search_name IS NULL
       OR btrim(search_name) = '';

    IF before_state.row_count IS DISTINCT FROM current_row_count
       OR before_state.name_checksum IS DISTINCT FROM current_name_checksum
       OR before_state.identity_checksum IS DISTINCT FROM current_identity_checksum
       OR invalid_projection_count <> 0 THEN
        RAISE EXCEPTION
            'V8 complex projection validation failed: rows %, name checksum %, identity checksum %, invalid projections %',
            current_row_count,
            current_name_checksum,
            current_identity_checksum,
            invalid_projection_count;
    END IF;
END
$validation$;

ALTER TABLE complex
    ALTER COLUMN display_name SET NOT NULL,
    ADD CONSTRAINT ck_complex_display_name_nonblank CHECK (btrim(display_name) <> '');

CREATE INDEX ix_complex_display_name_lower_trgm
    ON complex USING gin (lower(display_name) gin_trgm_ops);

CREATE INDEX ix_complex_search_name_trgm
    ON complex USING gin (search_name gin_trgm_ops);
