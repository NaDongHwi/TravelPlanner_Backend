package com.travel.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planner.entity.Place;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class GoogleMapsService {

    // yml 파일의 경로에 맞게 값을 가져옵니다.
    @Value("${google.maps.api-key}")
    private String googleMapsApiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String getRealTravelTimes(List<Place> route) {
        if (route == null || route.size() < 2) return "이동 시간 정보 없음";

        try {
            // 1. 출발지, 도착지 지정
            String origin = route.get(0).getLatitude() + "," + route.get(0).getLongitude();
            String destination = route.get(route.size() - 1).getLatitude() + "," + route.get(route.size() - 1).getLongitude();

            // 2. 중간 경유지(Waypoints)들을 파이프(|) 기호로 연결
            StringBuilder waypoints = new StringBuilder();
            for (int i = 1; i < route.size() - 1; i++) {
                waypoints.append(route.get(i).getLatitude()).append(",").append(route.get(i).getLongitude());
                if (i < route.size() - 2) waypoints.append("|");
            }

            // 3. 구글 맵스 Directions API 호출 (한국어로 응답받기)
            String url = String.format(
                    "https://maps.googleapis.com/maps/api/directions/json?origin=%s&destination=%s&waypoints=%s&key=%s&language=ko",
                    origin, destination, waypoints.toString(), googleMapsApiKey
            );

            String response = restTemplate.getForObject(url, String.class);
            JsonNode rootNode = objectMapper.readTree(response);

            // 4. 응답에서 구간별 이동 시간만  뽑아내기
            JsonNode legs = rootNode.path("routes").get(0).path("legs");
            StringBuilder timeInfo = new StringBuilder();

            for (int i = 0; i < legs.size(); i++) {
                String duration = legs.get(i).path("duration").path("text").asText();
                timeInfo.append("- ").append(route.get(i).getName())
                        .append(" -> ")
                        .append(route.get(i+1).getName())
                        .append(" (실제 소요 시간: ").append(duration).append(")\n");
            }

            return timeInfo.toString();

        } catch (Exception e) {
            return "구글 맵스 연동 오류로 실제 시간 측정 불가 (직선거리로 시간 배분 요망)";
        }
    }
}