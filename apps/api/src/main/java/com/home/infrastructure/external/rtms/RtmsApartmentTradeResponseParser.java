package com.home.infrastructure.external.rtms;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.ingest.OpenApiTradeIngestBatch;
import com.home.application.ingest.OpenApiTradeItem;

public class RtmsApartmentTradeResponseParser {

	private final ObjectMapper objectMapper;

	public RtmsApartmentTradeResponseParser(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public OpenApiTradeIngestBatch parse(String lawdCd, String dealYmd, Integer pageNo, String payload) {
		return parsePage(lawdCd, dealYmd, pageNo, payload).batch();
	}

	public RtmsApartmentTradePage parsePage(String lawdCd, String dealYmd, Integer pageNo, String payload) {
		if (payload == null || payload.isBlank()) {
			OpenApiTradeIngestBatch batch = new OpenApiTradeIngestBatch("RTMS", lawdCd, dealYmd, pageNo, List.of());
			return RtmsApartmentTradePage.single(batch);
		}
		try {
			JsonNode root = objectMapper.readTree(payload);
			validateHeader(root);
			List<OpenApiTradeItem> items = parseItems(root);
			JsonNode body = root.path("response").path("body");
			int resolvedPageNo = positiveInteger(body.path("pageNo"), pageNo == null ? 1 : pageNo);
			int numOfRows = positiveInteger(body.path("numOfRows"), Math.max(items.size(), 1));
			int totalCount = nonNegativeInteger(body.path("totalCount"), items.size());
			OpenApiTradeIngestBatch batch = new OpenApiTradeIngestBatch(
				"RTMS",
				lawdCd,
				dealYmd,
				resolvedPageNo,
				items
			);
			return new RtmsApartmentTradePage(batch, resolvedPageNo, numOfRows, totalCount);
		}
		catch (IOException exception) {
			throw new IllegalArgumentException("RTMS response payload is not valid JSON", exception);
		}
	}

	private void validateHeader(JsonNode root) {
		String resultCode = text(root.path("response").path("header").path("resultCode"));
		if (resultCode != null && !"000".equals(resultCode)) {
			String resultMsg = text(root.path("response").path("header").path("resultMsg"));
			throw new IllegalStateException("RTMS response failed: resultCode=%s resultMsg=%s"
				.formatted(resultCode, resultMsg == null ? "" : resultMsg));
		}
	}

	private List<OpenApiTradeItem> parseItems(JsonNode root) throws IOException {
		JsonNode itemNode = root
			.path("response")
			.path("body")
			.path("items")
			.path("item");
		if (itemNode.isMissingNode() || itemNode.isNull() || itemNode.isTextual()) {
			return List.of();
		}
		List<JsonNode> nodes = itemNodes(itemNode);
		List<OpenApiTradeItem> items = new ArrayList<>(nodes.size());
		for (JsonNode node : nodes) {
			items.add(new OpenApiTradeItem(
				text(node.path("aptDong")),
				text(node.path("aptNm")),
				text(node.path("aptSeq")),
				text(node.path("dealAmount")),
				integer(node.path("dealDay")),
				integer(node.path("dealMonth")),
				integer(node.path("dealYear")),
				decimal(node.path("excluUseAr")),
				integer(node.path("floor")),
				text(node.path("jibun")),
				text(node.path("sggCd")),
				text(node.path("umdCd")),
				objectMapper.writeValueAsString(node)
			));
		}
		return List.copyOf(items);
	}

	private static List<JsonNode> itemNodes(JsonNode itemNode) {
		if (itemNode.isArray()) {
			List<JsonNode> nodes = new ArrayList<>();
			itemNode.forEach(nodes::add);
			return nodes;
		}
		if (itemNode.isObject()) {
			return List.of(itemNode);
		}
		return List.of();
	}

	private static String text(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		String value = node.asText();
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static Integer integer(JsonNode node) {
		String value = text(node);
		if (value == null) {
			return null;
		}
		try {
			return Integer.valueOf(value);
		}
		catch (NumberFormatException exception) {
			return null;
		}
	}

	private static int positiveInteger(JsonNode node, int fallback) {
		Integer value = integer(node);
		return value == null || value < 1 ? Math.max(fallback, 1) : value;
	}

	private static int nonNegativeInteger(JsonNode node, int fallback) {
		Integer value = integer(node);
		return value == null || value < 0 ? Math.max(fallback, 0) : value;
	}

	private static Double decimal(JsonNode node) {
		String value = text(node);
		if (value == null) {
			return null;
		}
		try {
			return Double.valueOf(value);
		}
		catch (NumberFormatException exception) {
			return null;
		}
	}
}
