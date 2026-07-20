# `place.sbiz-academy` readiness

기준일: 2026-07-20
상태: `Partial`
실제 데이터 readiness: `3.0/10 Partial`
S2 collector 구현 품질: `10.0/10 Pass`
taxonomy contract foundation 구현 품질: `10.0/10 Pass`

## 검증 근거 확인

- 공공데이터포털 `15067631`이 로그인 없이 제공하는 공식 업종코드 CSV를 확보했다.
- 포털 명세와 artifact가 대분류 10개·중분류 75개·소분류 247개로 일치한다.
- provider CP949 원본 SHA-256과 UTF-8/LF tracked artifact SHA-256을 각각 기록했다.
- 공식 `P1`(`교육 서비스업`) 아래 소분류 18개 전체를 code/name allowlist로 고정했다.
- loader는 tracked file path·checksum·schema·계층별 count·P1 name·allowlist·canonical
  fingerprint 중 하나라도 달라지면 collector 구성 단계에서 중단한다.
- collector는 각 acquisition 시작에 공식 활용가이드대로 `largeUpjongList`를 조회한
  뒤 `middleUpjongList`를 `indsLclsCd`별로, `smallUpjongList`를
  `indsLclsCd`+`indsMclsCd`별로 조회하고, 합친 code/name 전체가 tracked artifact와
  일치할 때만 store partition을 요청한다.
- 성공한 parent별 taxonomy 응답 bytes를 raw bundle에 포함하고, adapter가 다시
  hierarchy·fingerprint·partition·store ID·page total을 검증한다.
- 2025-11-28 공식 OpenAPI 활용가이드와 포털 OAS에서 store field 계약을 대조했고,
  `newZipcd` 신우편번호와 `indsSclsCd`/`indsSclsNm` code-name 일치를 적용했다.
- 위치 projection·NEIS exact match·800m observer의 기존 offline 검증은 유지된다.
- API 상세 페이지의 `이용허락범위 제한 없음`, 제공기관, 국세청/카드사 원천 고지와
  공공데이터포털 제3자 권리 정책을 함께 검토했다. 제3자 provenance를 명시하고
  private raw 저장·내부 파생만 승인했으며 공개 재배포는 금지했다.

공식 taxonomy landing URL:
`https://www.data.go.kr/data/15067631/fileData.do`

checksum:

- provider download: `3055e458f887b3c0b94a2059c3a2571a28053de8b17a4e2e566d6b189f6e1948`
- tracked UTF-8 artifact: `f076ee0aa84ef829ae4279d259391d0d65790b052be7f8dab950e3dcc26a706f`
- canonical 대·중·소 fingerprint: `1ffabae679945e7151dd62d463100d760a168f5806cd18af8eb570bde04fabfc`

## TDD 근거

- 최초 RED: production loader가 요구하는 `sbiz_academy_taxonomy.json`이 없어 공식
  247개 taxonomy와 P1 allowlist 테스트가 `FileNotFoundError`로 실패했다.
- 예상 RED 실패: 합성 fixture만 있고 tracked official artifact가 없음을 정확히 확인했다.
- 최소 GREEN: official CSV·provenance config·결정적 parser를 추가하고 source checksum
  변경, 공식 `newZipcd`, store taxonomy name drift 차단 테스트를 보강했다.
- live taxonomy preflight 최초 RED: response envelope을 adapter가 읽지 못하고,
  collector가 세 taxonomy endpoint를 호출하지 않으며 변경 taxonomy 뒤에도 store를
  요청하는 5개 실패를 확인했다.
- live taxonomy preflight 최소 GREEN: 고정 endpoint 순서, raw response 보존,
  code/name exact 비교와 `TAXONOMY_CHANGED` fail-closed를 추가했다.
- hierarchy request 최초 RED: 중·소분류 요청에 공식 가이드의 parent parameter가
  없음을 exact path assertion으로 확인했다.
- hierarchy request 최소 GREEN: 모든 tracked parent를 scoped 조회하고 parent별 원문
  artifact를 보존하며 합산 fingerprint와 parent hierarchy를 재검증한다.
- 좁은 회귀: Sbiz collector·adapter·ingest·projection·observer·composition `87 passed`.
- 최신 전체 AI 회귀: `569 passed`, coverage `90.05%`.

