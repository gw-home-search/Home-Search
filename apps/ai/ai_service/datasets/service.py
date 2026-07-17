from __future__ import annotations

import hashlib
from collections.abc import Callable
from datetime import UTC, date, datetime
from typing import Protocol
from uuid import UUID

from .models import (
    AcquisitionRecord,
    ActiveSnapshot,
    DatasetSourceContract,
    LifecycleResult,
    ValidationOutcome,
)
from .validation import RawPayloadError, parse_rows, validate_rows


class PublicationStoreError(RuntimeError):
    pass


class DatasetRepository(Protocol):
    def register_source_contract(self, contract: DatasetSourceContract, registered_at: datetime) -> UUID: ...

    def acquire_raw(
        self,
        contract: DatasetSourceContract,
        contract_id: UUID,
        checksum: str,
        raw_bytes: bytes,
        source_date: date,
        collected_at: datetime,
    ) -> AcquisitionRecord: ...

    def record_validation(
        self, acquisition_id: UUID, outcome: ValidationOutcome, validated_at: datetime
    ) -> None: ...

    def record_parse_failure(self, acquisition_id: UUID, recorded_at: datetime) -> None: ...

    def publish(self, acquisition_id: UUID, published_at: datetime) -> UUID: ...

    def record_publication_failure(self, acquisition_id: UUID, recorded_at: datetime) -> None: ...

    def result(self, acquisition_id: UUID, *, idempotent: bool) -> LifecycleResult: ...

    def active_snapshot(self, source_id: str) -> ActiveSnapshot | None: ...

    def rollback(self, source_id: str, publication_id: UUID, activated_at: datetime) -> None: ...


class DatasetLifecycleService:
    def __init__(
        self,
        repository: DatasetRepository,
        *,
        clock: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        self._repository = repository
        self._clock = clock

    def ingest_validate_publish(
        self,
        contract: DatasetSourceContract,
        raw_bytes: bytes,
        *,
        source_date: date,
    ) -> LifecycleResult:
        collected_at = self._clock()
        checksum = hashlib.sha256(raw_bytes).hexdigest()
        contract_id = self._repository.register_source_contract(contract, collected_at)
        acquisition = self._repository.acquire_raw(
            contract,
            contract_id,
            checksum,
            raw_bytes,
            source_date,
            collected_at,
        )
        if not acquisition.created:
            return self._repository.result(acquisition.acquisition_id, idempotent=True)

        try:
            rows = parse_rows(raw_bytes, contract.encoding)
        except RawPayloadError:
            self._repository.record_parse_failure(acquisition.acquisition_id, self._clock())
            return self._repository.result(acquisition.acquisition_id, idempotent=False)

        active = self._repository.active_snapshot(contract.source_id)
        outcome = validate_rows(
            contract,
            rows,
            len(active.rows) if active else None,
            source_date=source_date,
            collected_at=collected_at,
        )
        self._repository.record_validation(acquisition.acquisition_id, outcome, self._clock())
        if outcome.has_blocking_issues:
            return self._repository.result(acquisition.acquisition_id, idempotent=False)
        try:
            self._repository.publish(acquisition.acquisition_id, self._clock())
        except PublicationStoreError:
            self._repository.record_publication_failure(acquisition.acquisition_id, self._clock())
        return self._repository.result(acquisition.acquisition_id, idempotent=False)

    def rollback(self, source_id: str, publication_id: UUID | None) -> None:
        if publication_id is None:
            raise ValueError("publication_id is required")
        self._repository.rollback(source_id, publication_id, self._clock())
