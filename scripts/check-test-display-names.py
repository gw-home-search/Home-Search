#!/usr/bin/env python3
"""Check Korean-first test display names without scanning ordinary literals."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)

REPO_ROOT = Path(__file__).resolve().parents[1]
HANGUL_RE = re.compile(r"[\u1100-\u11ff\u3130-\u318f\uac00-\ud7a3]")

JAVA_DISPLAY_NAME_RE = re.compile(r'@DisplayName\("(?P<name>(?:\\.|[^"\\])*)"\)')
JAVA_METHOD_RE = re.compile(
    r"^\s*(?:public|private|protected\s+)?(?:static\s+)?[\w<>\[\], ?]+\s+"
    r"(?P<name>\w+)\s*\([^;]*\)\s*(?:throws\b[^{]+)?\{?\s*$"
)
JAVA_PARAMETERIZED_NAME_RE = re.compile(r'@ParameterizedTest\s*\(\s*name\s*=\s*"(?P<name>(?:\\.|[^"\\])*)"\s*\)')
JAVA_METHOD_SOURCE_RE = re.compile(r'@MethodSource\("(?P<name>[^"]+)"\)')
JAVA_ARGUMENTS_OF_RE = re.compile(r'Arguments\.of\(\s*"(?P<name>(?:\\.|[^"\\])*)"')

TS_TEST_NAME_RE = re.compile(
    r"\b(?P<fn>describe|it|test)(?:\.(?:only|skip|todo|concurrent))*\s*\(\s*"
    r"(?P<quote>['\"`])(?P<name>.*?)(?P=quote)"
)
TS_EACH_RE = re.compile(r"\b(?:describe|it|test)\.each\s*[<(]")


@dataclass(frozen=True)
class Violation:
    path: str
    line: int
    text: str


def rel(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def has_hangul(value: str) -> bool:
    return bool(HANGUL_RE.search(value))


def placeholder_only(value: str) -> bool:
    normalized = re.sub(r"\{\d+\}", "", value).strip()
    return not re.search(r"[A-Za-z가-힣]", normalized)


def java_test_files() -> list[Path]:
    root = REPO_ROOT / "apps/api/src/test/java"
    if not root.exists():
        return []
    return sorted(path for path in root.rglob("*.java") if path.is_file())


def web_test_files() -> list[Path]:
    root = REPO_ROOT / "apps/web/src"
    if not root.exists():
        return []
    return sorted(path for pattern in ("*.test.ts", "*.test.tsx") for path in root.rglob(pattern))


def java_method_source_names(lines: list[str]) -> set[str]:
    names: set[str] = set()
    annotations: list[str] = []
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("@"):
            annotations.append(stripped)
            continue
        if JAVA_METHOD_RE.match(line):
            if any(annotation.startswith("@ParameterizedTest") for annotation in annotations):
                parameterized_name = " ".join(annotations)
                if "{0}" in parameterized_name:
                    for annotation in annotations:
                        match = JAVA_METHOD_SOURCE_RE.search(annotation)
                        if match:
                            names.add(match.group("name"))
            annotations = []
            continue
        if stripped and not stripped.startswith("@"):
            annotations = []
    return names


def collect_method_source_case_lines(lines: list[str], source_names: set[str]) -> list[tuple[int, str]]:
    if not source_names:
        return []

    cases: list[tuple[int, str]] = []
    active = False
    for line_number, line in enumerate(lines, 1):
        if not active:
            if any(re.search(rf"\b{re.escape(name)}\s*\(", line) for name in source_names):
                active = True
            continue

        match = JAVA_ARGUMENTS_OF_RE.search(line)
        if match:
            cases.append((line_number, match.group("name")))
        if line.strip() == ");":
            active = False
    return cases


def scan_java_text(path: Path, text: str) -> list[Violation]:
    relative = rel(path)
    lines = text.splitlines()
    violations: list[Violation] = []

    for line_number, line in enumerate(lines, 1):
        for match in JAVA_DISPLAY_NAME_RE.finditer(line):
            name = match.group("name")
            if not has_hangul(name):
                violations.append(Violation(relative, line_number, f"@DisplayName은 한글을 포함해야 합니다: {name}"))
        match = JAVA_PARAMETERIZED_NAME_RE.search(line)
        if match:
            name = match.group("name")
            if not has_hangul(name) and not placeholder_only(name):
                violations.append(
                    Violation(relative, line_number, f"@ParameterizedTest name은 한글 또는 placeholder여야 합니다: {name}")
                )

    annotations: list[tuple[int, str]] = []
    for line_number, line in enumerate(lines, 1):
        stripped = line.strip()
        if stripped.startswith("@"):
            annotations.append((line_number, stripped))
            continue
        if JAVA_METHOD_RE.match(line):
            annotation_text = " ".join(annotation for _, annotation in annotations)
            if "@Test" in annotation_text or "@ParameterizedTest" in annotation_text:
                if "@DisplayName" not in annotation_text:
                    violations.append(Violation(relative, line_number, "JUnit 테스트에는 @DisplayName이 필요합니다"))
            annotations = []
            continue
        if stripped and not stripped.startswith("@"):
            annotations = []

    for line_number, name in collect_method_source_case_lines(lines, java_method_source_names(lines)):
        if not has_hangul(name):
            violations.append(Violation(relative, line_number, f"parameterized case name은 한글을 포함해야 합니다: {name}"))

    return violations


def scan_web_text(path: Path, text: str) -> list[Violation]:
    relative = rel(path)
    violations: list[Violation] = []
    for line_number, line in enumerate(text.splitlines(), 1):
        if TS_EACH_RE.search(line):
            violations.append(Violation(relative, line_number, "each 기반 테스트 이름은 검사 스크립트 지원 후 사용해야 합니다"))
        for match in TS_TEST_NAME_RE.finditer(line):
            name = match.group("name")
            if not has_hangul(name):
                violations.append(Violation(relative, line_number, f"Vitest 표시 이름은 한글을 포함해야 합니다: {name}"))
    return violations


def scan_files(files: Iterable[Path]) -> list[Violation]:
    violations: list[Violation] = []
    for path in files:
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as exc:
            violations.append(Violation(rel(path), 0, f"파일을 읽을 수 없습니다: {exc}"))
            continue
        if path.suffix == ".java":
            violations.extend(scan_java_text(path, text))
        else:
            violations.extend(scan_web_text(path, text))
    return violations


def format_violations(violations: Iterable[Violation]) -> str:
    return "\n".join(f"- {item.path}:{item.line}: {item.text}" for item in violations)


def run_self_test() -> int:
    java_path = REPO_ROOT / "apps/api/src/test/java/SampleTest.java"
    web_path = REPO_ROOT / "apps/web/src/Sample.test.ts"
    good_java = """
