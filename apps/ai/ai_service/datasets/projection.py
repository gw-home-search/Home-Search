from __future__ import annotations

from typing import Protocol
from uuid import UUID


class ProjectionConnection(Protocol):
    def execute(self, query: str, parameters: tuple[object, ...]) -> object: ...


class ProjectionWriter(Protocol):
    def __call__(
        self,
        connection: ProjectionConnection,
        publication_id: UUID,
        acquisition_id: UUID,
        source_id: str,
    ) -> None: ...
