# `transport.rail-station` readiness

기준일: 2026-07-20

상태: `Pass`

실제 데이터 readiness: `10.0/10 Pass`

## 검증 근거 확인

- KRIC `id=32` 고정 download와 2026-06-30 XLSX를 사용한다. macro, external link,
  duplicate ZIP entry, 과도한 sheet/cell·압축을 fail-closed로 거부한다.
- live 중복 5쌍을 raw에서 재검토했다. 공식 field인 `line_name`을 occurrence identity에
  포함하고, 동일 확장 identity는 유일한 최신 유효 기준일이 있을 때만 최신 occurrence를
  유지한다. 최신일 동률·날짜 부재는 계속 duplicate로 차단한다.
- normalization schema는 `rail-station-v5`; superseded 2행은
  `RAIL_SUPERSEDED_OCCURRENCE` warning으로 evidence에 남긴다.
- 프로젝트 책임자의 전화 승인 진술과 공공데이터포털 fileData의
  `이용허락범위 제한 없음`에 따라 private raw·내부 파생만 승인하며 원본 공개 재배포는
  금지한다.

## live 결과

- 실제 refresh: `Pass`, source date `2026-06-30`, raw `1,099`, accepted occurrence
  `1,097`, rejected `0`, 좌표 `100%`, duplicate key `0`.
- warning: `RAIL_ROW_REFERENCE_DATE_INVALID 6`,
  `RAIL_SUPERSEDED_OCCURRENCE 2`.
- active occurrence와 distinct ID 모두 `1,097`, 좌표 누락 `0`, datasetVersion
  `2026-06-30-ff91cb28f356`.
- 같은 release 두 번째 refresh는 동일 acquisition/publication을 재사용해 `Pass`였고,
  v5 publication은 총 `1`개다.
- 서울 대표 좌표의 1.5km 공간 query 20회 실측 p95는 `7.971ms`, max는
  `8.469ms`로 200ms 기준을 통과했다.
- 최대 반경 3km의 warm-up 후 20회 p95는 `25.565ms`, max는 `30.258ms`다.
- 잠실엘스 1.5km signed JWT live JSON/SSE는 모두 `200`, capability
  `rail_station_lookup`, fact 5건, A등급 official citation, active datasetVersion
  `2026-06-30-ff91cb28f356`을 반환했다. SSE final은 1건이고 error event는 0건이다.
- live `고잔역` 2개 노선 occurrence는 exact 역명·250m 이내 규칙으로 1개 역으로
  병합됐고, 250m 밖 동일 역명 occurrence는 병합되지 않았다.
- raw bundle 복구 SHA-256
  `919c7b7763fa146d65e5f7483d73d1ab19628b27daa7ba7f3e35321e189eb515`,
  `313,741` bytes가 DB metadata와 일치한다.
- v2~v4 parse/quality failure acquisition은 삭제하지 않고 audit에 보존했다.

## 잔여 위험과 활성화

- 서면 이용 승인 transcript가 없다는 낮음(Low) 잔여 위험이 있다.
- 필수 항목을 포함한 readiness는 `10.0`으로 승인한다. 2026-07-21 다른 source와
  분리된 commit에서 누적 local runtime template
  `academy_lookup,rail_station_lookup`을 승인했으며 rollback은 `academy_lookup`이다.
- activation smoke의 JSON은 `200/success`였고 첫 SSE는 error event로 fail-closed했다.
  직접 engine 진단은 정상 근거를 반환했으며 제한 재검증 SSE는 `200/success`, A등급
  citation, active datasetVersion, final 1·error 0을 확인했다.

license evidence SHA-256:
`14d6a007c272b75456998f5289e08ff8f61cb82f04bb2fc6a72ef9c11327ff0d`

`api-contract: compatible`

security-audit: 지적사항 = none
