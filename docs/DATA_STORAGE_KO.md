# 데이터 저장 전략 한국어 참조

> 사람 전용 한국어 참조 문서입니다. 기준 문서는 `docs/DATA_STORAGE.md`입니다.

## 목표

실거래 데이터를 실패 원인 설명과 재처리가 가능할 정도로 안전하게 저장한다. V1은 집계 분석보다 정확성, 추적 가능성, 지도 표시를 우선한다.

## 저장 모델

V1은 두 계층을 사용한다.

- raw ingest records: 외부 API 원본 보존, 재처리, 실패 원인 추적
- normalized operational trades: 지도와 상세 API에서 사용하는 정규화 거래 데이터

## 중복 방지

기본 중복 기준은 `source + source_key`다. source key가 안정적이지 않은 경우를 대비해 `complex_id + deal_date + floor + excl_area + deal_amount`를 보조 안전장치로 둔다.

## 매칭

소스의 matching intent를 보존하되 match path, matched `complex_id`, matched `complex_pk`, 실패 이유를 기록한다. raw record나 failed-match record 없이 거래가 사라지면 안 된다.

## 파티셔닝과 지도 조회

trade는 `deal_date` 기준 yearly partition과 default partition을 둔다. 지도 표시는 parcel 위치, bounds 필터, complex 세대 수, 최신 거래 금액만 필요하며 랭킹이나 추세 테이블에 의존하지 않는다.
