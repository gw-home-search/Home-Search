# `place.sbiz-academy` readiness

기준일: 2026-07-20

상태: `Pass`

실제 데이터 readiness: `10.0/10 Pass`

## 검증 근거 확인

- 공공데이터포털 공식 업종코드 CSV의 대·중·소 `10/75/247`, P1 교육 소분류
  18개와 canonical fingerprint를 tracked contract로 고정했다.
- legacy taxonomy endpoint의 `25/266/1,255` 응답은 현재 공식 taxonomy 계약으로
  사용하지 않는다. 매 acquisition에는 checksum 검증된 tracked taxonomy 3개 artifact를
  포함하고 실제 store 행의 `indsSclsCd/indsSclsNm`을 18개 allowlist와 exact 비교한다.
- taxonomy 원문의 `ㆍ`를 NFKC로 바꾸던 fingerprint 결함을 최초 RED로 재현했다.
  taxonomy evidence는 원문을 보존하고 점포 텍스트만 기존 NFKC normalization을 유지한다.
- 전화번호와 provider 오류 body는 normalized row·projection·로그에 포함하지 않는다.

## live 결과

- 첫 수집: `Pass`, 201 pages, raw/accepted `191,250`, rejected `0`, 좌표 coverage
  `100%`, 18개 교육 분류, active datasetVersion `20260720-238f100fbe47`.
- projection/runtime: 고유 store ID `191,250`, 좌표 누락 `0`, unknown region `0`.
  NEIS exact 결과는 `EXACT 45,875`, `UNMATCHED 145,375`로 분리되며 fuzzy match는 없다.
- 두 번째 전량 수집: `NoChange`, staging `0`, publication 총 `1`; active pointer 유지.
- 서울 대표 좌표의 800m 공간 query 20회 실측 p95는 `25.373ms`,
  max는 `27.848ms`로 200ms 기준을 통과했다.
- 잠실엘스 800m signed JWT live JSON/SSE는 모두 `200`이며 Sbiz B등급과
  exact NEIS A등급 citation, active datasetVersion을 함께 반환했다. SSE final은 1건,
  error event는 0건이다.
- 최대 반경 2km를 warm-up 후 20회 측정한 p95는 `156.927ms`, max는
  `173.443ms`다. spatial nearest 5건을 먼저 제한하고 해당 ID의 active exact evidence만
  조회하는 두 개의 bounded query로 200ms 기준을 통과했다.
- 최신 raw bundle은 MinIO version object에서 복구해 DB와 동일한 SHA-256
  `e0736be2c05d3d41f90e0e694424c32e3a37e1c6c8026e0e34e6862d80e3d1ee`,
  `235,283,816` bytes를 확인했다.
- 최초 taxonomy parse failure acquisition과 raw는 삭제하지 않고 audit에 보존했다.

## 잔여 위험과 활성화

- provider 행정코드와 property 법정동 code mapping이 없어 정상 0건은
  `verifiedZero=false`다.
- mandatory chatbot grounding과 최대 반경 성능 기준을 모두 통과했다. 2026-07-21
  local runtime template에서 `academy_lookup`만 승인했으며 빈 allowlist를 rollback으로
  유지한다.
- activation smoke의 첫 공개 호출은 JSON `503`, SSE error로 fail-closed했고 직접
  engine 진단은 정상 근거를 반환했다. 제한 재검증에서 JSON/SSE 모두 `200/success`,
  Sbiz B+NEIS A citation과 active datasetVersion, SSE final 1·error 0을 확인했다.

taxonomy evidence:

- provider download: `3055e458f887b3c0b94a2059c3a2571a28053de8b17a4e2e566d6b189f6e1948`
- tracked UTF-8 artifact: `f076ee0aa84ef829ae4279d259391d0d65790b052be7f8dab950e3dcc26a706f`
- canonical fingerprint: `1ffabae679945e7151dd62d463100d760a168f5806cd18af8eb570bde04fabfc`

`api-contract: compatible`

security-audit: 지적사항 = none
