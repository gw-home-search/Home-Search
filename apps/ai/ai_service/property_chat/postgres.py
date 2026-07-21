from __future__ import annotations

from datetime import date
from decimal import Decimal
import re
from threading import Lock
from time import monotonic

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from .models import (
    AdministrativeRegionContext,
    ComplexRecord,
    MonthlyTrendRecord,
    TradeRecord,
)

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

    def find_complexes(
        self, name: str, region_name: str | None, limit: int
    ) -> list[ComplexRecord]:
        normalized_name = name.strip()
        if not normalized_name or len(normalized_name) > 100:
            raise ValueError("complex name is outside the supported range")
        if not 1 <= limit <= 6:
            raise ValueError("complex lookup limit is outside the supported range")
        name_pattern = f"%{_escape_like(normalized_name)}%"
        region_pattern = (
            f"%{_escape_like(region_name.strip())}%" if region_name is not None else None
        )
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                SELECT complex_id, display_name, region_code, region_name, address,
                       latitude, longitude, marker_safe, data_updated_at,
                       unit_count, use_date
                FROM ai_read.complex_fact
                WHERE (
                    display_name ILIKE %s ESCAPE '\\'
                    OR name ILIKE %s ESCAPE '\\'
                    OR trade_name ILIKE %s ESCAPE '\\'
                )
                  AND (%s::text IS NULL OR region_name ILIKE %s ESCAPE '\\')
                ORDER BY
                    CASE WHEN lower(display_name) = lower(%s) THEN 0 ELSE 1 END,
                    display_name,
                    complex_id
                LIMIT %s
                """,
                (
                    name_pattern,
                    name_pattern,
                    name_pattern,
                    region_pattern,
                    region_pattern,
                    normalized_name,
                    limit,
                ),
            ).fetchall()
        return [_complex_record(row) for row in rows]

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
        region_pattern = (
            f"%{_escape_like(region_name.strip())}%" if region_name is not None else None
        )
        with self._pool.connection() as connection:
            rows = connection.execute(
                """
                WITH requested AS (
                    SELECT requested_name, ordinal
                    FROM unnest(%s::text[]) WITH ORDINALITY AS value(requested_name, ordinal)
                ), matches AS (
                    SELECT requested.requested_name, requested.ordinal,
                           fact.complex_id, fact.display_name, fact.region_code,
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
                    WHERE (%s::text IS NULL OR fact.region_name ILIKE %s ESCAPE '\\')
                )
                SELECT * FROM matches
                WHERE match_rank <= %s
                ORDER BY ordinal, match_rank
                """,
                (list(normalized_names), region_pattern, region_pattern, limit_per_name),
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
    )


def _optional_decimal(value: float | None) -> Decimal | None:
    return Decimal(str(value)) if value is not None else None


def _optional_float(value: Decimal | None) -> float | None:
    return float(value) if value is not None else None


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
