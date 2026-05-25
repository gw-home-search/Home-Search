# Backend Quality Gate KO

> KO 생성 기준: canonical source only
> Source: `apps/api/BACKEND_QUALITY.md`
> Generated: 2026-05-25
> 기존 KO 본문은 읽지 않고 canonical source만 기준으로 재생성했습니다.

## 동기화 기준

이 문서는 `apps/api/BACKEND_QUALITY.md`의 현재 canonical 내용을 기준으로 한 한국어 동기화본입니다.
명령, 경로, API URL, JSON key, status 값, class/function 이름은 정밀성을 위해 원문 표기를 유지합니다.

## Canonical 내용

# Backend Quality Gate

This file defines the backend quality flow that agents, hooks, and CI should
use before claiming a backend slice is complete.

## Required Gate

Run the single backend gate:

```sh
cd apps/api && ./gradlew backendQualityCheck
```

The gate runs profile boundary tests, public API contract tests, PostGIS/Flyway
persistence tests, REST Docs/OpenAPI generation, JaCoCo coverage verification,
and Javadoc.

## Coverage

Backend line and instruction coverage must stay at or above 90%.

Coverage should be raised by behavior tests through public seams. Do not add
record/getter-only tests just to increase the percentage.

Excluded code is limited to the application entrypoint, DTO records,
package-info files, and generated/build output.

## Docs And OpenAPI

REST Docs tests generate snippets from MockMvc web tests. The OpenAPI YAML is
generated from those snippets at:

```text
apps/api/build/api-spec/openapi3.yaml
```

The YAML must include the current map paths and canonical public fields. It
must not expose audit or dedupe fields such as `complexPk`, `aptSeq`,
`sourceKey`, or their snake_case variants.

## Evidence

Backend PRs should include these evidence lines:

```text
Coverage: >=90%
Docs/OpenAPI: generated + verified
- `cd apps/api && ./gradlew backendQualityCheck` = pass (<reason>)
```