taxonomy contract foundation은 공식 artifact 이용조건·출처, 고정 schema, 원본/추적
checksum, 계층 count, P1 allowlist, canonical fingerprint, path·symlink 경계, fail-closed
오류, TDD·전체 회귀, AsciiDoc와 추적성을 모두 충족해 `10.0/10 Pass`로 평가한다.
taxonomy foundation 점수는 S2 전체 collector 점수나 실제 데이터 readiness를
대신하지 않는다.

2026-07-20 live 재검증은 key 인증 후 unscoped taxonomy 응답이
대·중·소 `25/266/1,255`로, 공식 포털과 2025-11-28 활용가이드의 `10/75/247`과
달라 `TAXONOMY_CHANGED`로 store partition 요청 전에 중단했다. 기존 교육업종 18개가
응답에 남아 있어도 학교·온라인·인적용역 등 새 분류를 위치 allowlist에 임의로
추가하지 않는다. hierarchy request 수정 후에도 첫 대분류 mismatch에서 안전 중단하며,
acquisition은 `0`, activation은 계속 금지한다.

## S2 collector 구현 품질 평가

| 항목 | 점수 | 검증 근거 확인 |
|---|---:|---|
| 범위·최소성 | `1.0/1.0` | Sbiz taxonomy·교육업종 partition collector와 adapter만 static catalog에 연결했고 scheduler·동적 plugin을 추가하지 않음 |
| 공개·내부 계약 | `1.0/1.0` | 공식 OAS의 taxonomy 3개와 `storeListInUpjong`, `newZipcd`, code/name 필드를 fixture 계약으로 고정하고 기존 JSON/SSE field를 변경하지 않음 |
| 이용조건·출처 | `1.0/1.0` | API source별 무제한 이용 허락, 제3자 원천·공통 정책, private raw·내부 파생 승인, 보수적 공개 재배포 금지, attribution과 evidence SHA-256을 고정 |
| 데이터 정확성·원자성 | `1.5/1.5` | 현행 taxonomy exact 비교 후에만 18개 partition 수집, total·page·중복 ID 검증, verified raw-first와 incomplete 미게시를 검증 |
| 보안·개인정보 | `1.0/1.0` | service key를 request path·bundle·DB·로그에서 제외하고 전화번호와 provider 오류 body를 투영·보존하지 않음 |
| 실패·복구·관측 | `1.0/1.0` | taxonomy 변경은 store 요청 전 중단하고 첫 page 실패는 raw 생성 없이 실패, 중간 실패는 safe reason incomplete bundle로 보존 |
| 테스트 품질 | `1.5/1.5` | taxonomy 호출 누락·schema envelope·변경 후 store 요청의 예상 RED 5건, 집중 회귀 `87 passed`, 전체 AI `554 passed`, coverage `90.18%` |
| 문서·운영 가능성 | `1.0/1.0` | source AsciiDoc, generated snippets, generic refresh·status·audit와 readiness blocker를 일치시킴 |
| 성능·자원 제한 | `0.5/0.5` | page 8MiB, bundle 1GiB, partition당 500 page, timeout 1..30초, retry 1회, owner-only temp streaming 제한 검증 |
| 리뷰·commit 추적성 | `0.5/0.5` | 공식 taxonomy foundation, schema 정렬, live preflight 책임과 잔여 위험을 분리 기록 |

구현 점수는 `10.0/10 Pass`다. 계약·데이터 정확성·보안·테스트 필수 항목을
포함해 offline 구현 평가를 충족했다. 실제 provider 호출이나 capability 활성화를
승인하지 않는다.

## 검증 공백 / 잔여 위험

- 이용조건 evidence는 `apps/ai/config/license_evidence/place.sbiz-academy.txt`에
  고정했고 SHA-256은
  `e66a879b6ce35af441c4b92b58711c7a2d7d538c87659883da6ab298fd98c86d`다.
- 실제 taxonomy endpoint는 호출했지만 공식 포털·활용가이드 count와 live 응답이
  불일치한다. provider가 현행 taxonomy의 공식 버전·분류 범위를 확인하기 전에는
  tracked fingerprint와 교육 위치 allowlist를 변경하지 않는다.
- 실제 store acquisition, 좌표 전국 95%·지역 90% coverage, S3 복구, 두 번째 수집,
  chatbot JSON/SSE golden은 미검증이다.
- Sbiz 행정코드와 property 법정동 코드 mapping 전에는 `verifiedZero=false`를 유지한다.
- runtime allowlist에는 `academy_lookup`을 추가하지 않았다.
- 2026-07-20 key 수정 후 인증은 통과했고 taxonomy mismatch로 안전 중단했다.

`api-contract: compatible`

`security-audit: 지적사항 = none`
