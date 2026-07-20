from __future__ import annotations

import hashlib
from collections.abc import Callable
from dataclasses import replace
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Protocol
from uuid import UUID

from .models import (
    AcquisitionRecord,
    ActiveSnapshot,
    ActiveSnapshotState,
    DatasetSourceContract,
    LifecycleResult,
    ParsedDataset,
    ValidationOutcome,
)
from .checksum import normalized_dataset_checksum_from_hashes
from .normalized_spool import NormalizedRowSpool
from .raw_store import StoredRawObject
from .secure_temp import SecureTempWorkspace
from .validation import RawPayloadError, parse_rows, validate_rows
from .bundle import PreparedBundle


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

    def publication_pending(self, acquisition_id: UUID) -> bool: ...

    def result(self, acquisition_id: UUID, *, idempotent: bool) -> LifecycleResult: ...

    def active_snapshot(self, source_id: str) -> ActiveSnapshot | None: ...

    def active_snapshot_state(self, source_id: str) -> ActiveSnapshotState | None: ...

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
        return self._ingest_validate_publish(
            contract, raw_bytes, source_date=source_date, observed_at=observed_at,
            adapter=adapter, content_type=content_type, stored_raw=None,
        )

    def _ingest_validate_publish(
        self,
        contract: DatasetSourceContract,
        raw_bytes: bytes,
        *,
        source_date: date | None,
        observed_at: datetime | None,
        adapter: DatasetAdapter | None,
        content_type: str,
        stored_raw: StoredRawObject | None,
    ) -> LifecycleResult:
        checksum = hashlib.sha256(raw_bytes).hexdigest()
        selected_adapter = adapter or JsonDatasetAdapter()
        parse_lazy = getattr(selected_adapter, "parse_lazy", None)
        if callable(parse_lazy):
            parser = lambda: parse_lazy(
                raw_bytes, contract, source_date=source_date
            )
        else:
            parser = lambda: selected_adapter.parse(
                raw_bytes, contract, source_date=source_date
            )
        return self._register_validate_publish(
            contract,
            checksum=checksum,
            raw_byte_length=len(raw_bytes),
            raw_bytes=raw_bytes,
            source_date=source_date,
            observed_at=observed_at,
            content_type=content_type,
            stored_raw=stored_raw,
            parser=parser,
        )

    def _register_validate_publish(
        self,
        contract: DatasetSourceContract,
        *,
        checksum: str,
        raw_byte_length: int,
        raw_bytes: bytes | None,
        source_date: date | None,
        observed_at: datetime | None,
        content_type: str,
        stored_raw: StoredRawObject | None,
        parser: Callable[[], ParsedDataset],
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
        if stored_raw is not None:
            if (
                stored_raw.checksum != checksum
                or stored_raw.byte_length != raw_byte_length
            ):
                raise ValueError("verified raw metadata does not match prepared content")
        elif self._raw_store is not None:
            if raw_bytes is None:
                raise ValueError("inline raw bytes are required")
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
                _required_raw_bytes(raw_bytes),
                source_date,
                observed_at,
                collected_at,
            )
        )
        if not acquisition.created:
            existing = self._repository.result(
                acquisition.acquisition_id, idempotent=True
            )
            if existing.status in {"Pass", "NoChange"}:
                return existing
            if not self._repository.publication_pending(acquisition.acquisition_id):
                return existing
            try:
                self._repository.publish(acquisition.acquisition_id, self._clock())
            except PublicationStoreError:
                self._repository.record_publication_failure(
                    acquisition.acquisition_id, self._clock()
                )
            except ValueError:
                concurrent = self._repository.result(
                    acquisition.acquisition_id, idempotent=True
                )
                if concurrent.status != "Pass":
                    raise
                return concurrent
            return self._repository.result(
                acquisition.acquisition_id, idempotent=True
            )

        try:
            parsed = parser()
        except RawPayloadError as exception:
            self._repository.record_parse_failure(
                acquisition.acquisition_id, exception.reason_code, self._clock()
            )
            return self._repository.result(acquisition.acquisition_id, idempotent=False)

        active = self._repository.active_snapshot_state(contract.source_id)
        validation_date = source_date or observed_at.date()  # type: ignore[union-attr]
        with SecureTempWorkspace(
            required_free_bytes=max(raw_byte_length * 2, 1)
        ) as workspace:
            spool = NormalizedRowSpool(workspace.create_file("normalized.ndjson"))
            try:
                outcome = validate_rows(
                    contract,
                    parsed.rows,
                    active.row_count if active else None,
                    source_date=validation_date,
                    collected_at=collected_at,
                    adapter_issues=parsed.issues,
                    adapter_rejections=parsed.row_rejections,
                    row_sink=spool.append,
                    retain_staged_rows=False,
                )
            except RawPayloadError as exception:
                spool.close()
                self._repository.record_parse_failure(
                    acquisition.acquisition_id, exception.reason_code, self._clock()
                )
                return self._repository.result(
                    acquisition.acquisition_id, idempotent=False
                )
            spool.close()
            temporal_value = source_date or observed_at
            assert temporal_value is not None
            normalized_checksum = normalized_dataset_checksum_from_hashes(
                source_id=contract.source_id,
                normalization_schema_version=contract.schema_version,
                temporal_basis=contract.temporal_basis,
                semantic_temporal_value=(
                    temporal_value.date().isoformat()
                    if isinstance(temporal_value, datetime)
                    else temporal_value.isoformat()
                ),
                row_hashes=spool.accepted_row_hashes,
            )
            no_change = (
                not outcome.has_blocking_issues
                and active is not None
                and active.normalized_checksum == normalized_checksum
            )
            outcome = replace(outcome, spool_path=spool.path)
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

    def ingest_validate_publish_prepared(
        self,
        contract: DatasetSourceContract,
        prepared: PreparedBundle,
        *,
        source_date: date | None,
        observed_at: datetime | None = None,
        adapter: DatasetAdapter | None = None,
        content_type: str = "application/zip",
    ) -> LifecycleResult:
        if self._raw_store is None:
            raise ValueError("prepared bundles require verified external raw storage")
        stored_raw = self._raw_store.put_verified_file(
            source_id=contract.source_id,
            checksum=prepared.checksum,
            path=prepared.path,
            byte_length=prepared.byte_length,
            content_type=content_type,
        )
        checksum, byte_length = _file_checksum_and_length(prepared.path)
        if checksum != prepared.checksum or byte_length != prepared.byte_length:
            raise ValueError("prepared bundle metadata does not match file content")
        selected_adapter = adapter or JsonDatasetAdapter()
        parse_file = getattr(selected_adapter, "parse_file", None)
        if callable(parse_file):
            return self._register_validate_publish(
                contract,
                checksum=checksum,
                raw_byte_length=byte_length,
                raw_bytes=None,
                source_date=source_date,
                observed_at=observed_at,
                content_type=content_type,
                stored_raw=stored_raw,
                parser=lambda: parse_file(
                    prepared.path, contract, source_date=source_date
                ),
            )
        raw_bytes = prepared.path.read_bytes()
        return self._ingest_validate_publish(
            contract,
            raw_bytes,
            source_date=source_date,
            observed_at=observed_at,
            adapter=adapter,
            content_type=content_type,
            stored_raw=stored_raw,
        )

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

    def preserve_incomplete_prepared(
        self,
        contract: DatasetSourceContract,
        prepared: PreparedBundle,
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
        stored_raw = self._raw_store.put_verified_file(
            source_id=contract.source_id,
            checksum=prepared.checksum,
            path=prepared.path,
            byte_length=prepared.byte_length,
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


def _required_raw_bytes(raw_bytes: bytes | None) -> bytes:
    if raw_bytes is None:
        raise ValueError("inline raw bytes are required")
    return raw_bytes


def _file_checksum_and_length(path: Path) -> tuple[str, int]:
    if path.is_symlink() or not path.is_file():
        raise ValueError("prepared bundle must be a regular file")
    digest = hashlib.sha256()
    byte_length = 0
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
            byte_length += len(chunk)
    return digest.hexdigest(), byte_length


class RawObjectStore(Protocol):
    def put_verified(
        self,
        *,
        source_id: str,
        checksum: str,
        content: bytes,
        content_type: str,
    ) -> StoredRawObject: ...

    def put_verified_file(
        self,
        *,
        source_id: str,
        checksum: str,
        path: Path,
        byte_length: int,
        content_type: str,
    ) -> StoredRawObject: ...
