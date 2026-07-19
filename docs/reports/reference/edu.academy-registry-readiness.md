# `edu.academy-registry` readiness

기준일: 2026-07-20
상태: `Partial`

17개 시도교육청 page coverage와 교육청별 `list_total_count` 일치를 강제하는 NEIS
adapter를 구현했다. 전화번호와 교습비는 normalized projection 후보에서 제외하고,
상호명·도로명주소는 Unicode NFKC와 공백 canonicalization만 적용한다.
property DB에는 기존 `public.region` 계층을 그대로 읽는 `ai_read.region_fact` V10을
추가했고 `home_search_ai_reader` SELECT-only 검증을 통과했다.

offline `academy_registry_summary` observer는 property DB에서 단지의 `si-do`와
`si-gun-gu` ancestor를 해석한 뒤 AI DB에서 교육청명+시군구명을 exact query한다.
DB 간 join은 없고 등록 총수·운영 수·관측일만 A등급 NEIS fact로 노출하며 반경·거리
표현은 grounding validator가 거부한다. 같은 시군구명이 다른 교육청에 존재하는
fixture와 missing/stale region evidence의 fail-closed 경계를 검증했다.

이용조건은 `PENDING`이며 실제 100,000~250,000행 snapshot, 시군구 집계,
live golden은 미완료다. `HOME_AI_ENABLED_REFERENCE_CAPABILITIES` allowlist에는
추가하지 않아 운영 capability는 활성화하지 않았다.

security-audit: 지적사항 = listed

- 실제 NEIS key·quota·schema·전국 total 검증과 AI DB registry projection 권한
  smoke가 미완료다.
