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
from typing import Any, Callable

from skill_routing import routing_payload, routing_text


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)

HARNESS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = HARNESS_ROOT.parents[1]
BACKLOG_PATH = HARNESS_ROOT / "slices" / "backlog.toml"
REPORT_ROOT = HARNESS_ROOT / "reports"
LLM_REPLAN_ROOT = Path("/private/tmp/home-search-v1-llm-replan")
VALID_STATUSES = {"candidate", "planned", "running", "done", "blocked"}
VALID_TARGETS = {"backend", "frontend", "both", "planning-only"}
VALID_INTENTS = {"plan", "dry", "run", "push", "pr", "report", "recover"}
VALID_PLANNING_MODES = {"standard", "critique", "llm-replan"}
DEFAULT_LIMIT = 3
DEFAULT_PLAN_ITERATIONS = 3


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
    json_candidates = [path for path in candidates if path.suffix == ".json"]
    if json_candidates:
        return max(json_candidates, key=lambda path: path.stat().st_mtime)
    paired_markdown = [path for path in candidates if path.suffix == ".md" and path.with_suffix(".json").exists()]
    if paired_markdown:
        return max(paired_markdown, key=lambda path: path.stat().st_mtime)
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
        "summary": "최근 report evidence를 찾지 못했습니다",
    }
    if path is None or not path.exists():
        return evidence
    if path.suffix == ".json":
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            evidence["summary"] = f"report 읽기 실패: {exc}"
            return evidence
        if not isinstance(payload, dict):
            evidence["summary"] = "report payload가 object가 아닙니다"
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
                "summary": str(payload.get("gate_review") or payload.get("next_action") or "JSON report 로드됨"),
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
    contract_gap = bool(re.search(r"(?ms)^### 계약 위험\s*\n(?!- none\s*$)-\s+.+", text))
    data_gap = bool(re.search(r"(?ms)^### 데이터[^\n]*\n(?!- none\s*$)-\s+.+", text))
    evidence.update(
        {
            "status": "Fail" if "상태: fail" in lower else "Partial" if "partial" in lower else "unknown",
            "gate_risks": risks[:5],
            "missing_tests": missing_tests[:5],
            "contract_gaps": ["이전 report에 contract risk 언급 있음"] if contract_gap else [],
            "data_safety_gaps": ["이전 report에 data safety 언급 있음"] if data_gap else [],
            "summary": "Markdown report 로드됨",
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


def printable(command: list[str]) -> str:
    return " ".join(command)


def first_lines(text: str, limit: int = 3) -> str:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    return " | ".join(lines[:limit])


def target_flag_for(targets: str) -> str:
    return f" --targets {targets}"


def action_commands(slice_id: str, targets: str, *, preset: str | None = None) -> dict[str, str]:
    target_flag = target_flag_for(targets)
    preset_flag = f" --preset {preset}" if preset else ""
    if targets == "planning-only":
        return {
            "plan": f".codex/harness/v1 plan {slice_id}{target_flag}{preset_flag}",
            "dry": "planning-only slice: 구현 dry-run 대신 계획 evidence만 검토",
            "run": "planning-only slice: 자동 구현 실행 금지",
            "push": "planning-only slice: push 금지",
            "pr": "planning-only slice: PR 자동화 금지",
            "report": f".codex/harness/v1 report {slice_id}",
            "recover": ".codex/harness/v1 hook이 막은 evidence 복구",
        }
    return {
        "plan": f".codex/harness/v1 plan {slice_id}{target_flag}{preset_flag}",
        "dry": f".codex/harness/v1 dry {slice_id}{target_flag}{preset_flag}",
        "run": f".codex/harness/v1 run {slice_id}{target_flag}{preset_flag}",
        "push": f".codex/harness/v1 run {slice_id}{target_flag}{preset_flag} --push",
        "pr": f".codex/harness/v1 run {slice_id}{target_flag}{preset_flag} --pr",
        "report": f".codex/harness/v1 report {slice_id}",
        "recover": ".codex/harness/v1 hook이 막은 evidence 복구",
    }


def action_options(commands: dict[str, str], targets: str) -> list[dict[str, str]]:
    rows = [
        {"intent": "plan", "when": "계획만 검토", "command": commands["plan"], "result": "mutation 없음"},
        {"intent": "dry", "when": "실행 전 안전 확장 확인", "command": commands["dry"], "result": "mutation 없음"},
        {"intent": "run", "when": "로컬 구현과 integration report까지", "command": commands["run"], "result": "remote publish 없음"},
        {"intent": "push", "when": "integration branch 원격 publish까지", "command": commands["push"], "result": "PR 생성 없음"},
        {"intent": "pr", "when": "draft PR open까지", "command": commands["pr"], "result": "PR merge 없음"},
        {"intent": "report", "when": "기존 payload 재보고", "command": commands["report"], "result": "구현 없음"},
        {"intent": "recover", "when": "검증/hook 실패 복구 계획", "command": commands["recover"], "result": "기본 mutation 없음"},
    ]
    if targets == "planning-only":
        return [row for row in rows if row["intent"] in {"plan", "report", "recover"}]
    return rows


def action_profile(intent: str, commands: dict[str, str], targets: str) -> dict[str, Any]:
    if intent not in VALID_INTENTS:
        raise PlanError(f"invalid --intent: {intent}")
    if targets == "planning-only" and intent in {"dry", "run", "push", "pr"}:
        return {
            "intent": intent,
            "sequence": ["plan"],
            "command": commands["plan"],
            "publishes": False,
            "mutates": False,
            "summary": "planning-only target은 구현, commit, integration, push, PR 자동화를 금지한다.",
        }
    profiles: dict[str, dict[str, Any]] = {
        "plan": {
            "sequence": ["plan pass 1", "plan pass 2", "plan pass 3"],
            "command": commands["plan"],
            "publishes": False,
            "mutates": False,
            "summary": "3-pass planning 결과와 다음 행동 옵션만 출력한다.",
        },
        "dry": {
            "sequence": ["plan pass 1", "plan pass 2", "plan pass 3", "dry-run"],
            "command": commands["dry"],
            "publishes": False,
            "mutates": False,
            "summary": "확장된 workflow를 실제 변경 없이 확인한다.",
        },
        "run": {
            "sequence": ["plan pass 1", "plan pass 2", "plan pass 3", "dry-run", "run"],
            "command": commands["run"],
            "publishes": False,
            "mutates": True,
            "summary": "local target commit, integration branch, report까지 만든다.",
        },
        "push": {
            "sequence": ["plan pass 1", "plan pass 2", "plan pass 3", "dry-run --push", "lint preflight", "run --push"],
            "command": commands["push"],
            "publishes": True,
            "mutates": True,
            "summary": "integration branch를 원격에 push하되 PR은 만들지 않는다.",
        },
        "pr": {
            "sequence": ["plan pass 1", "plan pass 2", "plan pass 3", "dry-run --pr", "lint preflight", "run --pr"],
            "command": commands["pr"],
            "publishes": True,
            "mutates": True,
            "summary": "integration branch push 후 draft PR을 생성한다.",
        },
        "report": {
            "sequence": ["load payload", "render report"],
            "command": commands["report"],
            "publishes": False,
            "mutates": False,
            "summary": "기존 payload를 바탕으로 report만 다시 렌더링한다.",
        },
        "recover": {
            "sequence": ["capture failure", "recover plan"],
            "command": commands["recover"],
            "publishes": False,
            "mutates": False,
            "summary": "검증 실패나 hook block을 실행 없이 복구 계획으로 전환한다.",
        },
    }
    profile = dict(profiles[intent])
    profile["intent"] = intent
    return profile


def planning_mode_profile(profile: dict[str, Any], planning_mode: str, plan_iterations: int) -> dict[str, Any]:
    if planning_mode != "llm-replan":
        return profile
    updated = dict(profile)
    pass_count = max(1, min(plan_iterations, 3))
    llm_passes = [f"LLM replan pass {index}" for index in range(1, pass_count + 1)]
    sequence = list(updated["sequence"])
    if sequence[:3] == ["plan pass 1", "plan pass 2", "plan pass 3"]:
        sequence = llm_passes + sequence[3:]
    else:
        sequence = llm_passes + sequence
    updated["sequence"] = sequence
    updated["summary"] = f"Codex exec read-only LLM 재계획을 이전 pass output 기반으로 {pass_count}회 수행한 뒤 다음 행동을 확정한다."
    return updated


def planning_iterations(
    view: dict[str, Any],
    evidence: dict[str, Any],
    profile: dict[str, Any],
    *,
    plan_iterations: int,
    planning_mode: str,
) -> list[dict[str, Any]]:
    if planning_mode == "llm-replan":
        return [
            {
                "pass": 1,
                "name": "LLM 초안 재계획",
                "purpose": "Codex exec read-only pass가 backlog, target, preset, 최근 evidence로 실행 계획을 다시 만든다.",
                "additions": [
                    f"target={view['targets']}",
                    f"preset={view['preset']}",
                    f"recent evidence={evidence.get('summary', 'not found')}",
                ],
                "decision": "초안 plan의 인수 기준, 최초 RED, 검증, 중단 조건을 재정의한다.",
            },
            {
                "pass": 2,
                "name": "LLM 비판 재계획",
                "purpose": "Codex exec read-only pass가 pass 1 output을 입력으로 받아 누락된 guardrail을 비판하고 보강한다.",
                "additions": [
                    "First RED, 예상 RED 실패, 최소 GREEN 재검토",
                    "V1 API URL/request/response/unit/error 영향 재검토",
                    "target별 검증 명령과 publish/readiness 중단 조건 재검토",
                ],
                "decision": "구현 전 stop condition과 verification evidence를 더 엄격하게 만든다.",
            },
            {
                "pass": 3,
                "name": "LLM 최종 재계획",
                "purpose": "Codex exec read-only pass가 pass 2 output을 입력으로 받아 최종 실행 순서와 다음 행동을 확정한다.",
                "additions": [
                    f"intent={profile['intent']}",
                    "sequence=" + " -> ".join(profile["sequence"]),
                    f"publish={'yes' if profile['publishes'] else 'no'}",
                ],
                "decision": profile["summary"],
            },
        ][: max(1, min(plan_iterations, 3))]
    iterations = [
        {
            "pass": 1,
            "name": "초안",
            "purpose": "backlog, target, preset, 최근 report evidence를 모아 기본 실행 계획을 만든다.",
            "additions": [
                f"target={view['targets']}",
                f"preset={view['preset']}",
                f"recent evidence={evidence.get('summary', 'not found')}",
            ],
            "decision": "기본 목표, 인수 기준, 검증 명령을 확정한다.",
        },
        {
            "pass": 2,
            "name": "검토",
            "purpose": "planning/tdd/api-contract/target skill contract로 누락된 guardrail을 보강한다.",
            "additions": [
                "First RED, 예상 RED 실패, 최소 GREEN 확인",
                "V1 API URL/request/response/unit/error 영향 확인",
                "target별 검증 명령과 중단 조건 확인",
            ],
            "decision": "구현 전 stop condition과 verification evidence를 명확히 한다.",
        },
        {
            "pass": 3,
            "name": "최종 실행 플랜",
            "purpose": "사용자 intent에 맞는 action profile과 다음 행동 옵션을 확정한다.",
            "additions": [
                f"intent={profile['intent']}",
                "sequence=" + " -> ".join(profile["sequence"]),
                f"publish={'yes' if profile['publishes'] else 'no'}",
            ],
            "decision": profile["summary"],
        },
    ]
    return iterations[: max(1, min(plan_iterations, 3))]


def critique_plan(view: dict[str, Any], profile: dict[str, Any], commands: dict[str, str]) -> dict[str, Any]:
    findings: list[str] = []
    target = view["targets"]
    verification = set(view["verification_commands"])
    if not view["acceptance_criteria"]:
        findings.append("인수 기준이 비어 있습니다.")
    if not view["first_red_candidates"]:
        findings.append("최초 RED 후보가 비어 있습니다.")
    if not view["stop_conditions"]:
        findings.append("중단 조건이 비어 있습니다.")
    if target == "frontend":
        expected = {"cd apps/web && npm run test", "cd apps/web && npm run build"}
        missing = sorted(expected - verification)
        if missing:
            findings.append("frontend 검증 명령이 누락되었습니다: " + ", ".join(missing))
    if target == "backend" and "cd apps/api && ./gradlew backendQualityCheck" not in verification:
        findings.append("backendQualityCheck 검증 명령이 누락되었습니다.")
    if profile["intent"] in {"push", "pr"}:
        sequence = " ".join(profile["sequence"])
        if "dry-run" not in sequence:
            findings.append("publish intent에는 dry-run preflight가 필요합니다.")
        if "lint preflight" not in sequence:
            findings.append("publish intent에는 lint preflight가 필요합니다.")
        if profile["intent"] == "pr" and not commands["pr"].endswith("--pr"):
            findings.append("PR intent command가 --pr로 끝나지 않습니다.")
        if profile["intent"] == "push" and not commands["push"].endswith("--push"):
            findings.append("push intent command가 --push로 끝나지 않습니다.")
    return {
        "status": "pass" if not findings else "fail",
        "checked": [
            "인수 기준",
            "최초 RED",
            "중단 조건",
            "target verification",
            "publish preflight sequence",
            "next action command",
        ],
        "findings": findings,
        "summary": "누락 없음" if not findings else "; ".join(findings),
    }


def llm_replan_output_path(slice_id: str, pass_number: int) -> Path:
    return LLM_REPLAN_ROOT / f"{slugify(slice_id)}-llm-replan-pass{pass_number}.md"


def llm_replan_prompt(seed_payload: dict[str, Any], pass_number: int, total_passes: int, previous_output: str) -> str:
    previous = previous_output.strip() or "없음: 이번 pass가 첫 LLM 재계획입니다."
    seed = json.dumps(seed_payload, ensure_ascii=False, indent=2, sort_keys=True)
    return f"""# Home Search V1 LLM Recursive Replan

$v1-slice-harness mode=plan
$planning
$tdd
$api-contract

You are running pass {pass_number} of an exactly {total_passes}-pass recursive LLM replanning chain.

Rules:
- Read-only planning only. Do not edit files, create branches, create worktrees, commit, push, open PRs, or run product verification.
- Do not read, search, summarize, quote, or use `*_KO.md`, `*_KO.local.md`, lowercase variants, or `/Users/gwongwangjae/saved-ai-exam`.
- Use the seed JSON and previous pass output below as the planning context.
- Pass 1 must produce a revised execution plan from the seed plan.
- Pass 2 must critique pass 1 and produce a revised plan.
- The final pass must critique the previous pass and produce the final execution plan and next action.
- Keep output Korean-first. Keep commands, paths, API fields, status values, and skill triggers exact.

Required output labels:
상태:
재계획 pass:
인수 기준:
최초 RED:
예상 RED 실패:
최소 GREEN:
검증:
중단 조건:
변경 사항:
잔여 위험:
다음 행동:

Seed plan JSON:
```json
{seed}
```

Previous pass output:
```text
{previous}
```
"""


def llm_replan_command(codex_bin: str, output_path: Path, prompt: str) -> list[str]:
    return [
        codex_bin,
        "exec",
        "--cd",
        str(REPO_ROOT),
        "--sandbox",
        "read-only",
        "--output-last-message",
        str(output_path),
        prompt,
    ]


def run_llm_replans(
    seed_payload: dict[str, Any],
    *,
    codex_bin: str,
    dry_run: bool,
    skip: bool,
    command_runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> list[dict[str, Any]]:
    plan = seed_payload["plan"]
    passes: list[dict[str, Any]] = []
    previous_output = ""
    max_passes = max(1, min(int(plan.get("plan_iterations") or len(plan.get("planning_iterations", [])) or 3), 3))
    for pass_number in range(1, max_passes + 1):
        output_path = llm_replan_output_path(str(plan["id"]), pass_number)
        prompt = llm_replan_prompt(seed_payload, pass_number, max_passes, previous_output)
        command = llm_replan_command(codex_bin, output_path, prompt)
        item: dict[str, Any] = {
            "pass": pass_number,
            "status": "not run",
            "output_path": str(output_path),
            "command": printable(command[:-1] + ["<prompt>"]),
            "summary": "",
        }
        if skip:
            item.update({"status": "skipped", "summary": "--skip-llm-replan"})
            passes.append(item)
            previous_output = f"pass {pass_number} skipped: --skip-llm-replan"
            continue
        if dry_run:
            item.update({"status": "not run", "summary": "dry-run; Codex exec command prepared"})
            passes.append(item)
            previous_output = f"pass {pass_number} dry-run: command prepared"
            continue

        output_path.parent.mkdir(parents=True, exist_ok=True)
        result = command_runner(
            command,
            cwd=REPO_ROOT,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        stdout = result.stdout or ""
        stderr = result.stderr or ""
        if result.returncode != 0:
            item.update({"status": "fail", "summary": first_lines(stderr or stdout or f"exit {result.returncode}")})
            passes.append(item)
            raise PlanError(f"LLM replan pass {pass_number} failed: {item['summary']}")
        if output_path.exists():
            previous_output = output_path.read_text(encoding="utf-8", errors="replace")
        else:
            previous_output = stdout
        item.update({"status": "pass", "summary": first_lines(previous_output or stdout or "no output")})
        passes.append(item)
    return passes


def plan_for_slice(
    item: dict[str, Any],
    evidence: dict[str, Any],
    *,
    targets: str | None,
    preset: str | None,
    intent: str = "plan",
    plan_iterations: int = DEFAULT_PLAN_ITERATIONS,
    planning_mode: str = "standard",
) -> dict[str, Any]:
    if targets and targets not in VALID_TARGETS:
        raise PlanError(f"invalid --targets: {targets}")
    if planning_mode not in VALID_PLANNING_MODES:
        raise PlanError(f"invalid --planning-mode: {planning_mode}")
    if targets and targets != item["targets"]:
        raise PlanError(f"{item['id']} backlog target is {item['targets']}, not {targets}")
    view = candidate_view(item, evidence)
    chosen_preset = slugify(preset) if preset else view["preset"]
    commands = action_commands(view["id"], view["targets"], preset=slugify(preset) if preset else None)
    profile = planning_mode_profile(action_profile(intent, commands, view["targets"]), planning_mode, plan_iterations)
    iterations = planning_iterations(view, evidence, profile, plan_iterations=plan_iterations, planning_mode=planning_mode)
    return {
        "id": view["id"],
        "title_ko": view["title_ko"],
        "description_ko": view["description_ko"],
        "status": view["status"],
        "targets": view["targets"],
        "preset": chosen_preset,
        "intent": intent,
        "planning_mode": planning_mode,
        "planning_iterations": iterations,
        "critique": critique_plan(view, profile, commands) if planning_mode == "critique" else None,
        "llm_replans": [],
        "action_profile": profile,
        "action_options": action_options(commands, view["targets"]),
        "acceptance_criteria": view["acceptance_criteria"],
        "first_red_candidates": view["first_red_candidates"],
        "expected_red_failure": "최초 RED 후보가 현재 구현 또는 evidence 부족으로 실패해야 한다.",
        "minimum_green": "acceptance criteria를 만족하는 최소 구현과 검증 evidence만 남긴다.",
        "skill_routing": routing_payload("plan", view["targets"]),
        "skill_routing_text": routing_text("plan", view["targets"]),
        "verification_commands": view["verification_commands"],
        "stop_conditions": view["stop_conditions"],
        "risk_notes": view["risk_notes"],
        "commands": commands,
    }


def render_next_text(payload: dict[str, Any]) -> str:
    candidates = payload["candidates"]
    recommended = payload["recommended"]
    lines = [
        f"상태: {payload['status']}",
        f"현재: {payload['current']['summary']}",
        "다음 slice 후보:",
    ]
    for index, item in enumerate(candidates, 1):
        marker = " 추천" if recommended and item["id"] == recommended["id"] else ""
        lines.append(f"{index}. {item['id']} [{item['targets']}, {item['preset']}]{marker}")
        lines.append(f"   - {item['title_ko']}")
    lines.append("인수 기준:")
    if recommended:
        lines.extend(f"- {criterion}" for criterion in recommended["acceptance_criteria"][:5])
    else:
        lines.append("- backlog 후보 없음")
    lines.append("검증:")
    if recommended:
        lines.extend(f"- {command}" for command in recommended["verification_commands"])
    else:
        lines.append("- not run")
    lines.append("사용 skill:")
    lines.append(routing_text("next", recommended["targets"] if recommended else None))
    lines.append(f"다음 행동: {payload['next_command']}")
    return "\n".join(lines)


def render_plan_text(payload: dict[str, Any]) -> str:
    plan = payload["plan"]
    profile = plan["action_profile"]
    lines = [
        f"상태: {payload['status']}",
        f"목표: {plan['title_ko']} ({plan['id']}, target={plan['targets']}, preset={plan['preset']}, intent={plan['intent']}, planning_mode={plan['planning_mode']})",
        plan["description_ko"],
        "planning 단계:",
    ]
    for item in plan["planning_iterations"]:
        additions = "; ".join(item["additions"])
        lines.append(f"- pass {item['pass']} {item['name']}: {item['purpose']} decision={item['decision']} additions={additions}")
    lines.extend(
        [
            "최종 실행 순서:",
            "- " + " -> ".join(profile["sequence"]),
            f"- publish: {'yes' if profile['publishes'] else 'no'}",
            f"- mutation: {'yes' if profile['mutates'] else 'no'}",
            f"- command: {profile['command']}",
        ]
    )
    if plan.get("critique"):
        critique = plan["critique"]
        lines.append("critique:")
        lines.append(f"- status: {critique['status']}")
        lines.append(f"- checked: {', '.join(critique['checked'])}")
        lines.append(f"- summary: {critique['summary']}")
    if plan.get("llm_replans"):
        lines.append("LLM 재계획:")
        for item in plan["llm_replans"]:
            lines.append(
                f"- pass {item['pass']}: status={item['status']} output={item['output_path']} summary={item['summary']}"
            )
    lines.append("인수 기준:")
    lines.extend(f"- {criterion}" for criterion in plan["acceptance_criteria"])
    lines.append("최초 RED:")
    lines.extend(f"- {candidate}" for candidate in plan["first_red_candidates"])
    lines.append(f"예상 RED 실패: {plan['expected_red_failure']}")
    lines.append(f"최소 GREEN: {plan['minimum_green']}")
    lines.append("사용 skill:")
    lines.append(plan["skill_routing_text"])
    lines.append("검증:")
    lines.extend(f"- {command}" for command in plan["verification_commands"])
    lines.append("중단 조건:")
    lines.extend(f"- {condition}" for condition in plan["stop_conditions"])
    lines.append("다음 행동 옵션:")
    lines.append("| intent | 언제 사용 | command | 결과 |")
    lines.append("| --- | --- | --- | --- |")
    for option in plan["action_options"]:
        lines.append(f"| {option['intent']} | {option['when']} | `{option['command']}` | {option['result']} |")
    lines.append(f"다음 행동: {profile['command']}")
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
        plan = plan_for_slice(
            find_slice(slices, args.slice),
            evidence,
            targets=args.targets,
            preset=args.preset,
            intent=args.intent,
            plan_iterations=args.plan_iterations,
            planning_mode=args.planning_mode,
        )
    except PlanError as exc:
        return fail(str(exc))
    payload = {
        "status": "dry-run" if args.dry_run else "Pass",
        "current": evidence,
        "plan": plan,
    }
    if args.planning_mode == "llm-replan":
        try:
            plan["llm_replans"] = run_llm_replans(
                payload,
                codex_bin=args.codex_bin,
                dry_run=args.dry_run,
                skip=args.skip_llm_replan,
            )
        except PlanError as exc:
            return fail(str(exc))
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
    parser.add_argument("--intent", choices=sorted(VALID_INTENTS), default="plan", help="Plan for a specific next action.")
    parser.add_argument(
        "--planning-mode",
        choices=sorted(VALID_PLANNING_MODES),
        default="standard",
        help="Render the base plan, deterministic critique, or read-only Codex exec recursive LLM replanning.",
    )
    parser.add_argument(
        "--plan-iterations",
        type=int,
        default=DEFAULT_PLAN_ITERATIONS,
        choices=(1, 2, 3),
        help="Number of planning passes to render.",
    )
    parser.add_argument("--codex-bin", default="codex", help="Codex CLI binary used by --planning-mode llm-replan.")
    parser.add_argument(
        "--skip-llm-replan",
        action="store_true",
        help="Record llm-replan passes as skipped without invoking Codex exec.",
    )


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
        plan = plan_for_slice(plan_item, evidence, targets=None, preset=None, intent="push", planning_mode="critique")
        llm_plan = plan_for_slice(plan_item, evidence, targets=None, preset=None, intent="pr", planning_mode="llm-replan")

        llm_commands: list[list[str]] = []

        def fake_runner(command: list[str], **_: Any) -> subprocess.CompletedProcess[str]:
            llm_commands.append(command)
            output = Path(command[command.index("--output-last-message") + 1])
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(
                f"상태: Pass\n재계획 pass: {len(llm_commands)}\n다음 행동: pass {len(llm_commands)}\n",
                encoding="utf-8",
            )
            return subprocess.CompletedProcess(command, 0, "", "")

        llm_replans = run_llm_replans(
            {"status": "Pass", "current": evidence, "plan": llm_plan},
            codex_bin="codex",
            dry_run=False,
            skip=False,
            command_runner=fake_runner,
        )
        for item in llm_replans:
            Path(str(item["output_path"])).unlink(missing_ok=True)
        explicit_preset_plan = plan_for_slice(
            plan_item,
            evidence,
            targets=None,
            preset="map-ui-state",
            intent="pr",
        )
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
            plan["intent"] == "push",
            plan["planning_mode"] == "critique",
            plan["critique"]["status"] == "pass",
            "llm-replan" in VALID_PLANNING_MODES,
            llm_plan["planning_mode"] == "llm-replan",
            len(llm_replans) == 3,
            all(item["status"] == "pass" for item in llm_replans),
            len(llm_commands) == 3,
            all(command[:2] == ["codex", "exec"] for command in llm_commands),
            all("--sandbox" in command and command[command.index("--sandbox") + 1] == "read-only" for command in llm_commands),
            "Previous pass output" in llm_commands[1][-1],
            "재계획 pass: 1" in llm_commands[1][-1],
            len(plan["planning_iterations"]) == 3,
            plan["action_profile"]["command"].endswith("--push"),
            explicit_preset_plan["action_profile"]["command"].endswith("--preset map-ui-state --pr"),
            "--preset map-ui-state" in explicit_preset_plan["commands"]["dry"],
            any(option["intent"] == "pr" for option in plan["action_options"]),
            "$planning" in plan["skill_routing_text"],
            "$tdd" in plan["skill_routing_text"],
            "$api-contract" in plan["skill_routing_text"],
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
