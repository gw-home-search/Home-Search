package com.home.infrastructure.persistence.ingest;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.home.application.ingest.ComplexMasterBootstrapResult;
import com.home.application.ingest.ComplexMasterBootstrapper;
import com.home.application.ingest.ComplexMetadata;
import com.home.application.ingest.ComplexMetadataResolver;
import com.home.application.ingest.OpenApiTradeItem;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcComplexMasterBootstrapper implements ComplexMasterBootstrapper {

	private static final int COMPLEX_PK_MAX_LENGTH = 64;

	private final JdbcClient jdbcClient;
	private final ParcelCoordinateResolver coordinateResolver;
	private final ComplexMetadataResolver metadataResolver;

	public JdbcComplexMasterBootstrapper(JdbcClient jdbcClient, ParcelCoordinateResolver coordinateResolver) {
		this(jdbcClient, coordinateResolver, ComplexMetadataResolver.noop());
	}

	public JdbcComplexMasterBootstrapper(
		JdbcClient jdbcClient,
		ParcelCoordinateResolver coordinateResolver,
		ComplexMetadataResolver metadataResolver
	) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
		this.coordinateResolver = Objects.requireNonNull(coordinateResolver);
		this.metadataResolver = Objects.requireNonNull(metadataResolver);
	}

	@Override
	public ComplexMasterBootstrapResult bootstrap(OpenApiTradeItem item) {
		Objects.requireNonNull(item, "item is required");

		String aptSeq = trimToNull(item.aptSeq());
		if (aptSeq == null) {
			return ComplexMasterBootstrapResult.skipped("master bootstrap skipped: aptSeq unavailable");
		}
		String aptName = trimToNull(item.aptName());
		List<Long> existingComplexIds = findComplexIdsByAptSeq(aptSeq);
		if (existingComplexIds.size() == 1) {
			Long complexId = existingComplexIds.get(0);
			Optional<String> pnu = RtmsPnuBuilder.build(item);
			if (pnu.isEmpty()) {
				return ComplexMasterBootstrapResult.skipped("master bootstrap skipped: pnu unavailable aptSeq=" + aptSeq);
			}
			Optional<String> complexPnu = findComplexParcelPnu(complexId);
			if (complexPnu.isEmpty()) {
				return ComplexMasterBootstrapResult.skipped(
					"master bootstrap skipped: complex parcel unavailable aptSeq=" + aptSeq
				);
			}
			if (!pnu.get().equals(complexPnu.get())) {
				return ComplexMasterBootstrapResult.skipped(
					"master bootstrap skipped: aptSeq parcel pnu conflict aptSeq=%s derivedPnu=%s complexPnu=%s"
						.formatted(aptSeq, pnu.get(), complexPnu.get())
				);
			}
			enrichComplexMetadata(complexId, item, pnu.get());
			if (aptName != null) {
				upsertAlias(complexId, "RTMS_APT_NAME", aptName, "RTMS");
			}
			return ComplexMasterBootstrapResult.alreadyPresent();
		}
		if (existingComplexIds.size() > 1) {
			return ComplexMasterBootstrapResult.skipped("master bootstrap skipped: ambiguous aptSeq=" + aptSeq);
		}

		if (aptName == null) {
			return ComplexMasterBootstrapResult.skipped("master bootstrap skipped: aptName unavailable aptSeq=" + aptSeq);
		}

		Optional<String> pnu = RtmsPnuBuilder.build(item);
		if (pnu.isEmpty()) {
			return ComplexMasterBootstrapResult.skipped("master bootstrap skipped: pnu unavailable aptSeq=" + aptSeq);
		}

		Optional<Long> parcelId = findParcelId(pnu.get()).or(() -> createParcel(pnu.get(), item));
		if (parcelId.isEmpty()) {
			return ComplexMasterBootstrapResult.skipped("master bootstrap skipped: coordinate unavailable pnu=" + pnu.get());
		}

		String complexPk = complexPk(aptSeq);
		if (complexPk.length() > COMPLEX_PK_MAX_LENGTH) {
			return ComplexMasterBootstrapResult.skipped("master bootstrap skipped: complex_pk too long aptSeq=" + aptSeq);
		}
		ComplexMetadata metadata = resolveMetadata(item, pnu.get());
		Long complexId = upsertComplex(parcelId.get(), complexPk, aptSeq, aptName, metadata);
		upsertAlias(complexId, "RTMS_APT_NAME", aptName, "RTMS");
		return ComplexMasterBootstrapResult.bootstrapped();
	}

	private List<Long> findComplexIdsByAptSeq(String aptSeq) {
		return jdbcClient.sql("""
			SELECT id
			FROM complex
			WHERE apt_seq = :aptSeq
			ORDER BY id
			LIMIT 2
			""")
			.param("aptSeq", aptSeq)
			.query(Long.class)
			.list();
	}

	private Optional<Long> findParcelId(String pnu) {
		return jdbcClient.sql("""
			SELECT id
			FROM parcel
			WHERE pnu = :pnu
			""")
			.param("pnu", pnu)
			.query(Long.class)
			.optional();
	}

	private Optional<String> findParcelAddress(String pnu) {
		return jdbcClient.sql("""
			SELECT address
			FROM parcel
			WHERE pnu = :pnu
			""")
			.param("pnu", pnu)
			.query(String.class)
			.optional();
	}

	private Optional<String> findComplexParcelPnu(Long complexId) {
		return jdbcClient.sql("""
			SELECT p.pnu
			FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			WHERE c.id = :complexId
			""")
			.param("complexId", complexId)
			.query(String.class)
			.optional();
	}

	private Optional<Long> createParcel(String pnu, OpenApiTradeItem item) {
		Optional<ParcelCoordinate> coordinate = coordinateResolver.resolve(pnu, item);
		if (coordinate.isEmpty()) {
			return Optional.empty();
		}
		return jdbcClient.sql("""
			INSERT INTO parcel (
			    region_id,
			    pnu,
			    latitude,
			    longitude,
			    geom
			)
			VALUES (
			    :regionId,
			    :pnu,
			    :latitude,
			    :longitude,
			    CASE
			        WHEN CAST(:geometryWkt AS text) IS NULL THEN NULL
			        ELSE ST_Multi(ST_GeomFromText(CAST(:geometryWkt AS text), 4326))
			    END
			)
			ON CONFLICT (pnu) DO UPDATE
			SET region_id = COALESCE(parcel.region_id, EXCLUDED.region_id),
			    latitude = EXCLUDED.latitude,
			    longitude = EXCLUDED.longitude,
			    geom = COALESCE(EXCLUDED.geom, parcel.geom),
			    updated_at = now()
			RETURNING id
			""")
			.param("regionId", findRegionId(item).orElse(null))
			.param("pnu", pnu)
			.param("latitude", coordinate.get().latitude())
			.param("longitude", coordinate.get().longitude())
			.param("geometryWkt", coordinate.get().geometryWkt())
			.query(Long.class)
			.optional();
	}

	private Optional<Long> findRegionId(OpenApiTradeItem item) {
		String sggCd = trimToNull(item.sggCd());
		String umdCd = trimToNull(item.umdCd());
		if (sggCd == null || umdCd == null) {
			return Optional.empty();
		}
		return jdbcClient.sql("""
			SELECT id
			FROM region
			WHERE code = :code
			""")
			.param("code", sggCd + umdCd)
			.query(Long.class)
			.optional();
	}

	private Long upsertComplex(
		Long parcelId,
		String complexPk,
		String aptSeq,
		String aptName,
		ComplexMetadata metadata
	) {
		return jdbcClient.sql("""
			INSERT INTO complex (
			    parcel_id,
			    complex_pk,
			    apt_seq,
			    name,
			    trade_name,
			    dong_cnt,
			    unit_cnt,
			    plat_area,
			    arch_area,
			    tot_area,
			    bc_rat,
			    vl_rat,
			    use_date
			)
			VALUES (
			    :parcelId,
			    :complexPk,
			    :aptSeq,
			    :name,
			    :tradeName,
			    :dongCnt,
			    :unitCnt,
			    :platArea,
			    :archArea,
			    :totArea,
			    :bcRat,
			    :vlRat,
			    :useDate
			)
			ON CONFLICT (complex_pk) DO UPDATE
			SET parcel_id = EXCLUDED.parcel_id,
			    apt_seq = COALESCE(complex.apt_seq, EXCLUDED.apt_seq),
			    name = complex.name,
			    trade_name = COALESCE(complex.trade_name, EXCLUDED.trade_name),
			    dong_cnt = COALESCE(complex.dong_cnt, EXCLUDED.dong_cnt),
			    unit_cnt = COALESCE(complex.unit_cnt, EXCLUDED.unit_cnt),
			    plat_area = COALESCE(complex.plat_area, EXCLUDED.plat_area),
			    arch_area = COALESCE(complex.arch_area, EXCLUDED.arch_area),
			    tot_area = COALESCE(complex.tot_area, EXCLUDED.tot_area),
			    bc_rat = COALESCE(complex.bc_rat, EXCLUDED.bc_rat),
			    vl_rat = COALESCE(complex.vl_rat, EXCLUDED.vl_rat),
			    use_date = COALESCE(complex.use_date, EXCLUDED.use_date),
			    updated_at = now()
			RETURNING id
			""")
			.param("parcelId", parcelId)
			.param("complexPk", complexPk)
			.param("aptSeq", aptSeq)
			.param("name", aptName)
			.param("tradeName", aptName)
			.param("dongCnt", metadata == null ? null : metadata.dongCnt())
			.param("unitCnt", metadata == null ? null : metadata.unitCnt())
			.param("platArea", metadata == null ? null : metadata.platArea())
			.param("archArea", metadata == null ? null : metadata.archArea())
			.param("totArea", metadata == null ? null : metadata.totArea())
			.param("bcRat", metadata == null ? null : metadata.bcRat())
			.param("vlRat", metadata == null ? null : metadata.vlRat())
			.param("useDate", metadata == null ? null : metadata.useDate())
			.query(Long.class)
			.single();
	}

	private void enrichComplexMetadata(Long complexId, OpenApiTradeItem item, String pnu) {
		ComplexMetadata metadata = resolveMetadata(item, pnu);
		if (metadata == null) {
			return;
		}
		jdbcClient.sql("""
			UPDATE complex
			SET dong_cnt = COALESCE(dong_cnt, :dongCnt),
			    unit_cnt = COALESCE(unit_cnt, :unitCnt),
			    plat_area = COALESCE(plat_area, :platArea),
			    arch_area = COALESCE(arch_area, :archArea),
			    tot_area = COALESCE(tot_area, :totArea),
			    bc_rat = COALESCE(bc_rat, :bcRat),
			    vl_rat = COALESCE(vl_rat, :vlRat),
			    use_date = COALESCE(use_date, :useDate),
			    updated_at = now()
			WHERE id = :complexId
			""")
			.param("complexId", complexId)
			.param("dongCnt", metadata.dongCnt())
			.param("unitCnt", metadata.unitCnt())
			.param("platArea", metadata.platArea())
			.param("archArea", metadata.archArea())
			.param("totArea", metadata.totArea())
			.param("bcRat", metadata.bcRat())
			.param("vlRat", metadata.vlRat())
			.param("useDate", metadata.useDate())
			.update();
	}

	private ComplexMetadata resolveMetadata(OpenApiTradeItem item, String pnu) {
		Optional<String> parcelAddress = findParcelAddress(pnu);
		return metadataResolver.resolve(item, pnu, parcelAddress.orElse(null)).orElse(null);
	}

	private void upsertAlias(Long complexId, String aliasType, String aliasName, String source) {
		String normalizedName = normalizeName(aliasName);
		if (normalizedName.isBlank()) {
			return;
		}
		jdbcClient.sql("""
			INSERT INTO complex_name_alias (
			    complex_id,
			    alias_type,
			    alias_name,
			    normalized_name,
			    source
			)
			VALUES (
			    :complexId,
			    :aliasType,
			    :aliasName,
			    :normalizedName,
			    :source
			)
			ON CONFLICT (complex_id, alias_type, normalized_name) DO UPDATE
			SET alias_name = EXCLUDED.alias_name,
			    source = COALESCE(complex_name_alias.source, EXCLUDED.source),
			    last_seen_at = now(),
			    updated_at = now()
			""")
			.param("complexId", complexId)
			.param("aliasType", aliasType)
			.param("aliasName", aliasName)
			.param("normalizedName", normalizedName)
			.param("source", source)
			.update();
	}

	private String complexPk(String aptSeq) {
		return "RTMS:" + aptSeq;
	}

	private String normalizeName(String value) {
		String text = trimToNull(value);
		if (text == null) {
			return "";
		}
		return text.replaceAll("\\s+", "")
			.replaceAll("[()\\[\\]{}.,·\\-_/]", "")
			.toLowerCase(Locale.ROOT);
	}

	private String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
