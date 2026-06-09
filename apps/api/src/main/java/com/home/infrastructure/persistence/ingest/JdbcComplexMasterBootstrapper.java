package com.home.infrastructure.persistence.ingest;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.home.application.ingest.matching.ComplexMasterBootstrapPolicy;
import com.home.application.ingest.matching.ComplexMasterBootstrapResult;
import com.home.application.ingest.matching.ComplexMasterBootstrapper;
import com.home.application.ingest.matching.ComplexIdentityResolver;
import com.home.application.ingest.trade.OpenApiTradeItem;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcComplexMasterBootstrapper implements ComplexMasterBootstrapper {

	private final JdbcClient jdbcClient;
	private final ParcelCoordinateResolver coordinateResolver;
	private final ComplexIdentityResolver identityResolver;
	private final ComplexMasterBootstrapPolicy policy;

	public JdbcComplexMasterBootstrapper(JdbcClient jdbcClient, ParcelCoordinateResolver coordinateResolver) {
		this(jdbcClient, coordinateResolver, ComplexIdentityResolver.noop());
	}

	public JdbcComplexMasterBootstrapper(
		JdbcClient jdbcClient,
		ParcelCoordinateResolver coordinateResolver,
		ComplexIdentityResolver identityResolver
	) {
		this(jdbcClient, coordinateResolver, identityResolver, new ComplexMasterBootstrapPolicy());
	}

	JdbcComplexMasterBootstrapper(
		JdbcClient jdbcClient,
		ParcelCoordinateResolver coordinateResolver,
		ComplexIdentityResolver identityResolver,
		ComplexMasterBootstrapPolicy policy
	) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
		this.coordinateResolver = Objects.requireNonNull(coordinateResolver);
		this.identityResolver = Objects.requireNonNull(identityResolver);
		this.policy = Objects.requireNonNull(policy);
	}

	@Override
	public ComplexMasterBootstrapResult bootstrap(OpenApiTradeItem item) {
		Objects.requireNonNull(item, "item is required");

		String aptSeq = trimToNull(item.aptSeq());
		Optional<ComplexMasterBootstrapResult> skip = policy.validateAptSeq(aptSeq);
		if (skip.isPresent()) {
			return skip.get();
		}
		String aptName = trimToNull(item.aptName());
		List<Long> existingComplexIds = findComplexIdsByAptSeq(aptSeq);
		skip = policy.validateExistingAptSeqCandidateCount(aptSeq, existingComplexIds.size());
		if (skip.isPresent()) {
			return skip.get();
		}
		if (existingComplexIds.size() == 1) {
			Long complexId = existingComplexIds.get(0);
			Optional<String> pnu = resolvePnu(item);
			Optional<String> complexPnu = pnu.isPresent() ? findComplexParcelPnu(complexId) : Optional.empty();
			skip = policy.validateExistingAptSeqPnu(aptSeq, pnu, complexPnu);
			if (skip.isPresent()) {
				return skip.get();
			}
			if (aptName != null) {
				upsertAlias(complexId, "RTMS_APT_NAME", aptName, "RTMS");
			}
			return ComplexMasterBootstrapResult.alreadyPresent();
		}

		skip = policy.validateNewAptName(aptSeq, aptName);
		if (skip.isPresent()) {
			return skip.get();
		}

		Optional<String> pnu = resolvePnu(item);
		skip = policy.validateNewPnu(aptSeq, pnu);
		if (skip.isPresent()) {
			return skip.get();
		}

		String complexPk = policy.complexPk(aptSeq);
		Optional<Long> existingComplexIdByPk = findComplexIdByComplexPk(complexPk);
		if (existingComplexIdByPk.isPresent()) {
			Optional<String> complexPnu = findComplexParcelPnu(existingComplexIdByPk.get());
			skip = policy.validateExistingComplexPkPnu(complexPk, pnu.get(), complexPnu);
			if (skip.isPresent()) {
				return skip.get();
			}
		}

		Optional<Long> parcelId = findParcelId(pnu.get()).or(() -> createParcel(pnu.get(), item));
		skip = policy.validateParcel(pnu.get(), parcelId);
		if (skip.isPresent()) {
			return skip.get();
		}

		skip = policy.validateComplexPkLength(aptSeq, complexPk);
		if (skip.isPresent()) {
			return skip.get();
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
			    latitude = COALESCE(EXCLUDED.latitude, parcel.latitude),
			    longitude = COALESCE(EXCLUDED.longitude, parcel.longitude),
			    geom = COALESCE(EXCLUDED.geom, parcel.geom),
			    updated_at = now()
			RETURNING id
			""")
			.param("regionId", region.map(RegionLookup::id).orElse(null))
			.param("pnu", pnu)
			.param("address", address)
			.param("latitude", coordinate.map(ParcelCoordinate::latitude).orElse(null))
			.param("longitude", coordinate.map(ParcelCoordinate::longitude).orElse(null))
			.param("geometryWkt", coordinate.map(ParcelCoordinate::geometryWkt).orElse(null))
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
