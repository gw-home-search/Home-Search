package com.home.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MigrationOperationRequestTest {

	@Test
	@DisplayName("migrate는 numeric target과 같은 confirm을 요구한다")
	void migrateRequiresMatchingTargetConfirmation() {
		MigrationOperationRequest request = MigrationOperationRequest.parse(new String[] {
			"--operation=migrate", "--target=5", "--confirm=5"
		});

		assertThat(request.operation()).isEqualTo(MigrationOperation.MIGRATE);
		assertThat(request.target()).isEqualTo("5");
		assertThat(request.confirm()).isEqualTo("5");
	}

	@Test
	@DisplayName("latest migrate는 명시적 numeric confirmation을 요구한다")
	void latestMigrateRequiresNumericConfirmation() {
		MigrationOperationRequest request = MigrationOperationRequest.parse(new String[] {
			"--operation=migrate", "--target=latest", "--confirm=6"
		});

		assertThat(request.target()).isEqualTo("latest");
		assertThat(request.confirm()).isEqualTo("6");
	}

	@Test
	@DisplayName("repair-missing-v3는 confirm 3 외 입력을 거부한다")
	void repairOnlyAcceptsV3Confirmation() {
		assertThatThrownBy(() -> MigrationOperationRequest.parse(new String[] {
			"--operation=repair-missing-v3", "--confirm=4"
		}))
			.isInstanceOf(MigrationUsageException.class)
			.extracting("exitCode")
			.isEqualTo(2);
	}

	@Test
	@DisplayName("backfill은 bounded batch와 sleep 기본값을 사용한다")
	void backfillUsesBoundedDefaults() {
		MigrationOperationRequest request = MigrationOperationRequest.parse(new String[] {
			"--operation=backfill-registry-trade-date"
		});

		assertThat(request.batchSize()).isEqualTo(20_000);
		assertThat(request.sleepMillis()).isEqualTo(100L);
	}

	@Test
	@DisplayName("operation 누락은 exit 2 usage error다")
	void missingOperationIsUsageError() {
		assertThatThrownBy(() -> MigrationOperationRequest.parse(new String[0]))
			.isInstanceOf(MigrationUsageException.class)
			.extracting("exitCode")
			.isEqualTo(2);
	}
}
