package com.home.infrastructure.persistence.ingest;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.home.application.ingest.ComplexMasterBootstrapResult;
import com.home.application.ingest.ComplexMasterBootstrapper;
import com.home.application.ingest.ComplexIdentityResolver;
import com.home.application.ingest.OpenApiTradeItem;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcComplexMasterBootstrapper implements ComplexMasterBootstrapper {

	private static final int COMPLEX_PK_MAX_LENGTH = 64;

	private final JdbcClient jdbcClient;
	private final ParcelCoordinateResolver coordinateResolver;
	private final ComplexIdentityResolver identityResolver;

	public JdbcComplexMasterBootstrapper(JdbcClient jdbcClient, ParcelCoordinateResolver coordinateResolver) {
		this(jdbcClient, coordinateResolver, ComplexIdentityResolver.noop());
	}

	public JdbcComplexMasterBootstrapper(
		JdbcClient jdbcClient,
		ParcelCoordinateResolver coordinateResolver,
		ComplexIdentityResolver identityResolver
	) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
		this.coordinateResolver = Objects.requireNonNull(coordinateResolver);
		this.identityResolver = Objects.requireNonNull(identityResolver);
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
			Optional<String> pnu = resolvePnu(item);
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

		Optional<String> pnu = resolvePnu(item);
		if (pnu.isEmpty()) {
			return ComplexMasterBootstrapResult.skipped("master bootstrap skipped: pnu unavailable aptSeq=" + aptSeq);
		}

		String complexPk = complexPk(aptSeq);
		Optional<Long> existingComplexIdByPk = findComplexIdByComplexPk(complexPk);
		if (existingComplexIdByPk.isPresent()) {
			Optional<String> complexPnu = findComplexParcelPnu(existingComplexIdByPk.get());
			if (complexPnu.isEmpty()) {
				return ComplexMasterBootstrapResult.skipped(
					"master bootstrap skipped: complex parcel unavailable complexPk=" + complexPk
				);
			}
			if (!pnu.get().equals(complexPnu.get())) {
				return ComplexMasterBootstrapResult.skipped(
					"master bootstrap skipped: complex_pk parcel pnu conflict complexPk=%s derivedPnu=%s complexPnu=%s"
						.formatted(complexPk, pnu.get(), complexPnu.get())
				);
			}
		}

		Optional<Long> parcelId = findParcelId(pnu.get()).or(() -> createParcel(pnu.get(), item));
		if (parcelId.isEmpty()) {
			return ComplexMasterBootstrapResult.skipped("master bootstrap skipped: coordinate unavailable pnu=" + pnu.get());
		}

		if (complexPk.length() > COMPLEX_PK_MAX_LENGTH) {
			return ComplexMasterBootstrapResult.skipped("master bootstrap skipped: complex_pk too long aptSeq=" + aptSeq);
		}
		Long complexId = upsertComplex(parcelId.get(), complexPk, aptSeq, aptName);
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

	private Optional<String> resolvePnu(OpenApiTradeItem item) {
		return RtmsPnuBuilder.build(item).or(() -> identityResolver.resolvePnu(item)
			.map(String::trim)
			.filter(this::validPnu));
	}

	private boolean validPnu(String value) {
		return value.matches("\\d{19}");
	}

	private Optional<Long> findComplexIdByComplexPk(String complexPk) {
		return jdbcClient.sql("""
			SELECT id
			FROM complex
			WHERE complex_pk = :complexPk
			""")
			.param("complexPk", complexPk)
			.query(Long.class)
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
		Optional<RegionLookup> region = findRegion(item);
		String address = region.flatMap(candidate -> RtmsParcelAddressFormatter.format(candidate.name(), pnu))
			.orElse(null);
		return jdbcClient.sql("""
			INSERT INTO parcel (
			    region_id,
			    pnu,
			    address,
			    latitude,
			    longitude,
			    geom
			)
			VALUES (
			    :regionId,
			    :pnu,
			    :address,
			    :latitude,
			    :longitude,
			    CASE
			        WHEN CAST(:geometryWkt AS text) IS NULL THEN NULL
			        ELSE ST_Multi(ST_GeomFromText(CAST(:geometryWkt AS text), 4326))
			    END
			)
			ON CONFLICT (pnu) DO UPDATE
			SET region_id = COALESCE(parcel.region_id, EXCLUDED.region_id),
			    address = COALESCE(parcel.address, EXCLUDED.address),
			    latitude = EXCLUDED.latitude,
			    longitude = EXCLUDED.longitude,
			    geom = COALESCE(EXCLUDED.geom, parcel.geom),
			    updated_at = now()
			RETURNING id
			""")
			.param("regionId", region.map(RegionLookup::id).orElse(null))
			.param("pnu", pnu)
			.param("address", address)
			.param("latitude", coordinate.get().latitude())
			.param("longitude", coordinate.get().longitude())
			.param("geometryWkt", coordinate.get().geometryWkt())
			.query(Long.class)
			.optional();
	}

	private Optional<RegionLookup> findRegion(OpenApiTradeItem item) {
		String sggCd = trimToNull(item.sggCd());
		String umdCd = trimToNull(item.umdCd());
		if (sggCd == null || umdCd == null) {
			return Optional.empty();
		}
		String fullCode = sggCd + umdCd;
		String compactCode = compactRegionCode(sggCd, umdCd);
		return jdbcClient.sql("""
			SELECT id, name
			FROM region
			WHERE code IN (:fullCode, :compactCode)
			ORDER BY CASE WHEN code = :fullCode THEN 0 ELSE 1 END
			LIMIT 1
			""")
			.param("fullCode", fullCode)
			.param("compactCode", compactCode)
			.query((resultSet, rowNumber) -> new RegionLookup(
				resultSet.getLong("id"),
				resultSet.getString("name")
			))
			.optional();
	}

	private String compactRegionCode(String sggCd, String umdCd) {
		if (umdCd.length() == 5 && umdCd.endsWith("00")) {
			return sggCd + umdCd.substring(0, 3);
		}
		return sggCd + umdCd;
	}

	private Long upsertComplex(
		Long parcelId,
		String complexPk,
		String aptSeq,
		String aptName
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
			SET apt_seq = COALESCE(complex.apt_seq, EXCLUDED.apt_seq),
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
			.param("dongCnt", null)
			.param("unitCnt", null)
			.param("platArea", null)
			.param("archArea", null)
			.param("totArea", null)
			.param("bcRat", null)
			.param("vlRat", null)
			.param("useDate", null)
			.query(Long.class)
			.single();
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

	private record RegionLookup(
		Long id,
		String name
	) {
	}
}
