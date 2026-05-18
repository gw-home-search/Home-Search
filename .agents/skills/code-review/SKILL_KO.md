---
name: code-review
description: Home Search 변경을 findings-first로 검토하며 correctness, V1 API compatibility, data safety, KO sync risk를 우선한다.
---

# Code Review Skill

Review 요청 또는 implementation 후 final self-review에 이 skill을 사용한다.

## Format

Findings first. 각 finding에는 severity, file/line, problem, impact, required fix를 포함한다.

Final self-review에서는 다음도 함께 보고한다.

- Verification evidence reviewed.
- Missing tests or verification gaps.
- Residual risk.
- Markdown 변경 시 KO sync status.

Severity:

- Critical.
- High.
- Medium.
- Low.

## Review Axes

- Correctness.
- V1 API compatibility.
- Data safety.
- Frontend map usability.
- Security/secrets.
- Missing tests.
- KO sync.

## Rules

- Style-only comment는 documented rule 위반이거나 실제 risk가 있을 때만 보고한다.
- Finding이 없으면 명확히 말하고 remaining test gap 또는 residual risk를 언급한다.
- Public API, DB, ingest invariant risk를 style보다 우선한다.
