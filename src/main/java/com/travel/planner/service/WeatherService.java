package com.travel.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

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
}