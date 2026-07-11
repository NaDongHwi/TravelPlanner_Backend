package com.travel.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;

@Service
public class WeatherService {

    @Value("${weather.openweathermap.api-key}")
    private String weatherApiKey;

    private final RestTemplate restTemplate = new RestTemplate();
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