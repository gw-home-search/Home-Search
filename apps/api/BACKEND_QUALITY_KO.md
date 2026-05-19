# 백엔드 품질 게이트

이 파일은 agent, hook, CI가 backend slice 완료를 판단하기 전에 사용할
백엔드 품질 흐름을 정의한다.

## 필수 게이트

단일 backend gate를 실행한다.

```sh
cd apps/api && ./gradlew backendQualityCheck
```

이 게이트는 profile 경계 테스트, V1 API 계약 테스트, PostGIS/Flyway
persistence 테스트, REST Docs/OpenAPI 생성, JaCoCo coverage 검증, Javadoc을
실행한다.

## 커버리지

backend line coverage와 instruction coverage는 90% 이상이어야 한다.

커버리지는 public seam을 통한 behavior test로 올린다. 수치만 맞추기 위한
record/getter 전용 테스트를 추가하지 않는다.

제외 대상은 application entrypoint, DTO record, package-info 파일,
generated/build output으로 제한한다.

## Docs And OpenAPI

REST Docs 테스트는 MockMvc web test에서 snippets를 생성한다. OpenAPI YAML은
그 snippets에서 생성되며 위치는 다음과 같다.

```text
apps/api/build/api-spec/openapi3.yaml
```

YAML에는 현재 V1 map path와 canonical public field가 포함되어야 한다.
`complexPk`, `aptSeq`, `sourceKey` 또는 snake_case 변형 같은 audit/dedupe
필드는 public response로 노출하면 안 된다.

## Evidence

backend PR에는 다음 evidence line을 포함한다.

```text
Coverage: >=90%
Docs/OpenAPI: generated + verified
- `cd apps/api && ./gradlew backendQualityCheck` = pass (<reason>)
```
