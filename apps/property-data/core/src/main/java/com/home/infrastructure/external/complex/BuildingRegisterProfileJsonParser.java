package com.home.infrastructure.external.complex;

import com.home.application.ingest.buildingprofile.BuildingProfilePageParser;
import com.home.application.ingest.buildingprofile.BuildingProfileParsedPage;
import com.home.application.ingest.buildingprofile.BuildingProfileParsedRecord;
import com.home.domain.complex.buildingprofile.BuildingProfileField;
import com.home.domain.complex.buildingprofile.BuildingProfileTypedValue;
import com.home.domain.complex.buildingprofile.BuildingProfileValueClassifier;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class BuildingRegisterProfileJsonParser implements BuildingProfilePageParser {
    private final ObjectMapper objectMapper;
    private final BuildingProfileValueClassifier classifier = new BuildingProfileValueClassifier();

    public BuildingRegisterProfileJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public BuildingProfileParsedPage parse(
            BuildingRegisterEndpoint endpoint, String pnu, int pageNo, int pageSize, String responseBody) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (pnu == null || !pnu.matches("[0-9]{19}")) throw new IllegalArgumentException("pnu must be 19 digits");
        if (pageNo <= 0 || pageSize <= 0) throw new IllegalArgumentException("pagination must be positive");
        if (responseBody == null) throw new IllegalArgumentException("building register body is missing");
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode response = child(root, "response", "Response");
            JsonNode header = child(response, "header", "Header");
            JsonNode body = child(response, "body", "Body");
            String resultCode = text(header, "resultCode", "RESULT_CODE");
            String resultMessage = text(header, "resultMsg", "RESULT_MSG");
            int totalCount = integer(body, "totalCount", "total_count", "TOTAL_COUNT");
            if (List.of("00", "000", "NORMAL_CODE").contains(resultCode)) {
                int actualPage = integer(body, "pageNo", "page_no", "PAGE_NO");
                int actualSize = integer(body, "numOfRows", "num_of_rows", "NUM_OF_ROWS");
                if (actualPage != pageNo || actualSize != pageSize) {
                    throw new IllegalArgumentException("building register pagination metadata does not match request");
                }
            }
            JsonNode items = child(body, "items", "Items");
            List<JsonNode> itemNodes = nodes(child(items, "item", "Item"));
            List<BuildingProfileParsedRecord> records = new ArrayList<>();
            Set<String> unknownKeys = new LinkedHashSet<>();
            for (int index = 0; index < itemNodes.size(); index++) {
                JsonNode item = itemNodes.get(index);
                records.add(record(endpoint, pnu, index, item));
                item.propertyNames().stream()
                        .filter(key -> BuildingProfileField.fromProviderKey(key).isEmpty())
                        .forEach(unknownKeys::add);
            }
            return new BuildingProfileParsedPage(resultCode, resultMessage, totalCount, records, unknownKeys);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("building register profile JSON parsing failed", exception);
        }
    }

    private BuildingProfileParsedRecord record(
            BuildingRegisterEndpoint endpoint, String pnu, int itemIndex, JsonNode item) {
        EnumMap<BuildingProfileField, BuildingProfileTypedValue> values = new EnumMap<>(BuildingProfileField.class);
        for (BuildingProfileField field : BuildingProfileField.values()) {
            if (endpoint == BuildingRegisterEndpoint.BASIC_OVERVIEW && !field.hierarchyLeanField()) continue;
            SourceNode source = source(item, field.providerKeys());
            values.put(
                    field,
                    classifier.classify(
                            field,
                            source.present(),
                            source.value() == null || source.value().isNull()
                                    ? null
                                    : source.value().asText()));
        }
        return new BuildingProfileParsedRecord(itemIndex, pnu, endpoint, values);
    }

    private SourceNode source(JsonNode node, Set<String> keys) {
        if (node == null) return new SourceNode(false, null);
        for (String key : keys) {
            if (node.has(key)) return new SourceNode(true, node.get(key));
        }
        return new SourceNode(false, null);
    }

    private List<JsonNode> nodes(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) return node.isObject() ? List.of(node) : List.of();
        List<JsonNode> result = new ArrayList<>();
        node.forEach(result::add);
        return result;
    }

    private JsonNode child(JsonNode node, String... keys) {
        if (node == null) return null;
        for (String key : keys) {
            JsonNode child = node.get(key);
            if (child != null && !child.isNull()) return child;
        }
        return null;
    }

    private String text(JsonNode node, String... keys) {
        JsonNode value = child(node, keys);
        return value == null ? null : value.asText().trim();
    }

    private int integer(JsonNode node, String... keys) {
        String value = text(node, keys);
        if (value == null || value.isBlank()) return 0;
        return new BigDecimal(value).intValueExact();
    }

    private record SourceNode(boolean present, JsonNode value) {}
}
