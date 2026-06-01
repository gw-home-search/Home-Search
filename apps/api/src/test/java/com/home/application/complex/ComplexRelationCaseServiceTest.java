package com.home.application.complex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplexRelationCaseServiceTest {

	@Test
	@DisplayName("complex relation case service는 case_key 기준으로 최신 판정 evidence를 저장한다")
	void savesClassificationAsLatestRelationCaseByCaseKey() {
		FakeRelationRepository relationRepository = new FakeRelationRepository(List.of(
			span(501L, "COMPLEX-PK-501", "APT-501", "A", "2024-01-01", "2025-06-01", "2010-01-01"),
			span(502L, "COMPLEX-PK-502", "APT-502", "B", "2025-01-01", "2025-12-01", "2015-01-01")
		));
		FakeRelationCaseRepository caseRepository = new FakeRelationCaseRepository();
		ComplexRelationCaseService service = new ComplexRelationCaseService(
			relationRepository,
			new ComplexRelationClassifier(),
			caseRepository,
			"relation-classifier-stable"
		);

		ComplexRelationCaseRecord saved = service.classifyAndSave(1001L);
		ComplexRelationCaseRecord savedAgain = service.classifyAndSave(1001L);

		assertThat(saved.id()).isEqualTo(1L);
		assertThat(saved.caseKey()).isEqualTo("test-case:1001");
		assertThat(saved.pnu()).isEqualTo("1168010300101400001");
		assertThat(saved.relationType()).isEqualTo(ComplexRelationType.CONCURRENT);
		assertThat(saved.relationConfidence()).isEqualTo(ComplexRelationConfidence.HIGH);
		assertThat(saved.classifierVersion()).isEqualTo("relation-classifier-stable");
		assertThat(saved.evidenceJson()).contains("\"relationType\":\"CONCURRENT\"");
		assertThat(saved.members())
			.extracting(
				ComplexRelationCaseMember::complexId,
				ComplexRelationCaseMember::complexPk,
				ComplexRelationCaseMember::aptSeq,
				ComplexRelationCaseMember::firstDeal,
				ComplexRelationCaseMember::lastDeal,
				ComplexRelationCaseMember::tradeCount
			)
			.containsExactly(
				tuple(
					501L,
					"COMPLEX-PK-501",
					"APT-501",
					LocalDate.of(2024, 1, 1),
					LocalDate.of(2025, 6, 1),
					3L
				),
				tuple(
					502L,
					"COMPLEX-PK-502",
					"APT-502",
					LocalDate.of(2025, 1, 1),
					LocalDate.of(2025, 12, 1),
					3L
				)
			);
		assertThat(savedAgain.id()).isEqualTo(saved.id());
		assertThat(caseRepository.savedCases).hasSize(1);
	}

	private static ComplexTradeSpan span(
		Long complexId,
		String complexPk,
		String aptSeq,
		String name,
		String firstDeal,
		String lastDeal,
		String useDate
	) {
		return new ComplexTradeSpan(
			complexId,
			complexPk,
			aptSeq,
			name,
			LocalDate.parse(firstDeal),
			LocalDate.parse(lastDeal),
			3,
			LocalDate.parse(useDate)
		);
	}

	private record FakeRelationRepository(List<ComplexTradeSpan> spans) implements ComplexRelationRepository {

		@Override
		public List<ComplexTradeSpan> findTradeSpansByParcelId(Long parcelId) {
			return spans;
		}
	}

	private static final class FakeRelationCaseRepository implements ComplexRelationCaseRepository {

		private long nextId = 1L;
		private final Map<String, ComplexRelationCaseRecord> savedCases = new LinkedHashMap<>();

		@Override
		public ComplexRelationCaseRecord save(
			Long parcelId,
			ComplexRelationClassification classification,
			String classifierVersion
		) {
			String caseKey = "test-case:" + parcelId;
			Long id = savedCases.containsKey(caseKey) ? savedCases.get(caseKey).id() : nextId++;
			ComplexRelationCaseRecord record = new ComplexRelationCaseRecord(
				id,
				caseKey,
				parcelId,
				"1168010300101400001",
				classification.type(),
				classification.confidence(),
				classification.reason(),
				classifierVersion,
				"{\"relationType\":\"" + classification.type().name() + "\"}",
				classification.spans().stream()
					.map(span -> new ComplexRelationCaseMember(
						span.complexId(),
						span.complexPk(),
						span.aptSeq(),
						span.name(),
						span.firstDeal(),
						span.lastDeal(),
						span.tradeCount(),
						span.useDate()
					))
					.toList()
			);
			savedCases.put(caseKey, record);
			return record;
		}

		@Override
		public List<ComplexRelationCaseRecord> findByParcelId(Long parcelId) {
			return savedCases.values().stream()
				.filter(record -> record.parcelId().equals(parcelId))
				.toList();
		}
	}
}
