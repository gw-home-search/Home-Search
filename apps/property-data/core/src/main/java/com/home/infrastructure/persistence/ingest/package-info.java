/**
 * raw ingest와 normalized trade 저장 invariant를 JDBC/Flyway schema로 검증하는 persistence adapter 패키지입니다.
 *
 * <p>raw evidence 저장부터 최종 status 갱신까지는 하나의 cross-repository transaction으로 묶지 않습니다.
 * normalized trade와 source-key registry의 원자성은 normalization adapter 내부 transaction이 담당합니다.
 * 전체 경계와 복구 한계는 {@code docs/DATA_STORAGE.md}를 따릅니다.</p>
 */
package com.home.infrastructure.persistence.ingest;
