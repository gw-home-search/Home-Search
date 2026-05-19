#!/usr/bin/env python3
"""Plan Home Search V1 slices before running the execution harness."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tomllib
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)

HARNESS_ROOT = Path(__file__).resolve().parent
BACKLOG_PATH = HARNESS_ROOT / "slices" / "backlog.toml"
REPORT_ROOT = HARNESS_ROOT / "reports"
VALID_STATUSES = {"candidate", "planned", "running", "done", "blocked"}
VALID_TARGETS = {"backend", "frontend", "both", "planning-only"}
DEFAULT_LIMIT = 3


class PlanError(ValueError):
    """Raised when backlog or planning input is invalid."""


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def slugify(value: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", value.strip().lower()).strip("-")
    if not slug:
        raise PlanError("slice id must contain at least one alphanumeric character")
    return slug


def as_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item) for item in value]
    return [str(value)]


def load_backlog(path: Path = BACKLOG_PATH) -> list[dict[str, Any]]:
    if not path.exists():
        raise PlanError(f"backlog file not found: {path}")
    with path.open("rb") as handle:
        data = tomllib.load(handle)
    raw_slices = data.get("slices")
    if not isinstance(raw_slices, list):
        raise PlanError("backlog must contain [[slices]] entries")
    slices = [normalize_slice(item) for item in raw_slices]
    ids = [item["id"] for item in slices]
    duplicates = sorted({item for item in ids if ids.count(item) > 1})
    if duplicates:
        raise PlanError(f"duplicate slice ids: {', '.join(duplicates)}")
    return slices


def normalize_slice(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise PlanError("each backlog slice must be a table")
    required = [
        "id",
        "title_ko",
        "description_ko",
        "status",
        "priority",
        "targets",
        "preset",
        "acceptance_criteria",
        "first_red_candidates",
        "verification_commands",
        "stop_conditions",
        "risk_notes",
    ]
    missing = [field for field in required if field not in raw]
    if missing:
        raise PlanError(f"slice missing required fields: {', '.join(missing)}")
    item = dict(raw)
    item["id"] = slugify(str(item["id"]))
    item["status"] = str(item["status"])
    item["targets"] = str(item["targets"])
    if item["status"] not in VALID_STATUSES:
        raise PlanError(f"{item['id']} has invalid status: {item['status']}")
    if item["targets"] not in VALID_TARGETS:
        raise PlanError(f"{item['id']} has invalid targets: {item['targets']}")
    try:
        item["priority"] = int(item["priority"])
    except (TypeError, ValueError) as exc:
        raise PlanError(f"{item['id']} priority must be an integer") from exc
    for field in (
        "acceptance_criteria",
        "first_red_candidates",
        "verification_commands",
        "stop_conditions",
        "risk_notes",
    ):
        item[field] = as_list(item.get(field))
    return item


def latest_report_path() -> Path | None:
    if not REPORT_ROOT.exists():
        return None
    candidates = [
        path
        for path in REPORT_ROOT.glob("*")
        if path.is_file()
        and path.suffix in {".json", ".md"}
        and not path.name.endswith("-pr-body.md")
        and not path.name.endswith("-last.md")
        and not path.name.endswith("-gate.md")
    ]
    if not candidates:
        return None
    return max(candidates, key=lambda path: path.stat().st_mtime)


def read_report_evidence(path: Path | None) -> dict[str, Any]:
    evidence: dict[str, Any] = {
        "report_path": str(path) if path else None,
        "report_slice": None,
        "status": "not found",
        "gate_risks": [],
        "missing_tests": [],
        "contract_gaps": [],
        "data_safety_gaps": [],
        "summary": "recent report evidence not found",
    }
    if path is None or not path.exists():
        return evidence
    if path.suffix == ".json":
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            evidence["summary"] = f"report read failed: {exc}"
            return evidence
        if not isinstance(payload, dict):
            evidence["summary"] = "report payload is not an object"
            return evidence
        verification = payload.get("verification") if isinstance(payload.get("verification"), dict) else {}
        missing = [
            command
            for command, result in verification.items()
            if not isinstance(result, dict) or str(result.get("status", "")).lower() != "pass"
        ]
        evidence.update(
            {
                "report_slice": payload.get("slice"),
                "status": payload.get("status") or "unknown",
                "gate_risks": as_list(payload.get("residual_risks")),
                "contract_gaps": as_list(payload.get("contract_risks")),
                "missing_tests": missing,
                "summary": str(payload.get("gate_review") or payload.get("next_action") or "JSON report loaded"),
            }
        )
        return evidence

    text = path.read_text(encoding="utf-8", errors="replace")
    lower = text.lower()
    risks = []
    for pattern in (r"주요 위험:\s*(.+)", r"residual risks?\s*[:\-]\s*(.+)", r"findings?\s*[:\-]\s*(.+)"):
        risks.extend(match.strip() for match in re.findall(pattern, text, flags=re.IGNORECASE))
    missing_tests = []
    for line in text.splitlines():
        if "not run" in line.lower() or "missing test" in line.lower() or "미확인" in line:
            missing_tests.append(line.strip("- ").strip())
    evidence.update(
        {
            "status": "Fail" if "상태: fail" in lower else "Partial" if "partial" in lower else "unknown",
            "gate_risks": risks[:5],
            "missing_tests": missing_tests[:5],
            "contract_gaps": ["previous report mentions contract risk"] if "contract" in lower and "risk" in lower else [],
            "data_safety_gaps": ["previous report mentions data safety"] if "data safety" in lower or "데이터" in text else [],
            "summary": "Markdown report loaded",
        }
    )
    return evidence


def git_context(enabled: bool) -> dict[str, Any]:
    if not enabled:
        return {}
    result = subprocess.run(
        ["git", "status", "--short"],
        cwd=HARNESS_ROOT.parents[1],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    branch = subprocess.run(
        ["git", "branch", "--show-current"],
        cwd=HARNESS_ROOT.parents[1],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    return {
        "branch": branch.stdout.strip() if branch.returncode == 0 else "",
        "dirty": bool(result.stdout.strip()) if result.returncode == 0 else None,
        "status_short": result.stdout.strip().splitlines()[:20] if result.returncode == 0 else [],
    }


def evidence_additions(evidence: dict[str, Any]) -> tuple[list[str], list[str]]:
    criteria: list[str] = []
    red: list[str] = []
    for risk in evidence.get("gate_risks", [])[:3]:
        criteria.append(f"이전 gate risk를 재현 가능한 검증 또는 명시적 stop condition으로 닫는다: {risk}")
    for command in evidence.get("missing_tests", [])[:3]:
        criteria.append(f"누락되었거나 통과하지 못한 검증을 acceptance evidence에 포함한다: {command}")
        red.append(f"검증 누락을 먼저 실패로 확인한다: {command}")
    for gap in evidence.get("contract_gaps", [])[:3]:
        criteria.append(f"V1 API URL/request/response/unit/error contract gap을 변경 전 테스트로 고정한다: {gap}")
        red.append("V1 API contract mismatch를 실패하는 public seam 테스트로 먼저 고정한다.")
    for gap in evidence.get("data_safety_gaps", [])[:3]:
        criteria.append(f"raw-before-normalized, dedupe, failed-match explainability gap을 acceptance에 포함한다: {gap}")
        red.append("중복 ingest 또는 failed match explainability 실패를 먼저 재현한다.")
    return criteria, red


def recommendation_score(item: dict[str, Any], evidence: dict[str, Any]) -> int:
    if item["status"] == "done":
        return -10_000
    if item["status"] == "blocked":
        return -5_000
    score = 1_000 - item["priority"]
    text = " ".join(
        [
            item["id"],
            item["preset"],
            item["description_ko"],
            " ".join(evidence.get("gate_risks", [])),
            " ".join(evidence.get("contract_gaps", [])),
            " ".join(evidence.get("data_safety_gaps", [])),
        ]
    ).lower()
    if evidence.get("contract_gaps") and any(token in text for token in ("contract", "api", "architecture")):
        score += 80
    if evidence.get("data_safety_gaps") and any(token in text for token in ("data", "ingest", "architecture")):
        score += 80
    if any(token in text for token in ("marker", "map", "kakao")) and evidence.get("gate_risks"):
        score += 50
    if item["status"] == "planned":
        score += 25
    if item["status"] == "running":
        score -= 100
    return score


def candidate_view(item: dict[str, Any], evidence: dict[str, Any]) -> dict[str, Any]:
    criteria_extra, red_extra = evidence_additions(evidence)
    view = dict(item)
    view["acceptance_criteria"] = item["acceptance_criteria"] + criteria_extra
    view["first_red_candidates"] = item["first_red_candidates"] + red_extra
    view["score"] = recommendation_score(item, evidence)
    return view


def select_candidates(
    slices: list[dict[str, Any]],
    evidence: dict[str, Any],
    *,
    limit: int,
    targets: str | None,
    preset: str | None,
) -> list[dict[str, Any]]:
    if targets and targets not in VALID_TARGETS:
        raise PlanError(f"invalid --targets: {targets}")
    filtered = []
    for item in slices:
        if targets and item["targets"] != targets:
            continue
        if preset and slugify(str(item["preset"])) != slugify(preset):
            continue
        if item["status"] in {"done", "blocked"}:
            continue
        filtered.append(candidate_view(item, evidence))
    filtered.sort(key=lambda item: (-item["score"], item["priority"], item["id"]))
    return filtered[: max(1, min(limit, 3))]


def find_slice(slices: list[dict[str, Any]], slice_name: str) -> dict[str, Any]:
    requested = slugify(slice_name)
    matches = [item for item in slices if item["id"] == requested]
    if len(matches) == 1:
        return matches[0]
    raise PlanError(f"slice not found in backlog: {requested}")


def plan_for_slice(
    item: dict[str, Any],
    evidence: dict[str, Any],
    *,
    targets: str | None,
    preset: str | None,
) -> dict[str, Any]:
    if targets and targets not in VALID_TARGETS:
        raise PlanError(f"invalid --targets: {targets}")
    if targets and targets != item["targets"]:
        raise PlanError(f"{item['id']} backlog target is {item['targets']}, not {targets}")
    view = candidate_view(item, evidence)
    chosen_preset = slugify(preset) if preset else view["preset"]
    target_flag = f" --targets {view['targets']}"
    dry_command = f".codex/harness/v1 dry {view['id']}{target_flag}"
    run_command = f".codex/harness/v1 run {view['id']}{target_flag}"
    if view["targets"] == "planning-only":
        dry_command = "planning-only slice: 구현 dry-run 대신 계획 evidence만 검토"
        run_command = "planning-only slice: 자동 구현 실행 금지"
    return {
        "id": view["id"],
        "title_ko": view["title_ko"],
        "description_ko": view["description_ko"],
        "status": view["status"],
        "targets": view["targets"],
        "preset": chosen_preset,
        "acceptance_criteria": view["acceptance_criteria"],
        "first_red_candidates": view["first_red_candidates"],
        "expected_red_failure": "First RED 후보가 현재 구현 또는 evidence 부족으로 실패해야 한다.",
        "minimum_green": "acceptance criteria를 만족하는 최소 구현과 검증 evidence만 남긴다.",
        "verification_commands": view["verification_commands"],
        "stop_conditions": view["stop_conditions"],
        "risk_notes": view["risk_notes"],
        "commands": {
            "plan": f".codex/harness/v1 plan {view['id']}{target_flag}",
            "dry_run": dry_command,
            "run": run_command,
        },
    }


def render_next_text(payload: dict[str, Any]) -> str:
    candidates = payload["candidates"]
    recommended = payload["recommended"]
    lines = [
        f"상태: {payload['status']}",
        f"현재: {payload['current']['summary']}",
        "다음 Slice:",
    ]
    for index, item in enumerate(candidates, 1):
        marker = " 추천" if recommended and item["id"] == recommended["id"] else ""
        lines.append(f"{index}. {item['id']} [{item['targets']}, {item['preset']}]{marker}")
        lines.append(f"   - {item['title_ko']}")
    lines.append("Acceptance Criteria:")
    if recommended:
        lines.extend(f"- {criterion}" for criterion in recommended["acceptance_criteria"][:5])
    else:
        lines.append("- backlog 후보 없음")
    lines.append("검증:")
    if recommended:
        lines.extend(f"- {command}" for command in recommended["verification_commands"])
    else:
        lines.append("- not run")
    lines.append(f"다음 행동: {payload['next_command']}")
    return "\n".join(lines)


def render_plan_text(payload: dict[str, Any]) -> str:
    plan = payload["plan"]
    lines = [
        f"상태: {payload['status']}",
        f"목표: {plan['title_ko']} ({plan['id']}, target={plan['targets']}, preset={plan['preset']})",
        plan["description_ko"],
        "Acceptance Criteria:",
    ]
    lines.extend(f"- {criterion}" for criterion in plan["acceptance_criteria"])
    lines.append("First RED:")
    lines.extend(f"- {candidate}" for candidate in plan["first_red_candidates"])
    lines.append(f"Expected RED failure: {plan['expected_red_failure']}")
    lines.append(f"Minimum GREEN: {plan['minimum_green']}")
    lines.append("검증:")
    lines.extend(f"- {command}" for command in plan["verification_commands"])
    lines.append("Stop Conditions:")
    lines.extend(f"- {condition}" for condition in plan["stop_conditions"])
    lines.append(f"다음 행동: {plan['commands']['dry_run']}")
    return "\n".join(lines)


def report_path_from_args(args: argparse.Namespace) -> Path | None:
    if args.from_report:
        return Path(args.from_report).resolve()
    return latest_report_path()


def run_next(args: argparse.Namespace) -> int:
    try:
        slices = load_backlog()
        evidence = read_report_evidence(report_path_from_args(args))
        current = dict(evidence)
        current["git"] = git_context(args.from_git)
        candidates = select_candidates(
            slices,
            evidence,
            limit=args.limit,
            targets=args.targets,
            preset=args.preset,
        )
    except PlanError as exc:
        return fail(str(exc))
    recommended = candidates[0] if candidates else None
    next_command = ".codex/harness/v1 plan " + recommended["id"] if recommended else "backlog 후보를 추가하세요"
    payload = {
        "status": "dry-run" if args.dry_run else "Pass",
        "current": current,
        "candidates": candidates,
        "recommended": recommended,
        "next_command": next_command,
    }
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True))
    else:
        print(render_next_text(payload))
    return 0


def run_plan(args: argparse.Namespace) -> int:
    try:
        slices = load_backlog()
        evidence = read_report_evidence(report_path_from_args(args))
        plan = plan_for_slice(find_slice(slices, args.slice), evidence, targets=args.targets, preset=args.preset)
    except PlanError as exc:
        return fail(str(exc))
    payload = {
        "status": "dry-run" if args.dry_run else "Pass",
        "current": evidence,
        "plan": plan,
    }
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True))
    else:
        print(render_plan_text(payload))
    return 0


def fail(message: str, code: int = 1) -> int:
    print(f"상태: Fail\n차단 사유: {message}\n다음 행동: backlog 또는 option을 확인한 뒤 다시 실행하세요.")
    return code


def add_common(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--json", action="store_true", help="Render machine-readable JSON.")
    parser.add_argument("--from-report", help="Use a specific report JSON/Markdown as recent evidence.")
    parser.add_argument("--from-git", action="store_true", help="Include current git status in the planning context.")
    parser.add_argument("--dry-run", action="store_true", help="Render without writing files; planning never mutates state.")
    parser.add_argument("--targets", choices=sorted(VALID_TARGETS), help="Filter or validate the target class.")
    parser.add_argument("--preset", help="Filter next candidates or override the displayed plan preset.")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Plan Home Search V1 slices before execution.")
    parser.add_argument("--self-test", action="store_true")
    subparsers = parser.add_subparsers(dest="command")

    next_parser = subparsers.add_parser("next", help="Recommend one to three next slice candidates.")
    add_common(next_parser)
    next_parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT, help="Candidate limit, clamped to 1..3.")

    plan_parser = subparsers.add_parser("plan", help="Render an execution brief for one backlog slice.")
    plan_parser.add_argument("slice")
    add_common(plan_parser)
    return parser


def run_self_test() -> int:
    try:
        slices = load_backlog()
        evidence = {
            "gate_risks": ["marker API failure hides current markers"],
            "missing_tests": ["cd apps/web && npm run test"],
            "contract_gaps": [],
            "data_safety_gaps": [],
            "summary": "self-test evidence",
        }
        candidates = select_candidates(slices, evidence, limit=3, targets=None, preset=None)
        plan_item = find_slice(slices, "kakao-map-marker-refresh-flow")
        plan = plan_for_slice(plan_item, evidence, targets=None, preset=None)
        payload = {"status": "Pass", "current": evidence, "candidates": candidates, "recommended": candidates[0]}
        rendered = json.dumps(payload, ensure_ascii=False)
        try:
            plan_for_slice(plan_item, evidence, targets="backend", preset=None)
            target_rejected = False
        except PlanError:
            target_rejected = True
        checks = [
            len(slices) >= 4,
            all(item["targets"] in VALID_TARGETS for item in slices),
            1 <= len(candidates) <= 3,
            payload["recommended"]["id"] == candidates[0]["id"],
            '"recommended"' in rendered,
            plan["targets"] == "frontend",
            target_rejected,
        ]
    except Exception as exc:  # pragma: no cover - self-test diagnostic path.
        print(f"self-test failed: v1_plan: {exc}", file=sys.stderr)
        return 1
    if all(checks):
        print("self-test passed: v1_plan")
        return 0
    print("self-test failed: v1_plan", file=sys.stderr)
    return 1


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        return run_self_test()
    if args.command == "next":
        return run_next(args)
    if args.command == "plan":
        return run_plan(args)
    parser.print_help()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
