# 챗봇 데이터 출처 카탈로그

## 원칙

챗봇 reference dataset은 `home_search_ai`에만 저장한다. 기존 부동산 사실은
`home_search.ai_read` SELECT-only view로 읽으며 `home_search`에 쓰지 않는다.
브라우저 대화, 사용자 질문, LLM 답변은 어떤 서버 DB에도 저장하지 않는다.

이 카탈로그의 `등급 후보`는 출처 유형을 뜻한다. 품질 검사를 통과한 active
snapshot 또는 검증된 실시간 응답에만 실제 근거 등급을 부여한다.

## Source Contract 필수 항목

각 source는 수집 전에 다음 항목을 등록해야 한다.

- `sourceId`, 제공기관, 공식 landing URL, 실제 acquisition URL
- 이용허락, 표시 의무, 재배포·파생물·캐시 허용 범위와 검토일
- 갱신 주기, source 기준일, freshness SLA와 grace period
- 파일/API 형식, 문자 인코딩, schema version, 좌표계
- 고유키, 필수 필드, 운영 상태 필드, 지역 코드
- expected coverage, row-count 변화 허용 범위
- 원본 checksum 방식과 개인정보·민감정보 포함 여부
- 담당 owner와 장애/변경 연락 경로

이용 조건 또는 저장 범위가 불명확하면 source 상태는 `보류`이고 수집하지 않는다.

## 출처 목록

