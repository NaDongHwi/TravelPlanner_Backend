package com.travel.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WeatherService {

    @Value("${weather.openweathermap.api-key}")
    private String weatherApiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 도시 이름을 받아 현재 기상 상태(비, 맑음 등)를 한국어로 반환
    public String getCurrentWeather(String city) {
        try {
            // 구글 도시 이름 매핑과 오픈웨더 쿼리 결합 (예: 도쿄 -> Tokyo)
            String queryCity = city.equals("도쿄") ? "Tokyo" : (city.equals("시즈오카") ? "Shizuoka" : city);
            String url = "https://api.openweathermap.org/data/2.5/weather?q={city}&appid={key}&lang=kr&units=metric";

            String response = restTemplate.getForObject(url, String.class, queryCity, weatherApiKey);
            JsonNode root = objectMapper.readTree(response);

            String weatherDescription = root.path("weather").get(0).path("description").asText();
            double temp = root.path("main").path("temp").asDouble();

            return String.format("%s (현재 기온: %.1f°C)", weatherDescription, temp);
        } catch (Exception e) {
            return "맑음 (기상 API 연동 일시 지연으로 기본 동선 연산 요망)";
        }
    }

    /** One Call 4.0 일별 예보를 일정 점수에 반영하는 최대 범위(오늘부터). 그보다 먼 날짜는 통계 기반이라 쓰지 않는다. */
    private static final int ONE_CALL_HORIZON_DAYS = 16;
    private static final int ONE_CALL_MAX_RECORDS = 10;      // 4.0 타임라인은 한 번에 최대 10건
    private static final double WET_POP_THRESHOLD = 0.5;

    /**
     * 여행 날짜별 악천후 여부 (true = 그날 비/눈 예보).
     *
     * 일정 점수에는 "요청한 순간의 오늘 날씨"가 아니라 "방문하는 날의 예보"를 써야 한다.
     * 1순위: One Call API 4.0 일별 타임라인(오늘부터 16일까지 사용).
     * 2순위: 4.0 호출이 실패하거나 해당 날짜가 응답에 없으면 기존 5일/3시간 예보(2.5)로 대신한다.
     * 예보가 없는 날짜는 결과에 넣지 않고, 엔진은 그런 날을 날씨 가감 없이 계획한다.
     * 어느 경로를 탔는지는 서버 로그의 "[날씨]" 줄로 확인할 수 있다.
     */
    public Map<LocalDate, Boolean> getBadWeatherByDate(double lat, double lon, LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) return new HashMap<>();
        LocalDate today = LocalDate.now(JST);
        LocalDate horizon = today.plusDays(ONE_CALL_HORIZON_DAYS);
        if (endDate.isBefore(today)) {
            System.out.println("[날씨] 여행일(" + startDate + "~" + endDate + ")이 이미 지난 날짜라 날씨를 반영하지 않습니다.");
            return new HashMap<>();
        }
        LocalDate from = startDate.isBefore(today) ? today : startDate;
        LocalDate to = endDate.isAfter(horizon) ? horizon : endDate;
        if (from.isAfter(to)) {
            System.out.println("[날씨] 여행일(" + startDate + "~" + endDate + ")이 예보 범위(" + today + "~" + horizon
                    + ") 밖이라 날씨를 반영하지 않습니다.");
            return new HashMap<>();
        }

        try {
            Map<LocalDate, Boolean> daily = fetchOneCallDaily(lat, lon, from, to);
            if (!daily.isEmpty()) return daily;
            System.err.println("[날씨] One Call 4.0 응답에 " + from + "~" + to + " 날짜가 없습니다 → 5일 예보(2.5)로 대체");
        } catch (Exception e) {
            System.err.println("[날씨] One Call 4.0 호출 실패 → 5일 예보(2.5)로 대체: " + e.getMessage());
        }
        return fetchFiveDayForecast(lat, lon, startDate, endDate, today);
    }

    private static final java.time.ZoneId JST = java.time.ZoneId.of("Asia/Tokyo");

    /** One Call 4.0: /onecall/timeline/1day — 하루 1건, 한 번에 최대 10건이라 필요한 만큼 나눠 부른다. */
    private Map<LocalDate, Boolean> fetchOneCallDaily(double lat, double lon, LocalDate from, LocalDate to) throws Exception {
        Map<LocalDate, Boolean> result = new HashMap<>();
        String url = "https://api.openweathermap.org/data/4.0/onecall/timeline/1day"
                + "?lat={lat}&lon={lon}&start={start}&cnt={cnt}&units=metric&appid={key}";

        int received = 0;
        LocalDate firstSeen = null;
        LocalDate lastSeen = null;
        String lastBody = null;

        LocalDate cursor = from;
        for (int call = 0; call < 3 && !cursor.isAfter(to); call++) {
            int cnt = (int) Math.min(ONE_CALL_MAX_RECORDS, java.time.temporal.ChronoUnit.DAYS.between(cursor, to) + 1);
            // 시작 시각은 그날 오전 10시(일본). 응답의 dt 가 현지 정오라서, "start 이후 기록"으로 해석하든
            // "start 가 속한 날(UTC)부터"로 해석하든 같은 날짜부터 받게 된다. (자정으로 보내면 UTC 로는 전날이 된다)
            long start = cursor.atTime(10, 0).atZone(JST).toEpochSecond();
            lastBody = restTemplate.getForObject(url, String.class, lat, lon, start, cnt, weatherApiKey);
            JsonNode root = objectMapper.readTree(lastBody);
            if (!root.path("data").isArray()) throw new IllegalStateException("응답에 data 배열이 없습니다: " + snippet(lastBody));

            LocalDate[] seen = collectDaily(root, from, to, result);
            received += root.path("data").size();
            if (seen[0] != null && (firstSeen == null || seen[0].isBefore(firstSeen))) firstSeen = seen[0];
            boolean progressed = seen[1] != null && !seen[1].isBefore(cursor) && (lastSeen == null || seen[1].isAfter(lastSeen));
            if (seen[1] != null && (lastSeen == null || seen[1].isAfter(lastSeen))) lastSeen = seen[1];
            if (!progressed) break;      // 더 받을 것이 없거나 앞으로 나아가지 못한다
            cursor = lastSeen.plusDays(1);
        }

        long wet = result.values().stream().filter(Boolean::booleanValue).count();
        System.out.println("[날씨] One Call 4.0: 요청 " + from + "~" + to + ", 응답 " + received + "건("
                + firstSeen + "~" + lastSeen + "), 반영 " + result.size() + "일(비·눈 " + wet + "일)");
        if (result.isEmpty()) System.err.println("[날씨] One Call 4.0 응답 앞부분: " + snippet(lastBody));
        return result;
    }

    /**
     * 응답의 일별 기록을 날짜로 바꿔 [from, to] 범위만 result 에 담는다.
     * 반환: {응답에 있던 가장 이른 날짜, 가장 늦은 날짜} (기록이 없으면 둘 다 null)
     */
    static LocalDate[] collectDaily(JsonNode root, LocalDate from, LocalDate to, Map<LocalDate, Boolean> result) {
        long offset = root.path("timezone_offset").asLong(9 * 3600L);
        LocalDate first = null;
        LocalDate last = null;
        for (JsonNode day : root.path("data")) {
            LocalDate date = dayOf(day.path("dt"), offset);
            if (date == null) continue;
            if (first == null || date.isBefore(first)) first = date;
            if (last == null || date.isAfter(last)) last = date;
            if (date.isBefore(from) || date.isAfter(to)) continue;
            result.put(date, isWetDay(day));
        }
        return new LocalDate[]{first, last};
    }

    /** dt(유닉스 초, UTC) → 현지 날짜. 밀리초나 "2026-10-10..." 같은 문자열로 와도 읽는다. */
    static LocalDate dayOf(JsonNode dt, long offsetSeconds) {
        if (dt == null || dt.isMissingNode() || dt.isNull()) return null;
        try {
            if (dt.isNumber() || dt.asText().matches("\\d+")) {
                long seconds = dt.asLong();
                if (seconds > 100_000_000_000L) seconds /= 1000;      // 밀리초
                return java.time.LocalDateTime.ofEpochSecond(seconds + offsetSeconds, 0, java.time.ZoneOffset.UTC).toLocalDate();
            }
            String text = dt.asText();
            return text.length() >= 10 ? LocalDate.parse(text.substring(0, 10)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 로그용: 응답 앞부분만, API 키는 가린다 (prev/next 링크에 appid 가 들어 있다) */
    private static String snippet(String body) {
        if (body == null) return "(빈 응답)";
        String masked = body.replaceAll("appid=[^&\"\\s]+", "appid=***");
        return masked.length() > 400 ? masked.substring(0, 400) + "..." : masked;
    }

    /** 일별 요약이 비/눈/뇌우이고 강수확률이 절반 이상(강수확률이 없으면 요약만으로 판단). */
    static boolean isWetDay(JsonNode day) {
        String main = day.path("weather").path(0).path("main").asText("");
        boolean wetMain = main.equals("Rain") || main.equals("Snow") || main.equals("Thunderstorm") || main.equals("Drizzle");
        if (!wetMain) return false;
        JsonNode pop = day.path("pop");
        return !pop.isNumber() || pop.asDouble() >= WET_POP_THRESHOLD;
    }

    /** 기존 방식: 5일/3시간 예보에서 일본 시각 낮(09~21시) 슬롯의 절반 이상이 비/눈이면 악천후. */
    private Map<LocalDate, Boolean> fetchFiveDayForecast(double lat, double lon, LocalDate startDate, LocalDate endDate,
                                                         LocalDate today) {
        Map<LocalDate, Boolean> result = new HashMap<>();
        if (startDate.isAfter(today.plusDays(5))) return result;

        try {
            String url = "https://api.openweathermap.org/data/2.5/forecast?lat={lat}&lon={lon}&appid={key}&lang=kr&units=metric";
            String response = restTemplate.getForObject(url, String.class, lat, lon, weatherApiKey);
            JsonNode list = objectMapper.readTree(response).path("list");

            Map<LocalDate, int[]> counts = new HashMap<>();   // [낮 시간대 슬롯 수, 그중 비/눈 슬롯 수]
            for (JsonNode node : list) {
                String dtTxt = node.path("dt_txt").asText();          // "2026-07-02 12:00:00" (UTC)
                if (dtTxt.length() < 13) continue;
                java.time.LocalDateTime utc = java.time.LocalDateTime.parse(dtTxt.replace(' ', 'T'));
                java.time.LocalDateTime jst = utc.plusHours(9);
                if (jst.getHour() < 9 || jst.getHour() > 21) continue;
                LocalDate date = jst.toLocalDate();
                if (date.isBefore(startDate) || date.isAfter(endDate)) continue;

                String main = node.path("weather").path(0).path("main").asText("");
                boolean wet = main.equals("Rain") || main.equals("Snow") || main.equals("Thunderstorm") || main.equals("Drizzle");
                int[] c = counts.computeIfAbsent(date, d -> new int[2]);
                c[0]++;
                if (wet) c[1]++;
            }
            for (Map.Entry<LocalDate, int[]> e : counts.entrySet()) {
                int[] c = e.getValue();
                if (c[0] >= 2) result.put(e.getKey(), c[1] * 2 >= c[0]);   // 낮 슬롯의 절반 이상이 비/눈
            }
        } catch (Exception e) {
            System.err.println("[날씨] 5일 예보 호출 실패(날씨 가감 없이 진행): " + e.getMessage());
        }
        return result;
    }

    public String getForecastWeatherByCoords(double lat, double lon, LocalDate startDate) {
        try {
            // 공식 문서 권장 방식: lat, lon 사용 (하드코딩 및 Deprecated 완벽 해결)
            String url = "https://api.openweathermap.org/data/2.5/forecast?lat={lat}&lon={lon}&appid={key}&lang=kr&units=metric";

            // 파라미터 바인딩 순서: lat, lon, apiKey
            String response = restTemplate.getForObject(url, String.class, lat, lon, weatherApiKey);
            JsonNode root = objectMapper.readTree(response);

            JsonNode list = root.path("list");
            for (JsonNode node : list) {
                String dtTxt = node.path("dt_txt").asText();
                if (dtTxt.startsWith(startDate.toString())) {
                    return node.path("weather").get(0).path("description").asText();
                }
            }
            return "맑음 (예보 데이터 없음)";
        } catch (Exception e) {
            System.err.println("오픈웨더 API 호출 실패: " + e.getMessage());
            return "맑음 (기상 API 연동 지연)";
        }
    }
}
