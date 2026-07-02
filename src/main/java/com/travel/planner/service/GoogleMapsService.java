package com.travel.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planner.entity.Place;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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

    // 'Places API (Text Search)'를 사용합니다.
    // 이중 인코딩을 방지
    public double[] getCoordinates(String city, String placeName) {
        try {
            // 1. 직접 인코딩하지 않고, 검색어만 만듭니다.
            String exactSearchQuery = placeName + " " + city;

            // 2. URL에 직접 글자를 더하지 않고, 중괄호 {query}, {key} 를 뚫어놓습니다.
            String url = "https://maps.googleapis.com/maps/api/place/textsearch/json?query={query}&key={key}&language=ko&region=jp";

            // 3. getForObject의 뒤쪽 파라미터로 변수들을 순서대로 넘겨주면, 스프링이 안전하게 1번만 조립해 줍니다.
            String response = restTemplate.getForObject(url, String.class, exactSearchQuery, googleMapsApiKey);

            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response);
            String status = root.path("status").asText();

            if ("OK".equals(status)) {
                com.fasterxml.jackson.databind.JsonNode location = root.path("results").get(0).path("geometry").path("location");
                double lat = location.path("lat").asDouble();
                double lng = location.path("lng").asDouble();
                return new double[]{lat, lng};
            } else {
                System.out.println("장소 검색 실패 (" + exactSearchQuery + ") - 원인: " + status);
            }
        } catch (Exception e) {
            System.out.println("네트워크 에러 (" + placeName + "): " + e.getMessage());
        }

        return new double[]{0.0, 0.0};
    }
}