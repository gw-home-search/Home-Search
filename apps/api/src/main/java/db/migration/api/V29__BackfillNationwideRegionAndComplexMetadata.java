package db.migration.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V29__BackfillNationwideRegionAndComplexMetadata extends BaseJavaMigration {

	private static final String REGION_SEED_RESOURCE =
		"db/migration/api/data/nationwide_region_seed_legacy.csv";
	private static final String COMPLEX_METADATA_RESOURCE =
		"db/migration/api/data/legacy_complex_metadata_candidates_20251111.csv";
	private static final int BATCH_SIZE = 1_000;

	@Override
	public void migrate(Context context) throws Exception {
		Connection connection = context.getConnection();
		createStagingTables(connection);
		loadRegionSeed(connection);
		loadComplexMetadataCandidates(connection);
		backfillRegions(connection);
		backfillParcels(connection);
		backfillComplexMetadata(connection);
	}

	private void createStagingTables(Connection connection) throws SQLException {
		execute(connection, """
			CREATE TEMP TABLE hs_region_seed (
			    code VARCHAR(32) PRIMARY KEY,
			    parent_code VARCHAR(32),
			    name VARCHAR(255) NOT NULL,
			    region_type VARCHAR(32) NOT NULL,
			    center_lat NUMERIC(10, 7),
			    center_lng NUMERIC(10, 7)
			) ON COMMIT DROP
			""");
		execute(connection, """
			CREATE TEMP TABLE hs_complex_metadata_candidate (
			    pnu VARCHAR(32) NOT NULL,
			    emd_code VARCHAR(16) NOT NULL,
			    name_key TEXT NOT NULL,
			    dong_cnt INTEGER NOT NULL,
			    unit_cnt INTEGER NOT NULL,
			    use_date DATE
			) ON COMMIT DROP
			""");
	}

	private void loadRegionSeed(Connection connection) throws SQLException, IOException {
		try (
			BufferedReader reader = resourceReader(REGION_SEED_RESOURCE);
			PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO hs_region_seed (
				    code,
				    parent_code,
				    name,
				    region_type,
				    center_lat,
				    center_lng
				)
				VALUES (?, NULLIF(?, ''), ?, ?, ?, ?)
				""")
		) {
			loadCsvRows(reader, columns -> {
				statement.setString(1, columns[0]);
				statement.setString(2, columns[1]);
				statement.setString(3, columns[2]);
				statement.setString(4, columns[3]);
				statement.setBigDecimal(5, new BigDecimal(columns[4]));
				statement.setBigDecimal(6, new BigDecimal(columns[5]));
				statement.addBatch();
			}, statement);
		}
	}

	private void loadComplexMetadataCandidates(Connection connection) throws SQLException, IOException {
		try (
			BufferedReader reader = resourceReader(COMPLEX_METADATA_RESOURCE);
			PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO hs_complex_metadata_candidate (
				    pnu,
				    emd_code,
				    name_key,
				    dong_cnt,
				    unit_cnt,
				    use_date
				)
				VALUES (?, ?, ?, ?, ?, ?)
				""")
		) {
			loadCsvRows(reader, columns -> {
				statement.setString(1, columns[0]);
				statement.setString(2, columns[1]);
				statement.setString(3, columns[2]);
				statement.setInt(4, Integer.parseInt(columns[3]));
				statement.setInt(5, Integer.parseInt(columns[4]));
				if (columns[5].isBlank()) {
					statement.setNull(6, Types.DATE);
				} else {
					statement.setDate(6, Date.valueOf(columns[5]));
				}
				statement.addBatch();
			}, statement);
		}
		execute(connection, "CREATE INDEX hs_cmc_pnu ON hs_complex_metadata_candidate (pnu)");
		execute(connection, "CREATE INDEX hs_cmc_name ON hs_complex_metadata_candidate (emd_code, name_key)");
	}

	private void backfillRegions(Connection connection) throws SQLException {
		execute(connection, """
			SELECT setval(
			    pg_get_serial_sequence('region', 'id'),
			    coalesce((SELECT max(id) FROM region), 0) + 1,
			    false
			)
			""");
		execute(connection, """
			INSERT INTO region (
			    code,
			    name,
			    region_type,
			    center_lat,
			    center_lng
			)
			SELECT
			    code,
			    name,
			    region_type,
			    center_lat,
			    center_lng
			FROM hs_region_seed
			ON CONFLICT (code) DO UPDATE
			SET name = EXCLUDED.name,
			    region_type = EXCLUDED.region_type,
			    center_lat = EXCLUDED.center_lat,
			    center_lng = EXCLUDED.center_lng,
			    updated_at = now()
			""");
		execute(connection, """
			UPDATE region child
			SET parent_id = parent.id,
			    updated_at = now()
			FROM hs_region_seed seed
			JOIN region parent ON parent.code = seed.parent_code
			WHERE child.code = seed.code
			  AND child.parent_id IS DISTINCT FROM parent.id
			""");
		execute(connection, """
			UPDATE region child
			SET parent_id = NULL,
			    updated_at = now()
			FROM hs_region_seed seed
			WHERE child.code = seed.code
			  AND seed.parent_code IS NULL
			  AND child.parent_id IS NOT NULL
			""");
	}

	private void backfillParcels(Connection connection) throws SQLException {
		execute(connection, """
			WITH parcel_region_candidate AS (
			    SELECT
			        p.id AS parcel_id,
			        region_candidate.id AS region_id,
			        trim(concat(
			            region_candidate.name,
			            ' ',
			            CASE WHEN substring(p.pnu FROM 11 FOR 1) = '2' THEN '산 ' ELSE '' END,
			            parts.bun,
			            CASE WHEN parts.ji IS NULL THEN '' ELSE '-' || parts.ji END
			        )) AS address
			    FROM parcel p
			    JOIN LATERAL (
			        SELECT r.id, r.name
			        FROM region r
			        WHERE r.region_type = 'eup-myeon-dong'
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
			    CROSS JOIN LATERAL (
			        SELECT
			            NULLIF(ltrim(substring(p.pnu FROM 12 FOR 4), '0'), '') AS bun,
			            NULLIF(ltrim(substring(p.pnu FROM 16 FOR 4), '0'), '') AS ji
			    ) parts
			    WHERE p.pnu ~ '^[0-9]{19}$'
			      AND parts.bun IS NOT NULL
			)
			UPDATE parcel p
			SET region_id = parcel_region_candidate.region_id,
			    address = CASE
			        WHEN p.address IS NULL OR p.address ILIKE 'Sample%' THEN parcel_region_candidate.address
			        ELSE p.address
			    END,
			    updated_at = now()
			FROM parcel_region_candidate
			WHERE p.id = parcel_region_candidate.parcel_id
			  AND (
			      p.region_id IS DISTINCT FROM parcel_region_candidate.region_id
			      OR p.address IS NULL
			      OR p.address ILIKE 'Sample%'
			  )
			""");
	}

	private void backfillComplexMetadata(Connection connection) throws SQLException {
		execute(connection, """
			CREATE OR REPLACE FUNCTION pg_temp.hs_name_key(value TEXT)
			RETURNS TEXT
			LANGUAGE sql
			IMMUTABLE
			AS $$
			    SELECT regexp_replace(lower(coalesce(value, '')), '[[:space:][:punct:]·]+', '', 'g')
			$$
			""");
		execute(connection, """
			CREATE TEMP TABLE hs_target_complex AS
			SELECT
			    c.id,
			    p.pnu,
			    substring(p.pnu FROM 1 FOR 10) AS emd_code,
			    pg_temp.hs_name_key(c.name) AS name_key,
			    pg_temp.hs_name_key(c.trade_name) AS trade_name_key,
			    count(*) OVER (PARTITION BY p.pnu) AS target_complexes_on_pnu
			FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			WHERE p.pnu ~ '^[0-9]{19}$'
			""");
		execute(connection, "CREATE INDEX hs_tc_pnu ON hs_target_complex (pnu)");
		execute(connection, "CREATE INDEX hs_tc_name ON hs_target_complex (emd_code, name_key)");
		execute(connection, "CREATE INDEX hs_tc_trade_name ON hs_target_complex (emd_code, trade_name_key)");
		execute(connection, """
			CREATE TEMP TABLE hs_exact_complex_metadata AS
			SELECT
			    pnu,
			    min(dong_cnt) AS dong_cnt,
			    min(unit_cnt) AS unit_cnt,
			    min(use_date) AS use_date
			FROM (
			    SELECT DISTINCT pnu, dong_cnt, unit_cnt, use_date
			    FROM hs_complex_metadata_candidate
			) unique_metadata
			GROUP BY pnu
			HAVING count(*) = 1
			""");
		execute(connection, "CREATE INDEX hs_ecm_pnu ON hs_exact_complex_metadata (pnu)");
		execute(connection, """
			CREATE TEMP TABLE hs_name_complex_metadata AS
			WITH name_match AS (
			    SELECT
			        target.id AS complex_id,
			        candidate.pnu,
			        candidate.dong_cnt,
			        candidate.unit_cnt,
			        candidate.use_date
			    FROM hs_target_complex target
			    JOIN hs_complex_metadata_candidate candidate
			      ON candidate.emd_code = target.emd_code
			     AND candidate.name_key IN (target.name_key, target.trade_name_key)
			    WHERE target.name_key <> ''
			       OR target.trade_name_key <> ''
			)
			SELECT
			    complex_id,
			    min(dong_cnt) AS dong_cnt,
			    min(unit_cnt) AS unit_cnt,
			    min(use_date) AS use_date
			FROM name_match
			GROUP BY complex_id
			HAVING count(DISTINCT pnu) = 1
			   AND count(DISTINCT dong_cnt::text || '|' || unit_cnt::text || '|' || coalesce(use_date::text, '')) = 1
			""");
		execute(connection, "CREATE INDEX hs_ncm_complex ON hs_name_complex_metadata (complex_id)");
		execute(connection, """
			CREATE TEMP TABLE hs_complex_metadata_backfill AS
			SELECT
			    target.id AS complex_id,
			    coalesce(exact.dong_cnt, name_match.dong_cnt) AS dong_cnt,
			    coalesce(exact.unit_cnt, name_match.unit_cnt) AS unit_cnt,
			    coalesce(exact.use_date, name_match.use_date) AS use_date
			FROM hs_target_complex target
			LEFT JOIN hs_exact_complex_metadata exact
			  ON exact.pnu = target.pnu
			 AND target.target_complexes_on_pnu = 1
			LEFT JOIN hs_name_complex_metadata name_match
			  ON name_match.complex_id = target.id
			WHERE exact.pnu IS NOT NULL
			   OR name_match.complex_id IS NOT NULL
			""");
		execute(connection, "CREATE INDEX hs_cmb_complex ON hs_complex_metadata_backfill (complex_id)");
		execute(connection, """
			UPDATE complex c
			SET dong_cnt = coalesce(c.dong_cnt, backfill.dong_cnt),
			    unit_cnt = coalesce(c.unit_cnt, backfill.unit_cnt),
			    use_date = coalesce(c.use_date, backfill.use_date),
			    metadata_status = CASE
			        WHEN coalesce(c.dong_cnt, backfill.dong_cnt) > 0
			          AND coalesce(c.unit_cnt, backfill.unit_cnt) > 0
			          AND coalesce(c.use_date, backfill.use_date) IS NOT NULL THEN 'RESOLVED'
			        ELSE 'PARTIAL'
			    END,
			    metadata_source = coalesce(c.metadata_source, 'LEGACY_CSV'),
			    metadata_checked_at = coalesce(c.metadata_checked_at, now()),
			    updated_at = now()
			FROM hs_complex_metadata_backfill backfill
			WHERE c.id = backfill.complex_id
			  AND (
			      c.dong_cnt IS NULL
			      OR c.unit_cnt IS NULL
			      OR c.use_date IS NULL
			  )
			  AND (
			      backfill.dong_cnt IS NOT NULL
			      OR backfill.unit_cnt IS NOT NULL
			      OR backfill.use_date IS NOT NULL
			  )
			""");
	}

	private void loadCsvRows(
		BufferedReader reader,
		CsvRowBinder binder,
		PreparedStatement statement
	) throws SQLException {
		try {
			reader.readLine();
			String line;
			int rowCount = 0;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				binder.bind(line.split(",", -1));
				rowCount++;
				if (rowCount % BATCH_SIZE == 0) {
					statement.executeBatch();
				}
			}
			statement.executeBatch();
		} catch (IOException ex) {
			throw new SQLException("Failed to read migration resource", ex);
		}
	}

	private BufferedReader resourceReader(String resourceName) throws SQLException {
		InputStream input = Thread.currentThread()
			.getContextClassLoader()
			.getResourceAsStream(resourceName);
		if (input == null) {
			throw new SQLException("Missing migration resource: " + resourceName);
		}
		return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
	}

	private void execute(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	@FunctionalInterface
	private interface CsvRowBinder {

		void bind(String[] columns) throws SQLException;
	}
}
