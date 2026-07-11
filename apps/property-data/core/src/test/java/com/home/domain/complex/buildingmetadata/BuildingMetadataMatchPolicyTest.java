package com.home.domain.complex.buildingmetadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import com.home.domain.complex.buildingmetadata.BuildingMetadataMatchPolicy.InternalCandidate;
import com.home.domain.complex.buildingmetadata.BuildingMetadataMatchPolicy.InternalName;
import com.home.domain.complex.buildingmetadata.BuildingMetadataMatchPolicy.SourceCandidate;
import com.home.domain.complex.metadata.ComplexMetadataFailureKind;
import com.home.domain.complex.metadata.ComplexMetadataStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildingMetadataMatchPolicyTest {
	private static final String PNU = "1168010300101400001";
	private final BuildingMetadataMatchPolicy policy = new BuildingMetadataMatchPolicy();

	@Test
	@DisplayName("이름 정규화는 NFKC·공백·구분기호만 제거하고 의미 단어와 숫자를 보존한다")
	void normalizesConservatively() {
		assertThat(ComplexNameNormalizer.normalize(" 래미안（1차）-아파트 ")).isEqualTo("래미안1차아파트");
		assertThat(ComplexNameNormalizer.normalize("래미안1차")).isNotEqualTo(ComplexNameNormalizer.normalize("래미안2차"));
	}

	@Test
	@DisplayName("ODC source identity는 숫자형 source key를 apt_seq와 비교하지 않고 승인 연결을 우선한다")
	void usesApprovedOdcIdentityWithoutComparingAptSeq() {
		var result = policy.resolveOdc(PNU, List.of(internal(501, "내부 이름")), List.of(
			new SourceCandidate("987654321", PNU, List.of("다른 이름"), BuildingMetadataValues.empty(), 501L)
		));

		assertThat(result.status()).isEqualTo(ComplexMetadataStatus.RESOLVED);
		assertThat(result.complexId()).isEqualTo(501L);
		assertThat(result.matchPath()).isEqualTo("APPROVED_SOURCE_IDENTITY");
	}

	@Test
	@DisplayName("ODC 이름은 PNU 내부 정규화 완전일치만 자동 확정하고 동률은 보류한다")
	void resolvesOnlyExactMutualNameAndRejectsTie() {
		var exact = policy.resolveOdc(PNU, List.of(internal(501, "래미안 1차"), internal(502, "래미안 2차")), List.of(
			new SourceCandidate("odc-1", PNU, List.of("래미안(1차)"), BuildingMetadataValues.empty(), null)
		));
		var partial = policy.resolveOdc(PNU, List.of(internal(501, "래미안 1차 아파트")), List.of(
			new SourceCandidate("odc-2", PNU, List.of("래미안"), BuildingMetadataValues.empty(), null)
		));
		var tie = policy.resolveOdc(PNU, List.of(internal(501, "동일 이름"), internal(502, "동일 이름")), List.of(
			new SourceCandidate("odc-3", PNU, List.of("동일 이름"), BuildingMetadataValues.empty(), null)
		));

		assertThat(exact.complexId()).isEqualTo(501L);
		assertThat(partial.status()).isEqualTo(ComplexMetadataStatus.AMBIGUOUS);
		assertThat(tie.failureKind()).isEqualTo(ComplexMetadataFailureKind.AMBIGUOUS);
	}

	@Test
	@DisplayName("건축물대장은 단일 PNU·단일 빈 이름만 허용하고 복수 단지는 보조값 충돌을 막는다")
	void handlesBlankBuildingNameAndAuxiliaryConflict() {
		var blankSingle = policy.resolveBuilding(PNU, List.of(internal(501, "래미안")), List.of(
			new SourceCandidate("bld-1", PNU, List.of(""), BuildingMetadataValues.empty(), null)
		));
		var blankMultiple = policy.resolveBuilding(PNU, List.of(internal(501, "래미안"), internal(502, "자이")), List.of(
			new SourceCandidate("bld-2", PNU, List.of(""), BuildingMetadataValues.empty(), null)
		));
		BuildingMetadataValues conflicting = new BuildingMetadataValues(8, 741, null, null, null, null, null,
			LocalDate.of(2015, 3, 20));
		var valueConflict = policy.resolveBuilding(PNU, List.of(
			new InternalCandidate(501, PNU, names("래미안"), new BuildingMetadataValues(8, 740, null, null, null, null, null,
				LocalDate.of(2015, 3, 20))),
			internal(502, "자이")
		), List.of(new SourceCandidate("bld-3", PNU, List.of("래미안"), conflicting, null)));

		assertThat(blankSingle.matchPath()).isEqualTo("BLD_SINGLE_PNU_EMPTY_NAME");
		assertThat(blankMultiple.status()).isEqualTo(ComplexMetadataStatus.AMBIGUOUS);
		assertThat(valueConflict.failureKind()).isEqualTo(ComplexMetadataFailureKind.AMBIGUOUS);
	}

	private InternalCandidate internal(long id, String name) {
		return new InternalCandidate(id, PNU, names(name), BuildingMetadataValues.empty());
	}

	private List<InternalName> names(String name) {
		return List.of(new InternalName("CANONICAL", name, 3));
	}
}
