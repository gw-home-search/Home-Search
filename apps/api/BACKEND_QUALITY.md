# Backend Quality Gate

This file defines the backend quality flow that agents, hooks, and CI should
use before claiming a backend slice is complete.

## Required Gate

Run the single backend gate:

```sh
cd apps/api && ./gradlew backendQualityCheck
```

The gate runs profile boundary tests, V1 API contract tests, PostGIS/Flyway
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

The YAML must include the current V1 map paths and canonical public fields. It
must not expose audit or dedupe fields such as `complexPk`, `aptSeq`,
`sourceKey`, or their snake_case variants.

## Evidence

Backend PRs should include these evidence lines:

```text
Coverage: >=90%
Docs/OpenAPI: generated + verified
- `cd apps/api && ./gradlew backendQualityCheck` = pass (<reason>)
```
