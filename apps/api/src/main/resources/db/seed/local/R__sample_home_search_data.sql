INSERT INTO region (id, parent_id, code, name, region_type, center_lat, center_lng)
VALUES
    (1, NULL, '11', 'Seoul', 'si-do', 37.5663000, 126.9780000),
    (11, 1, '11680', 'Gangnam-gu', 'si-gun-gu', 37.5172000, 127.0473000),
    (111, 11, '11680103', 'Sample-dong', 'eup-myeon-dong', 37.5123000, 127.0456000)
ON CONFLICT (code) DO NOTHING;

INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
VALUES (1001, 111, '1168010300101400001', 'Sample address', 37.5123000, 127.0456000)
ON CONFLICT (pnu) DO NOTHING;

INSERT INTO complex (
    id,
    parcel_id,
    complex_pk,
    apt_seq,
    name,
    trade_name,
    dong_cnt,
    unit_cnt,
    plat_area,
    arch_area,
    tot_area,
    bc_rat,
    vl_rat,
    use_date,
    metadata_status,
    metadata_source,
    metadata_checked_at
)
VALUES (
    501,
    1001,
    'COMPLEX-PK-501',
    'APT-501',
    'Sample Apartment',
    'Sample trade name',
    8,
    740,
    12345.67,
    2345.67,
    98765.43,
    22.50,
    199.80,
    DATE '2015-03-20',
    'RESOLVED',
    'SEED',
    now()
)
ON CONFLICT (complex_pk) DO NOTHING;

INSERT INTO raw_trade_ingest (
    id,
    source,
    source_key,
    lawd_cd,
    deal_ymd,
    page_no,
    payload,
    payload_hash,
    status,
    failure_reason,
    processed_at
)
VALUES
    (
        90001,
        'RTMS',
        'sample-rtms-20251201',
        '11680',
        '202512',
        1,
        '{"aptSeq":"APT-501","dealDate":"2025-12-01"}',
        'sample-payload-hash-1',
        'NORMALIZED',
        NULL,
        now()
    ),
    (
        90002,
        'RTMS',
        'sample-rtms-20251215',
        '11680',
        '202512',
        1,
        '{"aptSeq":"APT-501","dealDate":"2025-12-15"}',
        'sample-payload-hash-2',
        'NORMALIZED',
        NULL,
        now()
    ),
    (
        90003,
        'RTMS',
        'sample-rtms-match-failed',
        '11680',
        '202512',
        1,
        '{"aptSeq":"APT-404"}',
        'sample-payload-hash-3',
        'MATCH_FAILED',
        'no complex matched aptSeq=APT-404',
        now()
    ),
    (
        90004,
        'RTMS',
        'sample-rtms-20251201',
        '11680',
        '202512',
        1,
        '{"aptSeq":"APT-501","dealDate":"2025-12-01"}',
        'sample-payload-hash-duplicate',
        'DUPLICATE',
        'duplicate source_key=sample-rtms-20251201',
        now()
    )
ON CONFLICT (id) DO NOTHING;

INSERT INTO trade (
    id,
    complex_id,
    deal_date,
    deal_amount,
    floor,
    excl_area,
    apt_dong,
    source,
    source_key,
    complex_pk,
    apt_seq,
    raw_ingest_id
)
VALUES
    (
        9001,
        501,
        DATE '2025-12-01',
        125000,
        12,
        84.93,
        '101',
        'RTMS',
        'sample-rtms-20251201',
        'COMPLEX-PK-501',
        'APT-501',
        90001
    ),
    (
        9002,
        501,
        DATE '2025-12-15',
        130000,
        15,
        84.93,
        '101',
        'RTMS',
        'sample-rtms-20251215',
        'COMPLEX-PK-501',
        'APT-501',
        90002
    )
ON CONFLICT DO NOTHING;

INSERT INTO trade_source_key_registry (source, source_key, raw_ingest_id, trade_id)
VALUES
    ('RTMS', 'sample-rtms-20251201', 90001, 9001),
    ('RTMS', 'sample-rtms-20251215', 90002, 9002)
ON CONFLICT (source, source_key) DO NOTHING;

SELECT setval(pg_get_serial_sequence('region', 'id'), (SELECT max(id) FROM region), true);
SELECT setval(pg_get_serial_sequence('parcel', 'id'), (SELECT max(id) FROM parcel), true);
SELECT setval(pg_get_serial_sequence('complex', 'id'), (SELECT max(id) FROM complex), true);
SELECT setval(pg_get_serial_sequence('raw_trade_ingest', 'id'), (SELECT max(id) FROM raw_trade_ingest), true);
SELECT setval('trade_id_seq', (SELECT max(id) FROM trade), true);
SELECT setval(
    pg_get_serial_sequence('trade_source_key_registry', 'id'),
    (SELECT max(id) FROM trade_source_key_registry),
    true
);
