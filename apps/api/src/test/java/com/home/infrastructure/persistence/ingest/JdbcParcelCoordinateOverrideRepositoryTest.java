package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcParcelCoordinateOverrideRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("승인된 좌표 override만 parcel coordinate fallback으로 사용된다")
	void findsOnlyApprovedCoordinateOverrideByPnu() {
		jdbcClient.sql("""
			INSERT INTO parcel_coordinate_override (
			    pnu,
			    apt_seq,
			    apt_name,
			    address_text,
			    latitude,
			    longitude,
			    source,
			    confidence,
			    status,
			    reason,
			    approved_by,
			    approved_at
			)
			VALUES
			    (
			        '1168010300107770001',
			        'APT-777',
			        'Approved Apartment',
			        'Sample-dong 777-1',
			        37.6012345,
			        127.1543210,
			        'MANUAL',
			        'HIGH',
			        'APPROVED',
			        'operator verified missing snapshot coordinate',
			        'test-operator',
			        now()
			    ),
			    (
			        '1168010300108880001',
			        'APT-888',
			        'Candidate Apartment',
			        'Sample-dong 888-1',
			        37.7012345,
			        127.2543210,
			        'VWORLD_ADDRESS_SEARCH',
			        'LOW',
			        'CANDIDATE',
			        'address search candidate requires review',
			        NULL,
			        NULL
			    )
			""").update();
		JdbcParcelCoordinateOverrideRepository repository = new JdbcParcelCoordinateOverrideRepository(jdbcClient);

		assertThat(repository.findApprovedByPnu(" 1168010300107770001 "))
			.hasValueSatisfying(coordinate -> {
				assertThat(coordinate.latitude()).isEqualByComparingTo(new BigDecimal("37.6012345"));
				assertThat(coordinate.longitude()).isEqualByComparingTo(new BigDecimal("127.1543210"));
				assertThat(coordinate.geometryWkt()).isNull();
			});
		assertThat(repository.findApprovedByPnu("1168010300108880001")).isEmpty();
		assertThat(repository.findApprovedByPnu(" ")).isEmpty();
	}
}
