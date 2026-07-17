from __future__ import annotations

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


class ConversationContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    messages: list[ConversationMessage] = Field(default_factory=list, max_length=12)

    @model_validator(mode="after")
    def validate_total_content(self) -> "ConversationContext":
        if sum(len(message.content) for message in self.messages) > 12_000:
            raise ValueError("conversation content exceeds limit")
        return self


class ChatbotQueryRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    question: str = Field(min_length=1, max_length=2000)
    conversationContext: ConversationContext | None = None

    @field_validator("question")
    @classmethod
    def normalize_question(cls, value: str) -> str:
        question = value.strip()
        if not question:
            raise ValueError("question must not be blank")
        return question
