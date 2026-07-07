package com.home.infrastructure.persistence.region;

import java.util.Objects;
import java.util.function.Supplier;

import com.home.application.region.RegionRelationSynchronizationGateway;
import com.home.application.region.RegionRelationSynchronizationResult;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcRegionRelationSynchronizationRepository implements RegionRelationSynchronizationGateway {

	private final Supplier<JdbcClient> jdbcClientSupplier;
	private final Supplier<TransactionTemplate> transactionTemplateSupplier;

	JdbcRegionRelationSynchronizationRepository(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
		this(() -> jdbcClient, () -> transactionTemplate);
	}

	JdbcRegionRelationSynchronizationRepository(
		Supplier<JdbcClient> jdbcClientSupplier,
		Supplier<TransactionTemplate> transactionTemplateSupplier
	) {
		this.jdbcClientSupplier = Objects.requireNonNull(jdbcClientSupplier);
		this.transactionTemplateSupplier = Objects.requireNonNull(transactionTemplateSupplier);
	}

	@Override
	public RegionRelationSynchronizationResult synchronizeAll() {
		return transactionTemplate().execute(status -> synchronizeInTransaction());
	}

	private RegionRelationSynchronizationResult synchronizeInTransaction() {
		if (regionHierarchyCycleExists()) {
			throw new IllegalStateException("region hierarchy cycle detected");
		}
		boolean parcelRelationChanged = repairParcelRegionRelations() > 0;
		boolean complexRelationChanged = synchronizeComplexRegionRelations() > 0;
		if (complexParcelRegionMismatchExists()) {
			throw new IllegalStateException("complex and parcel region relation mismatch after synchronization");
		}
		boolean unitCntChanged = rebuildRegionUnitCountSums() > 0;
		return new RegionRelationSynchronizationResult(
			parcelRelationChanged || complexRelationChanged,
			unitCntChanged,
			unmatchedParcelExists()
		);
	}

	private int repairParcelRegionRelations() {
		return jdbcClient().sql("""
			WITH parcel_region_candidate AS (
			    SELECT
			        p.id AS parcel_id,
			        region_candidate.id AS region_id
			    FROM parcel p
			    LEFT JOIN LATERAL (
			        SELECT r.id
			        FROM region r
			        WHERE p.pnu ~ '^[0-9]{19}$'
			          AND r.region_type = 'eup-myeon-dong'
			          AND r.code IN (
			              substring(p.pnu FROM 1 FOR 10),
			              substring(p.pnu FROM 1 FOR 8)
			          )
			        ORDER BY CASE
			            WHEN r.code = substring(p.pnu FROM 1 FOR 10) THEN 0
			            ELSE 1
			        END
			        LIMIT 1
			    ) region_candidate ON true
			)
			UPDATE parcel p
			SET region_id = candidate.region_id,
			    updated_at = now()
			FROM parcel_region_candidate candidate
			WHERE p.id = candidate.parcel_id
			  AND p.region_id IS DISTINCT FROM candidate.region_id
			""").update();
	}

	private int synchronizeComplexRegionRelations() {
		return jdbcClient().sql("""
			UPDATE complex c
			SET region_id = p.region_id,
			    updated_at = now()
			FROM parcel p
			WHERE p.id = c.parcel_id
			  AND c.region_id IS DISTINCT FROM p.region_id
			""").update();
	}

	private int rebuildRegionUnitCountSums() {
		return jdbcClient().sql("""
			WITH RECURSIVE region_ancestor AS (
			    SELECT
			        r.id AS descendant_id,
			        r.id AS ancestor_id
			    FROM region r
			    UNION ALL
			    SELECT
			        region_ancestor.descendant_id,
			        parent.id AS ancestor_id
			    FROM region_ancestor
			    JOIN region child ON child.id = region_ancestor.ancestor_id
			    JOIN region parent ON parent.id = child.parent_id
			),
			region_unit AS (
			    SELECT
			        region_ancestor.ancestor_id AS region_id,
			        SUM(c.unit_cnt)::bigint AS unit_cnt_sum
			    FROM region_ancestor
			    JOIN complex c ON c.region_id = region_ancestor.descendant_id
			    GROUP BY region_ancestor.ancestor_id
			),
			region_unit_value AS (
			    SELECT
			        r.id AS region_id,
			        region_unit.unit_cnt_sum
			    FROM region r
			    LEFT JOIN region_unit ON region_unit.region_id = r.id
			)
			UPDATE region r
			SET unit_cnt_sum = value.unit_cnt_sum,
			    updated_at = now()
			FROM region_unit_value value
			WHERE r.id = value.region_id
			  AND r.unit_cnt_sum IS DISTINCT FROM value.unit_cnt_sum
			""").update();
	}

	private boolean complexParcelRegionMismatchExists() {
		return jdbcClient().sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM complex c
			    JOIN parcel p ON p.id = c.parcel_id
			    WHERE c.region_id IS DISTINCT FROM p.region_id
			)
			""").query(Boolean.class).single();
	}

	private boolean regionHierarchyCycleExists() {
		return jdbcClient().sql("""
			WITH RECURSIVE region_path AS (
			    SELECT
			        r.id AS current_id,
			        ARRAY[r.id] AS path,
			        false AS cycle_found
			    FROM region r
			    UNION ALL
			    SELECT
			        parent.id AS current_id,
			        region_path.path || parent.id,
			        parent.id = ANY(region_path.path) AS cycle_found
			    FROM region_path
			    JOIN region child ON child.id = region_path.current_id
			    JOIN region parent ON parent.id = child.parent_id
			    WHERE NOT region_path.cycle_found
			)
			SELECT EXISTS (SELECT 1 FROM region_path WHERE cycle_found)
			""").query(Boolean.class).single();
	}

	private boolean unmatchedParcelExists() {
		return jdbcClient().sql("SELECT EXISTS (SELECT 1 FROM parcel WHERE region_id IS NULL)")
			.query(Boolean.class)
			.single();
	}

	private JdbcClient jdbcClient() {
		return Objects.requireNonNull(jdbcClientSupplier.get(), "JdbcClient supplier returned null");
	}

	private TransactionTemplate transactionTemplate() {
		return Objects.requireNonNull(transactionTemplateSupplier.get(), "TransactionTemplate supplier returned null");
	}
}
