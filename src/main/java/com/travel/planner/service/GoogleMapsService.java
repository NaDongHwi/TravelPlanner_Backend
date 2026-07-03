package com.travel.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planner.entity.Place;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
public class GoogleMapsService {

    @Value("${google.maps.api-key}")
    private String googleMapsApiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String getRealTravelTimes(List<Place> route) {
        if (route == null || route.size() < 2) return "이동 시간 정보 없음";

        try {
            String origin = route.get(0).getLatitude() + "," + route.get(0).getLongitude();
            String destination = route.get(route.size() - 1).getLatitude() + "," + route.get(route.size() - 1).getLongitude();

            StringBuilder waypoints = new StringBuilder();
            for (int i = 1; i < route.size() - 1; i++) {
                waypoints.append(route.get(i).getLatitude()).append(",").append(route.get(i).getLongitude());
                if (i < route.size() - 2) waypoints.append("|");
            }

            String url = String.format(
                    "https://maps.googleapis.com/maps/api/directions/json?origin=%s&destination=%s&waypoints=%s&key=%s&language=ko",
                    origin, destination, waypoints.toString(), googleMapsApiKey
            );

            String response = restTemplate.getForObject(url, String.class);
            JsonNode rootNode = objectMapper.readTree(response);

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

    // 'Place Details API'를 찔러서 영업시간을 가져옵니다
    public Place getPlaceDetails(String city, String placeName) {
        Place resultPlace = new Place();
        resultPlace.setLatitude(0.0);
        resultPlace.setLongitude(0.0);
        resultPlace.setOpeningHours("영업시간 정보 없음"); // 기본값

        try {
            // 1차 검색: Text Search API로 위도, 경도, 그리고 고유 'place_id' 획득
            String exactSearchQuery = placeName + " " + city;
            String searchUrl = "https://maps.googleapis.com/maps/api/place/textsearch/json?query={query}&key={key}&language=ko&region=jp";

            String searchResponse = restTemplate.getForObject(searchUrl, String.class, exactSearchQuery, googleMapsApiKey);
            JsonNode searchRoot = objectMapper.readTree(searchResponse);

            if ("OK".equals(searchRoot.path("status").asText())) {
                JsonNode firstResult = searchRoot.path("results").get(0);

                // 좌표(lat, lng) 셋팅
                JsonNode location = firstResult.path("geometry").path("location");
                resultPlace.setLatitude(location.path("lat").asDouble());
                resultPlace.setLongitude(location.path("lng").asDouble());

                // 2차 검색: 얻어낸 place_id로 Place Details API를 찔러서 영업시간(opening_hours)만 빼오기
                String placeId = firstResult.path("place_id").asText();
                String detailsUrl = "https://maps.googleapis.com/maps/api/place/details/json?place_id={placeId}&fields=opening_hours&key={key}&language=ko";

                String detailsResponse = restTemplate.getForObject(detailsUrl, String.class, placeId, googleMapsApiKey);
                JsonNode detailsRoot = objectMapper.readTree(detailsResponse);

                if ("OK".equals(detailsRoot.path("status").asText())) {
                    JsonNode weekdayText = detailsRoot.path("result").path("opening_hours").path("weekday_text");

                    // 영업시간 배열 ["월요일: 09:00...", "화요일: 09:00..."] 을 하나의 텍스트로 합치기
                    if (!weekdayText.isMissingNode() && weekdayText.isArray()) {
                        List<String> hoursList = new ArrayList<>();
                        for (JsonNode node : weekdayText) {
                            hoursList.add(node.asText());
                        }
                        // " | " 기호로 요일별 시간을 묶어서 저장합니다.
                        resultPlace.setOpeningHours(String.join(" | ", hoursList));
                    }
                }
            } else {
                System.out.println("장소 검색 실패 (" + exactSearchQuery + ") - 원인: " + searchRoot.path("status").asText());
            }
        } catch (Exception e) {
            System.out.println("네트워크 에러 (" + placeName + "): " + e.getMessage());
        }

        return resultPlace; // 좌표와 영업시간이 꽉 찬 Place 객체를 반환!
    }
}