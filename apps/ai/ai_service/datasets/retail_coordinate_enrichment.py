from __future__ import annotations

import math
import os
import re
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Protocol
from uuid import UUID

import psycopg
from psycopg.rows import dict_row


SOURCE_ID = "retail.large-store"
_MAX_NON_SPATIAL_ROWS = 1_000
_PNU_PATTERN = re.compile(r"[0-9]{19}")
_DIRECT_DONG_SUFFIXES = ("동", "가")
_PARCEL_PATTERNS = (
    re.compile(
        r"^(산)?\s*([0-9]{1,4})번지\s*([0-9]{1,4})\s*호(?=$|\s|외|내)"
    ),
    re.compile(
        r"^(산)?\s*([0-9]{1,4})-([0-9]{1,4})(?:번지|호)?(?=$|\s|외|내)"
    ),
    re.compile(r"^(산)?\s*([0-9]{1,4})(?:번지|호)?(?=$|\s|외|내)"),
)


@dataclass(frozen=True)
class AdministrativeRegionPath:
    legal_dong_code: str
    sido_name: str
    sigungu_name: str
    eup_myeon_dong_name: str

    def __post_init__(self) -> None:
        if (
            not re.fullmatch(r"[0-9]{8}", self.legal_dong_code)
            or not self.sido_name.strip()
            or not self.sigungu_name.strip()
            or not self.eup_myeon_dong_name.endswith(_DIRECT_DONG_SUFFIXES)
        ):
            raise ValueError("administrative region path is not PNU-safe")


@dataclass(frozen=True)
class RetailRegistryFact:
    publication_id: UUID
    fact_id: str
    lot_address: str

    def __post_init__(self) -> None:
        if not self.fact_id.strip() or not self.lot_address.strip():
            raise ValueError("retail registry fact is incomplete")


@dataclass(frozen=True)
class ParcelCoordinate:
    pnu: str
    latitude: float
    longitude: float
    snapshot_version: str

    def __post_init__(self) -> None:
        if (
            _PNU_PATTERN.fullmatch(self.pnu) is None
            or not math.isfinite(self.latitude)
            or not math.isfinite(self.longitude)
            or not 33 <= self.latitude <= 39
            or not 124 <= self.longitude <= 132
            or not self.snapshot_version.strip()
        ):
            raise ValueError("parcel coordinate is invalid")


@dataclass(frozen=True)
class RetailCoordinateEnrichment:
    publication_id: UUID
    fact_id: str
    pnu: str
    latitude: float
    longitude: float
    coordinate_snapshot_version: str
    resolution_method: str
    resolved_at: datetime


@dataclass(frozen=True)
class RetailCoordinateEnrichmentReport:
    publication_id: UUID
    total_count: int
    spatial_count: int
    matched_count: int
    unresolved_count: int
    coordinate_coverage: float


class RetailEnrichmentTargetRepository(Protocol):
    def active_non_spatial_rows(
        self,
    ) -> tuple[UUID, int, int, tuple[RetailRegistryFact, ...]]: ...

    def publish(
        self,
        publication_id: UUID,
        enrichments: tuple[RetailCoordinateEnrichment, ...],
    ) -> int: ...


class PropertyRegionRepository(Protocol):
    def region_paths(self) -> tuple[AdministrativeRegionPath, ...]: ...


class CoordinateSnapshotRepository(Protocol):
    def coordinates(self, pnus: tuple[str, ...]) -> Mapping[str, ParcelCoordinate]: ...


