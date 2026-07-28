#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path


def fail(message: str) -> None:
    print(f"public API docs verification failed: {message}", file=sys.stderr)
    raise SystemExit(1)


if len(sys.argv) != 5:
    fail("expected MANIFEST OPENAPI ASCIIDOC API_CONTRACT")

manifest_path, openapi_path, asciidoc_path, contract_path = map(Path, sys.argv[1:])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
openapi = openapi_path.read_text(encoding="utf-8")
asciidoc = asciidoc_path.read_text(encoding="utf-8")
contract = contract_path.read_text(encoding="utf-8")

if manifest.get("formatVersion") != 1:
    fail("unsupported operation manifest format")

declared = {(item["method"].upper(), item["path"]) for item in manifest["operations"]}
if len(declared) != len(manifest["operations"]):
    fail("duplicate method/path in operation manifest")

generated: set[tuple[str, str]] = set()
current_path: str | None = None
for line in openapi.splitlines():
    path_match = re.match(r"^  (/[^:]+):\s*$", line)
    if path_match:
        current_path = path_match.group(1)
        continue
    method_match = re.match(r"^    (get|post|put|patch|delete):\s*$", line)
    if current_path and method_match:
        generated.add((method_match.group(1).upper(), current_path))

missing = sorted(declared - generated)
unexpected = sorted(generated - declared)
if missing or unexpected:
    fail(f"route-set mismatch missing={missing} unexpected={unexpected}")

for operation in manifest["operations"]:
    heading = re.compile(
        rf'{re.escape(operation["method"].upper())} `'
        rf'{re.escape(operation["path"])}(?:\?[^`]*)?`'
    )
    if not heading.search(contract):
        fail(f'canonical contract heading missing: {operation["method"].upper()} `{operation["path"]}`')

for snippet in [item["snippet"] for item in manifest["operations"]] + manifest.get("supplementalSnippets", []):
    count = len(re.findall(rf"operation::{re.escape(snippet)}\[", asciidoc))
    if count != 1:
        fail(f"snippet {snippet!r} must be included exactly once, found {count}")

for forbidden in ("/internal/", "/actuator", "/api/v1/admin/"):
    if forbidden in openapi or forbidden in asciidoc:
        fail(f"private route leaked into public artifact: {forbidden}")

print(f"public API docs verification passed: {len(declared)} routes")
