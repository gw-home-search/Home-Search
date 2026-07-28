from __future__ import annotations

from collections import defaultdict
from threading import Lock

_ALLOWED_METRICS = {"supervisor_graph_completed", "supervisor_graph_safe_final"}
_ALLOWED_LABELS = {"goal_count", "wave_count", "terminal_status", "terminal_reason"}


class PrometheusMetricsSink:
    def __init__(self) -> None:
        self._lock = Lock()
        self._counters: dict[tuple[str, tuple[tuple[str, str], ...]], int] = defaultdict(int)
        self._duration_count = 0
        self._duration_sum = 0.0

    def increment(self, name: str, labels: dict[str, str | int | bool]) -> None:
        if name not in _ALLOWED_METRICS:
            raise ValueError("operational metric is not approved")
        values = dict(labels)
        elapsed = values.pop("elapsed_milliseconds", None)
        if "reason" in values:
            values["terminal_reason"] = values.pop("reason")
        if not set(values).issubset(_ALLOWED_LABELS):
            raise ValueError("operational metric label is not approved")
        key = (name, tuple(sorted((label, str(value)) for label, value in values.items())))
        with self._lock:
            self._counters[key] += 1
            if name == "supervisor_graph_completed" and isinstance(elapsed, (int, float)):
                self._duration_count += 1
                self._duration_sum += float(elapsed)

    def render(self) -> str:
        lines = [
            "# HELP home_ai_supervisor_graph_total Supervisor Graph terminal outcomes.",
            "# TYPE home_ai_supervisor_graph_total counter",
        ]
        with self._lock:
            counters = sorted(self._counters.items())
            duration_count = self._duration_count
            duration_sum = self._duration_sum
        for (name, labels), value in counters:
            rendered = (("outcome", name.removeprefix("supervisor_graph_")), *labels)
            encoded = ",".join(f'{label}="{_escape(item)}"' for label, item in rendered)
            lines.append(f"home_ai_supervisor_graph_total{{{encoded}}} {value}")
        lines.extend([
            "# HELP home_ai_supervisor_graph_duration_milliseconds Supervisor Graph execution duration.",
            "# TYPE home_ai_supervisor_graph_duration_milliseconds summary",
            f"home_ai_supervisor_graph_duration_milliseconds_count {duration_count}",
            f"home_ai_supervisor_graph_duration_milliseconds_sum {duration_sum}",
        ])
        return "\n".join(lines) + "\n"

    def reset(self) -> None:
        with self._lock:
            self._counters.clear()
            self._duration_count = 0
            self._duration_sum = 0.0


def _escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')


SUPERVISOR_METRICS = PrometheusMetricsSink()
