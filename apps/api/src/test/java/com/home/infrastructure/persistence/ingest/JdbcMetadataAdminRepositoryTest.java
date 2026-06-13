package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.infrastructure.persistence.ingest.matching.JdbcMetadataAdminRepository;
import com.home.domain.complex.metadata.OdcloudPnuAliasStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
class JdbcMetadataAdminRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("metadata admin repository는 HOLD와 retry를 감사 이력과 함께 저장한다")
	void holdsAndRetriesWithAuditHistory() {
		seedMetadataComplex();
		var repository = new JdbcMetadataAdminRepository(jdbcClient, transactionTemplate);

		assertThat(repository.hold(501L, "operator", "source conflict").updated()).isTrue();
		assertThat(repository.findPending(10, 0)).singleElement().satisfies(row -> {
			assertThat(row.holdAt()).isNotNull();
			assertThat(row.holdReason()).isEqualTo("source conflict");
		});
		assertThat(repository.retry(501L, "operator", "source updated").updated()).isTrue();

		var detail = repository.detail(501L);
		assertThat(detail.complex().holdAt()).isNull();
		assertThat(detail.complex().nextAttemptAt()).isNotNull();
		assertThat(detail.decisions()).extracting(decision -> decision.decisionType())
			.containsExactly("RETRY_REQUESTED", "HOLD");
	}

	@Test
	@DisplayName("metadata admin repository는 alias 제안과 승인 상태를 감사 이력과 함께 저장한다")
	void proposesAndApprovesAliasWithAuditHistory() {
		seedMetadataComplex();
		var repository = new JdbcMetadataAdminRepository(jdbcClient, transactionTemplate);

		var proposed = repository.proposeAlias("11680103", "11110102", "operator", "legacy source");
		assertThat(proposed.status()).isEqualTo(OdcloudPnuAliasStatus.PENDING);
		assertThat(repository.approveAlias(proposed.id(), "reviewer", "verified").updated()).isTrue();
		assertThat(repository.aliases()).filteredOn(alias -> alias.id() == proposed.id()).singleElement()
			.satisfies(alias -> {
				assertThat(alias.status()).isEqualTo(OdcloudPnuAliasStatus.APPROVED);
				assertThat(alias.approvedBy()).isEqualTo("reviewer");
			});
		assertThat(repository.detail(501L).complex().nextAttemptAt()).isNotNull();
	}

	private void seedMetadataComplex() {
		jdbcClient.sql("INSERT INTO region(id,code,name,region_type) VALUES(1,'1168010300','Sample','eup-myeon-dong')")
			.update();
		jdbcClient.sql("""
			INSERT INTO parcel(id,region_id,pnu,address,latitude,longitude)
			VALUES(1001,1,'1168010300101400001','Sample address',37.5,127.0)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex(id,parcel_id,complex_pk,apt_seq,name,trade_name,metadata_status)
			VALUES(501,1001,'PK-501','APT-501','Sample','Sample','UNAVAILABLE')
			""").update();
	}
}
