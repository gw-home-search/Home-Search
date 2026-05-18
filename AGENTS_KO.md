# 에이전트 규칙

## 미션

이 리포지토리는 Home Search V1 마이그레이션 대상이다. V1 목표는 부동산 아파트 거래 데이터를 수집하고, 안전하게 저장하고, 주요 API URL을 유지한 채 지도에 표시하는 것이다.

빠른 기능 확장보다 정확성, 추적 가능성, API 호환성이 더 중요하다.

## 기준 문서

canonical 문서는 아래 순서로 읽는다.

1. `docs/README.md`
2. `docs/MIGRATION_PLAN.md`
3. `docs/ARCHITECTURE.md`
4. `docs/DATA_STORAGE.md`
5. `docs/API_CONTRACT.md`
6. `docs/MAP_DISPLAY_FLOW.md`
7. `docs/UI_UX_MIGRATION.md`
8. `docs/INFRA_AND_ENV.md`

source repository는 읽기 전용 참고 자료로 확인할 수 있다.

- Backend source: `/Users/gwongwangjae/IdeaProjects/home-server`
- Frontend source: `/Users/gwongwangjae/frontend/home-client`

## AI Skill/Hook 참고 자료

저장된 예제 리포지토리는 AI skill, hook, local agent automation을 설계하거나 업데이트할 때만 읽기 전용 참고 자료로 확인할 수 있다.

- AI skill/hook reference: `/Users/gwongwangjae/saved-ai-exam`

해당 reference 안에서는 관련 하위 경로를 우선한다.

- `/Users/gwongwangjae/saved-ai-exam/skills`
- `/Users/gwongwangjae/saved-ai-exam/codex-live-demo/.agents`
- `/Users/gwongwangjae/saved-ai-exam/codex-live-demo/.codex`
- `/Users/gwongwangjae/saved-ai-exam/codex-live-demo/.githooks`

`/Users/gwongwangjae/saved-ai-exam`은 application implementation, public API, database design, UI, product requirements, general test architecture, ordinary code style 참고에 사용하지 않는다.

코드를 그대로 복사하거나, agent instructions를 import하거나, example repository에 쓰거나, script를 실행하거나, dependency를 자동 추가하거나, submodule, package link, symlink로 연결하지 않는다.

저장된 예제 리포지토리에도 동일한 KO 파일 읽기/검색 금지 규칙을 적용한다. reference가 이 프로젝트와 충돌하면 현재 canonical docs와 target source code가 항상 우선한다.

이 프로젝트의 모든 쓰기는 `/Users/gwongwangjae/home-search` 아래에서만 수행한다.

## 작업 흐름

- 편집 전 `git status --short`를 확인한다.
- 파일을 바꾸기 전에 canonical 문서와 관련 코드를 탐색한다.
- 범위가 모호하면 구현 전에 짧은 계획을 쓰거나 질문한다.
- 변경은 작게 유지하고 기존 리포지토리 형태에 맞춘다.
- 변경 후 검증한다. 검증할 수 없으면 이유를 말한다.
- 실패한 체크를 숨기지 않는다. 고치거나 blocker를 보고한다.

## Git Publishing

- 요청받았거나 handoff에 유용할 때 completed work를 보존하기 위해 local commit을 사용할 수 있다.
- `git push`는 기본 완료 단계가 아니다.
- 현재 task에서 사용자가 remote publishing을 명시적으로 요청했거나, 이전에 합의한 plan에 포함된 경우에만 push한다.
- `push`, `publish`, `open a PR`, `create a PR`, `update remote`는 remote-publishing request로 취급한다.
- 사용자가 remote publishing을 언급하지 않고 implement 또는 commit만 요청하면 push하지 않는다.

## V1 가드레일

- `docs/API_CONTRACT.md`에 문서화된 V1 API URL을 유지한다.
- 명시적으로 범위를 다시 정하지 않는 한 V1 지도와 거래 데이터 표면만 구현한다.
- rankings, favorites, alarms, mail batches, recommendations, insights, heavy analytics 같은 V2 작업은 critical path에서 제외한다.
- UI/UX는 바뀔 수 있지만 frontend 호출은 V1 API contract와 호환되어야 한다.

