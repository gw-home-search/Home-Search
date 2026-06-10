---
name: security-audit
description: Home Search security specialist skill for secure-by-default guidance, diff security review, full audit, and threat modeling across apps/api, apps/web, and infra. Use for "security audit", "security review", "vulnerability", "threat model", "보안", "보안 점검", "보안 리뷰", "취약점", "위협 모델". Not for general code review (use code-review), failing-command debugging (use systematic-debugging), or API contract decisions (use api-contract).
---


# Security Audit Skill

Use this skill whenever work touches a security surface or the user asks for a
security review, audit, vulnerability check, or threat model. It is a
checkpoint skill in execute and gate flows: a completion claim for production
code changes must include an explicit `security-audit: 지적사항` evidence line.

## Review Discipline

Read before producing findings. This skill is executed by an LLM; these rules
counter LLM-specific review failure modes and take precedence over the
checklists below.

- Signal over coverage. A short list of exploitable findings beats a long
  checklist echo. Do not raise a control as "missing" unless its absence
  enables a concrete abuse path in this repository.
- Prove the data flow before claiming a taint bug (SQL injection, XXE, log or
  markdown injection, SSRF, path traversal). Name the tainted source, the sink,
  and the path between them. If you cannot trace request or ingest data into
  the sink, do not raise it. Parameterized queries and static SQL fragments are
  not injectable by default.
- No finding without an exploit path and impact. Every 지적사항 states who can
  trigger it, how, and what they gain. If you cannot, downgrade or drop it.
- Respect the documented project threat model, but never use it to wave away a
  real authorization, data-exposure, or secret-leak finding.
- `지적사항 = none` is a verified claim, not a default. Emit it only after
  walking the relevant checklist, and always attach a one-line `검증 범위`
  listing what you actually inspected.
- Prefer the minimal mitigation that matches the documented threat. Do not
  introduce auth frameworks, gateways, or rate-limit infrastructure as a
  reflex; that can violate project later-scope guardrails. Route scope
  questions to `planning`.

## Severity Rubric

Severity = exposure × exploitability × impact, calibrated to this project
(public read-only API, single admin access code, batch ingest):

- 치명(Critical): secret committed to source or git history; remote
  unauthenticated code execution, SQL injection, or data tampering; an admin
  route reachable without the access code.
- 높음(High): admin-guarded RCE or injection; sensitive data (secrets, PII,
  keyed request URLs) written to logs or evidence tables; an admin mutation
  exposed without interceptor coverage.
- 중간(Medium): unbounded or cost-amplifying query without caps; missing output
  escaping into a Slack or markdown sink; XXE-capable parser on a reachable
  ingest path; error response leaking internals.
- 낮음(Low): defense-in-depth gaps with no concrete path today (for example a
  non-constant-time compare on a local-only code, or a missing security header
  on a JSON API), and hardening recommendations.

A leaked secret is always 치명 regardless of perceived reach, and requires
rotation, not just deletion. Map findings to a CWE id when one applies so IDs
stay stable across runs.

## When To Use

- Security review of a diff, branch, or PR.
- Full security audit of `apps/api`, `apps/web`, or infra config.
- Threat modeling a feature, endpoint, pipeline, or admin surface.
- Implementation guidance when touching admin access, secrets, SQL, external
  API calls, parsers, file or network IO, logging, or error responses.

## Do Not Use

- General correctness, style, or test-gap review; use `code-review`.
- Failing lint/test/build/hook/CI reproduction; use `systematic-debugging`.
- Public API URL, request, response, unit, or error compatibility decisions;
  use `api-contract` and `contract-reviewer`.

## Routes To

- `api-contract` before any mitigation that changes a public URL, response
  shape, or error body.
- `tdd` when a security fix changes behavior and a valid RED test is possible.
- `code-review` for findings that are quality issues without security impact.

## Required Inputs

- Root `AGENTS.md` (data/API invariants, Docker/data safety rules).
- `apps/api/AGENTS.md` and `apps/api/CONTEXT.md` when backend is in scope.
- `docs/API_CONTRACT.md`, `docs/INFRA_AND_ENV.md`, `docs/DATA_STORAGE.md`.

## Operating Modes

1. Secure-by-default: while implementing, apply the checklists below as
   defaults and flag deviations inline. No report required.
2. Diff security review: review only the changed surface, findings-first, and
   end with the evidence line from Output Format.
3. Full audit: sweep the requested scope and produce a prioritized report with
   an executive summary, numbered findings, severity sections, and file:line
   evidence. Write a report file only when the user names a path; default is
   chat output. Never commit an audit report with live findings into the repo.
