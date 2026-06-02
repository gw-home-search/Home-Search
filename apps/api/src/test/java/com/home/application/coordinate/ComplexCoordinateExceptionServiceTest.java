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
	@DisplayName("한 complex의 여러 apt_dong은 여러 VWorld 건물 동 footprint를 집계해 표시 좌표를 저장한다")
	void aggregatesMultipleBuildingFootprintsForOneComplex() {
		InMemoryCoordinateRepository repository = new InMemoryCoordinateRepository();
		repository.targets = Map.of(
			1001L,
			new ComplexCoordinateParcelTargets(
				1001L,
				"4128510200115660000",
				List.of(
					new ComplexCoordinateTarget(1590L, "41285-15", "중산마을10(동신)", Set.of("1001", "1002", "1003")),
					new ComplexCoordinateTarget(1628L, "41285-12", "중산마을10(경남)", Set.of("1006", "1008"))
				)
			)
		);
		repository.footprints = Map.of(
			"4128510200115660000",
			List.of(
				new BuildingFootprintCandidate(9001L, "4128510200115660000", "중산마을", "1001동", bd("37.6875000"), bd("126.7780000")),
				new BuildingFootprintCandidate(9002L, "4128510200115660000", "중산마을", "1002동", bd("37.6880000"), bd("126.7783000")),
				new BuildingFootprintCandidate(9003L, "4128510200115660000", "중산마을", "1003동", bd("37.6885000"), bd("126.7786000")),
				new BuildingFootprintCandidate(9006L, "4128510200115660000", "중산마을", "1006동", bd("37.6890000"), bd("126.7779000")),
				new BuildingFootprintCandidate(9008L, "4128510200115660000", "중산마을", "1008동", bd("37.6896000"), bd("126.7781000"))
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
			.extracting(
				ResolvedDisplayCoordinate::complexId,
				ResolvedDisplayCoordinate::latitude,
				ResolvedDisplayCoordinate::longitude,
				ResolvedDisplayCoordinate::reason
			)
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple(
					1590L,
					bd("37.6880000"),
					bd("126.7783000"),
					"apt_dong matched building dong_name aggregate footprint_count=3"
				),
				org.assertj.core.groups.Tuple.tuple(
					1628L,
					bd("37.6893000"),
					bd("126.7780000"),
					"apt_dong matched building dong_name aggregate footprint_count=2"
				)
			);
	}

	@Test
	@DisplayName("동일 VWorld 동 footprint가 여러 complex에 걸치면 좌표를 저장하지 않고 AMBIGUOUS로 남긴다")
	void keepsAmbiguousWhenBuildingFootprintMatchesMultipleComplexes() {
		InMemoryCoordinateRepository repository = new InMemoryCoordinateRepository();
		repository.targets = Map.of(
			1001L,
			new ComplexCoordinateParcelTargets(
				1001L,
				"4128510200115660000",
				List.of(
					new ComplexCoordinateTarget(1590L, "41285-15", "중산마을10(동신)", Set.of("1001", "1002")),
					new ComplexCoordinateTarget(1628L, "41285-12", "중산마을10(경남)", Set.of("1002", "1006"))
				)
			)
		);
		repository.footprints = Map.of(
			"4128510200115660000",
			List.of(
				new BuildingFootprintCandidate(9001L, "4128510200115660000", "중산마을", "1001동", bd("37.6875000"), bd("126.7780000")),
				new BuildingFootprintCandidate(9002L, "4128510200115660000", "중산마을", "1002동", bd("37.6880000"), bd("126.7783000")),
				new BuildingFootprintCandidate(9006L, "4128510200115660000", "중산마을", "1006동", bd("37.6890000"), bd("126.7779000"))
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
			.extracting(ComplexCoordinateCaseUpdate::status, ComplexCoordinateCaseUpdate::reason)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(
				ComplexCoordinateCaseStatus.AMBIGUOUS,
				"building dong candidates overlap across complexes"
			));
	}

	@Test
	@DisplayName("same-PNU 후보에 저장된 footprint가 없으면 VWorld source를 PNU 단건으로 조회해 저장 후 resolve한다")
	void fetchesVworldFootprintsWhenSamePnuCandidateHasNoStoredFootprints() {
		InMemoryCoordinateRepository repository = new InMemoryCoordinateRepository();
		repository.targets = Map.of(
			1001L,
			new ComplexCoordinateParcelTargets(
				1001L,
				"4128510200115660000",
				List.of(
					new ComplexCoordinateTarget(1590L, "41285-15", "중산마을10(동신)", Set.of("1001")),
					new ComplexCoordinateTarget(1628L, "41285-12", "중산마을10(경남)", Set.of("1006"))
				)
			)
		);
		FakeBuildingFootprintSource footprintSource = new FakeBuildingFootprintSource(Map.of(
			"4128510200115660000",
			List.of(
				importFootprint("4128510200115660000", "1001동", "37.6875000", "126.7780000"),
				importFootprint("4128510200115660000", "1006동", "37.6890000", "126.7779000")
			)
		));
		ComplexCoordinateExceptionService service = new ComplexCoordinateExceptionService(
			repository,
			parcelId -> List.of(),
			new ComplexRelationClassifier(),
			ComplexCoordinateIdentityVerifier.trusting(),
			footprintSource
		);

		ComplexCoordinateResolutionResult result = service.resolveExceptionCase(1001L);

		assertThat(result.status()).isEqualTo(ComplexCoordinateCaseStatus.RESOLVED);
		assertThat(footprintSource.requestedPnus).containsExactly("4128510200115660000");
		assertThat(repository.savedFootprints).hasSize(2);
		assertThat(repository.displayCoordinates)
			.extracting(ResolvedDisplayCoordinate::complexId, ResolvedDisplayCoordinate::latitude, ResolvedDisplayCoordinate::longitude)
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple(1590L, bd("37.6875000"), bd("126.7780000")),
				org.assertj.core.groups.Tuple.tuple(1628L, bd("37.6890000"), bd("126.7779000"))
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

	@Test
	@DisplayName("ODC identity를 확인할 수 없으면(기본 degrade) VWorld 동명 후보로 해석을 진행한다")
	void degradesToResolutionWhenOdcloudIdentityIsUnavailable() {
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
			(parcelTargets, target) -> ComplexCoordinateIdentityVerification.unavailable("ODC identity candidate unavailable")
		);

		ComplexCoordinateResolutionResult result = service.resolveExceptionCase(1001L);

		assertThat(result.status()).isEqualTo(ComplexCoordinateCaseStatus.RESOLVED);
		assertThat(repository.displayCoordinates)
			.extracting(ResolvedDisplayCoordinate::complexId)
			.containsExactly(501L);
	}

	@Test
	@DisplayName("block-on-unavailable strict 모드면 ODC UNAVAILABLE에서 좌표를 추측하지 않는다")
	void blocksCoordinateResolutionWhenOdcloudIdentityIsUnavailableAndStrict() {
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
			(parcelTargets, target) -> ComplexCoordinateIdentityVerification.unavailable("ODC identity candidate unavailable"),
			BuildingFootprintSource.unavailable(),
			true,
			false
		);

		ComplexCoordinateResolutionResult result = service.resolveExceptionCase(1001L);

		assertThat(result.status()).isEqualTo(ComplexCoordinateCaseStatus.UNAVAILABLE);
		assertThat(repository.displayCoordinates).isEmpty();
		assertThat(repository.caseUpdates)
			.extracting(ComplexCoordinateCaseUpdate::status, ComplexCoordinateCaseUpdate::reason)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(
				ComplexCoordinateCaseStatus.UNAVAILABLE,
				"identity verification unavailable complexId=501 reason=ODC identity candidate unavailable"
			));
	}

	@Test
	@DisplayName("ODC identity 조회가 실패해도(기본 degrade) VWorld 동명 후보로 해석을 진행한다")
	void degradesToResolutionWhenOdcloudIdentityFails() {
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
			(parcelTargets, target) -> ComplexCoordinateIdentityVerification.failed("ODC identity lookup failed")
		);

		ComplexCoordinateResolutionResult result = service.resolveExceptionCase(1001L);

		assertThat(result.status()).isEqualTo(ComplexCoordinateCaseStatus.RESOLVED);
		assertThat(repository.displayCoordinates)
			.extracting(ResolvedDisplayCoordinate::complexId)
			.containsExactly(501L);
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

	private static BuildingFootprintImportCandidate importFootprint(
		String pnu,
		String dongName,
		String latitude,
		String longitude
	) {
		return new BuildingFootprintImportCandidate(
			pnu,
			"중산마을",
			dongName,
			"VWORLD-" + pnu + "-" + dongName,
			bd(latitude),
			bd(longitude),
			"VWORLD_WFS",
			"test"
		);
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
		private final List<BuildingFootprintImportCandidate> savedFootprints = new ArrayList<>();
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
		public void saveBuildingFootprints(List<BuildingFootprintImportCandidate> footprints) {
			savedFootprints.addAll(footprints);
			Map<String, List<BuildingFootprintCandidate>> mutable = new java.util.HashMap<>(this.footprints);
			for (BuildingFootprintImportCandidate footprint : footprints) {
				List<BuildingFootprintCandidate> existing = new ArrayList<>(
					mutable.getOrDefault(footprint.pnu(), List.of())
				);
				existing.add(new BuildingFootprintCandidate(
					(long) savedFootprints.size() + existing.size(),
					footprint.pnu(),
					footprint.buildingName(),
					footprint.dongName(),
					footprint.latitude(),
					footprint.longitude()
				));
				mutable.put(footprint.pnu(), existing);
			}
			this.footprints = mutable;
		}

		@Override
		public void saveResolvedDisplayCoordinate(ResolvedDisplayCoordinate coordinate) {
			displayCoordinates.add(coordinate);
		}
	}

	private static final class FakeBuildingFootprintSource implements BuildingFootprintSource {

		private final Map<String, List<BuildingFootprintImportCandidate>> footprints;
		private final List<String> requestedPnus = new ArrayList<>();

		private FakeBuildingFootprintSource(Map<String, List<BuildingFootprintImportCandidate>> footprints) {
			this.footprints = footprints;
		}

		@Override
		public List<BuildingFootprintImportCandidate> fetchByPnu(String pnu) {
			requestedPnus.add(pnu);
			return footprints.getOrDefault(pnu, List.of());
		}
	}
}
