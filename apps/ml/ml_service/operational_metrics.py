from __future__ import annotations

import math
from collections import defaultdict
from threading import Lock

APPROVED_ROUTE_TEMPLATES = frozenset({"/health", "/metrics", "/predict", "unmatched"})
_BUCKETS = (0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0)


class PrometheusHttpMetrics:
    def __init__(self) -> None:
        self._lock = Lock()
        self._requests: dict[tuple[str, str], int] = defaultdict(int)
        self._duration_count: dict[tuple[str, str], int] = defaultdict(int)
        self._duration_sum: dict[tuple[str, str], float] = defaultdict(float)
        self._duration_buckets: dict[tuple[str, str], list[int]] = defaultdict(
            lambda: [0] * len(_BUCKETS)
        )

    def observe(self, route: str, status_code: int, elapsed_seconds: float) -> None:
        if route not in APPROVED_ROUTE_TEMPLATES:
            raise ValueError("metrics require an approved route template")
        if not math.isfinite(elapsed_seconds) or elapsed_seconds < 0:
            raise ValueError("elapsed_seconds must be finite and non-negative")
        status = _status_class(status_code)
        key = (route, status)
        with self._lock:
            self._requests[key] += 1
            self._duration_count[key] += 1
            self._duration_sum[key] += elapsed_seconds
            buckets = self._duration_buckets[key]
            for index, upper_bound in enumerate(_BUCKETS):
                if elapsed_seconds <= upper_bound:
                    buckets[index] += 1

    def render(self) -> str:
        with self._lock:
            requests = dict(self._requests)
            counts = dict(self._duration_count)
            sums = dict(self._duration_sum)
            buckets = {key: list(value) for key, value in self._duration_buckets.items()}

        lines = [
            "# HELP home_ml_up ML service metrics renderer availability.",
            "# TYPE home_ml_up gauge",
            "home_ml_up 1",
            "# HELP home_ml_http_requests_total HTTP requests by bounded route template and status class.",
            "# TYPE home_ml_http_requests_total counter",
        ]
        for (route, status), value in sorted(requests.items()):
            labels = _labels(route, status)
            lines.append(f"home_ml_http_requests_total{{{labels}}} {value}")

        lines.extend(
            [
                "# HELP home_ml_http_request_duration_seconds HTTP request duration by bounded route template and status class.",
                "# TYPE home_ml_http_request_duration_seconds histogram",
            ]
        )
        for key in sorted(counts):
            route, status = key
            labels = _labels(route, status)
            for upper_bound, value in zip(_BUCKETS, buckets[key], strict=True):
                lines.append(
                    "home_ml_http_request_duration_seconds_bucket"
                    f'{{{labels},le="{_format_number(upper_bound)}"}} {value}'
                )
            lines.append(
                "home_ml_http_request_duration_seconds_bucket"
                f'{{{labels},le="+Inf"}} {counts[key]}'
            )
            lines.append(f"home_ml_http_request_duration_seconds_count{{{labels}}} {counts[key]}")
            lines.append(
                f"home_ml_http_request_duration_seconds_sum{{{labels}}} {_format_number(sums[key])}"
            )
        return "\n".join(lines) + "\n"


def _status_class(status_code: int) -> str:
    status_class = status_code // 100
    return f"{status_class}xx" if 1 <= status_class <= 5 else "other"


def _labels(route: str, status: str) -> str:
    return f'route="{route}",status="{status}"'


def _format_number(value: float) -> str:
    return format(value, "g")


HTTP_METRICS = PrometheusHttpMetrics()
