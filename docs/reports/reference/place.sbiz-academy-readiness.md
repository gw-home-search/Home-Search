# `place.sbiz-academy` readiness

기준일: 2026-07-20
상태: `Partial`
실제 데이터 readiness: `2.0/10 Partial`
taxonomy contract foundation 구현 품질: `10.0/10 Pass`

## 검증 근거 확인

- 공공데이터포털 `15067631`이 로그인 없이 제공하는 공식 업종코드 CSV를 확보했다.
- 포털 명세와 artifact가 대분류 10개·중분류 75개·소분류 247개로 일치한다.
- provider CP949 원본 SHA-256과 UTF-8/LF tracked artifact SHA-256을 각각 기록했다.
- 공식 `P1`(`교육 서비스업`) 아래 소분류 18개 전체를 code/name allowlist로 고정했다.
- loader는 tracked file path·checksum·schema·계층별 count·P1 name·allowlist·canonical
  fingerprint 중 하나라도 달라지면 collector 구성 단계에서 중단한다.
- 각 acquisition bundle에 이 계약에서 결정적으로 생성한 대·중·소 taxonomy artifact를
  포함하고, adapter가 fingerprint·partition·store ID·page total을 다시 검증한다.
- 위치 projection·NEIS exact match·800m observer의 기존 offline 검증은 유지된다.

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
  변경 차단 테스트를 보강했다.
- 좁은 회귀: Sbiz collector·adapter·ingest·projection·observer·composition `86 passed`.
- 전체 AI 회귀: `546 passed`, coverage `90.21%`.

taxonomy contract foundation은 공식 artifact 이용조건·출처, 고정 schema, 원본/추적
checksum, 계층 count, P1 allowlist, canonical fingerprint, path·symlink 경계, fail-closed
오류, TDD·전체 회귀, AsciiDoc와 추적성을 모두 충족해 `10.0/10 Pass`로 평가한다.
이는 S2 전체 collector 점수나 실제 데이터 readiness를 대신하지 않는다.

## 검증 공백 / 잔여 위험

- 업종코드 파일의 이용허락 제한 없음은 확인했지만, 상가업소 API raw를 private S3에
  저장·가공하는 이용조건 승인은 별도 `PENDING`이다.
- 실제 대·중·소 taxonomy endpoint의 현행 response schema와 tracked artifact 비교는
  아직 수행하지 않았다. 따라서 S2 전체 구현 점수는 확정하지 않는다.
- 실제 store acquisition, 좌표 전국 95%·지역 90% coverage, S3 복구, 두 번째 수집,
  chatbot JSON/SSE golden은 미검증이다.
- Sbiz 행정코드와 property 법정동 코드 mapping 전에는 `verifiedZero=false`를 유지한다.
- runtime allowlist에는 `academy_lookup`을 추가하지 않았다.

`api-contract: compatible`

`security-audit: 지적사항 = none`
