# API 계약

> 사람 전용 한국어 참조 문서입니다. 기준 문서는 `docs/API_CONTRACT.md`입니다.

## 목적

이 문서는 Home Search V1 마이그레이션의 public API 계약을 설명합니다.
V1 백엔드와 프론트엔드가 맞춰야 하는 URL, 요청, 응답, 단위, 오류 처리,
호환성 기준을 한곳에 고정하는 것이 목적입니다.

이 문서는 다음 작업의 기준이 됩니다.

- 백엔드 controller, DTO, repository projection, controller test 작성.
- 프론트엔드 API client, marker adapter, 상세 패널, 검색 흐름 작성.
- 원본 백엔드와 원본 프론트엔드의 필드명이 서로 맞지 않을 때 target V1 기준 결정.

V1에서는 지도 표시를 위해 주요 API URL을 유지합니다. 내부 저장 구조나 조회
방식은 바뀔 수 있지만, 그 변경이 public V1 API로 새어 나오면 안 됩니다.
public API를 바꾸려면 먼저 기준 문서인 `docs/API_CONTRACT.md`가 업데이트되어야
합니다.

## 이번 작업 범위

이번 배치는 API 계약 문서를 명확하게 정리하는 작업입니다.

이번에 하는 일:

- V1 API가 어떤 작업을 통제하는지 목적을 명확히 적습니다.
- endpoint별 요청, 응답, 상태 코드, 호환성 메모를 균일하게 정리합니다.
- 금액, 좌표, 날짜, 오류 응답 형식을 명시합니다.
- backend/frontend가 이 계약을 어떻게 써야 하는지 설명합니다.

이번에 하지 않는 일:

- 백엔드 코드 구현.
- 프론트엔드 adapter 수정.
- OpenAPI YAML 작성.
- 테스트 코드 작성.
- V2 기능 구현.

다음 작업은 이 문서를 기준으로 backend DTO, controller test, repository
projection, frontend API adapter를 구현하는 순서로 이어가면 됩니다.

## 고정 경로

원본 백엔드:

- `/Users/gwongwangjae/IdeaProjects/home-server`

원본 프론트엔드:

- `/Users/gwongwangjae/frontend/home-client`

대상 저장소:

- `/Users/gwongwangjae/home-search`

## 계약 우선순위

원본 백엔드와 원본 프론트엔드는 읽기 전용 참고 자료입니다. 원본 코드와 target
V1 계약이 충돌하면 target V1 구현은 `docs/API_CONTRACT.md`를 우선합니다.

원본 코드의 불일치는 마이그레이션 메모로 남기고, target V1에서는 표준 필드와
표준 URL을 기준으로 맞춥니다.

V1 범위는 지도 표시, 검색, 지역 탐색, 상세 정보, 거래 목록까지입니다. 랭킹,
즐겨찾기, 알림, 메일 배치, 추천, 인증 흐름, 무거운 분석 기능은 V2로 미룹니다.

## 공통 규칙

- 표준 URL은 항상 `/api/v1/...`처럼 앞에 `/`를 포함합니다.
- 요청과 응답은 JSON입니다.
- 좌표는 WGS84 / EPSG:4326 기준입니다.
- `lat`, `latitude`는 위도이고 `lng`, `longitude`는 경도입니다.
- 날짜는 `YYYY-MM-DD` 형식입니다.
- `dealAmount`, `latestDealAmount`는 만원 단위 정수입니다.
- `priceEokMin`, `priceEokMax`는 억 단위 입력값입니다. 백엔드는 내부 비교 전에
  만원 단위로 변환합니다.
- nullable filter 값은 해당 필터를 적용하지 않는다는 뜻입니다.
- 요청 자체가 유효하면 결과가 없어도 가능한 한 `200`과 빈 배열을 반환합니다.
- optional 응답 필드는 `null`이거나 생략될 수 있습니다.

## 호환성 기준

V1에서 허용되는 변경:

- optional 응답 필드 추가.
- optional로 문서화된 필드를 `null`로 반환하거나 생략.
- 마이그레이션 중 프론트 adapter에서 legacy field를 임시 수용.

