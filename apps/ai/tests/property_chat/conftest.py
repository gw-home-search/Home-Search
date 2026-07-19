from __future__ import annotations

from collections.abc import Iterator

import psycopg
import pytest
from testcontainers.postgres import PostgresContainer


@pytest.fixture(scope="session")
def property_postgres_dsn() -> Iterator[str]:
    with PostgresContainer("postgres:16-alpine") as postgres:
        dsn = postgres.get_connection_url().replace("postgresql+psycopg2", "postgresql")
        with psycopg.connect(dsn) as connection:
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
                     '서울 송파구 잠실동 19', 37.513, 127.082, true, '2026-07-16T00:00:00Z'),
                    (2, '강남동 A_타워', 'A_타워', 'A_타워', '11680101', '강남동',
                     '서울 강남구 강남동 1', 37.50, 127.03, true, '2026-07-16T00:00:00Z'),
                    (3, '강남동 AB타워', 'AB타워', 'AB타워', '11680101', '강남동',
                     '서울 강남구 강남동 2', 37.51, 127.04, true, '2026-07-16T00:00:00Z');
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
