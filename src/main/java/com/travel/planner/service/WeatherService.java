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

    /**
     * 여행 날짜별 악천후 여부 (true = 낮 시간대에 비/눈 예보가 많음).
     *
     * 일정 점수에는 "요청한 순간의 오늘 날씨"가 아니라 "방문하는 날의 예보"를 써야 한다.
     * OpenWeather 무료 예보는 5일치(3시간 간격)라서 그 범위 밖의 날짜는 결과에 넣지 않고,
     * 엔진은 예보가 없는 날을 날씨 가감 없이 계획한다.
     */
    public Map<LocalDate, Boolean> getBadWeatherByDate(double lat, double lon, LocalDate startDate, LocalDate endDate) {
        Map<LocalDate, Boolean> result = new HashMap<>();
        LocalDate today = LocalDate.now();
        if (startDate.isAfter(today.plusDays(5)) || endDate.isBefore(today)) return result;

        try {
            String url = "https://api.openweathermap.org/data/2.5/forecast?lat={lat}&lon={lon}&appid={key}&lang=kr&units=metric";
            String response = restTemplate.getForObject(url, String.class, lat, lon, weatherApiKey);
            JsonNode list = objectMapper.readTree(response).path("list");

            Map<LocalDate, int[]> counts = new HashMap<>();   // [낮 시간대 슬롯 수, 그중 비/눈 슬롯 수]
            for (JsonNode node : list) {
                String dtTxt = node.path("dt_txt").asText();          // "2026-07-02 12:00:00" (UTC)
                if (dtTxt.length() < 13) continue;
                // 일본 시각(UTC+9)으로 옮겨 낮(09~21시) 슬롯만 센다
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
            System.err.println("오픈웨더 예보 호출 실패(날씨 가감 없이 진행): " + e.getMessage());
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