| sourceId | 데이터셋·공식 URL | 제공기관 / 갱신 | 등급 후보 | 용도 | Slice 0 상태 |
|---|---|---|---|---|---|
| `property.ai_read` | 기존 `home_search`의 승인된 read-only view | property-data / 운영 데이터 | A | 단지, 거래, 가격 집계 | Slice 3 감사와 view/role 필요 |
| `edu.school-location` | [전국초중등학교위치표준데이터](https://www.data.go.kr/data/15021148/standard.do?recommendDataYn=Y) | 교육부 계열 / 반기 | A | 학교 ID, 유형, 운영상태, 위치 | 후보 등록; 이용조건·수집 미완료 |
| `edu.elementary-zone` | [전국초등학교통학구역표준데이터](https://www.data.go.kr/data/15021149/standard.do) | 교육부 계열 / 반기 | A | 초등 통학구역 polygon | 후보 등록; 이용조건·수집 미완료 |
| `edu.middle-zone` | [전국중학교학교군표준데이터](https://www.data.go.kr/data/15021151/standard.do?recommendDataYn=Y) | 교육부 계열 / 반기 | A | 중학교 학교군·중학구 polygon | 후보 등록; 이용조건·수집 미완료 |
| `edu.high-zone` | [전국고등학교학교군표준데이터](https://www.data.go.kr/data/15021153/standard.do) | 교육부 계열 / 반기 | A | 평준화지역 고등학교 학교군 polygon | 후보 등록; 이용조건·수집 미완료 |
| `edu.academy` | [전국학원및교습소표준데이터](https://www.data.go.kr/data/15096277/standard.do) | 교육부·KERIS / 수시 | A | 공식 등록 학원·교습소 | 후보 등록; 이용조건·수집 미완료 |
| `edu.school-info` | [학교알리미 공개용 데이터](https://www.data.go.kr/data/15014351/fileData.do) | KERIS / 파일 버전별 | A | 승인된 학생·교사·시설 지표 | 지표·freshness 계약 미확정 |
| `edu.statistics` | [교육통계서비스 유초중등 데이터셋](https://www.data.go.kr/data/15139276/fileData.do) | 교육부 / 파일 버전별 | A | 정의가 고정된 교육 통계 | 지표·freshness 계약 미확정 |
| `transport.rail-station` | [전국도시철도역사정보표준데이터](https://www.data.go.kr/data/15013205/standard.do) | 국토교통부·국가철도공단 / 연간 | A | 역명, 노선, 위치 | 계약 등록됨; 미수집 |
| `medical.hira-hospital` | [건강보험심사평가원 병원정보서비스](https://www.data.go.kr/data/15001698/openapi.do?recommendDataYn=Y) | 건강보험심사평가원 / API | B | 공식 의료기관 유형·위치 | API operation·쿼터 계약 미확정 |
| `childcare.center` | [전국어린이집표준데이터](https://www.data.go.kr/data/15013108/standard.do?recommendDataYn=Y) | 교육부·한국사회보장정보원 / 수시 | A 또는 B | 유형, 정원 등 공개 항목 | 후보 등록; 이용조건·수집 미완료 |
| `place.kakao-local` | [Kakao Local REST API](https://developers.kakao.com/docs/ko/local/dev-guide) | Kakao / 실시간 검색 | C | 주소·좌표 변환, 키워드·카테고리 장소 탐색 | 운영정책·캐시 승인 전 비활성 |
| `law.open-law` | [국가법령정보 공동활용](https://open.law.go.kr/LSO/openApi/guideList.do) | 법제처 / 현행·연혁 API | A | 법령, 시행령·시행규칙, 조문 버전 | 이용 조건·대상 법령 계약 미확정 |
| `official.redevelopment` | 지자체·국토부·공공기관 allowlist | 문서별 | D | 정비사업 공식 공고의 현재 단계 | allowlist 미확정 |

공공데이터포털 landing page에 표시된 갱신 주기는 수집 SLA의 입력값일 뿐이다.
실제 파일의 `데이터기준일자`, schema, 지역 coverage를 매번 검증한다.

## Kakao 사용 경계

- 카테고리 검색은 공식 문서의 `radius`, `page`, `size` 제한을 지키고 응답에
  실제 검색 조건과 조회 시각을 남긴다.
- 쿼터 숫자를 코드에 고정하지 않는다. 배포 시 앱 관리 페이지와
  [쿼터 문서](https://developers.kakao.com/docs/ko/getting-started/quota)를 확인한다.
- [운영 정책](https://developers.kakao.com/terms/ko/site-policies)의 저장·표시 조건을
  별도 검토해 승인하기 전에는 응답을 영구 저장하지 않는다.
- 0건은 “시설 없음”이 아니라 “지정한 Kakao 검색 조건에서 확인되지 않음”이다.
- Kakao 결과는 공식 학군, 전체 시설 원장, 실제 통근시간의 대체 데이터가 아니다.

## 게시 파이프라인

```text
source contract -> immutable raw object -> staging -> validate
  -> quarantine + quality report -> atomic active snapshot -> rollback pointer
```

1. 원본 bytes와 checksum, 수집 시각, source 기준일을 먼저 저장한다.
2. 같은 checksum 재수집은 새로운 snapshot을 중복 게시하지 않는다.
3. staging row는 운영 질의에서 접근할 수 없다.
4. 필수키, 중복, 좌표, 지역 coverage, 결측률, 이전 버전 대비 증감을 검사한다.
5. 불량 row는 삭제하지 않고 `reasonCode`와 함께 quarantine에 둔다.
6. 모든 blocking 검사가 `Pass`인 version만 active pointer로 전환한다.
7. 게시 실패 시 기존 active version을 유지하고 pointer 전환만으로 롤백한다.

## 데이터 준비 보고서

각 dataset 활성화 PR은 아래 형식을 채운다.

```text
데이터셋:
상태: Pass|Partial|Fail
출처 / 이용 조건:
source 기준일 / 수집일 / checksum:
원본 / 정상 / 격리 row 수:
시도 / 시군구 coverage:
좌표 유효 / 공간 매칭 성공률:
결측 / 중복 / 이상 증감:
활성화 가능한 Capability:
검증 근거 확인:
검증 공백:
잔여 위험:
```

`Partial` 또는 `Fail` 보고서는 신규 Capability를 `지원`으로 전환할 수 없다.
