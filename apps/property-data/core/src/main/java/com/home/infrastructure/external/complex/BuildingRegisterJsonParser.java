package com.home.infrastructure.external.complex;

import com.home.application.ingest.buildingregister.BuildingRegisterPageParser;
import com.home.application.ingest.buildingregister.BuildingRegisterPageResponse;
import com.home.application.ingest.buildingregister.BuildingRegisterRecordSnapshotCommand;
import com.home.application.ingest.buildingregister.ParsedBuildingRegisterPage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class BuildingRegisterJsonParser implements BuildingRegisterPageParser {
    private final ObjectMapper objectMapper;

    public BuildingRegisterJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public ParsedBuildingRegisterPage parse(BuildingRegisterPageResponse response) {
        if (response.body() == null) throw new IllegalArgumentException("building register body is missing");
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode header = path(root, "response", "header");
            JsonNode body = path(root, "response", "body");
            String resultCode = text(header, "resultCode", "RESULT_CODE");
            String resultMessage = text(header, "resultMsg", "RESULT_MSG");
            int totalCount = defaultValue(integer(body, "totalCount", "total_count"), 0);
            List<JsonNode> items = nodes(body == null ? null : path(body, "items", "item"));
            List<BuildingRegisterRecordSnapshotCommand> records = new ArrayList<>();
            for (int index = 0; index < items.size(); index++) {
                records.add(record(response, items.get(index), index));
            }
            return new ParsedBuildingRegisterPage(resultCode, resultMessage, totalCount, records);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("building register JSON parsing failed", exception);
        }
    }

    private BuildingRegisterRecordSnapshotCommand record(
            BuildingRegisterPageResponse response, JsonNode item, int index) {
        return new BuildingRegisterRecordSnapshotCommand(
                index,
                response.pnu(),
                response.endpoint(),
                text(item, "mgmBldrgstPk", "MGM_BLDRGST_PK", "bldMgmBldRgstPk"),
                text(item, "mgmUpBldrgstPk", "MGM_UP_BLDRGST_PK"),
                text(item, "regstrGbCd", "REGSTR_GB_CD"),
                text(item, "regstrKindCd", "REGSTR_KIND_CD"),
                text(item, "newOldRegstrGbCd", "NEW_OLD_REGSTR_GB_CD"),
                text(item, "mainAtchGbCd", "MAIN_ATCH_GB_CD"),
                text(item, "bldNm", "BLD_NM"),
                text(item, "dongNm", "DONG_NM"),
                text(item, "mainPurpsCd", "MAIN_PURPS_CD"),
                decimal(item, "platArea", "PLAT_AREA"),
                decimal(item, "archArea", "ARCH_AREA"),
                decimal(item, "totArea", "TOT_AREA"),
                decimal(item, "vlRatEstmTotArea", "VL_RAT_ESTM_TOT_AREA"),
                decimal(item, "bcRat", "BC_RAT"),
                decimal(item, "vlRat", "VL_RAT"),
                integer(item, "mainBldCnt", "MAIN_BLD_CNT"),
                integer(item, "atchBldCnt", "ATCH_BLD_CNT"),
                integer(item, "hhldCnt", "HHLD_CNT"),
                date(text(item, "useAprDay", "USE_APR_DAY")),
                date(text(item, "crtnDay", "CRTN_DAY")));
    }

    private List<JsonNode> nodes(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (node.isArray()) {
            List<JsonNode> result = new ArrayList<>();
            node.forEach(result::add);
            return result;
        }
        if (!node.isObject()) return List.of();
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
        String result = value.asText().trim();
        return result.isEmpty() ? null : result;
    }

    private Integer integer(JsonNode node, String... keys) {
        String value = text(node, keys);
        try {
            return value == null ? null : new BigDecimal(value).intValueExact();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private BigDecimal decimal(JsonNode node, String... keys) {
        String value = text(node, keys);
        try {
            return value == null ? null : new BigDecimal(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private LocalDate date(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value.replaceAll("[-./]", ""), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private int defaultValue(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
