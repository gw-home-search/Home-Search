WITH parcel_address AS (
    SELECT
        p.id,
        r.id AS region_id,
        trim(concat(
            r.name,
            ' ',
            CASE WHEN substring(p.pnu FROM 11 FOR 1) = '2' THEN '산 ' ELSE '' END,
            parts.bun,
            CASE WHEN parts.ji IS NULL THEN '' ELSE '-' || parts.ji END
        )) AS address
    FROM parcel p
    JOIN LATERAL (
        SELECT candidate.id, candidate.name
        FROM region candidate
        WHERE candidate.id = p.region_id
           OR candidate.code IN (
               substring(p.pnu FROM 1 FOR 10),
               substring(p.pnu FROM 1 FOR 8)
           )
        ORDER BY CASE
            WHEN candidate.id = p.region_id THEN 0
            WHEN candidate.code = substring(p.pnu FROM 1 FOR 10) THEN 1
            ELSE 2
        END
        LIMIT 1
    ) r ON true
    CROSS JOIN LATERAL (
        SELECT
            NULLIF(ltrim(substring(p.pnu FROM 12 FOR 4), '0'), '') AS bun,
            NULLIF(ltrim(substring(p.pnu FROM 16 FOR 4), '0'), '') AS ji
    ) parts
    WHERE p.pnu ~ '^[0-9]{19}$'
      AND (p.region_id IS NULL OR p.address IS NULL)
      AND parts.bun IS NOT NULL
)
UPDATE parcel p
SET region_id = COALESCE(p.region_id, parcel_address.region_id),
    address = COALESCE(p.address, parcel_address.address),
    updated_at = now()
FROM parcel_address
WHERE p.id = parcel_address.id;

ALTER TABLE parcel
    ADD CONSTRAINT ck_parcel_pnu_19_digits
    CHECK (pnu ~ '^[0-9]{19}$') NOT VALID;