class SampleTest {
    @Test
    @DisplayName("public API는 성공 응답을 반환한다")
    void ok() {
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("필터 오류를 거부한다")
    void parameterized(String name) {
    }

    private static Stream<Arguments> cases() {
        return Stream.of(
            Arguments.of("pyeong 최소값이 최대값보다 크다", "{}")
        );
    }
}
"""
    bad_java = """
class SampleTest {
    @Test
    void missingDisplayName() {
    }

    @Test
    @DisplayName("returns result")
    void englishDisplayName() {
    }

    @ParameterizedTest(name = "case {0}")
    @MethodSource("cases")
    @DisplayName("필터 오류를 거부한다")
    void parameterized(String name) {
    }

    private static Stream<Arguments> cases() {
        return Stream.of(
            Arguments.of("price min greater than max", "{}")
        );
    }
}
"""
    good_web = "describe('fetchMapMarkers API 어댑터', () => { it('project marker를 반환한다', () => {}) })"
    bad_web = "describe('fetchMapMarkers', () => { it('returns markers', () => {}) })"
    checks = [
        not scan_java_text(java_path, good_java),
        len(scan_java_text(java_path, bad_java)) == 4,
        not scan_web_text(web_path, good_web),
        len(scan_web_text(web_path, bad_web)) == 2,
    ]
    if all(checks):
        print("self-test passed: check-test-display-names")
        return 0
    print("self-test failed: check-test-display-names", file=sys.stderr)
    return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="JUnit/Vitest 테스트 표시 이름 한글 규칙을 검사합니다.")
    parser.add_argument("--self-test", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.self_test:
        return run_self_test()

    violations = scan_files([*java_test_files(), *web_test_files()])
    if not violations:
        print("test display name policy passed")
        return 0
    print("test display name policy failed", file=sys.stderr)
    print(format_violations(violations), file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
