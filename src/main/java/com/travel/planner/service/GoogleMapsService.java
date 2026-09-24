package com.travel.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.Region;
import com.travel.planner.util.PrefectureMapper;
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
    public Place getPlaceDetails(String city, String placeName, String lang) {
        Place resultPlace = new Place();
        resultPlace.setLatitude(0.0);
        resultPlace.setLongitude(0.0);
        resultPlace.setOpeningHours("영업시간 정보 없음"); // 기본값

        // 언어 값이 비어있을 경우를 대비한 기본값 설정 방어 로직
        String targetLang = (lang != null && !lang.trim().isEmpty()) ? lang : "ko";

        try {
            // 1차 검색: Text Search API로 위도, 경도, 그리고 고유 'place_id' 획득
            String exactSearchQuery = placeName + " " + city;
            // language={lang} 파라미터로 동적 변경
            String searchUrl = "https://maps.googleapis.com/maps/api/place/textsearch/json?query={query}&key={key}&language={lang}&region=jp";

            // 매핑 인자 맨 마지막에 targetLang 추가 투입
            String searchResponse = restTemplate.getForObject(searchUrl, String.class, exactSearchQuery, googleMapsApiKey, targetLang);
            JsonNode searchRoot = objectMapper.readTree(searchResponse);

            if ("OK".equals(searchRoot.path("status").asText())) {
                JsonNode firstResult = searchRoot.path("results").get(0);

                // [수질 검증 필터링] 구글이 내려준 실시간 평점과 리뷰 수를 스캔합니다.
                double rating = firstResult.path("rating").asDouble(0.0);
                int reviewCount = firstResult.path("user_ratings_total").asInt(0);

                // AI가 지어낸 가짜 장소이거나, 평점 3.5 미만 또는 리뷰 50개 미만의 불량/무명 장소인 경우
                if (rating < 3.5 || reviewCount < 50) {
                    System.out.println("[수질 검증 탈락] '" + placeName + "' (평점: " + rating + ", 리뷰: " + reviewCount + "개) -> 고품질 DB 기준 미달로 차단합니다.");
                    return resultPlace; // 위경도가 0.0인 상태로 리턴하여 컨트롤러에게 위험을 알림
                }

                // 좌표(lat, lng) 셋팅
                JsonNode location = firstResult.path("geometry").path("location");
                resultPlace.setLatitude(location.path("lat").asDouble());
                resultPlace.setLongitude(location.path("lng").asDouble());

                // DB 저장을 위해 placeId를 객체에 세팅
                String placeId = firstResult.path("place_id").asText();
                resultPlace.setPlaceId(placeId);

                // 2차 검색: 얻어낸 place_id로 Place Details API를 찔러서 영업시간(opening_hours)만 빼오기
                // language={lang} 파라미터로 동적 변경
                String detailsUrl = "https://maps.googleapis.com/maps/api/place/details/json?place_id={placeId}&fields=opening_hours&key={key}&language={lang}";

                // 매핑 인자 맨 마지막에 targetLang 추가 투입
                String detailsResponse = restTemplate.getForObject(detailsUrl, String.class, placeId, googleMapsApiKey, targetLang);
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

        return resultPlace; // 좌표와 영업시간이 찬 Place 객체를 반환
    }

    // AI 데이터 인리치먼트 전용: 장소의 실제 구글 리뷰 5개를 결합하여 텍스트로 반환
    public String getPlaceReviews(String city, String placeName) {
        try {
            String exactSearchQuery = placeName + " " + city;
            String searchUrl = "https://maps.googleapis.com/maps/api/place/textsearch/json?query={query}&key={key}&language=ko&region=jp";
            String searchResponse = restTemplate.getForObject(searchUrl, String.class, exactSearchQuery, googleMapsApiKey);
            JsonNode searchRoot = objectMapper.readTree(searchResponse);

            if ("OK".equals(searchRoot.path("status").asText())) {
                String placeId = searchRoot.path("results").get(0).path("place_id").asText();

                // reviews 필드를 명시하여 Place Details API 호출
                String detailsUrl = "https://maps.googleapis.com/maps/api/place/details/json?place_id={placeId}&fields=reviews&key={key}&language=ko";
                String detailsResponse = restTemplate.getForObject(detailsUrl, String.class, placeId, googleMapsApiKey);
                JsonNode detailsRoot = objectMapper.readTree(detailsResponse);

                if ("OK".equals(detailsRoot.path("status").asText())) {
                    JsonNode reviews = detailsRoot.path("result").path("reviews");
                    StringBuilder reviewText = new StringBuilder();

                    if (!reviews.isMissingNode() && reviews.isArray()) {
                        for (JsonNode review : reviews) {
                            reviewText.append(review.path("text").asText()).append("\n");
                        }
                        return reviewText.toString();
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[" + placeName + "] 리뷰 수집 실패: " + e.getMessage());
            return null; // 통신 에러 시 'null'을 반환하여 상위 로직에 위험을 알립니다
        }
        return "리뷰 정보 없음"; // 정상 통신 완료 + 진짜 리뷰가 1개도 없을 때만 이 문자가 반환됩니다.
    }

    // [자동 페이지네이션 업그레이드] next_page_token을 추적하여 한 키워드당 최대 60개 명소를 싹 긁어옵니다.
    // isEmergency 파라미터 추가 및 내부 메서드로 전달
    public List<Place> searchNewPlacesFromGoogle(String city, String keyword, boolean isEmergency) {
        List<Place> fetchedPlaces = new ArrayList<>();
        String baseUrl = "https://maps.googleapis.com/maps/api/place/textsearch/json?query={query}&key={key}&language=ko&region=jp";
        String pageTokenUrl = "https://maps.googleapis.com/maps/api/place/textsearch/json?pagetoken={pagetoken}&key={key}&language=ko";

        try {
            String searchQuery = city + " " + keyword;
            String response = restTemplate.getForObject(baseUrl, String.class, searchQuery, googleMapsApiKey);
            JsonNode root = objectMapper.readTree(response);

            // 1페이지 데이터 리스트에 적재
            parsePlacesFromNode(root, fetchedPlaces, city, isEmergency);

            String nextToken = root.path("next_page_token").asText();
            int pageCount = 1;

            while (nextToken != null && !nextToken.isEmpty() && pageCount < 3) {
                Thread.sleep(2000);
                System.out.println("➡️ [" + keyword + "] 다음 페이지 토큰 발견! " + (pageCount + 1) + "페이지 연속 수집 중...");
                String nextResponse = restTemplate.getForObject(pageTokenUrl, String.class, nextToken, googleMapsApiKey);
                JsonNode nextRoot = objectMapper.readTree(nextResponse);

                parsePlacesFromNode(nextRoot, fetchedPlaces, city, isEmergency);
                nextToken = nextRoot.path("next_page_token").asText();
                pageCount++;
            }

        } catch (Exception e) {
            System.out.println("구글 장소 크롤링 실패: " + e.getMessage());
        }
        return fetchedPlaces;
    }

    // [수질 관리] 다단 필터링 적용
    // isEmergency 파라미터 추가 및 완화 커트라인 로직(1.5점, 10개) 적용
    private void parsePlacesFromNode(JsonNode root, List<Place> fetchedPlaces, String city, boolean isEmergency) {
        if ("OK".equals(root.path("status").asText())) {
            JsonNode results = root.path("results");
            for (JsonNode node : results) {
                double rating = node.path("rating").asDouble(0.0);
                int reviewCount = node.path("user_ratings_total").asInt(0);
                String placeName = node.path("name").asText();

                String address = node.path("formatted_address").asText();
                if (address == null) continue;

                String lowerAddr = address.toLowerCase();
                if (lowerAddr.contains("대한민국") || lowerAddr.contains("한국") ||
                        lowerAddr.contains("korea") || lowerAddr.contains("seoul") || lowerAddr.contains("서울")) {
                    continue;
                }

                boolean isHighQuality = (rating >= 4.0 && reviewCount >= 300);
                boolean isSuperLandmark = (rating >= 3.6 && reviewCount >= 1500);
                // 💡 [신규] 긴급 수집 모드일 경우: 평점 1.5 이상, 리뷰 10개 이상이면 무조건 통과!
                boolean isEmergencyPass = isEmergency && (rating >= 1.5 && reviewCount >= 10);

                // 셋 중 하나라도 만족하면 DB에 적재
                if (isHighQuality || isSuperLandmark || isEmergencyPass) {
                    Place place = new Place();
                    place.setPlaceId(node.path("place_id").asText());
                    place.setName(placeName);
                    place.setCity(city);
                    place.setLatitude(node.path("geometry").path("location").path("lat").asDouble());
                    place.setLongitude(node.path("geometry").path("location").path("lng").asDouble());
                    place.setCategory(determineCategoryFromTypes(node.path("types")));

                    fetchedPlaces.add(place);
                }
            }
        }
    }

    // [자체 지명 정제 엔진] 입력된 텍스트를 구글 맵스를 통해 일본 내 정식 행정구역명으로 변환합니다.
    public String getFormalizedJapanCity(String cityInput) {
        try {
            String url = "https://maps.googleapis.com/maps/api/geocode/json?address={address}&components=country:JP&key={key}&language=ko";
            String response = restTemplate.getForObject(url, String.class, cityInput, googleMapsApiKey);
            JsonNode root = objectMapper.readTree(response);

            if ("OK".equals(root.path("status").asText())) {
                String formattedAddress = root.path("results").get(0).path("formatted_address").asText();

                // [9대 지방 필터링 스캔 구동]
                // 분석 주소가 바르지 않거나 47개 도도부현을 찾지 못하면 IllegalArgumentException이 터지며 상위 프로세스 정지
                Region recognizedRegion = PrefectureMapper.getRegionFromAddress(formattedAddress);

                System.out.println("[지명 검증 완료] 정식 주소: " + formattedAddress + " -> 판정 권역: " + recognizedRegion.name());
                return formattedAddress;
            } else {
                throw new RuntimeException("구글 맵스에서 해당 지명을 식별하지 못했습니다.");
            }
        } catch (Exception e) {
            // 가짜 데이터 적재 방지를 위해 예외 메시지를 그대로 감싸서 컨트롤러 단으로 밀어 올립니다.
            throw new RuntimeException("지명 정밀 검증 실패: " + e.getMessage());
        }
    }

    // [자동 분류 엔진] 구글의 types 배열을 분석하여 5대 카테고리로 매핑합니다.
    private String determineCategoryFromTypes(JsonNode typesNode) {
        if (typesNode == null || !typesNode.isArray()) return "관광지"; // 기본값

        for (JsonNode typeNode : typesNode) {
            String type = typeNode.asText().toLowerCase();
            // 1. 숙소
            if (type.equals("lodging")) return "숙소";
            // 2. 교통
            if (type.equals("train_station") || type.equals("transit_station") || type.equals("airport") || type.equals("subway_station") || type.equals("bus_station")) return "교통";
            // 3. 식음
            if (type.equals("restaurant") || type.equals("cafe") || type.equals("food") || type.equals("bakery") || type.equals("bar") || type.equals("meal_takeaway")) return "식음";
            // 4. 쇼핑
            if (type.equals("shopping_mall") || type.equals("department_store") || type.equals("supermarket") || type.equals("clothing_store") || type.equals("store")) return "쇼핑";
        }
        return "관광지"; // 위 조건에 걸리지 않는 모든 명소(공원, 신사, 박물관 등)는 관광지로 통일
    }

    // [숙소 역제안용] 구글 맵스 숙소 검색기
    public List<Place> searchRecommendedHotels(String city) {
        List<Place> recommendedHotels = new ArrayList<>();
        // 키워드를 "호텔"로 강제 고정
        String url = "https://maps.googleapis.com/maps/api/place/textsearch/json?query={query}&key={key}&language=ko&region=jp";

        try {
            String response = restTemplate.getForObject(url, String.class, city + " 호텔", googleMapsApiKey);
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response);

            if ("OK".equals(root.path("status").asText())) {
                for (com.fasterxml.jackson.databind.JsonNode node : root.path("results")) {
                    double rating = node.path("rating").asDouble(0.0);
                    int reviewCount = node.path("user_ratings_total").asInt(0);

                    // 조건: 리뷰 3.5 이상, 100개 이상!
                    if (rating >= 3.5 && reviewCount >= 100) {
                        Place hotel = new Place();
                        hotel.setName(node.path("name").asText());
                        hotel.setCity(city);
                        hotel.setLatitude(node.path("geometry").path("location").path("lat").asDouble());
                        hotel.setLongitude(node.path("geometry").path("location").path("lng").asDouble());
                        recommendedHotels.add(hotel);
                    }
                    if (recommendedHotels.size() >= 5) break; // 최대 5개까지만 수집
                }
            }
        } catch (Exception e) {
            System.out.println("구글 숙소 추천 검색 실패: " + e.getMessage());
        }
        return recommendedHotels;
    }
}