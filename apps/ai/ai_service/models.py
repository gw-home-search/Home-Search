from __future__ import annotations

import math
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class ConversationMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=2000)

    @field_validator("content")
    @classmethod
    def normalize_content(cls, value: str) -> str:
        content = value.strip()
        if not content:
            raise ValueError("message content must not be blank")
        return content


class ConversationMemory(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version: Literal[1, 2]
    complexId: int | None = Field(default=None, gt=0, le=9_007_199_254_740_991)
    complexIds: list[int] | None = Field(default=None, max_length=5)
    regionCode: str | None = Field(default=None, pattern=r"^[0-9]{2,10}$")
    scopeKind: Literal["COMPLEX", "ADMIN_REGION", "MAP_VIEWPORT", "RECOMMENDATION"]

    @field_validator("version", "complexId", mode="before")
    @classmethod
    def require_integer_memory_fields(cls, value: object) -> object:
        if value is not None and (isinstance(value, bool) or not isinstance(value, int)):
            raise ValueError("memory integer fields must be integers")
        return value

    @field_validator("complexIds", mode="before")
    @classmethod
    def require_integer_complex_ids(cls, value: object) -> object:
        if value is None:
            return value
        if not isinstance(value, list) or any(
            isinstance(item, bool) or not isinstance(item, int) for item in value
        ):
            raise ValueError("memory complex ids must be integers")
        return value

    @model_validator(mode="after")
    def validate_scope_identity(self) -> "ConversationMemory":
        if self.version == 2:
            if (
                self.scopeKind != "RECOMMENDATION"
                or self.complexId is not None
                or self.complexIds is None
                or not 2 <= len(self.complexIds) <= 5
                or len(self.complexIds) != len(set(self.complexIds))
                or any(
                    complex_id <= 0 or complex_id > 9_007_199_254_740_991
                    for complex_id in self.complexIds
                )
            ):
                raise ValueError("recommendation memory is invalid")
            return self
        if self.scopeKind == "RECOMMENDATION" or self.complexIds is not None:
            raise ValueError("version 1 memory cannot contain recommendation candidates")
        if self.scopeKind == "COMPLEX" and self.complexId is None:
            raise ValueError("COMPLEX memory requires complexId")
        if self.scopeKind == "ADMIN_REGION" and self.regionCode is None:
            raise ValueError("ADMIN_REGION memory requires regionCode")
        return self


class ConversationContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    messages: list[ConversationMessage] = Field(default_factory=list, max_length=12)
    memory: ConversationMemory | None = None

    @model_validator(mode="after")
    def validate_total_content(self) -> "ConversationContext":
        if sum(len(message.content) for message in self.messages) > 12_000:
            raise ValueError("conversation content exceeds limit")
        return self


class MapBounds(BaseModel):
    model_config = ConfigDict(extra="forbid")

    swLat: float
    swLng: float
    neLat: float
    neLng: float

    @field_validator("swLat", "swLng", "neLat", "neLng", mode="before")
    @classmethod
    def validate_finite_coordinate(cls, value: object) -> float:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError("map coordinate must be a number")
        coordinate = float(value)
        if not math.isfinite(coordinate):
            raise ValueError("map coordinate must be finite")
        return coordinate

    @model_validator(mode="after")
    def validate_korea_bounds(self) -> "MapBounds":
        if not 33 <= self.swLat <= 39 or not 33 <= self.neLat <= 39:
            raise ValueError("latitude is outside supported bounds")
        if not 124 <= self.swLng <= 132 or not 124 <= self.neLng <= 132:
            raise ValueError("longitude is outside supported bounds")
        if self.swLat >= self.neLat or self.swLng >= self.neLng:
            raise ValueError("map bounds must be ordered")
        if self.neLat - self.swLat > 6 or self.neLng - self.swLng > 8:
            raise ValueError("map bounds span exceeds limit")
        return self


class MapViewportContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    bounds: MapBounds
    level: int = Field(ge=1, le=12)

    @field_validator("level", mode="before")
    @classmethod
    def reject_non_integer_level(cls, value: object) -> object:
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError("map level must be an integer")
        return value


class SelectedComplexContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    complexId: int = Field(gt=0, le=9_007_199_254_740_991)
    parcelId: int = Field(gt=0, le=9_007_199_254_740_991)

    @field_validator("complexId", "parcelId", mode="before")
    @classmethod
    def reject_non_integer_identifier(cls, value: object) -> object:
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError("identifier must be an integer")
        return value


class UiContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    mapViewport: MapViewportContext | None = None
    selectedComplex: SelectedComplexContext | None = None

    @model_validator(mode="after")
    def require_context_hint(self) -> "UiContext":
        if self.mapViewport is None and self.selectedComplex is None:
            raise ValueError("uiContext requires at least one hint")
        return self


class ChatbotQueryRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    question: str = Field(min_length=1, max_length=2000)
    uiContext: UiContext | None = None
    conversationContext: ConversationContext | None = None

    @field_validator("question")
    @classmethod
    def normalize_question(cls, value: str) -> str:
        question = value.strip()
        if not question:
            raise ValueError("question must not be blank")
        return question
