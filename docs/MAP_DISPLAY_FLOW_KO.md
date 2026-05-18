# 지도 표시 흐름 한국어 참조

> 사람 전용 한국어 참조 문서입니다. 기준 문서는 `docs/MAP_DISPLAY_FLOW.md`입니다.

## 목표

안정적인 V1 API를 사용해 Kakao map 위에 실거래 데이터를 표시한다.

## V1 흐름

지도 idle 시점에 bounds와 level을 읽고, 상세 zoom에서는 `/api/v1/map/complexes`, 넓은 zoom에서는 `/api/v1/map/regions`를 호출한다. complex marker를 클릭하면 detail drawer를 열고 `/api/v1/detail/{parcelId}`와 `/api/v1/trade/{parcelId}`를 호출한다.

## Level 규칙

소스 호환성을 위해 기존 level 기준을 유지한다.

- `level <= 4`: complex marker
- `level >= 10`: `si-do`
- `level >= 7`: `si-gun-gu`
- `level >= 4`: `eup-myeon-dong`

## 백엔드 경계

`/api/v1/map/complexes`는 지도 표시를 위한 최소 작업만 수행한다. ranking table, trend table, 30일 aggregate, mail/favorite state에 의존하지 않는다.

## 실패 동작

marker API 실패 시 현재 marker를 비우고, 지도는 계속 사용할 수 있게 두며, 작은 non-blocking error state를 보여준다.