V1에서 breaking change로 보는 변경:

- 문서화된 응답 필드 제거.
- 문서화된 필드 이름 변경.
- 문서화된 필드 타입 또는 단위 변경.
- optional 필드를 required로 변경.
- public URL 또는 HTTP method 변경.

target V1 백엔드는 기준 문서의 표준 필드를 반환해야 합니다. 원본 코드의
legacy field는 프론트 adapter에서 임시로 받아줄 수 있지만, 새 target 코드는
표준 필드를 우선해야 합니다.

## 오류 정책

V1 오류 응답은 Spring `ProblemDetail` 계열 구조를 기준으로 합니다.

최소 오류 필드:

- `type`
- `title`
- `status`
- `detail`
- `exception`
- `timestamp`

상태 코드 기준:

- 요청 body, query parameter, enum 값이 잘못된 경우: `400`
- region, parcel, complex, detail, trade의 상위 리소스가 없는 경우: `404`
- 예기치 못한 서버 오류 또는 외부 연동 실패: `500`

예시:

```json
{
  "type": "/docs/index.html#error-code-list",
  "title": "C401",
  "status": 400,
  "detail": "Invalid parameter format.",
  "exception": "MapApiException",
  "timestamp": "2026-05-18T10:30:00"
}
```

## V1 API

### POST `/api/v1/map/regions`

목적:

- 현재 지도 bounds 안의 지역 단위 marker를 반환합니다.
- 카카오맵이 넓게 zoom out된 상태에서 사용합니다.

요청:

```json
{
  "swLat": 37.45,
  "swLng": 126.85,
  "neLat": 37.70,
  "neLng": 127.20,
  "region": "si-gun-gu"
}
```

필수 요청 필드:

- `swLat`
- `swLng`
- `neLat`
- `neLng`
- `region`

허용되는 `region` 값:

- `si-do`
- `si-gun-gu`
- `eup-myeon-dong`

응답:

```json
[
  {
    "id": 1,
    "name": "Seoul",
    "lat": 37.5663,
    "lng": 126.9780,
    "trend": null
  }
]
```

응답 필드:

- `id`: 지역 id.
- `name`: 표시 이름.
- `lat`: marker 위도.
- `lng`: marker 경도.
- `trend`: optional 지역 추세 값. V1에서는 `null`이거나 생략될 수 있습니다.

메모:

- 지역 trend 계산은 V1 지도 표시의 필수 조건이 아닙니다.
- 원본 repository alias와 target field명이 어긋날 수 있으므로 target V1에서는
  `name`, `lat`, `lng`를 표준으로 맞춥니다.
- `unitCntSum`은 V1 지역 marker 필수 필드가 아닙니다.

### POST `/api/v1/map/complexes`

목적:

- 현재 지도 bounds 안의 parcel 단위 아파트 marker를 반환합니다.
- 카카오맵이 상세 zoom level일 때 사용합니다.

요청:

```json
{
  "swLat": 37.45,
  "swLng": 126.85,
  "neLat": 37.70,
  "neLng": 127.20,
  "pyeongMin": null,
  "pyeongMax": null,
  "priceEokMin": null,
  "priceEokMax": null,
  "ageMin": null,
  "ageMax": null,
  "unitMin": null,
  "unitMax": null
}
```

응답:

```json
[
  {
    "parcelId": 1001,
    "lat": 37.5123,
    "lng": 127.0456,
    "latestDealAmount": 125000,
    "unitCntSum": 740
  }
]
```

응답 필드:

- `parcelId`: detail/trade API에서 사용할 parcel id.
- `lat`: marker 위도.
- `lng`: marker 경도.
- `latestDealAmount`: optional 최신 거래 금액. 만원 단위 정수입니다.
- `unitCntSum`: 해당 parcel 아래 단지들의 총 세대 수입니다.

메모:

- 원본 코드에는 `parcelId`, `id`, `latitude`, `lat`, `longitude`, `lng`가 섞여
  있습니다.