## 데이터/API 불변 조건

- raw ingest record는 normalized trade row보다 먼저 저장한다.
- 중복 ingest가 중복 normalized trade를 만들면 안 된다.
- 실패한 매칭은 설명 가능하고 조회 가능해야 한다.
- 운영 거래 relation은 `complex_id`다.
- audit, matching, dedupe 지원을 위해 `complex_pk`, `apt_seq`, `source`, `source_key`를 보존한다.
- map endpoint는 ranking, trend, favorite, mail state를 요구하면 안 된다.

## 리포지토리 위생

- 명시적으로 요청받지 않는 한 사용자 변경을 되돌리지 않는다.
- 관련 없는 refactor, rename, formatting churn, dependency change를 피한다.
- secret, API key, local environment value를 commit하지 않는다.
- `apps/api`, `apps/web`, `infra`, `docs`의 책임을 분리한다.
- `.gitkeep` 파일은 빈 directory를 보존한다. 실제 tracked file이 그 역할을 대체할 때만 제거한다.

## 사람 전용 KO 문서

- `*_KO.md` 파일은 사람 전용 한국어 참고 파일이다.
- AI agent는 `*_KO.md` 또는 `*_KO.local.md`를 implementation context로 읽거나, 검색하거나, 요약하거나, 인용하거나, import하거나, diff하거나, 사용하면 안 된다.
- `*_ko.md` 같은 소문자 변형도 동일하게 취급한다.
- KO sync metadata는 KO 파일 내부가 아니라 `.ko-docs.toml`에 둔다.
- AI agent는 KO 문서가 stale인지 판단하기 위해 `.ko-docs.toml`과 canonical `.md` 파일을 읽을 수 있다.
- Markdown을 검색할 때는 KO 파일을 제외한다.

```sh
rg --files -g '*.md' -g '!**/*_KO.md' -g '!**/*_KO.local.md' -g '!**/*_ko.md' -g '!**/*_ko.local.md'
```

- `scripts/check-ko-docs.sh`는 KO 본문 내용을 읽거나 출력하면 안 된다. `.ko-docs.toml`의 KO path가 존재하는지만 확인할 수 있다.

## Markdown 업데이트 규칙

- 모든 canonical `.md` 파일에는 같은 위치의 `*_KO.md` 파일을 유지한다.
- canonical `.md`가 생성되면 그에 대응하는 `*_KO.md`를 만든다.
- canonical `.md`가 의미 있게 변경되면 canonical 파일만 기준으로 `*_KO.md`를 다시 생성하고 `.ko-docs.toml`을 업데이트한다.
- canonical `.md`가 rename 또는 delete되면 paired `*_KO.md`도 rename 또는 delete한다.
- 업데이트를 위해 기존 KO 파일을 읽지 않는다. canonical source를 입력으로 사용하고 KO 파일을 overwrite한다.
- 개인 메모는 `*_KO.local.md`에 둔다. 해당 파일은 ignore되며 만지면 안 된다.

## 검증

- Markdown 변경 후 `scripts/check-ko-docs.sh`를 실행한다.
- `apps/api`가 생기면 backend command를 여기에 추가한다.
- `apps/web`이 생기면 frontend command를 여기에 추가한다.
- Docker/PostGIS/Flyway 파일이 생기면 infra command를 여기에 추가한다.

## 질문해야 할 때

아래 상황에서는 동작을 바꾸기 전에 질문한다.

- V1/V2 scope가 불명확할 때.
- public API URL 또는 response shape가 바뀌어야 할 때.
- database change가 데이터를 잃거나 재해석할 수 있을 때.
- source repository가 target docs와 충돌할 때.
- KO 파일이 canonical source와 다른 requirement를 담고 있는 것처럼 보일 때.