class RetailCoordinateEnrichmentService:
    def __init__(
        self,
        *,
        target_repository: RetailEnrichmentTargetRepository,
        region_repository: PropertyRegionRepository,
        coordinate_repository: CoordinateSnapshotRepository,
        clock: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        self._target_repository = target_repository
        self._region_repository = region_repository
        self._coordinate_repository = coordinate_repository
        self._clock = clock

    def run(self) -> RetailCoordinateEnrichmentReport:
        publication_id, total_count, spatial_count, rows = (
            self._target_repository.active_non_spatial_rows()
        )
        if (
            total_count <= 0
            or not 0 <= spatial_count <= total_count
            or len(rows) > _MAX_NON_SPATIAL_ROWS
            or len({row.fact_id for row in rows}) != len(rows)
            or any(row.publication_id != publication_id for row in rows)
        ):
            raise RuntimeError("retail enrichment input is outside the bounded policy")
        regions = self._region_repository.region_paths()
        candidates = {
            row.fact_id: pnu
            for row in rows
            if (pnu := derive_exact_lot_pnu(row.lot_address, regions)) is not None
        }
        requested_pnus = tuple(sorted(set(candidates.values())))
        coordinates = self._coordinate_repository.coordinates(requested_pnus)
        if not set(coordinates).issubset(requested_pnus):
            raise RuntimeError("coordinate source returned an unrequested PNU")
        resolved_at = self._clock()
        if resolved_at.tzinfo is None:
            raise ValueError("resolved_at must be timezone-aware")
        enrichments = tuple(
            RetailCoordinateEnrichment(
                publication_id=publication_id,
                fact_id=row.fact_id,
                pnu=candidates[row.fact_id],
                latitude=coordinates[candidates[row.fact_id]].latitude,
                longitude=coordinates[candidates[row.fact_id]].longitude,
                coordinate_snapshot_version=(
                    coordinates[candidates[row.fact_id]].snapshot_version
                ),
                resolution_method="EXACT_LOT_PNU",
                resolved_at=resolved_at,
            )
            for row in rows
            if row.fact_id in candidates and candidates[row.fact_id] in coordinates
        )
        published_count = self._target_repository.publish(publication_id, enrichments)
        if published_count != len(enrichments):
            raise RuntimeError("retail enrichment publication is incomplete")
        enriched_spatial_count = spatial_count + len(enrichments)
        if enriched_spatial_count > total_count:
            raise RuntimeError("retail enrichment exceeds source row count")
        return RetailCoordinateEnrichmentReport(
            publication_id=publication_id,
            total_count=total_count,
            spatial_count=enriched_spatial_count,
            matched_count=len(enrichments),
            unresolved_count=len(rows) - len(enrichments),
            coordinate_coverage=enriched_spatial_count / total_count,
        )


def derive_exact_lot_pnu(
    lot_address: str,
    regions: tuple[AdministrativeRegionPath, ...],
) -> str | None:
    address = " ".join(lot_address.split())
    if not address:
        return None
    matches: set[str] = set()
    for region in regions:
        tokens = (
            *region.sido_name.split(),
            *region.sigungu_name.split(),
            region.eup_myeon_dong_name,
        )
        prefix = re.match(
            r"^" + r"\s*".join(re.escape(token) for token in tokens) + r"\s+",
            address,
        )
        if prefix is None:
            continue
        parcel_text = address[prefix.end() :]
        parcel = next(
            (match for pattern in _PARCEL_PATTERNS if (match := pattern.match(parcel_text))),
            None,
        )
        if parcel is None:
            continue
        main_number = int(parcel.group(2))
        if main_number == 0:
            continue
        sub_number = (
            int(parcel.group(3) or 0) if parcel.lastindex and parcel.lastindex >= 3 else 0
        )
        land_type = "2" if parcel.group(1) else "1"
        matches.add(
            f"{region.legal_dong_code}00{land_type}"
            f"{main_number:04d}{sub_number:04d}"
        )
    return next(iter(matches)) if len(matches) == 1 else None


class PostgresRetailEnrichmentTargetRepository:
    def __init__(
        self,
        dsn: str,
        *,
        expected_database: str = "home_search_ai",
        expected_username: str = "home_search_ai_importer",
    ) -> None:
        self._dsn = dsn
        self._expected_database = expected_database
        self._expected_username = expected_username
        self._verify_connection()

    def active_non_spatial_rows(
        self,
    ) -> tuple[UUID, int, int, tuple[RetailRegistryFact, ...]]:
        with self._connect() as connection:
            metadata = connection.execute(
                """
                SELECT active.publication_id, coverage.total_count,
                       coverage.spatial_count + COALESCE(enrichment.count, 0)
                           AS spatial_count
                FROM dataset_active_snapshot active
                JOIN (
                    SELECT publication_id, sum(total_count)::bigint AS total_count,
                           sum(spatial_count)::bigint AS spatial_count
                    FROM reference_projection.source_coverage
                    GROUP BY publication_id
                ) coverage ON coverage.publication_id = active.publication_id
                LEFT JOIN (
                    SELECT publication_id, count(*)::bigint AS count
                    FROM reference_projection.retail_coordinate_enrichment
                    GROUP BY publication_id
                ) enrichment ON enrichment.publication_id = active.publication_id
                WHERE active.source_id = %s
                """,
                (SOURCE_ID,),
            ).fetchone()
            if metadata is None:
                raise RuntimeError("active retail publication is missing")
            rows = connection.execute(
                """
                SELECT registry.publication_id, registry.fact_id, registry.lot_address
                FROM reference_projection.registry_fact registry
                LEFT JOIN reference_projection.retail_coordinate_enrichment enrichment
                  ON enrichment.publication_id = registry.publication_id
                 AND enrichment.fact_id = registry.fact_id
                WHERE registry.publication_id = %s
                  AND registry.source_id = %s
                  AND registry.lot_address IS NOT NULL
                  AND enrichment.fact_id IS NULL
                ORDER BY registry.fact_id
                """,
                (metadata["publication_id"], SOURCE_ID),
            ).fetchall()
        return (
            metadata["publication_id"],
            int(metadata["total_count"]),
            int(metadata["spatial_count"]),
            tuple(
                RetailRegistryFact(
                    publication_id=row["publication_id"],
                    fact_id=str(row["fact_id"]),
                    lot_address=str(row["lot_address"]),
                )
                for row in rows
            ),
        )

    def publish(
        self,
        publication_id: UUID,
        enrichments: tuple[RetailCoordinateEnrichment, ...],
    ) -> int:
        if any(item.publication_id != publication_id for item in enrichments):
            raise ValueError("retail enrichment publication mismatch")
        with self._connect() as connection:
            active = connection.execute(
                """
                SELECT publication_id FROM dataset_active_snapshot
                WHERE source_id = %s FOR UPDATE
                """,
                (SOURCE_ID,),
            ).fetchone()
            if active is None or active["publication_id"] != publication_id:
                raise RuntimeError("active retail publication changed")
            for item in enrichments:
                connection.execute(
                    """
                    INSERT INTO reference_projection.retail_coordinate_enrichment(
                        publication_id, fact_id, pnu, position,
                        coordinate_snapshot_version, resolution_method, resolved_at
                    ) VALUES (
                        %s, %s, %s,
                        ST_SetSRID(ST_MakePoint(%s, %s), 4326)::geography,
                        %s, %s, %s
                    )
                    """,
                    (
                        item.publication_id,
                        item.fact_id,
                        item.pnu,
                        item.longitude,
                        item.latitude,
                        item.coordinate_snapshot_version,
                        item.resolution_method,
                        item.resolved_at,
                    ),
                )
        return len(enrichments)

    def _verify_connection(self) -> None:
        with self._connect() as connection:
            if (
                connection.info.dbname != self._expected_database
                or connection.info.user != self._expected_username
            ):
                raise ValueError("retail enrichment target DSN has the wrong boundary")

    def _connect(self):
        return psycopg.connect(
            self._dsn,
            row_factory=dict_row,
            options="-c statement_timeout=3000",
        )


class PostgresPropertyRegionRepository:
    def __init__(
        self,
        dsn: str,
        *,
        expected_database: str = "home_search",
        expected_username: str = "home_search_ai_reader",
    ) -> None:
        self._dsn = dsn
        self._expected_database = expected_database
        self._expected_username = expected_username

    def region_paths(self) -> tuple[AdministrativeRegionPath, ...]:
        with psycopg.connect(
            self._dsn,
            row_factory=dict_row,
            options="-c default_transaction_read_only=on -c statement_timeout=3000",
        ) as connection:
            if (
                connection.info.dbname != self._expected_database
                or connection.info.user != self._expected_username
            ):
                raise ValueError("property region DSN has the wrong boundary")
            rows = connection.execute(
                """
                SELECT child.region_code AS legal_dong_code,
                       sido.region_name AS sido_name,
                       sigungu.region_name AS sigungu_name,
                       child.region_name AS eup_myeon_dong_name
                FROM ai_read.region_fact child
                JOIN ai_read.region_fact sigungu
                  ON sigungu.region_id = child.parent_region_id
                JOIN ai_read.region_fact sido
                  ON sido.region_id = sigungu.parent_region_id
                WHERE child.region_type = 'eup-myeon-dong'
                  AND (child.region_name LIKE '%%동' OR child.region_name LIKE '%%가')
                ORDER BY child.region_code
                """
            ).fetchall()
        return tuple(AdministrativeRegionPath(**row) for row in rows)


class PostgresCoordinateSnapshotRepository:
    def __init__(
        self,
        dsn: str,
        *,
        expected_database: str = "home_search_coordinate_source",
        expected_username: str = "home_search_coordinate_reader",
    ) -> None:
        self._dsn = dsn
        self._expected_database = expected_database
        self._expected_username = expected_username

    def coordinates(self, pnus: tuple[str, ...]) -> Mapping[str, ParcelCoordinate]:
        if len(pnus) > _MAX_NON_SPATIAL_ROWS or any(
            _PNU_PATTERN.fullmatch(pnu) is None for pnu in pnus
        ):
            raise ValueError("coordinate lookup is outside the bounded policy")
        if not pnus:
            return {}
        with psycopg.connect(
            self._dsn,
            row_factory=dict_row,
            options="-c default_transaction_read_only=on -c statement_timeout=3000",
        ) as connection:
            if (
                connection.info.dbname != self._expected_database
                or connection.info.user != self._expected_username
            ):
                raise ValueError("coordinate source DSN has the wrong boundary")
            rows = connection.execute(
                """
                SELECT pnu, latitude, longitude, snapshot_version
                FROM reference.parcel_coordinate_snapshot
                WHERE pnu = ANY(%s)
                ORDER BY pnu
                """,
                (list(pnus),),
            ).fetchall()
        return {
            str(row["pnu"]): ParcelCoordinate(
                pnu=str(row["pnu"]),
                latitude=float(row["latitude"]),
                longitude=float(row["longitude"]),
                snapshot_version=str(row["snapshot_version"]),
            )
            for row in rows
        }


def enrich_from_environment(environment: Mapping[str, str]) -> RetailCoordinateEnrichmentReport:
    required = (
        "HOME_AI_IMPORTER_DSN",
        "HOME_AI_PROPERTY_DSN",
        "HOME_COORDINATE_SOURCE_READER_DSN",
    )
    if any(not environment.get(key, "").strip() for key in required):
        raise RuntimeError("retail coordinate enrichment configuration is incomplete")
    return RetailCoordinateEnrichmentService(
        target_repository=PostgresRetailEnrichmentTargetRepository(
            environment["HOME_AI_IMPORTER_DSN"]
        ),
        region_repository=PostgresPropertyRegionRepository(
            environment["HOME_AI_PROPERTY_DSN"]
        ),
        coordinate_repository=PostgresCoordinateSnapshotRepository(
            environment["HOME_COORDINATE_SOURCE_READER_DSN"]
        ),
    ).run()


def main() -> None:
    report = enrich_from_environment(os.environ)
    print("상태: Pass")
    print(f"sourceId: {SOURCE_ID}")
    print(f"totalCount: {report.total_count}")
    print(f"spatialCount: {report.spatial_count}")
    print(f"matchedCount: {report.matched_count}")
    print(f"unresolvedCount: {report.unresolved_count}")
    print(f"coordinateCoverage: {report.coordinate_coverage:.6f}")


if __name__ == "__main__":
    main()
