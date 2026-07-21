from __future__ import annotations

import pytest

from ai_service.property_chat.capability_handlers import CapabilityCatalog


class StubHandler:
    def __init__(self, capability: str) -> None:
        self.capability = capability

    async def observe(self, plan: object, complex_record: object) -> object:
        del plan, complex_record
        raise AssertionError("not executed")


def test_capability_catalog_keeps_static_order_and_rejects_duplicate_handlers() -> None:
    identity = StubHandler("complex_identity")
    recent = StubHandler("recent_trade_lookup")
    catalog = CapabilityCatalog((identity, recent))  # type: ignore[arg-type]

    assert catalog.capabilities == ("complex_identity", "recent_trade_lookup")
    assert catalog.handler_for("complex_identity") is identity
    assert catalog.handler_for("price_trend") is None

    with pytest.raises(ValueError, match="duplicate capability handler"):
        CapabilityCatalog((identity, identity))  # type: ignore[arg-type]
