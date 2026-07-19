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
    ParsedDataset,
    ValidationOutcome,
)
from .checksum import normalized_dataset_checksum
from .raw_store import StoredRawObject
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
        source_date: date | None,
        observed_at: datetime | None,
        collected_at: datetime,
    ) -> AcquisitionRecord: ...

    def acquire_stored_raw(
        self,
        contract: DatasetSourceContract,
        contract_id: UUID,
        raw_object: StoredRawObject,
        source_date: date | None,
        observed_at: datetime | None,
        collected_at: datetime,
    ) -> AcquisitionRecord: ...

    def record_validation(
        self,
        acquisition_id: UUID,
        outcome: ValidationOutcome,
        validated_at: datetime,
        *,
        normalized_checksum: str,
        normalization_schema_version: str,
        no_change: bool,
    ) -> None: ...

    def record_parse_failure(
        self, acquisition_id: UUID, reason_code: str, recorded_at: datetime
    ) -> None: ...

    def record_incomplete(
        self, acquisition_id: UUID, reason_codes: tuple[str, ...], recorded_at: datetime
    ) -> None: ...

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
        raw_store: RawObjectStore | None = None,
        clock: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        self._repository = repository
        self._raw_store = raw_store
        self._clock = clock

    def ingest_validate_publish(
        self,
        contract: DatasetSourceContract,
        raw_bytes: bytes,
        *,
        source_date: date | None,
        observed_at: datetime | None = None,
        adapter: DatasetAdapter | None = None,
        content_type: str = "application/octet-stream",
    ) -> LifecycleResult:
        collected_at = self._clock()
        if (
            contract.temporal_basis == "SOURCE_DATE"
            and (source_date is None or observed_at is not None)
        ) or (
            contract.temporal_basis == "OBSERVED_AT"
            and (source_date is not None or observed_at is None)
        ):
            raise ValueError("temporal value does not match source contract")
        checksum = hashlib.sha256(raw_bytes).hexdigest()
        stored_raw = None
        if self._raw_store is not None:
            stored_raw = self._raw_store.put_verified(
                source_id=contract.source_id,
                checksum=checksum,
                content=raw_bytes,
                content_type=content_type,
            )
        contract_id = self._repository.register_source_contract(contract, collected_at)
        acquisition = (
            self._repository.acquire_stored_raw(
                contract, contract_id, stored_raw, source_date, observed_at, collected_at
            )
            if stored_raw is not None
            else self._repository.acquire_raw(
                contract,
                contract_id,
                checksum,
                raw_bytes,
                source_date,
                observed_at,
                collected_at,
            )
        )
        if not acquisition.created:
            return self._repository.result(acquisition.acquisition_id, idempotent=True)

        selected_adapter = adapter or JsonDatasetAdapter()
        try:
            parsed = selected_adapter.parse(raw_bytes, contract, source_date=source_date)
        except RawPayloadError as exception:
            self._repository.record_parse_failure(
                acquisition.acquisition_id, exception.reason_code, self._clock()
            )
            return self._repository.result(acquisition.acquisition_id, idempotent=False)

        active = self._repository.active_snapshot(contract.source_id)
        validation_date = source_date or observed_at.date()  # type: ignore[union-attr]
        outcome = validate_rows(
            contract,
            parsed.rows,
            len(active.rows) if active else None,
            source_date=validation_date,
            collected_at=collected_at,
            adapter_issues=parsed.issues,
            adapter_rejections=parsed.row_rejections,
        )
        normalized_checksum = normalized_dataset_checksum(
            source_id=contract.source_id,
            normalization_schema_version=contract.schema_version,
            temporal_value=source_date or observed_at,  # type: ignore[arg-type]
            rows=(row.row_data for row in outcome.staged_rows if row.accepted),
        )
        no_change = (
            not outcome.has_blocking_issues
            and active is not None
            and active.normalized_checksum == normalized_checksum
        )
        self._repository.record_validation(
            acquisition.acquisition_id,
            outcome,
            self._clock(),
            normalized_checksum=normalized_checksum,
            normalization_schema_version=contract.schema_version,
            no_change=no_change,
        )
        if outcome.has_blocking_issues:
            return self._repository.result(acquisition.acquisition_id, idempotent=False)
        if no_change:
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

    def preserve_incomplete(
        self,
        contract: DatasetSourceContract,
        raw_bytes: bytes,
        *,
        source_date: date | None,
        observed_at: datetime | None = None,
        reason_codes: tuple[str, ...],
        content_type: str = "application/zip",
    ) -> LifecycleResult:
        if self._raw_store is None:
            raise ValueError("incomplete bundles require verified external raw storage")
        if not reason_codes or any(not reason.strip() for reason in reason_codes):
            raise ValueError("incomplete bundle reason codes are required")
        collected_at = self._clock()
        if (
            contract.temporal_basis == "SOURCE_DATE"
            and (source_date is None or observed_at is not None)
        ) or (
            contract.temporal_basis == "OBSERVED_AT"
            and (source_date is not None or observed_at is None)
        ):
            raise ValueError("temporal value does not match source contract")
        checksum = hashlib.sha256(raw_bytes).hexdigest()
        stored_raw = self._raw_store.put_verified(
            source_id=contract.source_id,
            checksum=checksum,
            content=raw_bytes,
            content_type=content_type,
        )
        contract_id = self._repository.register_source_contract(contract, collected_at)
        acquisition = self._repository.acquire_stored_raw(
            contract,
            contract_id,
            stored_raw,
            source_date,
            observed_at,
            collected_at,
        )
        if acquisition.created:
            self._repository.record_incomplete(
                acquisition.acquisition_id, reason_codes, self._clock()
            )
        return self._repository.result(
            acquisition.acquisition_id, idempotent=not acquisition.created
        )


class DatasetAdapter(Protocol):
    def parse(
        self,
        raw_bytes: bytes,
        contract: DatasetSourceContract,
        *,
        source_date: date | None,
    ) -> ParsedDataset: ...


class JsonDatasetAdapter:
    def parse(
        self,
        raw_bytes: bytes,
        contract: DatasetSourceContract,
        *,
        source_date: date | None,
    ) -> ParsedDataset:
        del source_date
        return ParsedDataset(rows=parse_rows(raw_bytes, contract.encoding))


class RawObjectStore(Protocol):
    def put_verified(
        self,
        *,
        source_id: str,
        checksum: str,
        content: bytes,
        content_type: str,
    ) -> StoredRawObject: ...
