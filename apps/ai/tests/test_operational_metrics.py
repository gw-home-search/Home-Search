import pytest
from fastapi.testclient import TestClient

from ai_service.main import app
from ai_service.operational_metrics import PrometheusMetricsSink, SUPERVISOR_METRICS


def test_supervisor_metrics_render_only_approved_bounded_labels() -> None:
    sink = PrometheusMetricsSink()
    sink.increment("supervisor_graph_completed", {
        "goal_count": 2, "wave_count": 1, "terminal_status": "ANSWERED",
        "terminal_reason": "COMPLETED", "elapsed_milliseconds": 125,
    })
    rendered = sink.render()
    assert 'outcome="completed"' in rendered
    assert 'terminal_status="ANSWERED"' in rendered
    assert "home_ai_supervisor_graph_duration_milliseconds_sum 125.0" in rendered
    assert "question" not in rendered


def test_supervisor_metrics_reject_sensitive_or_unbounded_labels() -> None:
    with pytest.raises(ValueError, match="label is not approved"):
        PrometheusMetricsSink().increment("supervisor_graph_safe_final", {"user_id": "123"})


def test_metrics_endpoint_exposes_prometheus_text() -> None:
    SUPERVISOR_METRICS.reset()
    SUPERVISOR_METRICS.increment("supervisor_graph_safe_final", {"reason": "invariant_or_runtime"})
    response = TestClient(app).get("/metrics")
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/plain")
    assert 'terminal_reason="invariant_or_runtime"' in response.text
