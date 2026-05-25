#!/usr/bin/env python3
"""Stop hook verification gate for Home Search.

The hook checks for evidence. It does not run tests, perform review, or call
AI services.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


FALLBACK_REPO_ROOT = Path("/Users/gwongwangjae/home-search")

IGNORED_PARTS = {
    ".gradle",
    ".idea",
    ".next",
    ".vite",
    "build",
    "coverage",
    "dist",
    "node_modules",
    "out",
    "target",
}

SUCCESS_RE = re.compile(
    r"(Process exited with code 0|exit(?:_| )code[\"']?\s*[:=]\s*0|\bBUILD SUCCESSFUL\b|"
    r"\btests? passed\b|KO docs manifest is synchronized|=\s*pass\b)",
    re.IGNORECASE,
)

BACKEND_TEST_RE = re.compile(r"(\./gradlew|gradle)\s+(test|verify)\b")
BACKEND_QUALITY_RE = re.compile(r"(\./gradlew|gradle)\s+backendQualityCheck\b")
FRONTEND_TEST_RE = re.compile(r"npm\s+run\s+(test|build)\b")
KO_CHECK_RE = re.compile(r"bash\s+scripts/check-ko-docs\.sh\b")
COVERAGE_EVIDENCE_RE = re.compile(
    r"(Coverage\s*:\s*>=?\s*90%|coverageCheck\b.*=\s*pass|jacocoTestCoverageVerification\b.*=\s*pass)",
    re.IGNORECASE,
)
OPENAPI_EVIDENCE_RE = re.compile(
    r"(Docs/OpenAPI\s*:\s*(generated|생성).*(verified|검증)|apiDocsCheck\b.*=\s*pass|openapi3\.ya?ml)",
    re.IGNORECASE,
)


def load_payload() -> dict[str, Any]:
    raw = sys.stdin.read()
    if not raw.strip():
        return {}
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def git_root(cwd: Path) -> Path | None:
    try:
        result = subprocess.run(
            ["git", "-C", str(cwd), "rev-parse", "--show-toplevel"],
            cwd=str(cwd) if cwd.exists() else None,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
    except OSError:
        return None
    if result.returncode != 0:
        return None
    root = result.stdout.strip()
    return Path(root).resolve(strict=False) if root else None


def payload_cwd(payload: dict[str, Any]) -> Path:
    raw = payload.get("cwd")
    if isinstance(raw, str) and raw:
        return Path(raw)
    return Path(os.getcwd())


def repo_root_from_payload(payload: dict[str, Any]) -> Path:
    cwd = payload_cwd(payload)
    for candidate in (cwd, Path(os.getcwd())):
        root = git_root(candidate)
        if root is not None:
            return root
    return FALLBACK_REPO_ROOT


def run_git(args: list[str], repo_root: Path) -> list[str]:
    try:
        result = subprocess.run(
            ["git", *args],
            cwd=repo_root,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
    except OSError:
        return []
    if result.returncode != 0:
        return []
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def changed_files(repo_root: Path) -> list[str]:
    paths: set[str] = set()
    for args in (
        ["diff", "--name-only"],
        ["diff", "--cached", "--name-only"],
        ["ls-files", "--others", "--exclude-standard"],
    ):
        paths.update(run_git(args, repo_root))
    return sorted(path for path in paths if not ignored_path(path))


def ignored_path(path: str) -> bool:
    parts = set(Path(path).parts)
    return bool(parts & IGNORED_PARTS)


def transcript_text(payload: dict[str, Any]) -> str:
    parts: list[str] = []
    last = payload.get("last_assistant_message") or payload.get("lastAssistantMessage")
    if isinstance(last, str):
        parts.append(last)
    transcript = payload.get("transcript_path") or payload.get("transcriptPath")
    if isinstance(transcript, str):
        transcript_path = Path(transcript)
        try:
            if transcript_path.exists() and transcript_path.is_file():
                parts.append(transcript_path.read_text(encoding="utf-8", errors="replace"))
        except OSError:
            pass
    return "\n".join(parts)


def has_successful_command(text: str, command_re: re.Pattern[str]) -> bool:
    return bool(command_re.search(text) and SUCCESS_RE.search(text))


def has_backend_quality_evidence(text: str) -> bool:
    return has_successful_command(text, BACKEND_QUALITY_RE)


def has_coverage_evidence(text: str) -> bool:
    return bool(COVERAGE_EVIDENCE_RE.search(text))


def has_openapi_evidence(text: str) -> bool:
    return bool(OPENAPI_EVIDENCE_RE.search(text))


def has_first_red_evidence(text: str) -> bool:
    if re.search(r"blocked/no test environment", text, re.IGNORECASE):
        return True
    first_red_label = r"(?:최초 RED|First RED)"
    if re.search(rf"{first_red_label}\s*:\s*(없음|no|none)", text, re.IGNORECASE):
        return False
    return bool(
        re.search(
            rf"({first_red_label}\s*:\s*(있음|yes)|valid RED|RED validity\s*=\s*Pass|"
            r"예상 RED 실패|expected RED failure|RED failure|failing test)",
            text,
            re.IGNORECASE,
        )
    )


def has_expected_red_failure_evidence(text: str) -> bool:
    if re.search(r"blocked/no test environment", text, re.IGNORECASE):
        return True
    return bool(
        re.search(
            r"(?:예상 RED 실패|Expected RED failure)\s*:\s*(확인|yes|confirmed|해당 없음|n/a)",
            text,
            re.IGNORECASE,
        )
    )


def has_minimum_green_evidence(text: str) -> bool:
    if re.search(r"blocked/no test environment", text, re.IGNORECASE):
        return True
    return bool(
        re.search(
            r"(?:최소 GREEN|Minimum GREEN)\s*:\s*(확인|yes|confirmed|해당 없음|n/a)",
            text,
            re.IGNORECASE,
        )
    )


def has_tdd_work_evidence(text: str) -> bool:
    return (
        has_first_red_evidence(text)
        and has_expected_red_failure_evidence(text)
        and has_minimum_green_evidence(text)
    )


def has_contract_reviewer_evidence(text: str) -> bool:
    return bool(
        re.search(
            r"contract-reviewer\s*:\s*(?:게이트 결정|Gate decision)\s*=\s*(Pass|Partial|Fail)",
            text,
            re.IGNORECASE,
        )
    )


def has_implementation_review_evidence(text: str) -> bool:
    return bool(
        re.search(r"reviewer\s*:\s*(?:지적사항|Findings)\s*=\s*(none|listed|없음|있음)", text, re.IGNORECASE)
        or re.search(r"tdd-guide\s*:\s*RED validity\s*=\s*(Pass|Partial|Fail)", text, re.IGNORECASE)
    )


def has_short_korean_review(text: str) -> bool:
    required_groups = (
        ("상태:",),
        ("최초 RED:", "First RED:"),
        ("검증:",),
        ("주요 위험:",),
        ("다음 행동:",),
    )
    if not all(any(label in text for label in labels) for labels in required_groups):
        return False
    return bool(re.search(r"상태:\s*(Pass|Partial|Fail)", text))


def is_markdown(path: str) -> bool:
    lowered = path.lower()
    return lowered.endswith(".md") and not lowered.endswith(("_ko.md", "_ko.local.md"))


def is_ko_doc(path: str) -> bool:
    lowered = path.lower()
    return lowered.endswith("_ko.md") and not lowered.endswith("_ko.local.md")


def has_ko_approval_evidence(text: str, ko_paths: list[str]) -> bool:
    if not ko_paths:
        return True
    if not re.search(r"KO 수정 승인\s*:\s*확인", text):
        return False
    if "KO 생성 기준: canonical source only" not in text:
        return False
    return all(path in text for path in ko_paths)


def is_contract_related(path: str) -> bool:
    if path == "docs/API_CONTRACT.md":
        return True
    if path.startswith("apps/api/") and re.search(
        r"/(web|dto|controller|error|validation)/|Controller|Request|Response|ProblemDetail",
        path,
    ):
        return True
    if path.startswith("apps/web/") and re.search(
        r"/(api|fixtures|mocks?)/|adapter|marker|fetch",
        path,
        re.IGNORECASE,
    ):
        return True
    return False


def is_behavior_change(path: str) -> bool:
    if path.startswith("apps/api/src/main/"):
        return True
    if path.startswith("apps/web/src/") and not re.search(r"\.test\.[tj]sx?$", path):
        return True
    return False


def is_implementation_change(path: str) -> bool:
    if is_behavior_change(path):
        return True
    if path.startswith(".codex/hooks"):
        return True
    if path == ".codex/hooks.json":
        return True
    return False


def missing_evidence(files: list[str], text: str) -> list[str]:
    missing: list[str] = []

    if any(path.startswith("apps/api/") for path in files):
        if not has_backend_quality_evidence(text):
            missing.append("apps/api 변경: backendQualityCheck evidence 필요")
        if not has_coverage_evidence(text):
            missing.append("apps/api 변경: Coverage >=90% evidence 필요")
        if not has_openapi_evidence(text):
            missing.append("apps/api 변경: Docs/OpenAPI generated + verified evidence 필요")

    if any(path.startswith("apps/web/") for path in files):
        if not has_successful_command(text, FRONTEND_TEST_RE):
            missing.append("apps/web 변경: npm run test/build evidence 필요")

    if any(is_behavior_change(path) for path in files):
        if not has_tdd_work_evidence(text):
            missing.append("behavior 변경: 최초 RED, 예상 RED 실패, 최소 GREEN evidence 필요")

    if any(is_contract_related(path) for path in files):
        if not has_contract_reviewer_evidence(text):
            missing.append("contract 관련 변경: contract-reviewer evidence 필요")

    if any(is_implementation_change(path) for path in files):
        if not has_implementation_review_evidence(text):
            missing.append("구현 변경: reviewer 또는 tdd-guide evidence 필요")
        if not has_short_korean_review(text):
            missing.append("짧은 한글 리뷰 형식 필요")

    if any(is_markdown(path) for path in files):
        if not has_successful_command(text, KO_CHECK_RE):
            missing.append("Markdown 변경: bash scripts/check-ko-docs.sh evidence 필요")

    ko_paths = sorted(path for path in files if is_ko_doc(path))
    if ko_paths:
        if not has_successful_command(text, KO_CHECK_RE):
            missing.append("KO 문서 변경: bash scripts/check-ko-docs.sh evidence 필요")
        if not has_ko_approval_evidence(text, ko_paths):
            missing.append("KO 문서 변경: KO 수정 승인/대상/canonical source only evidence 필요")

    return missing


def block(lines: list[str]) -> None:
    prompt_lines = ["완료 전 evidence가 부족합니다."]
    prompt_lines.extend(f"- {line}" for line in lines[:7])
    prompt_lines.append("다음 행동: 누락된 검증/리뷰 evidence를 남기고 짧은 한글 리뷰를 작성하세요.")
    prompt = "\n".join(prompt_lines[:10])
    print(json.dumps({"decision": "block", "reason": prompt}, ensure_ascii=False))
    raise SystemExit(0)


def run_self_test() -> int:
    missing_red = missing_evidence(
        ["apps/api/src/main/java/com/home/App.java"],
        "\n".join(
            [
                "상태: Partial",
                "검증: ./gradlew backendQualityCheck = pass",
                "Coverage: >=90%",
                "Docs/OpenAPI: generated + verified",
                "reviewer: 지적사항 = 없음",
                "주요 위험: 없음",
                "다음 행동: 최초 RED 보강",
            ]
        ),
    )
    missing_review = missing_evidence(
        [".codex/hooks/pre_tool_use_policy.py"],
        "\n".join(
            [
                "최초 RED: blocked/no test environment",
                "예상 RED 실패: 해당 없음",
                "최소 GREEN: 확인",
                "검증: python3 .codex/hooks/pre_tool_use_policy.py --self-test = pass",
                "reviewer: 지적사항 = 없음",
            ]
        ),
    )
    complete_hook_review = missing_evidence(
        [".codex/hooks/pre_tool_use_policy.py"],
        "\n".join(
            [
                "상태: Pass",
                "최초 RED: blocked/no test environment",
                "예상 RED 실패: 해당 없음",
                "최소 GREEN: 확인",
                "검증: python3 .codex/hooks/pre_tool_use_policy.py --self-test = pass",
                "reviewer: 지적사항 = 없음",
                "주요 위험: 없음",
                "다음 행동: 없음",
            ]
        ),
    )
    markdown_complete = missing_evidence(
        [".codex/harness/home"],
        "검증: bash scripts/check-ko-docs.sh = pass",
    )
    missing_backend_quality = missing_evidence(
        ["apps/api/src/main/java/com/home/App.java"],
        "\n".join(
            [
                "상태: Pass",
                "최초 RED: 있음",
                "예상 RED 실패: 확인",
                "최소 GREEN: 확인",
                "검증: ./gradlew test = pass",
                "Coverage: >=90%",
                "Docs/OpenAPI: generated + verified",
                "reviewer: 지적사항 = 없음",
                "주요 위험: 없음",
                "다음 행동: 없음",
            ]
        ),
    )
    missing_coverage = missing_evidence(
        ["apps/api/src/main/java/com/home/App.java"],
        "\n".join(
            [
                "상태: Pass",
                "최초 RED: 있음",
                "예상 RED 실패: 확인",
                "최소 GREEN: 확인",
                "검증: ./gradlew backendQualityCheck = pass",
                "Docs/OpenAPI: generated + verified",
                "reviewer: 지적사항 = 없음",
                "주요 위험: 없음",
                "다음 행동: 없음",
            ]
        ),
    )
    ko_missing_approval = missing_evidence(
        ["AGENTS_KO.md"],
        "검증: bash scripts/check-ko-docs.sh = pass",
    )
    ko_complete = missing_evidence(
        ["AGENTS_KO.md"],
        "\n".join(
            [
                "KO 수정 승인: 확인",
                "KO 대상: AGENTS_KO.md",
                "KO 생성 기준: canonical source only",
                "검증: bash scripts/check-ko-docs.sh = pass",
            ]
        ),
    )

    tests = [
        ("missing First RED is detected", any("최초 RED" in item or "First RED" in item for item in missing_red)),
        ("missing Korean review is detected", any("짧은 한글 리뷰" in item for item in missing_review)),
        ("complete hook review passes", not complete_hook_review),
        ("markdown KO evidence passes", not markdown_complete),
        ("backendQualityCheck evidence is required", any("backendQualityCheck" in item for item in missing_backend_quality)),
        ("coverage evidence is required", any("Coverage" in item for item in missing_coverage)),
        ("KO approval evidence is required", any("KO 수정 승인" in item for item in ko_missing_approval)),
        ("KO approval evidence passes", not ko_complete),
    ]
    failed = [name for name, passed in tests if not passed]
    if failed:
        print("self-test failed:")
        for name in failed:
            print(f"- {name}")
        return 1
    print("self-test passed: stop_verification_gate")
    return 0


def main() -> None:
    payload = load_payload()
    repo_root = repo_root_from_payload(payload)
    files = changed_files(repo_root)
    if not files:
        return

    missing = missing_evidence(files, transcript_text(payload))
    if missing:
        block(missing)


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        raise SystemExit(run_self_test())
    main()