- target V1 백엔드는 `parcelId`, `lat`, `lng`를 표준으로 반환합니다.
- 프론트 adapter는 마이그레이션 중 `id`, `latitude`, `longitude`를 임시로
  받아줄 수 있습니다.
- 지도 marker API는 ranking, trend, favorite, alarm, mail, auth 상태에
  의존하면 안 됩니다.

### GET `/api/v1/search/complexes?q=`

목적:

- 사용자가 입력한 검색어로 아파트 단지를 검색합니다.
- 왼쪽 sidebar 검색 흐름에서 사용합니다.

요청:

- query parameter `q`: 필수 문자열. 검색 전 trim합니다.

응답:

```json
[
  {
    "complexId": 501,
    "complexName": "Sample Apartment",
    "parcelId": 1001,
    "latitude": 37.5123,
    "longitude": 127.0456,
    "address": "Sample address"
  }
]
```

메모:

- 이 endpoint는 원본 프론트 호환성을 위해 `latitude`, `longitude`를 유지합니다.
- 빈 검색어나 결과 없음은 `200 []`로 처리합니다.

### GET `/api/v1/region`

목적:

- 지역 탐색의 최상위 지역 목록을 불러옵니다.

응답:

```json
[
  {
    "id": 1,
    "name": "Seoul"
  }
]
```

### GET `/api/v1/region/{regionId}`

목적:

- 지역 상세, 하위 지역, 지도 이동용 중심 좌표를 불러옵니다.

응답:

```json
{
  "id": 1,
  "name": "Seoul",
  "latitude": 37.5663,
  "longitude": 126.9780,
  "children": [
    {
      "id": 11,
      "name": "Gangnam-gu"
    }
  ]
}
```

없는 `regionId`는 `404`입니다.

### GET `/api/v1/detail/{parcelId}`

목적:

- 선택된 marker의 parcel 및 대표 complex 상세 정보를 반환합니다.

응답:

```json
{
  "parcelId": 1001,
  "latitude": 37.5123,
  "longitude": 127.0456,
  "address": "Sample address",
  "tradeName": "Sample trade name",
  "name": "Sample complex name",
  "dongCnt": 8,
  "unitCnt": 740,
  "platArea": 12345.67,
  "archArea": 2345.67,
  "totArea": 98765.43,
  "bcRat": 22.5,
  "vlRat": 199.8,
  "useDate": "2015-03-20"
}
```

메모:

- 원본 DTO는 `null` 값을 생략합니다. target V1도 nullable 필드를 생략할 수
  있습니다.

### GET `/api/v1/trade/{parcelId}`

목적:

- 선택된 parcel 아래 complex들의 거래 목록을 반환합니다.

응답:

```json
{
  "parcelId": 1001,
  "trades": [
    {
      "tradeId": 9001,
      "dealDate": "2025-12-01",
      "exclArea": 84.93,
      "dealAmount": 125000,
      "aptDong": "101",
      "floor": 12
    }
  ]
}
```

거래 항목:

- `tradeId`: 거래 id.
- `dealDate`: `YYYY-MM-DD`.
- `exclArea`: 전용면적, 제곱미터 기준.
- `dealAmount`: 거래 금액, 만원 단위 정수.
- `aptDong`: optional 동명 또는 동 번호.
- `floor`: optional 층수.

메모:

- target V1 조회 경로는 `complex_id`를 기준으로 동작해야 합니다.
- `complex_pk`, `apt_seq`, `source`, `source_key`는 감사, 매칭, 중복 제거를 위해
  보존하지만 public 응답에는 노출하지 않습니다.
- 기본 정렬은 최신순입니다. `dealDate desc`, 같은 날짜면 `tradeId desc`를
  사용합니다.

## V2로 미루는 API

다음 API는 V1 핵심 경로에서 제외합니다.

- `/api/v1/rankings/top-price-30d`
- `/api/v1/rankings/top-volume-30d`
- `/api/v1/favorites`
- `/api/v1/users/me`
- `/auth/access`
- `/admin/batch/trade-alarm/run`

이 내용은 원본 지식에서 삭제하지 않습니다. 다만 수집, 저장, 지도 표시를 막는
blocker로 취급하지 않습니다.
