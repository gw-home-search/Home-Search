package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.home.application.ingest.buildingmetadata.BuildingMetadataTarget;
import com.home.application.ingest.buildingmetadata.ParsedBuildingMetadataSource;
import com.home.domain.complex.buildingmetadata.BuildingMetadataMatchPolicy.SourceCandidate;
import com.home.domain.complex.buildingmetadata.BuildingMetadataSourceKind;
import com.home.domain.complex.buildingmetadata.BuildingMetadataValues;
import com.home.domain.complex.metadata.ComplexMetadataFailureKind;
import com.home.domain.complex.metadata.ComplexMetadataStatus;
import com.home.infrastructure.persistence.ingest.matching.JdbcBuildingMetadataEvidenceRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcBuildingMetadataEvidenceRepositoryTest extends JdbcMigrationTestSupport {
	private static final UUID REQUEST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
	private JdbcBuildingMetadataEvidenceRepository repository;

	@BeforeEach
	void migrate() {
		flyway(null).clean(); flyway(null).migrate();
		repository = new JdbcBuildingMetadataEvidenceRepository(jdbcClient,transactionTemplate);
	}

	@Test
	void selectsOnlyMissingAreaTargetsAndCountsAllComplexesOnPnu() {
		seed(501,1001,"1168010300101400001",false);
		seed(502,1002,"1168010300101400001",true);
		seed(503,1003,"1168010300101400002",true);

		List<BuildingMetadataTarget> targets = repository.findTargets("missing",10,null,null);

		assertThat(targets).extracting(BuildingMetadataTarget::complexId).containsExactly(502L,503L);
		assertThat(targets.get(0).pnuComplexCount()).isEqualTo(2);
	}

	@Test
	void fillsNullsStoresIdentityAndAliasWithoutOverwritingExistingCoreValues() {
		seed(501,1001,"1168010300101400001",true);
		jdbcClient.sql("UPDATE complex SET dong_cnt=1,unit_cnt=100,use_date='2015-01-01' WHERE id=501").update();
		BuildingMetadataTarget target = repository.findTargets("missing",1,null,null).get(0);
		BuildingMetadataValues values = new BuildingMetadataValues(1,100,new BigDecimal("1000"),new BigDecimal("200"),
			new BigDecimal("3000"),new BigDecimal("20"),new BigDecimal("300"),LocalDate.of(2015,1,1));

		var result = repository.apply(target,BuildingMetadataSourceKind.BLD_TITLE,
			new ParsedBuildingMetadataSource(1,List.of(new SourceCandidate("BLD-501",target.pnu(),
				List.of("Sample Apartment"),values,null))),REQUEST_ID);

		assertThat(result.projectionApplied()).isTrue();
		assertThat(jdbcClient.sql("SELECT bld_mgm_bld_rgst_pk FROM complex WHERE id=501").query(String.class).single())
			.isEqualTo("BLD-501");
		assertThat(jdbcClient.sql("SELECT projection_applied FROM complex_metadata_enrichment_attempt WHERE complex_id=501")
			.query(Boolean.class).single()).isTrue();
		assertThat(jdbcClient.sql("SELECT count(*) FROM complex_name_alias WHERE complex_id=501 AND source='BLD'")
			.query(Integer.class).single()).isOne();
	}

	@Test
	void recordsConflictWithoutProjectionAndSharedPnuIsIdempotentlyExcludedFromMissingMode() {
		seed(501,1001,"1168010300101400001",true);
		seed(502,1002,"1168010300101400001",true);
		BuildingMetadataTarget shared = repository.findTargets("missing",10,null,null).get(0);
		repository.recordAmbiguousPnu(shared,REQUEST_ID);
		assertThat(repository.findTargets("missing",10,501L,501L)).isEmpty();

		seed(503,1003,"1168010300101400002",true);
		jdbcClient.sql("UPDATE complex SET unit_cnt=100 WHERE id=503").update();
		BuildingMetadataTarget target = repository.findTargets("missing",10,503L,503L).get(0);
		BuildingMetadataValues conflict = new BuildingMetadataValues(null,101,new BigDecimal("1000"),null,null,null,null,null);
		var result = repository.apply(target,BuildingMetadataSourceKind.BLD_RECAP_TITLE,
			new ParsedBuildingMetadataSource(1,List.of(new SourceCandidate("BLD-503",target.pnu(),
				List.of("Sample Apartment"),conflict,null))),REQUEST_ID);
		assertThat(result.projectionApplied()).isFalse();
		assertThat(jdbcClient.sql("SELECT plat_area FROM complex WHERE id=503").query(BigDecimal.class).optional()).isEmpty();
	}

	@Test
	void recordsAllUnsafeCandidateShapesAndExplicitFailures() {
		seed(501,1001,"1168010300101400001",true);
		BuildingMetadataTarget target = repository.findTargets("missing",1,null,null).get(0);
		SourceCandidate valid = candidate("BLD-501","Sample Apartment",BuildingMetadataValues.empty(),target.pnu());
		assertThat(repository.apply(target,BuildingMetadataSourceKind.BLD_RECAP_TITLE,
			new ParsedBuildingMetadataSource(2,List.of(valid,valid)),REQUEST_ID).status()).isEqualTo(ComplexMetadataStatus.AMBIGUOUS);

		seed(502,1002,"1168010300101400002",true);
		BuildingMetadataTarget missingKey = repository.findTargets("missing",1,502L,502L).get(0);
		assertThat(repository.apply(missingKey,BuildingMetadataSourceKind.BLD_TITLE,
			new ParsedBuildingMetadataSource(1,List.of(candidate(null,"Sample Apartment",BuildingMetadataValues.empty(),missingKey.pnu()))),REQUEST_ID)
			.status()).isEqualTo(ComplexMetadataStatus.FAILED);

		seed(503,1003,"1168010300101400003",true);
		jdbcClient.sql("UPDATE complex SET bld_mgm_bld_rgst_pk='OLD-503' WHERE id=503").update();
		BuildingMetadataTarget identityConflict = repository.findTargets("missing",1,503L,503L).get(0);
		assertThat(repository.apply(identityConflict,BuildingMetadataSourceKind.BLD_TITLE,
			new ParsedBuildingMetadataSource(1,List.of(candidate("NEW-503","Sample Apartment",BuildingMetadataValues.empty(),identityConflict.pnu()))),REQUEST_ID)
			.projectionApplied()).isFalse();

		seed(504,1004,"1168010300101400004",true); seed(505,1005,"1168010300101400005",true);
		jdbcClient.sql("UPDATE complex SET bld_mgm_bld_rgst_pk='SHARED-KEY' WHERE id=504").update();
		BuildingMetadataTarget ownerConflict = repository.findTargets("missing",1,505L,505L).get(0);
		assertThat(repository.apply(ownerConflict,BuildingMetadataSourceKind.BLD_TITLE,
			new ParsedBuildingMetadataSource(1,List.of(candidate("SHARED-KEY","Sample Apartment",BuildingMetadataValues.empty(),ownerConflict.pnu()))),REQUEST_ID)
			.status()).isEqualTo(ComplexMetadataStatus.AMBIGUOUS);

		seed(506,1006,"1168010300101400006",true);
		BuildingMetadataTarget nameConflict = repository.findTargets("missing",1,506L,506L).get(0);
		assertThat(repository.apply(nameConflict,BuildingMetadataSourceKind.BLD_TITLE,
			new ParsedBuildingMetadataSource(1,List.of(candidate("BLD-506","Different Apartment",BuildingMetadataValues.empty(),nameConflict.pnu()))),REQUEST_ID)
			.projectionApplied()).isFalse();

		seed(507,1007,"1168010300101400007",true);
		BuildingMetadataTarget failure = repository.findTargets("missing",1,507L,507L).get(0);
		repository.recordFailure(failure,BuildingMetadataSourceKind.BLD_TITLE,ComplexMetadataStatus.FAILED,
			ComplexMetadataFailureKind.TRANSIENT,"temporary",REQUEST_ID,java.time.Instant.now());
		assertThat(repository.findTargets("retry",10,507L,507L)).isNotEmpty();
		assertThatThrownBy(() -> repository.findTargets("refresh",1,null,null)).isInstanceOf(IllegalArgumentException.class);
	}

	private SourceCandidate candidate(String key,String name,BuildingMetadataValues values,String pnu) {
		return new SourceCandidate(key,pnu,List.of(name),values,null);
	}

	private void seed(long id,long parcelId,String pnu,boolean missing) {
		jdbcClient.sql("INSERT INTO parcel(id,pnu,address) VALUES (:pid,:pnu,'Sample') ON CONFLICT (pnu) DO NOTHING")
			.param("pid",parcelId).param("pnu",pnu).update();
		jdbcClient.sql("""
			INSERT INTO complex(id,parcel_id,complex_pk,name,plat_area,arch_area,tot_area,bc_rat,vl_rat)
			VALUES (:id,(SELECT id FROM parcel WHERE pnu=:pnu),:pk,'Sample Apartment',:plat,:arch,:tot,:bc,:vl)
			""").param("id",id).param("pnu",pnu).param("pk","RTMS:"+id)
			.param("plat",missing?null:new BigDecimal("1")).param("arch",missing?null:new BigDecimal("1"))
			.param("tot",missing?null:new BigDecimal("1")).param("bc",missing?null:new BigDecimal("1"))
			.param("vl",missing?null:new BigDecimal("1")).update();
	}
}
