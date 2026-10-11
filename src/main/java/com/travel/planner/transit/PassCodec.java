package com.travel.planner.transit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 패스의 이용 범위(coverage) ↔ JSON 문자열.
 *
 * 형식: [{"operators":["東京地下鉄"],"categories":["SUBWAY"],"lines":[],"exceptLines":[],"exceptStations":[],"boxes":"35.5,139.6,35.8,139.9"}]
 * 여러 줄 중 하나에만 맞으면 그 승차는 패스로 탈 수 있다.
 */
public final class PassCodec {

    private PassCodec() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 형식이 틀리면 IllegalArgumentException. 비어 있으면 빈 목록 */
    public static List<PassAdvisor.Cover> parseCovers(String json) {
        List<PassAdvisor.Cover> covers = new ArrayList<>();
        if (json == null || json.isBlank()) return covers;
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("패스 범위(coverage)의 JSON 형식이 잘못되었습니다: " + e.getMessage());
        }
        if (!root.isArray()) throw new IllegalArgumentException("패스 범위(coverage)는 배열이어야 합니다.");
        for (JsonNode node : root) {
            PassAdvisor.Cover cover = new PassAdvisor.Cover();
            cover.operators = strings(node.get("operators"));
            cover.categories = FareBook.parseCategories(String.join(",", strings(node.get("categories"))));
            cover.lines = strings(node.get("lines"));
            cover.exceptLines = strings(node.get("exceptLines"));
            cover.exceptStations = strings(node.get("exceptStations"));
            JsonNode boxes = node.get("boxes");
            cover.boxes = FareBook.parseBoxes(boxes == null || boxes.isNull() ? null : boxes.asText());
            if (cover.operators.isEmpty() && cover.categories.isEmpty() && cover.lines.isEmpty() && cover.boxes.isEmpty()) {
                throw new IllegalArgumentException("패스 범위 한 줄에는 운영사·종류·노선·지역 중 하나는 있어야 합니다. (조건이 없으면 모든 교통수단을 덮게 됩니다)");
            }
            covers.add(cover);
        }
        return covers;
    }

    public static String toJson(List<PassAdvisor.Cover> covers) {
        ArrayNode array = MAPPER.createArrayNode();
        for (PassAdvisor.Cover cover : covers) {
            ObjectNode node = array.addObject();
            put(node, "operators", cover.operators);
            List<String> categories = new ArrayList<>();
            for (RailCategory category : cover.categories) categories.add(category.name());
            put(node, "categories", categories);
            put(node, "lines", cover.lines);
            put(node, "exceptLines", cover.exceptLines);
            put(node, "exceptStations", cover.exceptStations);
            if (!cover.boxes.isEmpty()) node.put("boxes", FareBook.formatBoxes(cover.boxes));
        }
        return array.toString();
    }

    private static void put(ObjectNode node, String field, List<String> values) {
        if (values == null || values.isEmpty()) return;
        ArrayNode array = node.putArray(field);
        for (String value : values) array.add(value);
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) return values;
        if (node.isArray()) {
            for (JsonNode item : node) {
                String text = item.asText("").trim();
                if (!text.isEmpty()) values.add(text);
            }
        } else {
            values.addAll(FareBook.split(node.asText(""), ","));
        }
        return values;
    }
}
