from __future__ import annotations

from collections.abc import Iterator

import psycopg
import pytest
from testcontainers.postgres import PostgresContainer


@pytest.fixture(scope="session")
def property_postgres_dsn() -> Iterator[str]:
    with PostgresContainer("postgis/postgis:16-3.4") as postgres:
        dsn = postgres.get_connection_url().replace("postgresql+psycopg2", "postgresql")
        with psycopg.connect(dsn) as connection:
            connection.execute("CREATE EXTENSION IF NOT EXISTS postgis")
            connection.execute("CREATE SCHEMA ai_read")
            connection.execute(
                """
                CREATE TABLE ai_read.region_fact (
                    region_id bigint PRIMARY KEY,
                    parent_region_id bigint,
                    region_code text NOT NULL UNIQUE,
                    region_name text NOT NULL,
                    region_type text NOT NULL
                );
                CREATE TABLE ai_read.complex_fact (
                    complex_id bigint PRIMARY KEY,
                    display_name text NOT NULL,
                    name text,
                    trade_name text,
                    region_code text,
                    region_name text,
                    address text,
                    latitude numeric,
                    longitude numeric,
                    marker_safe boolean NOT NULL,
                    data_updated_at timestamptz NOT NULL,
                    unit_count integer,
                    use_date date
                );
                CREATE TABLE ai_read.complex_search_fact (
                    complex_id bigint PRIMARY KEY,
                    display_name text NOT NULL,
                    canonical_name text NOT NULL,
                    trade_name text,
                    canonical_search_name text NOT NULL,
                    aliases text[] NOT NULL,
                    alias_search_names text[] NOT NULL,
                    region_code text,
                    region_name text,
                    address text,
                    search_document text NOT NULL,
                    unit_count integer,
                    use_date date,
                    marker_safe boolean NOT NULL,
                    data_updated_at timestamptz NOT NULL
                );
                CREATE TABLE ai_read.trade_fact (
                    trade_id bigint PRIMARY KEY,
                    complex_id bigint NOT NULL,
                    deal_date date NOT NULL,
                    deal_amount_ten_thousand_krw bigint NOT NULL,
                    exclusive_area_square_meters numeric NOT NULL,
                    floor integer
                );
                INSERT INTO ai_read.complex_fact VALUES
                    (1, '잠실동 잠실엘스', '잠실엘스', '잠실엘스', '11710101', '잠실동',
                     '서울 송파구 잠실동 19', 37.513, 127.082, true, '2026-07-16T00:00:00Z', 5678, '2008-09-30'),
                    (2, '강남동 A_타워', 'A_타워', 'A_타워', '11680101', '강남동',
                     '서울 강남구 강남동 1', 37.50, 127.03, true, '2026-07-16T00:00:00Z', 100, '2010-01-01'),
                    (3, '강남동 AB타워', 'AB타워', 'AB타워', '11680101', '강남동',
                     '서울 강남구 강남동 2', 37.51, 127.04, true, '2026-07-16T00:00:00Z', 200, '2011-01-01');
                INSERT INTO ai_read.complex_search_fact VALUES
                    (1, '잠실동 잠실엘스', '잠실엘스', '잠실엘스', '잠실엘스', '{}', '{}',
                     '11710101', '잠실동', '서울 송파구 잠실동 19', '잠실동잠실엘스 서울송파구잠실동19',
                     5678, '2008-09-30', true, '2026-07-16T00:00:00Z'),
                    (2, '강남동 A_타워', 'A_타워', 'A_타워', 'a타워', '{}', '{}',
                     '11680101', '강남동', '서울 강남구 강남동 1', '강남동a타워 서울강남구강남동1',
                     100, '2010-01-01', true, '2026-07-16T00:00:00Z'),
                    (3, '강남동 AB타워', 'AB타워', 'AB타워', 'ab타워', '{}', '{}',
                     '11680101', '강남동', '서울 강남구 강남동 2', '강남동ab타워 서울강남구강남동2',
                     200, '2011-01-01', true, '2026-07-16T00:00:00Z');
                INSERT INTO ai_read.region_fact VALUES
                    (11, NULL, '11', '서울특별시', 'si-do'),
                    (11710, 11, '11710', '송파구', 'si-gun-gu'),
                    (11710101, 11710, '11710101', '잠실동', 'eup-myeon-dong');
                INSERT INTO ai_read.trade_fact VALUES
                    (11, 1, '2026-01-01', 240000, 84.1, 10),
                    (12, 1, '2026-01-31', 250000, 84.9, 12),
                    (13, 1, '2026-02-01', 260000, 86.1, 15),
                    (14, 1, '2026-02-15', 270000, 84.0, 18);
                """
            )
        yield dsn
