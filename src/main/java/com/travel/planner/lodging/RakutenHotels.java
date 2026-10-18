package com.travel.planner.lodging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 라쿠텐 트래블 API(SimpleHotelSearch·VacantHotelSearch) 응답 읽기.
 *
 * 응답의 "hotels" 는 formatVersion 에 따라 [{ "hotel": [ {...}, {...} ] }] 또는 [[ {...}, {...} ]] 모양이다.
 * 둘 다 읽을 수 있게, 호텔 하나에 해당하는 묶음 안에서 hotelBasicInfo·dailyCharge 를 이름으로 찾아 모은다.
 */
public final class RakutenHotels {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RakutenHotels() {}

    /** 호텔 하나 */
    public static final class Hotel {
        public long hotelNo;
        public String name;
        public double latitude;          // datumType=1(WGS84, 도) 로 요청했을 때의 값
        public double longitude;
        public String url;               // 호텔 안내 페이지
        public Integer minCharge;        // 표시 최저가: 1실 1박, 세금·서비스료 포함 "목안" (날짜와 무관)
        public Integer lowestNightTotal; // (빈방 검색) 예약 가능한 요금 가운데 첫날 밤 합계가 가장 싼 값
    }

    public static List<Hotel> parse(String json) {
        List<Hotel> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonNode hotels = MAPPER.readTree(json).path("hotels");
            if (!hotels.isArray()) return out;
            for (JsonNode entry : hotels) {
                List<JsonNode> basics = new ArrayList<>();
                List<JsonNode> charges = new ArrayList<>();
                collect(entry, "hotelBasicInfo", basics);
                collect(entry, "dailyCharge", charges);
                if (basics.isEmpty()) continue;
                JsonNode b = basics.get(0);
                Hotel h = new Hotel();
                h.hotelNo = b.path("hotelNo").asLong(0);
                h.name = text(b, "hotelName");
                h.latitude = b.path("latitude").asDouble(Double.NaN);
                h.longitude = b.path("longitude").asDouble(Double.NaN);
                h.url = text(b, "hotelInformationUrl");
                h.minCharge = positive(b.path("hotelMinCharge"));
                for (JsonNode c : charges) {
                    Integer total = positive(c.path("total"));
                    if (total != null && (h.lowestNightTotal == null || total < h.lowestNightTotal)) h.lowestNightTotal = total;
                }
                if (h.hotelNo > 0) out.add(h);
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }

    /** 오류 응답의 error_description (없으면 null) */
    public static String error(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root.has("error")) return root.path("error").asText() + ": " + root.path("error_description").asText("");
            // 2026년 개편 뒤의 형식: {"errors":{"errorCode":403,"errorMessage":"..."}}
            JsonNode errors = root.path("errors");
            if (errors.isObject() && errors.has("errorMessage")) return errors.path("errorMessage").asText();
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void collect(JsonNode node, String field, List<JsonNode> out) {
        if (node == null) return;
        if (node.isObject()) {
            JsonNode hit = node.get(field);
            if (hit != null && hit.isObject()) out.add(hit);
            else if (hit != null && hit.isArray()) hit.forEach(out::add);
            node.fields().forEachRemaining(e -> {
                if (!e.getKey().equals(field)) collect(e.getValue(), field, out);
            });
        } else if (node.isArray()) {
            for (JsonNode child : node) collect(child, field, out);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static Integer positive(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) return null;
        int n = v.isNumber() ? v.asInt() : parseInt(v.asText());
        return n > 0 ? n : null;
    }

    private static int parseInt(String s) {
        try {
            return (int) Math.round(Double.parseDouble(s.replace(",", "").trim()));
        } catch (Exception e) {
            return 0;
        }
    }
}
