package com.home.application.coordinate;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.home.application.complex.ComplexRelationClassifier;
import com.home.application.complex.ComplexRelationConfidence;
import com.home.application.complex.ComplexRelationRepository;
import com.home.application.complex.ComplexRelationType;
import com.home.application.complex.ComplexTradeSpan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplexCoordinateExceptionServiceTest {

	@Test
	@DisplayName("같은 parcel의 동시 존재 또는 불명확한 complex만 좌표 보정 case로 등록한다")
	void stagesOnlyConcurrentOrUnknownMultiComplexParcels() {
		InMemoryCoordinateRepository repository = new InMemoryCoordinateRepository();
		repository.caseCandidates = List.of(
			new ComplexCoordinateCaseCandidate(1001L),
			new ComplexCoordinateCaseCandidate(1002L)
		);
		FakeRelationRepository relationRepository = new FakeRelationRepository(Map.of(
			1001L,
			List.of(
				span(501L, "APT-501", "A", LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), 2, null),
				span(502L, "APT-502", "B", LocalDate.of(2024, 6, 1), LocalDate.of(2025, 6, 1), 2, null)
			),
			1002L,
			List.of(
				span(601L, "APT-601", "Old", LocalDate.of(2015, 1, 1), LocalDate.of(2016, 1, 1), 2, LocalDate.of(1990, 1, 1)),
				span(602L, "APT-602", "New", LocalDate.of(2020, 1, 1), LocalDate.of(2025, 1, 1), 2, LocalDate.of(2020, 1, 1))
			)
		));
		ComplexCoordinateExceptionService service = new ComplexCoordinateExceptionService(
			repository,
			relationRepository,
			new ComplexRelationClassifier()
		);

		ComplexCoordinateExceptionResult result = service.stageExceptionCases(10);

		assertThat(result.processed()).isEqualTo(2);
		assertThat(result.pending()).isEqualTo(1);
		assertThat(result.skipped()).isEqualTo(1);
		assertThat(repository.caseUpdates)
			.extracting(ComplexCoordinateCaseUpdate::parcelId, ComplexCoordinateCaseUpdate::status)
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple(1001L, ComplexCoordinateCaseStatus.PENDING),
				org.assertj.core.groups.Tuple.tuple(1002L, ComplexCoordinateCaseStatus.SKIPPED)
			);
	}

	@Test
	@DisplayName("apt_dong이 건물 동명 후보를 하나로 좁히면 complex별 표시 좌표를 저장한다")
	void resolvesDisplayCoordinatesWhenAptDongMatchesSingleBuildingCandidate() {
		InMemoryCoordinateRepository repository = new InMemoryCoordinateRepository();
		repository.targets = Map.of(
			1001L,
			new ComplexCoordinateParcelTargets(
				1001L,
				"1168010300101400001",
				List.of(
					new ComplexCoordinateTarget(501L, "APT-501", "Tower A", Set.of("101")),
					new ComplexCoordinateTarget(502L, "APT-502", "Tower B", Set.of("201"))
				)
			)
		);
		repository.footprints = Map.of(
			"1168010300101400001",
			List.of(
				new BuildingFootprintCandidate(9001L, "1168010300101400001", "Tower A", "101동", bd("37.5010000"), bd("127.0010000")),
				new BuildingFootprintCandidate(9002L, "1168010300101400001", "Tower B", "201동", bd("37.5020000"), bd("127.0020000"))
			)
		);
		ComplexCoordinateExceptionService service = new ComplexCoordinateExceptionService(
			repository,
			parcelId -> List.of(),
			new ComplexRelationClassifier()
		);

		ComplexCoordinateResolutionResult result = service.resolveExceptionCase(1001L);

		assertThat(result.status()).isEqualTo(ComplexCoordinateCaseStatus.RESOLVED);
		assertThat(repository.displayCoordinates)
			.extracting(ResolvedDisplayCoordinate::complexId, ResolvedDisplayCoordinate::latitude, ResolvedDisplayCoordinate::longitude)
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple(501L, bd("37.5010000"), bd("127.0010000")),
				org.assertj.core.groups.Tuple.tuple(502L, bd("37.5020000"), bd("127.0020000"))
			);
	}

	@Test
	@DisplayName("동명 후보가 여러 개면 좌표를 추측하지 않고 AMBIGUOUS로 남긴다")
	void keepsAmbiguousWhenAptDongMatchesMultipleBuildingCandidates() {
		InMemoryCoordinateRepository repository = new InMemoryCoordinateRepository();
		repository.targets = Map.of(
			1001L,
			new ComplexCoordinateParcelTargets(
				1001L,
				"1168010300101400001",
				List.of(new ComplexCoordinateTarget(501L, "APT-501", "Tower A", Set.of("101")))
			)
		);
		repository.footprints = Map.of(
			"1168010300101400001",
			List.of(
				new BuildingFootprintCandidate(9001L, "1168010300101400001", "Tower A", "101동", bd("37.5010000"), bd("127.0010000")),
				new BuildingFootprintCandidate(9002L, "1168010300101400001", "Tower A Annex", "101", bd("37.5020000"), bd("127.0020000"))
			)
		);
		ComplexCoordinateExceptionService service = new ComplexCoordinateExceptionService(
			repository,
			parcelId -> List.of(),
			new ComplexRelationClassifier()
		);

		ComplexCoordinateResolutionResult result = service.resolveExceptionCase(1001L);

		assertThat(result.status()).isEqualTo(ComplexCoordinateCaseStatus.AMBIGUOUS);
		assertThat(repository.displayCoordinates).isEmpty();
		assertThat(repository.caseUpdates)
			.extracting(ComplexCoordinateCaseUpdate::status)
			.containsExactly(ComplexCoordinateCaseStatus.AMBIGUOUS);
	}

	@Test
	@DisplayName("ODC identity가 애매하면 동명 후보가 맞아도 complex 좌표를 추측하지 않는다")
	void blocksCoordinateResolutionWhenOdcloudIdentityIsAmbiguous() {
		InMemoryCoordinateRepository repository = new InMemoryCoordinateRepository();
		repository.targets = Map.of(
			1001L,
			new ComplexCoordinateParcelTargets(
				1001L,
				"1168010300101400001",
				List.of(new ComplexCoordinateTarget(501L, "APT-501", "Tower A", Set.of("101")))
			)
		);
		repository.footprints = Map.of(
			"1168010300101400001",
			List.of(new BuildingFootprintCandidate(9001L, "1168010300101400001", "Tower A", "101동", bd("37.5010000"), bd("127.0010000")))
		);
		ComplexCoordinateExceptionService service = new ComplexCoordinateExceptionService(
			repository,
			parcelId -> List.of(),
			new ComplexRelationClassifier(),
			(parcelTargets, target) -> ComplexCoordinateIdentityVerification.ambiguous("ODC has multiple PNU candidates")
		);

		ComplexCoordinateResolutionResult result = service.resolveExceptionCase(1001L);

		assertThat(result.status()).isEqualTo(ComplexCoordinateCaseStatus.AMBIGUOUS);
		assertThat(repository.displayCoordinates).isEmpty();
		assertThat(repository.caseUpdates)
			.extracting(ComplexCoordinateCaseUpdate::status, ComplexCoordinateCaseUpdate::reason)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(
				ComplexCoordinateCaseStatus.AMBIGUOUS,
				"identity verification ambiguous complexId=501 reason=ODC has multiple PNU candidates"
			));
	}

	private static ComplexTradeSpan span(
		Long complexId,
		String aptSeq,
		String name,
		LocalDate firstDeal,
		LocalDate lastDeal,
		long tradeCount,
		LocalDate useDate
	) {
		return new ComplexTradeSpan(complexId, aptSeq, name, firstDeal, lastDeal, tradeCount, useDate);
	}

	private static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}

	private static final class FakeRelationRepository implements ComplexRelationRepository {

		private final Map<Long, List<ComplexTradeSpan>> spansByParcelId;

		private FakeRelationRepository(Map<Long, List<ComplexTradeSpan>> spansByParcelId) {
			this.spansByParcelId = spansByParcelId;
		}

		@Override
		public List<ComplexTradeSpan> findTradeSpansByParcelId(Long parcelId) {
			return spansByParcelId.getOrDefault(parcelId, List.of());
		}
	}

	private static final class InMemoryCoordinateRepository implements ComplexCoordinateExceptionRepository {

		private List<ComplexCoordinateCaseCandidate> caseCandidates = List.of();
		private Map<Long, ComplexCoordinateParcelTargets> targets = Map.of();
		private Map<String, List<BuildingFootprintCandidate>> footprints = Map.of();
		private final List<ComplexCoordinateCaseUpdate> caseUpdates = new ArrayList<>();
		private final List<ResolvedDisplayCoordinate> displayCoordinates = new ArrayList<>();

		@Override
		public List<ComplexCoordinateCaseCandidate> findExceptionCaseCandidates(int limit) {
			return caseCandidates.stream().limit(limit).toList();
		}

		@Override
		public void saveCaseUpdate(ComplexCoordinateCaseUpdate update) {
			caseUpdates.add(update);
		}

		@Override
		public Optional<ComplexCoordinateParcelTargets> findParcelTargets(Long parcelId) {
			return Optional.ofNullable(targets.get(parcelId));
		}

		@Override
		public List<BuildingFootprintCandidate> findBuildingFootprintsByPnu(String pnu) {
			return footprints.getOrDefault(pnu, List.of());
		}

		@Override
		public void saveResolvedDisplayCoordinate(ResolvedDisplayCoordinate coordinate) {
			displayCoordinates.add(coordinate);
		}
	}
}
