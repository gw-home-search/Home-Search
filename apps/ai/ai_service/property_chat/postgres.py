from __future__ import annotations

from datetime import date
from decimal import Decimal
import math
import re
from threading import Lock
from time import monotonic

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from .criteria_recommendation import CriteriaCandidateScope
from .models import (
    AdministrativeRegionContext,
    ComplexRecord,
    MonthlyTrendRecord,
    TradeRecord,
    QueryCapability,
)
from .candidate_selection import CandidateObservationSummary

_AREA_TOLERANCE_SQUARE_METERS = Decimal("1.0")
_FRESHNESS_CACHE_SECONDS = 300
_EARLIEST_SUPPORTED_TRADE_YEAR = 2006


class PostgresPropertyFactRepository:
    def __init__(
        self,
        dsn: str,
        *,
        expected_database: str = "home_search",
        expected_username: str = "home_search_ai_reader",
        min_pool_size: int = 1,
        max_pool_size: int = 5,
    ) -> None:
        if not dsn.strip():
            raise ValueError("property DSN is required")
        if not expected_database.strip():
            raise ValueError("expected database is required")
        if not expected_username.strip():
            raise ValueError("expected username is required")
        if not 1 <= min_pool_size <= max_pool_size <= 20:
            raise ValueError("property pool size is outside the supported range")
        self._pool = ConnectionPool(
            conninfo=dsn,
            min_size=min_pool_size,
            max_size=max_pool_size,
            check=ConnectionPool.check_connection,
            kwargs={
                "row_factory": dict_row,
                "options": "-c default_transaction_read_only=on -c statement_timeout=5000",
            },
            open=True,
        )
        self._freshness_lock = Lock()
        self._latest_trade_date_cache: date | None = None
        self._latest_trade_date_checked_at: float | None = None
        try:
            with self._pool.connection() as connection:
                if connection.info.dbname != expected_database:
                    raise ValueError("property DSN must target the expected database")
                if connection.info.user != expected_username:
                    raise ValueError("property DSN must use the AI reader role")
        except Exception:
            self._pool.close()
            raise

    def close(self) -> None:
        self._pool.close()

    def find_complex_by_id(self, complex_id: int) -> ComplexRecord | None:
        if isinstance(complex_id, bool) or complex_id <= 0:
            raise ValueError("complex id must be positive")
        with self._pool.connection() as connection:
            row = connection.execute(
                """
                SELECT complex_id, parcel_id, display_name, region_code, region_name, address,
                       latitude, longitude, marker_safe, data_updated_at,
                       unit_count, use_date
                FROM ai_read.complex_fact
                WHERE complex_id = %s
                """,
                (complex_id,),
            ).fetchone()
        return _complex_record(row) if row is not None else None

    def find_complexes(
        self, name: str, region_name: str | None, limit: int
    ) -> list[ComplexRecord]:
        normalized_name = name.strip()
        if not normalized_name or len(normalized_name) > 100:
            raise ValueError("complex name is outside the supported range")
        if not 1 <= limit <= 6:
            raise ValueError("complex lookup limit is outside the supported range")
        literal_name_pattern = f"%{_escape_like(normalized_name)}%"
        compact_name_pattern = f"%{_escape_like(re.sub(r'\s+', '', normalized_name))}%"
        region_pattern = (
            f"%{_escape_like(region_name.strip())}%" if region_name is not None else None
        )
        with self._pool.connection() as connection:
            literal_rows = connection.execute(
                """
                SELECT complex_id, parcel_id, display_name, region_code, region_name, address,
                       latitude, longitude, marker_safe, data_updated_at,
                       unit_count, use_date
                FROM ai_read.complex_fact
                WHERE (
                    display_name ILIKE %s ESCAPE '\\'
                    OR name ILIKE %s ESCAPE '\\'
                    OR trade_name ILIKE %s ESCAPE '\\'
                    OR regexp_replace(display_name, '[[:space:]]+', '', 'g')
                        ILIKE %s ESCAPE '\\'
                    OR regexp_replace(name, '[[:space:]]+', '', 'g')
                        ILIKE %s ESCAPE '\\'
                    OR regexp_replace(trade_name, '[[:space:]]+', '', 'g')
                        ILIKE %s ESCAPE '\\'
                )
                  AND (%s::text IS NULL OR region_name ILIKE %s ESCAPE '\\'
                       OR address ILIKE %s ESCAPE '\\')
                ORDER BY
                    CASE
                        WHEN lower(display_name) = lower(%s)
                          OR lower(name) = lower(%s)
                          OR lower(trade_name) = lower(%s) THEN 0
                        ELSE 1
                    END,
                    display_name,
                    complex_id
                LIMIT %s
                """,
                (
                    literal_name_pattern,
                    literal_name_pattern,
                    literal_name_pattern,
                    compact_name_pattern,
                    compact_name_pattern,
                    compact_name_pattern,
                    region_pattern,
                    region_pattern,
                    region_pattern,
                    normalized_name,
                    normalized_name,
                    normalized_name,
                    limit,
                ),
            ).fetchall()
        if literal_rows:
            return [_complex_record(row) for row in literal_rows]

        search_tokens = _complex_search_tokens(normalized_name)
        if not search_tokens:
            return []
        requires_literal_name_match = "%" in normalized_name or "_" in normalized_name
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                SELECT search.complex_id, base.parcel_id, search.display_name, search.region_code,
                       search.region_name, search.address, base.latitude, base.longitude,
                       search.marker_safe, search.data_updated_at,
                       search.unit_count, search.use_date,
                       CASE
                           WHEN lower(search.display_name) = lower(%s)
                             OR lower(search.canonical_name) = lower(%s)
                             OR lower(search.trade_name) = lower(%s) THEN 0
                           WHEN search.canonical_search_name = %s THEN 1
                           WHEN %s = ANY(search.alias_search_names) THEN 2
                           ELSE 3
                       END AS match_tier
                FROM ai_read.complex_search_fact search
                JOIN ai_read.complex_fact base ON base.complex_id = search.complex_id
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM unnest(%s::text[]) token(value)
                    WHERE search.search_document NOT LIKE ('%%' || token.value || '%%')
                )
                  AND (
                      %s::boolean = false
                      OR search.display_name ILIKE %s ESCAPE '\\'
                      OR search.canonical_name ILIKE %s ESCAPE '\\'
                      OR search.trade_name ILIKE %s ESCAPE '\\'
                  )
                  AND (%s::text IS NULL OR search.region_name ILIKE %s ESCAPE '\\'
                       OR search.address ILIKE %s ESCAPE '\\')
                ORDER BY
                    CASE
                        WHEN lower(search.display_name) = lower(%s)
                          OR lower(search.canonical_name) = lower(%s)
                          OR lower(search.trade_name) = lower(%s) THEN 0
                        WHEN search.canonical_search_name = %s THEN 1
                        WHEN %s = ANY(search.alias_search_names) THEN 2
                        ELSE 3
                    END,
                    search.display_name,
                    search.complex_id
                LIMIT %s
                """,
                (
                    normalized_name,
                    normalized_name,
                    normalized_name,
                    "".join(search_tokens),
                    "".join(search_tokens),
                    list(search_tokens),
                    requires_literal_name_match,
                    literal_name_pattern,
                    literal_name_pattern,
                    literal_name_pattern,
                    region_pattern,
                    region_pattern,
                    region_pattern,
                    normalized_name,
                    normalized_name,
                    normalized_name,
                    "".join(search_tokens),
                    "".join(search_tokens),
                    limit,
                ),
            ).fetchall()
        return [_complex_record(row) for row in rows]

    def complex_profile(self, complex_id: int) -> dict[str, object] | None:
        if isinstance(complex_id, bool) or not isinstance(complex_id, int) or complex_id <= 0:
            raise ValueError("complex id must be positive")
        with self._pool.connection() as connection:
            row = connection.execute(
                """
                SELECT ratio_scope, ratio_quality, building_coverage_rate,
                       floor_area_ratio, household_scope, household_quality,
                       household_count, family_count, unit_count,
                       parking_scope, parking_quality, total_parking_count,
                       parking_per_household, building_scope, building_quality,
                       main_building_count, max_ground_floor_count,
                       max_underground_floor_count, max_height_m,
                       elevator_scope, elevator_quality, ride_elevator_count,
                       emergency_elevator_count, safety_scope, safety_quality,
                       seismic_design_status, date_scope, date_quality,
                       permit_date, construction_start_date, use_approval_date,
                       address_scope, address_quality, parcel_address, road_address,
                       energy_scope, energy_quality, energy_efficiency_grades,
                       data_updated_at
                FROM ai_read.complex_profile_fact
                WHERE complex_id = %s
                """,
                (complex_id,),
            ).fetchone()
        if row is None:
            return None
        return {key: _profile_value(value) for key, value in dict(row).items()}

    def find_complexes_batch(
        self,
        names: tuple[str, ...],
        region_name: str | None,
        limit_per_name: int,
    ) -> dict[str, tuple[ComplexRecord, ...]]:
        if (
            not 2 <= len(names) <= 4
            or len(names) != len(set(names))
            or any(not name.strip() or len(name.strip()) > 100 for name in names)
            or not 1 <= limit_per_name <= 6
        ):
            raise ValueError("comparison complex lookup is outside the supported range")
        normalized_names = tuple(name.strip() for name in names)
        normalized_region = region_name.strip() if region_name is not None else None
        if normalized_region is not None and not 1 <= len(normalized_region) <= 100:
            raise ValueError("comparison region lookup is outside the supported range")
        region_pattern = (
            f"%{_escape_like(normalized_region)}%"
            if normalized_region is not None
            else None
        )
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                WITH RECURSIVE matched_roots AS (
                    SELECT region_id, parent_region_id, region_code
                    FROM ai_read.region_fact
                    WHERE lower(region_name) = lower(%s)
                ), root_count AS (
                    SELECT count(*)::integer AS match_count FROM matched_roots
                ), target_regions AS (
                    SELECT region_id, region_code
                    FROM matched_roots
                    WHERE (SELECT match_count FROM root_count) = 1
                    UNION ALL
                    SELECT child.region_id, child.region_code
                    FROM ai_read.region_fact child
                    JOIN target_regions parent
                      ON child.parent_region_id = parent.region_id
                ), requested AS (
                    SELECT requested_name, ordinal
                    FROM unnest(%s::text[]) WITH ORDINALITY AS value(requested_name, ordinal)
                ), matches AS (
                    SELECT requested.requested_name, requested.ordinal,
                           fact.complex_id, fact.parcel_id, fact.display_name, fact.region_code,
                           fact.region_name, fact.address, fact.latitude, fact.longitude,
                           fact.marker_safe, fact.data_updated_at, fact.unit_count, fact.use_date,
                           row_number() OVER (
                               PARTITION BY requested.ordinal
                               ORDER BY CASE
                                   WHEN lower(fact.display_name) = lower(requested.requested_name)
                                     OR lower(fact.name) = lower(requested.requested_name)
                                     OR lower(fact.trade_name) = lower(requested.requested_name)
                                   THEN 0 ELSE 1 END,
                                   fact.display_name, fact.complex_id
                           ) AS match_rank
                    FROM requested
                    JOIN ai_read.complex_fact fact ON (
                        fact.display_name ILIKE ('%%' || replace(replace(replace(
                            requested.requested_name, '\\', '\\\\'), '%%', '\\%%'), '_', '\\_') || '%%') ESCAPE '\\'
                        OR fact.name ILIKE ('%%' || replace(replace(replace(
                            requested.requested_name, '\\', '\\\\'), '%%', '\\%%'), '_', '\\_') || '%%') ESCAPE '\\'
                        OR fact.trade_name ILIKE ('%%' || replace(replace(replace(
                            requested.requested_name, '\\', '\\\\'), '%%', '\\%%'), '_', '\\_') || '%%') ESCAPE '\\'
                    )
                    WHERE (
                        %s::text IS NULL
                        OR fact.region_name ILIKE %s ESCAPE '\\'
                        OR fact.address ILIKE %s ESCAPE '\\'
                        OR EXISTS (
                            SELECT 1 FROM target_regions region
                            WHERE region.region_code = fact.region_code
                        )
                    )
                )
                SELECT * FROM matches
                WHERE match_rank <= %s
                ORDER BY ordinal, match_rank
                """,
                (
                    normalized_region,
                    list(normalized_names),
                    region_pattern,
                    region_pattern,
                    region_pattern,
                    limit_per_name,
                ),
            ).fetchall()
        result: dict[str, list[ComplexRecord]] = {name: [] for name in normalized_names}
        for row in rows:
            result[str(row["requested_name"])].append(_complex_record(row))
        return {name: tuple(records) for name, records in result.items()}

    def recent_trades_batch(
        self,
        complex_ids: tuple[int, ...],
        start_date: date,
        end_date: date,
        exclusive_area_square_meters: float,
        limit_per_complex: int,
    ) -> dict[int, tuple[TradeRecord, ...]]:
        if (
            not 2 <= len(complex_ids) <= 4
            or len(complex_ids) != len(set(complex_ids))
            or any(complex_id <= 0 for complex_id in complex_ids)
            or not 1 <= limit_per_complex <= 3
        ):
            raise ValueError("comparison trade query is outside the supported range")
        _validate_trade_query(
            complex_ids[0], start_date, end_date, exclusive_area_square_meters
        )
        area = Decimal(str(exclusive_area_square_meters))
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                WITH ranked AS (
                    SELECT trade_id, complex_id, deal_date,
                           deal_amount_ten_thousand_krw,
                           exclusive_area_square_meters, floor,
                           row_number() OVER (
                               PARTITION BY complex_id ORDER BY deal_date DESC, trade_id DESC
                           ) AS trade_rank
                    FROM ai_read.trade_fact
                    WHERE complex_id = ANY(%s::bigint[])
                      AND deal_date >= %s AND deal_date <= %s
                      AND exclusive_area_square_meters BETWEEN %s - %s AND %s + %s
                )
                SELECT * FROM ranked WHERE trade_rank <= %s
                ORDER BY complex_id, trade_rank
                """,
                (
                    list(complex_ids), start_date, end_date, area,
                    _AREA_TOLERANCE_SQUARE_METERS, area,
                    _AREA_TOLERANCE_SQUARE_METERS, limit_per_complex,
                ),
            ).fetchall()
        result: dict[int, list[TradeRecord]] = {complex_id: [] for complex_id in complex_ids}
        for row in rows:
            result[int(row["complex_id"])].append(_trade_record(row))
        return {complex_id: tuple(trades) for complex_id, trades in result.items()}

    def candidate_observation_summaries(
        self,
        complex_ids: tuple[int, ...],
        start_date: date | None,
        end_date: date | None,
        exclusive_area_square_meters: float | None,
        capability: QueryCapability,
    ) -> tuple[CandidateObservationSummary, ...]:
        if (
            not 1 <= len(complex_ids) <= 6
            or len(complex_ids) != len(set(complex_ids))
            or any(isinstance(value, bool) or value <= 0 for value in complex_ids)
            or capability not in {"recent_trade_lookup", "price_trend"}
        ):
            raise ValueError("candidate observation query is outside the supported range")
        _validate_trade_query(
            complex_ids[0], start_date, end_date, exclusive_area_square_meters
        )
        area = _optional_decimal(exclusive_area_square_meters)
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                SELECT requested.complex_id,
                       count(trade.trade_id)::integer AS observation_count,
                       max(trade.deal_date) AS latest_observation_date
                FROM unnest(%s::bigint[]) requested(complex_id)
                LEFT JOIN ai_read.trade_fact trade
                  ON trade.complex_id = requested.complex_id
                 AND (%s::date IS NULL OR trade.deal_date >= %s)
                 AND (%s::date IS NULL OR trade.deal_date <= %s)
                 AND (
                    %s::numeric IS NULL
                    OR trade.exclusive_area_square_meters BETWEEN %s - %s AND %s + %s
                 )
                GROUP BY requested.complex_id
                ORDER BY array_position(%s::bigint[], requested.complex_id)
                """,
                (
                    list(complex_ids), start_date, start_date, end_date, end_date,
                    area, area, _AREA_TOLERANCE_SQUARE_METERS,
                    area, _AREA_TOLERANCE_SQUARE_METERS, list(complex_ids),
                ),
            ).fetchall()
        return tuple(
            CandidateObservationSummary(
                complex_id=int(row["complex_id"]),
                exact_observation_count=int(row["observation_count"]),
                latest_observation_date=row["latest_observation_date"],
                supported_capabilities=(capability,)
                if int(row["observation_count"]) > 0 else (),
            )
            for row in rows
        )

    def recommendation_candidates(
        self,
        region_name: str,
        start_date: date,
        end_date: date,
        exclusive_area_square_meters: float,
        limit: int,
    ) -> dict[int, tuple[ComplexRecord, tuple[TradeRecord, ...]]] | None:
        normalized_region = region_name.strip()
        if (
            not normalized_region
            or len(normalized_region) > 100
            or not 1 <= limit <= 5_000
        ):
            raise ValueError("recommendation candidate query is outside the supported range")
        _validate_trade_query(
            1, start_date, end_date, exclusive_area_square_meters
        )
        area = Decimal(str(exclusive_area_square_meters))
        with self._pool.connection() as connection:
            region_rows = connection.execute(
                """
                WITH RECURSIVE matched_roots AS (
                    SELECT region_id, parent_region_id, region_code
                    FROM ai_read.region_fact
                    WHERE lower(region_name) = lower(%s)
                ), root_count AS (
                    SELECT count(*)::integer AS match_count FROM matched_roots
                ), target_regions AS (
                    SELECT region_id, region_code
                    FROM matched_roots
                    WHERE (SELECT match_count FROM root_count) = 1
                    UNION ALL
                    SELECT child.region_id, child.region_code
                    FROM ai_read.region_fact child
                    JOIN target_regions parent
                      ON child.parent_region_id = parent.region_id
                )
                SELECT root_count.match_count, target_regions.region_code
                FROM root_count
                LEFT JOIN target_regions ON root_count.match_count = 1
                ORDER BY target_regions.region_code
                """,
                (normalized_region,),
            ).fetchall()
            if not region_rows or int(region_rows[0]["match_count"]) != 1:
                return None
            region_codes = [
                str(row["region_code"])
                for row in region_rows
                if row["region_code"] is not None
            ]
            if not region_codes:
                return {}
            rows = connection.execute(
                """
                WITH candidate_complexes AS (
                    SELECT complex.complex_id, complex.parcel_id, complex.display_name,
                           complex.region_code, complex.region_name, complex.address,
                           complex.latitude, complex.longitude, complex.marker_safe,
                           complex.data_updated_at, complex.unit_count, complex.use_date
                    FROM ai_read.complex_fact complex
                    WHERE complex.region_code = ANY(%s)
                      AND complex.marker_safe
                ), selected AS MATERIALIZED (
                    SELECT complex.*, trade.trade_id, trade.deal_date,
                           trade.deal_amount_ten_thousand_krw,
                           trade.exclusive_area_square_meters, trade.floor
                    FROM candidate_complexes complex
                    CROSS JOIN LATERAL (
                        SELECT trade.trade_id, trade.deal_date,
                               trade.deal_amount_ten_thousand_krw,
                               trade.exclusive_area_square_meters, trade.floor
                        FROM ai_read.trade_fact trade
                        WHERE trade.complex_id = complex.complex_id
                          AND trade.deal_date >= %s AND trade.deal_date <= %s
                          AND trade.exclusive_area_square_meters
                              BETWEEN %s - %s AND %s + %s
                        ORDER BY trade.deal_date DESC, trade.trade_id DESC
                        LIMIT 3
                    ) trade
                ), eligible AS (
                    SELECT complex_id
                    FROM selected
                    GROUP BY complex_id
                    HAVING count(*) = 3
                    ORDER BY complex_id
                    LIMIT %s
                )
                SELECT selected.*
                FROM selected
                JOIN eligible USING (complex_id)
                ORDER BY selected.complex_id,
                         selected.deal_date DESC, selected.trade_id DESC
                """,
                (
                    region_codes, start_date, end_date, area,
                    _AREA_TOLERANCE_SQUARE_METERS, area,
                    _AREA_TOLERANCE_SQUARE_METERS, limit,
                ),
            ).fetchall()
        result: dict[int, tuple[ComplexRecord, list[TradeRecord]]] = {}
        for row in rows:
            complex_id = int(row["complex_id"])
            if complex_id not in result:
                result[complex_id] = (_complex_record(row), [])
            result[complex_id][1].append(_trade_record(row))
        return {
            complex_id: (record, tuple(trades))
            for complex_id, (record, trades) in result.items()
        }

    def criteria_candidates(
        self, region_name: str, limit: int
    ) -> CriteriaCandidateScope | None:
        normalized_region = region_name.strip()
        if not normalized_region or len(normalized_region) > 100 or not 1 <= limit <= 5_000:
            raise ValueError("criteria candidate query is outside the supported range")
        leaf_region = normalized_region.rsplit(maxsplit=1)[-1]
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                WITH RECURSIVE region_leaves AS (
                    SELECT region_id, parent_region_id, region_code, region_name,
                           CASE WHEN lower(region_name) = lower(%s) THEN 0 ELSE 1 END AS priority
                    FROM ai_read.region_fact
                    WHERE lower(region_name) = lower(%s)
                       OR lower(regexp_replace(region_name, '(시|군|구)$', '')) = lower(%s)
                ), region_paths AS (
                    SELECT leaf.region_id AS root_region_id,
                           leaf.parent_region_id AS next_parent_region_id,
                           leaf.region_code AS root_region_code,
                           leaf.region_name AS root_region_name,
                           leaf.region_name::text AS full_path,
                           leaf.priority
                    FROM region_leaves leaf
                    UNION ALL
                    SELECT path.root_region_id, parent.parent_region_id,
                           path.root_region_code, path.root_region_name,
                           parent.region_name || ' ' || path.full_path,
                           path.priority
                    FROM region_paths path
                    JOIN ai_read.region_fact parent
                      ON parent.region_id = path.next_parent_region_id
                ), region_matches AS (
                    SELECT root_region_id AS region_id,
                           root_region_code AS region_code,
                           root_region_name AS region_name,
                           CASE
                               WHEN right(lower(' ' || full_path), char_length(' ' || %s))
                                    = lower(' ' || %s) THEN 0
                               WHEN position(' ' in %s) = 0 THEN priority
                               ELSE 2
                           END AS priority
                    FROM region_paths
                    WHERE next_parent_region_id IS NULL
                ), preferred_priority AS (
                    SELECT min(priority) AS priority
                    FROM region_matches
                    WHERE priority < 2
                ), matched_roots AS (
                    SELECT match.region_id, match.region_code, match.region_name
                    FROM region_matches match
                    JOIN preferred_priority preferred USING (priority)
                    WHERE match.priority < 2
                ), root_count AS (
                    SELECT count(*)::integer AS match_count FROM matched_roots
                ), target_regions AS (
                    SELECT region_id, region_code
                    FROM matched_roots
                    WHERE (SELECT match_count FROM root_count) = 1
                    UNION ALL
                    SELECT child.region_id, child.region_code
                    FROM ai_read.region_fact child
                    JOIN target_regions parent
                      ON child.parent_region_id = parent.region_id
                ), selected AS (
                    SELECT complex.complex_id, complex.parcel_id, complex.display_name,
                           complex.region_code, complex.region_name, complex.address,
                           complex.latitude, complex.longitude, complex.marker_safe,
                           complex.data_updated_at, complex.unit_count, complex.use_date
                    FROM ai_read.complex_fact complex
                    JOIN target_regions region
                      ON region.region_code = complex.region_code
                    WHERE complex.marker_safe
                    ORDER BY complex.complex_id
                    LIMIT %s
                )
                SELECT root_count.match_count, root.region_name AS scope_label, selected.*
                FROM root_count
                LEFT JOIN matched_roots root
                  ON (SELECT match_count FROM root_count) = 1
                LEFT JOIN selected ON true
                ORDER BY selected.complex_id
                """,
                (
                    leaf_region,
                    leaf_region,
                    re.sub(r"(시|군|구)$", "", leaf_region),
                    normalized_region,
                    normalized_region,
                    normalized_region,
                    limit,
                ),
            ).fetchall()
        if not rows or int(rows[0]["match_count"]) != 1:
            return None
        return CriteriaCandidateScope(
            scope_label=str(rows[0]["scope_label"]),
            candidates=tuple(
                _complex_record(row) for row in rows if row["complex_id"] is not None
            ),
        )

    def criteria_candidates_near_point(
        self,
        latitude: float,
        longitude: float,
        radius_meters: int,
        limit: int,
    ) -> tuple[ComplexRecord, ...]:
        if (
            isinstance(latitude, bool)
            or isinstance(longitude, bool)
            or not math.isfinite(latitude)
            or not math.isfinite(longitude)
            or not 33 <= latitude <= 39
            or not 124 <= longitude <= 132
            or not 300 <= radius_meters <= 2_000
            or not 1 <= limit <= 5_000
        ):
            raise ValueError("station criteria candidate query is outside the supported range")
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                WITH origin AS (
                    SELECT ST_SetSRID(ST_MakePoint(%s, %s), 4326)::geography AS point
                )
                SELECT complex.complex_id, complex.parcel_id, complex.display_name,
                       complex.region_code, complex.region_name, complex.address,
                       complex.latitude, complex.longitude, complex.marker_safe,
                       complex.data_updated_at, complex.unit_count, complex.use_date
                FROM ai_read.complex_fact complex
                CROSS JOIN origin
                WHERE complex.marker_safe
                  AND complex.latitude IS NOT NULL
                  AND complex.longitude IS NOT NULL
                  AND ST_DWithin(
                      ST_SetSRID(ST_MakePoint(
                          complex.longitude, complex.latitude
                      ), 4326)::geography,
                      origin.point,
                      %s + 0.001
                  )
                ORDER BY ST_Distance(
                    ST_SetSRID(ST_MakePoint(
                        complex.longitude, complex.latitude
                    ), 4326)::geography,
                    origin.point
                ), complex.complex_id
                LIMIT %s
                """,
                (longitude, latitude, radius_meters, limit),
            ).fetchall()
        return tuple(_complex_record(row) for row in rows)

    def recent_trades(
        self,
        complex_id: int,
        start_date: date | None,
        end_date: date | None,
        exclusive_area_square_meters: float | None,
        limit: int,
    ) -> list[TradeRecord]:
        _validate_trade_query(complex_id, start_date, end_date, exclusive_area_square_meters)
        if not 1 <= limit <= 10:
            raise ValueError("trade limit is outside the supported range")
        area = _optional_decimal(exclusive_area_square_meters)
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                SELECT trade_id, complex_id, deal_date,
                       deal_amount_ten_thousand_krw,
                       exclusive_area_square_meters, floor
                FROM ai_read.trade_fact
                WHERE complex_id = %s
                  AND (%s::date IS NULL OR deal_date >= %s)
                  AND (%s::date IS NULL OR deal_date <= %s)
                  AND (
                      %s::numeric IS NULL
                      OR exclusive_area_square_meters BETWEEN %s - %s AND %s + %s
                  )
                ORDER BY deal_date DESC, trade_id DESC
                LIMIT %s
                """,
                (
                    complex_id,
                    start_date,
                    start_date,
                    end_date,
                    end_date,
                    area,
                    area,
                    _AREA_TOLERANCE_SQUARE_METERS,
                    area,
                    _AREA_TOLERANCE_SQUARE_METERS,
                    limit,
                ),
            ).fetchall()
        return [_trade_record(row) for row in rows]

    def resolve_region_context(
        self, region_code: str
    ) -> AdministrativeRegionContext | None:
        if re.fullmatch(r"[0-9]{2,10}", region_code) is None:
            raise ValueError("region code is outside the supported range")
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                WITH RECURSIVE ancestors AS (
                    SELECT region_id, parent_region_id, region_code,
                           region_name, region_type
                    FROM ai_read.region_fact
                    WHERE region_code = %s
                    UNION
                    SELECT parent.region_id, parent.parent_region_id,
                           parent.region_code, parent.region_name, parent.region_type
                    FROM ai_read.region_fact parent
                    JOIN ancestors child ON child.parent_region_id = parent.region_id
                )
                SELECT region_name, region_type
                FROM ancestors
                WHERE region_type IN ('si-do', 'si-gun-gu')
                """,
                (region_code,),
            ).fetchall()
        names_by_type = {str(row["region_type"]): str(row["region_name"]) for row in rows}
        if len(rows) != 2 or set(names_by_type) != {"si-do", "si-gun-gu"}:
            return None
        province_name = names_by_type["si-do"]
        return AdministrativeRegionContext(
            province_name=province_name,
            district_name=names_by_type["si-gun-gu"],
            education_office_name=f"{province_name}교육청",
        )

    def monthly_trends(
        self,
        complex_id: int,
        start_date: date,
        end_date: date,
        exclusive_area_square_meters: float | None,
    ) -> list[MonthlyTrendRecord]:
        _validate_trade_query(complex_id, start_date, end_date, exclusive_area_square_meters)
        area = _optional_decimal(exclusive_area_square_meters)
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                SELECT date_trunc('month', deal_date)::date AS month,
                       round(avg(deal_amount_ten_thousand_krw))::bigint AS average_amount,
                       count(*)::integer AS trade_count,
                       min(deal_amount_ten_thousand_krw)::bigint AS minimum_amount,
                       max(deal_amount_ten_thousand_krw)::bigint AS maximum_amount
                FROM ai_read.trade_fact
                WHERE complex_id = %s
                  AND deal_date >= %s
                  AND deal_date <= %s
                  AND (
                      %s::numeric IS NULL
                      OR exclusive_area_square_meters BETWEEN %s - %s AND %s + %s
                  )
                GROUP BY date_trunc('month', deal_date)
                ORDER BY month
                """,
                (
                    complex_id,
                    start_date,
                    end_date,
                    area,
                    area,
                    _AREA_TOLERANCE_SQUARE_METERS,
                    area,
                    _AREA_TOLERANCE_SQUARE_METERS,
                ),
            ).fetchall()
        return [
            MonthlyTrendRecord(
                complex_id=complex_id,
                month=row["month"],
                average_amount_ten_thousand_krw=row["average_amount"],
                trade_count=row["trade_count"],
                minimum_amount_ten_thousand_krw=row["minimum_amount"],
                maximum_amount_ten_thousand_krw=row["maximum_amount"],
            )
            for row in rows
        ]

    def latest_trade_date(self) -> date | None:
        now = monotonic()
        with self._freshness_lock:
            if (
                self._latest_trade_date_checked_at is not None
                and now - self._latest_trade_date_checked_at
                < _FRESHNESS_CACHE_SECONDS
            ):
                return self._latest_trade_date_cache
            latest = self._load_latest_trade_date()
            self._latest_trade_date_cache = latest
            self._latest_trade_date_checked_at = now
            return latest

    def _load_latest_trade_date(self) -> date | None:
        with self._pool.connection() as connection:
            for year in range(date.today().year, _EARLIEST_SUPPORTED_TRADE_YEAR - 1, -1):
                row = connection.execute(
                    """
                    SELECT max(deal_date) AS latest_trade_date
                    FROM ai_read.trade_fact
                    WHERE deal_date >= %s AND deal_date < %s
                    """,
                    (date(year, 1, 1), date(year + 1, 1, 1)),
                ).fetchone()
                if row["latest_trade_date"] is not None:
                    return row["latest_trade_date"]
        return None


def _escape_like(value: str) -> str:
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


_COMPLEX_SEARCH_STOP_WORDS = frozenset({
    "아파트", "apt", "어때", "어떄", "어떤가요", "괜찮아", "괜찮나요", "살기",
})


def _complex_search_tokens(value: str) -> tuple[str, ...]:
    tokens: list[str] = []
    for raw_token in re.findall(r"[0-9A-Za-z가-힣]+", value.lower()):
        token = re.sub(r"(?:아파트|apt)$", "", raw_token).strip()
        if not token or token in _COMPLEX_SEARCH_STOP_WORDS:
            continue
        if token not in tokens:
            tokens.append(token)
    return tuple(tokens[:8])


def _complex_record(row: dict[str, object]) -> ComplexRecord:
    return ComplexRecord(
        complex_id=int(row["complex_id"]),
        display_name=str(row["display_name"]),
        region_code=str(row["region_code"]) if row["region_code"] is not None else None,
        region_name=str(row["region_name"]) if row["region_name"] is not None else None,
        address=str(row["address"]) if row["address"] is not None else None,
        latitude=_optional_float(row["latitude"]),  # type: ignore[arg-type]
        longitude=_optional_float(row["longitude"]),  # type: ignore[arg-type]
        marker_safe=bool(row["marker_safe"]),
        data_updated_at=row["data_updated_at"],  # type: ignore[arg-type]
        unit_count=int(row["unit_count"]) if row["unit_count"] is not None else None,
        use_date=row["use_date"],  # type: ignore[arg-type]
        parcel_id=(
            int(row["parcel_id"])
            if row.get("parcel_id") is not None else None
        ),
        match_tier=(
            int(row["match_tier"])
            if row.get("match_tier") is not None else 3
        ),
    )


def _optional_decimal(value: float | None) -> Decimal | None:
    return Decimal(str(value)) if value is not None else None


def _optional_float(value: Decimal | None) -> float | None:
    return float(value) if value is not None else None


def _profile_value(value: object) -> object:
    if isinstance(value, Decimal):
        return float(value)
    if isinstance(value, (date,)):
        return value.isoformat()
    if hasattr(value, "isoformat"):
        return value.isoformat()  # type: ignore[union-attr]
    return value


def _validate_trade_query(
    complex_id: int,
    start_date: date | None,
    end_date: date | None,
    exclusive_area_square_meters: float | None,
) -> None:
    if complex_id <= 0:
        raise ValueError("complex_id must be positive")
    if start_date and end_date and start_date > end_date:
        raise ValueError("start_date must not be after end_date")
    if exclusive_area_square_meters is not None and not (
        0 < exclusive_area_square_meters <= 1000
    ):
        raise ValueError("exclusive area is outside the supported range")


def _trade_record(row: dict[str, object]) -> TradeRecord:
    return TradeRecord(
        trade_id=int(row["trade_id"]),
        complex_id=int(row["complex_id"]),
        deal_date=row["deal_date"],  # type: ignore[arg-type]
        deal_amount_ten_thousand_krw=int(row["deal_amount_ten_thousand_krw"]),
        exclusive_area_square_meters=float(row["exclusive_area_square_meters"]),
        floor=int(row["floor"]) if row["floor"] is not None else None,
    )
