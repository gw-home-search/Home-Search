from __future__ import annotations

import unittest

from ml_service.operational_metrics import PrometheusHttpMetrics


class PrometheusHttpMetricsTest(unittest.TestCase):
    def test_render_exposes_bounded_request_and_latency_metrics(self) -> None:
        metrics = PrometheusHttpMetrics()

        metrics.observe("/predict", 200, 0.125)
        metrics.observe("/predict", 503, 2.5)

        rendered = metrics.render()
        self.assertIn('home_ml_http_requests_total{route="/predict",status="2xx"} 1', rendered)
        self.assertIn('home_ml_http_requests_total{route="/predict",status="5xx"} 1', rendered)
        self.assertIn(
            'home_ml_http_request_duration_seconds_bucket{route="/predict",status="5xx",le="5"} 1',
            rendered,
        )
        self.assertIn('home_ml_http_request_duration_seconds_sum{route="/predict",status="5xx"} 2.5', rendered)
        self.assertNotIn("transaction_id", rendered)

    def test_observe_rejects_unbounded_route_labels(self) -> None:
        metrics = PrometheusHttpMetrics()

        with self.assertRaisesRegex(ValueError, "approved route template"):
            metrics.observe("/predict/customer-123", 200, 0.01)


if __name__ == "__main__":
    unittest.main()
