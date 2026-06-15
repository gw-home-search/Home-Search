INSERT INTO news_collection_keyword (
    query_text,
    keyword_type,
    priority,
    cadence,
    enabled,
    next_due_at
)
VALUES
    ('재건축', 'TOPIC', 150, 'DAILY', true, now()),
    ('주택 공급', 'TOPIC', 140, 'DAILY', true, now()),
    ('부동산 정책', 'TOPIC', 130, 'DAILY', true, now()),
    ('주택담보대출 금리', 'TOPIC', 120, 'DAILY', true, now()),
    ('교통 호재', 'TOPIC', 110, 'DAILY', true, now())
ON CONFLICT (keyword_type, query_text, source_table, source_id)
DO UPDATE SET
    priority = EXCLUDED.priority,
    cadence = EXCLUDED.cadence,
    enabled = EXCLUDED.enabled,
    updated_at = now();

INSERT INTO news_collection_keyword (
    query_text,
    keyword_type,
    priority,
    cadence,
    enabled,
    next_due_at,
    source_table,
    source_id
)
SELECT
    name,
    'REGION',
    100,
    'DAILY',
    true,
    now(),
    'region',
    id::text
FROM region
WHERE region_type = 'si-do'
ON CONFLICT (keyword_type, query_text, source_table, source_id)
DO UPDATE SET
    priority = EXCLUDED.priority,
    cadence = EXCLUDED.cadence,
    enabled = EXCLUDED.enabled,
    updated_at = now();

INSERT INTO news_collection_keyword (
    query_text,
    keyword_type,
    priority,
    cadence,
    enabled,
    next_due_at,
    source_table,
    source_id
)
SELECT
    sido.name || ' ' || sgg.name,
    'REGION',
    80,
    'WEEKLY',
    true,
    now() + ((sgg.id % 7) * interval '1 day'),
    'region',
    sgg.id::text
FROM region sgg
JOIN region sido ON sido.id = sgg.parent_id
WHERE sgg.region_type = 'si-gun-gu'
ON CONFLICT (keyword_type, query_text, source_table, source_id)
DO UPDATE SET
    priority = EXCLUDED.priority,
    cadence = EXCLUDED.cadence,
    enabled = EXCLUDED.enabled,
    updated_at = now();
