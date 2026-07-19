# `edu.academy-registry` readiness

기준일: 2026-07-19
상태: `Partial`

17개 시도교육청 page coverage와 교육청별 `list_total_count` 일치를 강제하는 NEIS
adapter를 구현했다. 전화번호와 교습비는 normalized projection 후보에서 제외하고,
상호명·도로명주소는 Unicode NFKC와 공백 canonicalization만 적용한다.
property DB에는 기존 `public.region` 계층을 그대로 읽는 `ai_read.region_fact` V10을
추가했고 `home_search_ai_reader` SELECT-only 검증을 통과했다.

이용조건은 `PENDING`이며 실제 100,000~250,000행 snapshot, 시군구 집계,
chatbot `academy_registry_summary`, live golden은 미완료다. 운영 capability는
활성화하지 않았다.

security-audit: 지적사항 = listed

- 실제 NEIS key·quota·schema·전국 total 검증과 AI DB registry projection 권한
  smoke가 미완료다.
