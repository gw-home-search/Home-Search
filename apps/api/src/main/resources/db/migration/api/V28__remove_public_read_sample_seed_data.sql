DELETE FROM trade_source_key_registry
WHERE source = 'RTMS'
  AND raw_ingest_id IN (90001, 90002)
  AND source_key IN ('sample-rtms-20251201', 'sample-rtms-20251215');

DELETE FROM trade
WHERE id IN (9001, 9002)
  AND source = 'RTMS'
  AND source_key IN ('sample-rtms-20251201', 'sample-rtms-20251215')
  AND complex_pk = 'COMPLEX-PK-501'
  AND apt_seq = 'APT-501';

DELETE FROM raw_trade_ingest
WHERE id IN (90001, 90002, 90003, 90004)
  AND source = 'RTMS'
  AND source_key IN ('sample-rtms-20251201', 'sample-rtms-20251215', 'sample-rtms-match-failed');

DELETE FROM complex
WHERE id = 501
  AND complex_pk = 'COMPLEX-PK-501'
  AND apt_seq = 'APT-501'
  AND name = 'Sample Apartment';

DELETE FROM parcel
WHERE id = 1001
  AND pnu = '1168010300101400001'
  AND address = 'Sample address'
  AND NOT EXISTS (
      SELECT 1
      FROM complex
      WHERE complex.parcel_id = parcel.id
  );

UPDATE region
SET name = CASE code
        WHEN '11' THEN '서울특별시'
        WHEN '11680' THEN '강남구'
        WHEN '11680103' THEN '개포동'
        ELSE name
    END,
    updated_at = now()
WHERE code IN ('11', '11680', '11680103')
  AND name IN ('Seoul', 'Gangnam-gu', 'Sample-dong');

UPDATE parcel
SET address = regexp_replace(address, '^Sample-dong(\s*)', '개포동\1'),
    updated_at = now()
WHERE pnu LIKE '11680103%'
  AND address LIKE 'Sample-dong%';
