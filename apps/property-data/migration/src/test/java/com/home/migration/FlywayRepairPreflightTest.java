package com.home.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.home.migration.FlywayRepairPreflight.MigrationSnapshot;

import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FlywayRepairPreflightTest {

	@Test
	@DisplayName("검증된 V1 checksum 정렬과 V3 missing 및 후속 pending만 repair 대상으로 허용한다")
	void acceptsKnownV1AlignmentAndMissingV3() {
		var decision = FlywayRepairPreflight.verify(List.of(
			snapshot("1", MigrationState.SUCCESS, 2_040_410_589, 1_472_119_118),
			snapshot("2", MigrationState.SUCCESS, null, null),
			snapshot("3", MigrationState.MISSING_SUCCESS, -1_894_376_378, null),
			snapshot("4", MigrationState.SUCCESS, 927_770_666, 927_770_666),
			snapshot("5", MigrationState.PENDING, null, null),
			snapshot("6", MigrationState.PENDING, null, null),
			snapshot("7", MigrationState.PENDING, null, null)
		));

		assertThat(decision.alignV1()).isTrue();
	}

	@Test
	@DisplayName("알려지지 않은 V1 checksum mismatch는 repair 전에 거부한다")
	void rejectsUnknownV1ChecksumMismatch() {
		assertThatThrownBy(() -> FlywayRepairPreflight.verify(List.of(
			snapshot("1", MigrationState.SUCCESS, 123, 1_472_119_118),
			snapshot("2", MigrationState.SUCCESS, null, null),
			snapshot("3", MigrationState.MISSING_SUCCESS, -1_894_376_378, null),
			snapshot("4", MigrationState.SUCCESS, 927_770_666, 927_770_666)
		))).isInstanceOf(MigrationOperationException.class).hasMessageContaining("V1 checksum");
	}

	@Test
	@DisplayName("이미 일치하는 V1은 alignment 없이 V3 deletion만 허용한다")
	void acceptsAlreadyAlignedV1() {
		var decision = FlywayRepairPreflight.verify(base(
			snapshot("1", MigrationState.SUCCESS, 1_472_119_118, 1_472_119_118)));
		assertThat(decision.alignV1()).isFalse();
	}

	@Test
	@DisplayName("V2와 V4 checksum 또는 V3 missing 상태가 다르면 repair를 거부한다")
	void rejectsUnexpectedAppliedStates() {
		assertThatThrownBy(() -> FlywayRepairPreflight.verify(base(
			snapshot("2", MigrationState.FAILED, null, null))))
			.isInstanceOf(MigrationOperationException.class).hasMessageContaining("V2");
		assertThatThrownBy(() -> FlywayRepairPreflight.verify(base(
			snapshot("3", MigrationState.SUCCESS, -1_894_376_378, null))))
			.isInstanceOf(MigrationOperationException.class).hasMessageContaining("V3");
		assertThatThrownBy(() -> FlywayRepairPreflight.verify(base(
			snapshot("4", MigrationState.SUCCESS, 1, 2))))
			.isInstanceOf(MigrationOperationException.class).hasMessageContaining("V4");
	}

	@Test
	@DisplayName("필수 migration 부재와 V4 이후 비 pending 상태를 거부한다")
	void rejectsMissingOrUnexpectedFutureMigration() {
		assertThatThrownBy(() -> FlywayRepairPreflight.verify(List.of(
			snapshot("1", MigrationState.SUCCESS, 1_472_119_118, 1_472_119_118))))
			.isInstanceOf(MigrationOperationException.class).hasMessageContaining("V2");
		assertThatThrownBy(() -> FlywayRepairPreflight.verify(base(
			snapshot("5", MigrationState.SUCCESS, 1, 1))))
			.isInstanceOf(MigrationOperationException.class).hasMessageContaining("post-V4");
		assertThatThrownBy(() -> FlywayRepairPreflight.verify(base(
			snapshot("repeatable", MigrationState.PENDING, null, null))))
			.isInstanceOf(MigrationOperationException.class).hasMessageContaining("post-V4");
	}

	private List<MigrationSnapshot> base(MigrationSnapshot replacement) {
		java.util.Map<String,MigrationSnapshot> values = new java.util.LinkedHashMap<>();
		values.put("1", snapshot("1",MigrationState.SUCCESS,2_040_410_589,1_472_119_118));
		values.put("2", snapshot("2",MigrationState.SUCCESS,null,null));
		values.put("3", snapshot("3",MigrationState.MISSING_SUCCESS,-1_894_376_378,null));
		values.put("4", snapshot("4",MigrationState.SUCCESS,927_770_666,927_770_666));
		values.put("5", snapshot("5",MigrationState.PENDING,null,null));
		values.put(replacement.version(),replacement);
		return List.copyOf(values.values());
	}

	private MigrationSnapshot snapshot(String version, MigrationState state, Integer applied, Integer resolved) {
		return new MigrationSnapshot(version, state, applied, resolved);
	}
}
