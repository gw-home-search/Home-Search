package com.home.infrastructure.external.complex;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.ingest.buildingmetadata.BuildingMetadataSourceParser;
import com.home.application.ingest.buildingmetadata.BuildingMetadataSourceResponse;
import com.home.application.ingest.buildingmetadata.BuildingMetadataProviderException;
import com.home.application.ingest.buildingmetadata.ParsedBuildingMetadataSource;
import com.home.domain.complex.buildingmetadata.BuildingMetadataMatchPolicy.SourceCandidate;
import com.home.domain.complex.buildingmetadata.BuildingMetadataSourceKind;
import com.home.domain.complex.buildingmetadata.BuildingMetadataValues;
import com.home.domain.complex.metadata.ComplexMetadataFailureKind;

public class BuildingMetadataJsonParser implements BuildingMetadataSourceParser {
	private final ObjectMapper objectMapper;

	public BuildingMetadataJsonParser(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public ParsedBuildingMetadataSource parse(BuildingMetadataSourceResponse snapshot) {
		try {
			JsonNode root = objectMapper.readTree(snapshot.body());
			validateProviderResult(root);
			return parseBuilding(root, snapshot.requestedPnu());
		}
		catch (BuildingMetadataProviderException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw new IllegalArgumentException("building metadata JSON parsing failed", exception);
		}
	}

	private void validateProviderResult(JsonNode root) {
		JsonNode header = path(root, "response", "header");
		if (header == null) return;
		String resultCode = text(header, "resultCode", "RESULT_CODE");
		if (resultCode == null || "00".equals(resultCode) || "000".equals(resultCode)) return;
		boolean fatal = java.util.Set.of("20", "22", "30", "31", "32").contains(resultCode);
		throw new BuildingMetadataProviderException("building provider failure resultCode=" + resultCode,
			ComplexMetadataFailureKind.PERMANENT, fatal);
	}

	private ParsedBuildingMetadataSource parseBuilding(JsonNode root, String requestedPnu) {
		JsonNode body = path(root, "response", "body");
		if (body == null) body = firstNode(root, "body");
		JsonNode itemNode = body == null ? null : path(body, "items", "item");
		List<JsonNode> items = nodes(itemNode);
		List<SourceCandidate> candidates = items.stream()
			.filter(item -> "02000".equals(text(item, "mainPurpsCd", "MAIN_PURPS_CD")))
			.map(item -> new SourceCandidate(
				text(item, "mgmBldrgstPk", "MGM_BLDRGST_PK", "bldMgmBldRgstPk"),
				requestedPnu,
				List.of(textOrEmpty(item, "bldNm", "BLD_NM")),
				new BuildingMetadataValues(integer(item, "mainBldCnt", "MAIN_BLD_CNT"), integer(item, "hhldCnt", "HHLD_CNT"),
					decimal(item, "platArea", "PLAT_AREA"), decimal(item, "archArea", "ARCH_AREA"),
					decimal(item, "totArea", "TOT_AREA"), decimal(item, "bcRat", "BC_RAT"),
					decimal(item, "vlRat", "VL_RAT"), date(text(item, "useAprDay", "USE_APR_DAY"))),
				null
			)).toList();
		Integer total = body == null ? null : integer(body, "totalCount", "total_count");
		return new ParsedBuildingMetadataSource(total == null ? items.size() : total, candidates);
	}

	private List<JsonNode> nodes(JsonNode node) {
		if (node == null || node.isNull()) return List.of();
		if (node.isArray()) {
			List<JsonNode> result = new ArrayList<>();
			node.forEach(result::add);
			return result;
		}
		return List.of(node);
	}

	private JsonNode path(JsonNode node, String... keys) {
		JsonNode current = node;
		for (String key : keys) {
			current = firstNode(current, key);
			if (current == null) return null;
		}
		return current;
	}

	private JsonNode firstNode(JsonNode node, String... keys) {
		if (node == null) return null;
		for (String key : keys) {
			JsonNode value = node.get(key);
			if (value != null && !value.isNull()) return value;
		}
		return null;
	}

	private String text(JsonNode node, String... keys) {
		JsonNode value = firstNode(node, keys);
		if (value == null) return null;
		String text = value.asText().trim();
		return text.isEmpty() ? null : text;
	}
	private String textOrEmpty(JsonNode node, String... keys) { String value = text(node, keys); return value == null ? "" : value; }
	private Integer integer(JsonNode node, String... keys) { String value = text(node, keys); try { return value == null ? null : new BigDecimal(value).intValueExact(); } catch (RuntimeException exception) { return null; } }
	private BigDecimal decimal(JsonNode node, String... keys) { String value = text(node, keys); try { return value == null ? null : new BigDecimal(value); } catch (RuntimeException exception) { return null; } }
	private LocalDate date(String value) {
		if (value == null) return null;
		String normalized = value.replaceAll("[-./]", "");
		try { return LocalDate.parse(normalized, DateTimeFormatter.BASIC_ISO_DATE); }
		catch (DateTimeParseException exception) { return null; }
	}
}
