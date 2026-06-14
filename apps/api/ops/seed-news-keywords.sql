INSERT INTO news_collection_keyword (
    query_text,
    keyword_type,
    priority,
    cadence,
    enabled,
    next_due_at
)
VALUES
    ('강남구', 'REGION', 200, 'DAILY', true, now()),
    ('송파구', 'REGION', 190, 'DAILY', true, now()),
    ('서초구', 'REGION', 180, 'DAILY', true, now()),
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
    next_due_at = LEAST(news_collection_keyword.next_due_at, now()),
    updated_at = now();