4. Threat model: enumerate trust boundaries, assets, realistic attacker
   capabilities and explicit non-capabilities, abuse paths, and mitigations.
   Anchor every claim to repository evidence and state assumptions. Confirm
   assumptions with the user before final likelihood × impact ranking; if no
   interactive user is available (autonomous or harness run), state the
   assumptions explicitly and give a conditional ranking instead of blocking.

## Project Security Model

- The public map/trade API is read-only and unauthenticated by design. Do not
  raise "no login" as a finding for public endpoints. This stance excuses the
  absence of authentication only. It does NOT excuse: an admin route missing
  interceptor coverage, a public endpoint returning data the contract does not
  intend, or access to non-public rows (IDOR-style). Treat those as real
  findings.
- The only privileged surface is the admin coordinate path guarded by
  `AdminCoordinateAccessInterceptor` (`X-Admin-Access-Code`). Every new admin
  route must be covered by the interceptor. Access codes must never be logged,
  persisted in evidence tables, or echoed in errors. Prefer constant-time
  comparison for access-code checks.
- Secrets: provider credentials (`APT_SERVICE_KEY`, `VW_SERVICE_KEY`,
  `HERMES_AUTH_TOKEN`) live only as env placeholders in `application.yml`.
  Never hardcode them in source, tests, fixtures, or docs. Check git history,
  not just the working tree. A leaked secret must be rotated, not just removed.
  Verify secrets never reach logs, failure reasons, or evidence rows at
  runtime.
- Keep the house redaction patterns on every new log line, failure reason, and
  notification: serviceKey-style query redaction and Slack notifier
  sanitization are the reference implementations.
- Ingest evidence rows (raw payloads, run reports, failure reasons) are
  long-lived audit data: never persist secrets, keyed request URLs, or
  personal data in them.

## Backend Checklist (apps/api)

- SQL: `JdbcClient` named parameters only. User-controlled data must never
  enter SQL string composition; composed fragments (the `ComplexMarkerSql`
  pattern) stay static. Any request data concatenated or formatted into SQL is
  치명(Critical).
- Input: `@Valid` DTO constraints on every endpoint; bounds and limit caps on
  expensive PostGIS queries (bbox size, page size, limit) to resist cost
  abuse.
- Errors: API responses go through `ApiExceptionHandler`; no stack traces,
  SQL, internal paths, or upstream URLs in response bodies.
- Output sinks: escape externally-sourced text (ingested news titles, provider
  strings) before it reaches Slack/Hermes notifications or Obsidian/markdown
  export. The real injection risk for ingest text is Slack mrkdwn or markdown
  injection, not HTML.
- External ingest: XML/JSON parsers configured XXE-safe with response size and
  timeout limits; retries bounded with backoff.
- Actuator: only the exposure intended by `application.yml` (health/metrics);
  flag any widening of `management` endpoint exposure.
- Dependencies: report CVE-check gaps as findings or recommendations only;
  never auto-install scanners or add dependencies.
- Docker/data: follow root `AGENTS.md` Docker/Data Safety rules; destructive
  commands require explicit user approval in the current task.

## Web Checklist (apps/web)

- No secrets, admin access codes, or provider keys in client source, bundles,
  or committed env files.
- Admin features must not hardcode `X-Admin-Access-Code`; inject at runtime.
- Render external strings (complex names, news titles) as text, not HTML.
- The Kakao/map client key is necessarily public; do not flag its presence in
  the bundle. Verify it is domain-restricted instead of treating it as leaked.

## Output Format

Findings first, Korean-first labels, numbered IDs (S1, S2, ...):

- 지적사항: each finding has severity 치명(Critical)/높음(High)/중간(Medium)/
  낮음(Low), file:line, optional CWE id, problem, exploit path with impact, the
  required fix, and a fix-verification step (how to confirm the fix closes it).
- Close with 검증 근거 확인, 검증 공백, and 잔여 위험.
- If 지적사항 = none, attach a one-line `검증 범위` stating what was inspected.
- Always end with the machine-checked evidence line, exactly one of:
  `security-audit: 지적사항 = none` or `security-audit: 지적사항 = listed`.
- In PR bodies, fill the `## 보안 영향` section with `보안 영향:` plus the
  evidence line above.

## Overrides

Documented project decisions win over generic best practice (for example the
unauthenticated public API). When a best practice is intentionally bypassed,
report it once, recommend documenting the bypass, and do not fight the user.

## Stop Conditions

Stop and ask before:

- Changing secrets, env values, or environment files.
- Public API URL or response shape changes; route to `api-contract`.
- Destructive Docker or database actions.
- Adding dependencies or installing security scanners.
- Mass refactors justified only by hypothetical risk.
