#!/usr/bin/env python3
"""Fail-closed budget-production Terraform plan and recurring-cost gate."""

from __future__ import annotations

import argparse
import json
from decimal import Decimal
from pathlib import Path
from typing import Any


FORBIDDEN_TYPES = {
    "aws_db_instance",
    "aws_db_cluster",
    "aws_rds_cluster",
    "aws_rds_cluster_instance",
    "aws_msk_cluster",
    "aws_msk_serverless_cluster",
    "aws_nat_gateway",
    "aws_vpc_endpoint",
    "aws_lb",
    "aws_lb_listener",
    "aws_lb_target_group",
    "aws_vpn_gateway",
    "aws_ec2_client_vpn_endpoint",
    "aws_customer_gateway",
    "aws_elasticache_cluster",
    "aws_elasticache_replication_group",
    "aws_prometheus_workspace",
    "aws_grafana_workspace",
    "aws_ebs_fast_snapshot_restore",
}


class GateError(RuntimeError):
    pass


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise GateError(f"JSON object가 필요합니다: {path}")
    return value


def changed_resources(plan: dict[str, Any]) -> list[dict[str, Any]]:
    resources = plan.get("resource_changes")
    if not isinstance(resources, list):
        raise GateError("Terraform plan JSON에 resource_changes가 없습니다.")
    return [resource for resource in resources if resource.get("mode") == "managed"]


def verify_plan(resources: list[dict[str, Any]]) -> None:
    violations: list[str] = []
    for resource in resources:
        resource_type = resource.get("type")
        actions = resource.get("change", {}).get("actions", [])
        if "delete" in actions:
            violations.append(f"destroy:{resource.get('address')}")
        if resource_type in FORBIDDEN_TYPES and actions not in (["no-op"], ["read"]):
            violations.append(f"forbidden:{resource_type}:{resource.get('address')}")
    if violations:
        raise GateError("plan 경계 위반: " + ", ".join(sorted(violations)))

    hosts = [resource for resource in resources if resource.get("type") == "aws_instance" and "delete" not in resource.get("change", {}).get("actions", [])]
    if hosts:
        if len(hosts) != 1:
            raise GateError("budget-production host는 정확히 1개여야 합니다.")
        instance_type = hosts[0].get("change", {}).get("after", {}).get("instance_type")
        if instance_type != "t3a.large":
            raise GateError("budget-production host instance_type은 t3a.large여야 합니다.")

    data_volumes = [resource for resource in resources if resource.get("address") == "aws_ebs_volume.data[0]"]
    if data_volumes:
        after = data_volumes[0].get("change", {}).get("after", {})
        if after.get("type") != "gp3" or Decimal(str(after.get("size", 0))) < 80:
            raise GateError("data EBS는 80 GiB 이상 gp3여야 합니다.")


def calculate(fixture: dict[str, Any], account_forecast: Decimal | None) -> dict[str, str]:
    if fixture.get("format_version") != 1 or fixture.get("region") != "ap-northeast-2":
        raise GateError("지원하지 않는 cost fixture입니다.")
    items = fixture.get("items")
    if not isinstance(items, list) or not items:
        raise GateError("cost fixture items가 비어 있습니다.")
    incremental = sum((Decimal(str(item["monthly_usd"])) for item in items), Decimal("0"))
    incremental_gate = Decimal(str(fixture["incremental_gate_usd"]))
    baseline = Decimal(str(fixture["account_baseline_max_usd"]))
    total_gate = Decimal(str(fixture["account_total_gate_usd"]))
    modeled_total = incremental + baseline
    account_total = max(modeled_total, account_forecast) if account_forecast is not None else modeled_total
    if incremental > incremental_gate:
        raise GateError(f"증분 recurring estimate ${incremental:.2f}가 ${incremental_gate:.2f} gate를 초과합니다.")
    if account_total > total_gate:
        raise GateError(f"계정 total forecast ${account_total:.2f}가 ${total_gate:.2f} gate를 초과합니다.")
    return {
        "incremental_monthly_usd": f"{incremental:.2f}",
        "account_baseline_max_usd": f"{baseline:.2f}",
        "account_total_usd": f"{account_total:.2f}",
        "incremental_gate_usd": f"{incremental_gate:.2f}",
        "account_total_gate_usd": f"{total_gate:.2f}",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--account-forecast-usd", type=Decimal)
    arguments = parser.parse_args()
    try:
        plan = load_json(arguments.plan)
        fixture = load_json(arguments.fixture)
        verify_plan(changed_resources(plan))
        result = calculate(fixture, arguments.account_forecast_usd)
    except (GateError, KeyError, ValueError, json.JSONDecodeError) as exception:
        print(f"상태: Fail - {exception}")
        return 1
    print(json.dumps({"status": "pass", **result}, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
