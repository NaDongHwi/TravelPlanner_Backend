package com.travel.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.Region;
import com.travel.planner.util.PrefectureMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GoogleMapsService {

    @Value("${google.maps.api-key}")
    private String googleMapsApiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 1. 이동 시간 정보
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

    // 2. 영업시간 및 좌표 수집
    public Place getPlaceDetails(String city, String placeName, String lang) {
        Place resultPlace = new Place();
        resultPlace.setLatitude(0.0);
        resultPlace.setLongitude(0.0);
        resultPlace.setOpeningHours("영업시간 정보 없음"); // 기본값

        String targetLang = (lang != null && !lang.trim().isEmpty()) ? lang : "ko";
        String url = "https://places.googleapis.com/v1/places:searchText";

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Goog-Api-Key", googleMapsApiKey);
            // 가져올 필드를 명시 (좌표, 영업시간, 평점, 리뷰 수)
            headers.set("X-Goog-FieldMask", "places.id,places.location,places.regularOpeningHours.weekdayDescriptions,places.rating,places.userRatingCount");

            Map<String, Object> body = new HashMap<>();
            body.put("textQuery", placeName + " " + city);
            body.put("languageCode", targetLang);
            body.put("regionCode", "JP");

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            String response = restTemplate.postForObject(url, request, String.class);
            JsonNode rootNode = objectMapper.readTree(response);
            JsonNode places = rootNode.path("places");

            if (!places.isMissingNode() && places.isArray() && places.size() > 0) {
                JsonNode firstResult = places.get(0);

                double rating = firstResult.path("rating").asDouble(0.0);
                int reviewCount = firstResult.path("userRatingCount").asInt(0);

                if (rating < 3.5 || reviewCount < 20) {
                    System.out.println("[수질 검증 탈락] '" + placeName + "' (평점: " + rating + ", 리뷰: " + reviewCount + "개) -> 고품질 DB 기준 미달로 차단합니다.");
                    return resultPlace;
                }

                resultPlace.setLatitude(firstResult.path("location").path("latitude").asDouble());
                resultPlace.setLongitude(firstResult.path("location").path("longitude").asDouble());
                resultPlace.setPlaceId(firstResult.path("id").asText());

                // 영업시간 조립
                JsonNode weekdayText = firstResult.path("regularOpeningHours").path("weekdayDescriptions");
                if (!weekdayText.isMissingNode() && weekdayText.isArray()) {
                    List<String> hoursList = new ArrayList<>();
                    for (JsonNode node : weekdayText) {
                        hoursList.add(node.asText());
                    }
                    resultPlace.setOpeningHours(String.join(" | ", hoursList));
                }
            } else {
                System.out.println("장소 검색 실패 (" + placeName + " " + city + ") - 원인: 결과 없음");
            }
        } catch (Exception e) {
            System.out.println("네트워크 에러 (" + placeName + "): " + e.getMessage());
        }

        return resultPlace;
    }

    // 이름 검색 대신, 고유 Place ID를 사용해 100% 정확하게 장소 상세 정보를 가져오는 메서드
    public Place getPlaceDetailsById(String placeId, String lang) {
        Place resultPlace = new Place();
        resultPlace.setOpeningHours("영업시간 정보 없음"); // 기본값

        String targetLang = (lang != null && !lang.trim().isEmpty()) ? lang : "ko";
        // Text Search가 아닌 신버전 Place Details 엔드포인트 (GET 방식)
        String url = "https://places.googleapis.com/v1/places/" + placeId + "?languageCode=" + targetLang;

        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("X-Goog-Api-Key", googleMapsApiKey);
            // 딱 필요한 정보만 핀포인트로 요청
            headers.set("X-Goog-FieldMask", "id,location,regularOpeningHours.weekdayDescriptions,rating,userRatingCount,displayName");

            org.springframework.http.HttpEntity<Void> request = new org.springframework.http.HttpEntity<>(headers);
            org.springframework.http.ResponseEntity<String> response = restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, request, String.class);

            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(response.getBody());

            if (node != null && !node.isMissingNode()) {
                // 이미 DB에 있는 인증된 장소이므로 평점/리뷰 수 검증(수질 검증) 로직을 생략합니다.
                // (국립공원, 거리 등은 원래 평점이 누락되기도 함)
                resultPlace.setLatitude(node.path("location").path("latitude").asDouble(0.0));
                resultPlace.setLongitude(node.path("location").path("longitude").asDouble(0.0));

                // 영업시간 조립
                com.fasterxml.jackson.databind.JsonNode weekdayText = node.path("regularOpeningHours").path("weekdayDescriptions");
                if (!weekdayText.isMissingNode() && weekdayText.isArray()) {
                    List<String> hoursList = new ArrayList<>();
                    for (com.fasterxml.jackson.databind.JsonNode desc : weekdayText) {
                        hoursList.add(desc.asText());
                    }
                    resultPlace.setOpeningHours(String.join(" | ", hoursList));
                }
            }
        } catch (Exception e) {
            System.out.println("Place Details (ID) 통신 에러 (" + placeId + "): " + e.getMessage());
        }

        return resultPlace;
    }

    // 3. 리뷰 수집 도구
    public String getPlaceReviews(String city, String placeName) {
        String url = "https://places.googleapis.com/v1/places:searchText";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Goog-Api-Key", googleMapsApiKey);
            headers.set("X-Goog-FieldMask", "places.reviews"); // 리뷰만 타겟팅

            Map<String, Object> body = new HashMap<>();
            body.put("textQuery", placeName + " " + city);
            body.put("languageCode", "ko");
            body.put("regionCode", "JP");

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            String response = restTemplate.postForObject(url, request, String.class);
            JsonNode rootNode = objectMapper.readTree(response);
            JsonNode places = rootNode.path("places");

            if (!places.isMissingNode() && places.isArray() && places.size() > 0) {
                JsonNode reviews = places.get(0).path("reviews");
                StringBuilder reviewText = new StringBuilder();

                if (!reviews.isMissingNode() && reviews.isArray()) {
                    for (JsonNode review : reviews) {
                        reviewText.append(review.path("text").path("text").asText()).append("\n");
                    }
                    return reviewText.toString();
                }
            }
        } catch (Exception e) {
            System.out.println("[" + placeName + "] 리뷰 수집 실패: " + e.getMessage());
            return null;
        }
        return "리뷰 정보 없음";
    }

    // 4. 대량 자동 수집 -> Places API (New) 및 JSON 페이징 적용
    public List<Place> searchNewPlacesFromGoogle(String city, String keyword, boolean isEmergency) {
        List<Place> fetchedPlaces = new ArrayList<>();
        String url = "https://places.googleapis.com/v1/places:searchText";

        try {
            String searchQuery = city + " " + keyword;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Goog-Api-Key", googleMapsApiKey);
            headers.set("X-Goog-FieldMask", "places.id,places.displayName.text,places.formattedAddress,places.rating,places.userRatingCount,places.location,places.types,nextPageToken");

            Map<String, Object> body = new HashMap<>();
            body.put("textQuery", searchQuery);
            body.put("languageCode", "ko");
            body.put("regionCode", "JP");

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            String response = restTemplate.postForObject(url, request, String.class);
            JsonNode root = objectMapper.readTree(response);

            // 1페이지 처리
            parsePlacesFromNode(root, fetchedPlaces, city, isEmergency);

            String nextToken = root.path("nextPageToken").asText(null);
            int pageCount = 1;

            while (nextToken != null && !nextToken.isEmpty() && pageCount < 3) {
                Thread.sleep(2000);
                System.out.println("➡️ [" + keyword + "] 다음 페이지 토큰 발견! " + (pageCount + 1) + "페이지 연속 수집 중...");

                // body에 pageToken 추가 후 다음 페이지 요청
                body.put("pageToken", nextToken);
                HttpEntity<Map<String, Object>> nextRequest = new HttpEntity<>(body, headers);
                String nextResponse = restTemplate.postForObject(url, nextRequest, String.class);
                JsonNode nextRoot = objectMapper.readTree(nextResponse);

                parsePlacesFromNode(nextRoot, fetchedPlaces, city, isEmergency);
                nextToken = nextRoot.path("nextPageToken").asText(null);
                pageCount++;
            }

        } catch (Exception e) {
            System.out.println("구글 장소 크롤링 실패: " + e.getMessage());
        }
        return fetchedPlaces;
    }

    // 5. 수질 관리 필터 및 우편번호 트랩 방어
    private void parsePlacesFromNode(JsonNode root, List<Place> fetchedPlaces, String city, boolean isEmergency) {
        JsonNode results = root.path("places"); // 신버전 API는 배열 이름이 'places'
        if (results.isMissingNode() || !results.isArray()) return;

        String coreCityName = city.replace("일본", "").replaceAll("〒[0-9]{3}-[0-9]{4}", "").trim();
        if (coreCityName.contains(" ")) {
            coreCityName = coreCityName.split(" ")[0];
        }

        String safeCityName = coreCityName;
        if (safeCityName.length() >= 2) {
            if (safeCityName.endsWith("도") && !safeCityName.equals("홋카이도")) {
                safeCityName = safeCityName.substring(0, safeCityName.length() - 1);
            } else if (safeCityName.endsWith("부") || safeCityName.endsWith("현") || safeCityName.endsWith("시")) {
                safeCityName = safeCityName.substring(0, safeCityName.length() - 1);
            }
        }

        for (JsonNode node : results) {
            // 신버전 API JSON 경로 매핑
            double rating = node.path("rating").asDouble(0.0);
            int reviewCount = node.path("userRatingCount").asInt(0);
            String placeName = node.path("displayName").path("text").asText();
            String address = node.path("formattedAddress").asText();

            if (address == null) continue;

            String lowerAddr = address.toLowerCase();
            if (lowerAddr.contains("대한민국") || lowerAddr.contains("한국") ||
                    lowerAddr.contains("korea") || lowerAddr.contains("seoul") || lowerAddr.contains("서울")) {
                continue;
            }

            if (!address.contains(safeCityName)) {
                continue;
            }

            boolean isHighQuality = (rating >= 4.0 && reviewCount >= 100);
            boolean isSuperLandmark = (rating >= 3.6 && reviewCount >= 300);
            boolean isEmergencyPass = isEmergency && (rating >= 1.5 && reviewCount >= 10);

            if (isHighQuality || isSuperLandmark || isEmergencyPass) {
                Place place = new Place();
                place.setPlaceId(node.path("id").asText());
                place.setName(placeName);
                place.setCity(city);
                place.setLatitude(node.path("location").path("latitude").asDouble());
                place.setLongitude(node.path("location").path("longitude").asDouble());
                place.setCategory(determineCategoryFromTypes(node.path("types")));

                fetchedPlaces.add(place);
            }
        }
    }

    // 6. Geocoding API 기반 자체 지명 정제 엔진 (에러 상세 출력 로직 추가됨)
    public String getFormalizedJapanCity(String cityInput) {
        try {
            String url = "https://maps.googleapis.com/maps/api/geocode/json?address={address}&components=country:JP&key={key}&language=ko";
            String response = restTemplate.getForObject(url, String.class, cityInput, googleMapsApiKey);
            JsonNode root = objectMapper.readTree(response);

            // 구글이 반환한 실제 상태 코드 확인
            String status = root.path("status").asText();

            if ("OK".equals(status)) {
                String formattedAddress = root.path("results").get(0).path("formatted_address").asText();
                Region recognizedRegion = PrefectureMapper.getRegionFromAddress(formattedAddress);
                System.out.println("[지명 검증 완료] 정식 주소: " + formattedAddress + " -> 판정 권역: " + recognizedRegion.name());
                return formattedAddress;
            } else {
                // 구글이 뱉어낸 진짜 이유를 콘솔에 빨간 글씨로 출력
                String errorMessage = root.path("error_message").asText("이유 없음");
                System.err.println("[구글 Geocoding API 에러] 상태: " + status + " / 사유: " + errorMessage);
                throw new RuntimeException("구글 API 거부: " + status);
            }
        } catch (Exception e) {
            throw new RuntimeException("지명 정밀 검증 실패: " + e.getMessage());
        }
    }

    // 7. 자동 분류 엔진
    private String determineCategoryFromTypes(JsonNode typesNode) {
        if (typesNode == null || !typesNode.isArray()) return "관광지"; // 기본값

        for (JsonNode typeNode : typesNode) {
            String type = typeNode.asText().toLowerCase();
            if (type.equals("lodging") || type.contains("hotel")) return "숙소";
            if (type.equals("train_station") || type.equals("transit_station") || type.equals("airport") || type.equals("subway_station") || type.equals("bus_station")) return "교통";
            if (type.equals("restaurant") || type.equals("cafe") || type.equals("food") || type.equals("bakery") || type.equals("bar") || type.equals("meal_takeaway")) return "식음";
            if (type.equals("shopping_mall") || type.equals("department_store") || type.equals("supermarket") || type.equals("clothing_store") || type.equals("store")) return "쇼핑";
        }
        return "관광지";
    }

    // 8. 숙소 역제안용 구글 맵스 숙소 검색기
    public List<Place> searchRecommendedHotels(String city) {
        List<Place> recommendedHotels = new ArrayList<>();
        String url = "https://places.googleapis.com/v1/places:searchText";

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Goog-Api-Key", googleMapsApiKey);
            headers.set("X-Goog-FieldMask", "places.displayName.text,places.location,places.rating,places.userRatingCount");

            Map<String, Object> body = new HashMap<>();
            body.put("textQuery", city + " 유명 호텔");
            body.put("languageCode", "ko");
            body.put("regionCode", "JP");

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            String response = restTemplate.postForObject(url, request, String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode places = root.path("places");

            if (!places.isMissingNode() && places.isArray()) {
                for (JsonNode node : places) {
                    double rating = node.path("rating").asDouble(0.0);
                    int reviewCount = node.path("userRatingCount").asInt(0);

                    if (rating >= 3.5 && reviewCount >= 50) {
                        Place hotel = new Place();
                        hotel.setName(node.path("displayName").path("text").asText());
                        hotel.setCity(city);
                        hotel.setLatitude(node.path("location").path("latitude").asDouble());
                        hotel.setLongitude(node.path("location").path("longitude").asDouble());
                        recommendedHotels.add(hotel);
                    }
                    if (recommendedHotels.size() >= 5) break;
                }
            }
        } catch (Exception e) {
            System.out.println("구글 숙소 추천 검색 실패: " + e.getMessage());
        }
        return recommendedHotels;
    }

    // 9. 프론트엔드 표시용 상세 정보 실시간 조회 (DB 저장 안 함, DTO에만 담기 위함)
    public String[] getPlaceDetailsForDisplay(String placeId, String lang, java.time.LocalDate targetDate) {
        String[] details = new String[]{"주소 정보 없음", "전화번호 정보 없음", "영업시간 정보 없음"};

        if (placeId == null || placeId.isEmpty() || placeId.equals("DUMMY_FREE_TIME")) {
            return details;
        }

        String targetLang = (lang != null && !lang.trim().isEmpty()) ? lang : "ko";
        String url = "https://places.googleapis.com/v1/places/" + placeId + "?languageCode=" + targetLang;

        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("X-Goog-Api-Key", googleMapsApiKey);
            headers.set("X-Goog-FieldMask", "formattedAddress,nationalPhoneNumber,regularOpeningHours.weekdayDescriptions");

            org.springframework.http.HttpEntity<Void> request = new org.springframework.http.HttpEntity<>(headers);
            org.springframework.http.ResponseEntity<String> response = restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, request, String.class);

            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(response.getBody());

            if (node != null && !node.isMissingNode()) {
                if (node.has("formattedAddress")) details[0] = node.get("formattedAddress").asText();
                if (node.has("nationalPhoneNumber")) details[1] = node.get("nationalPhoneNumber").asText();

                com.fasterxml.jackson.databind.JsonNode weekdayText = node.path("regularOpeningHours").path("weekdayDescriptions");
                if (!weekdayText.isMissingNode() && weekdayText.isArray() && weekdayText.size() > 0) {

                    // 방문할 날짜의 요일을 한국어로 구합니다.
                    String[] koreanDays = {"월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"};
                    int dayOfWeekValue = targetDate.getDayOfWeek().getValue(); // 1(월) ~ 7(일)
                    String targetDayName = koreanDays[dayOfWeekValue - 1];

                    boolean isFound = false;
                    for (com.fasterxml.jackson.databind.JsonNode descNode : weekdayText) {
                        String desc = descNode.asText();
                        // 구글이 준 배열 중 "토요일: 오전 9:00~오후 10:00" 처럼 해당 요일이 포함된 문장만 추출
                        if (desc.contains(targetDayName)) {
                            details[2] = desc;
                            isFound = true;
                            break;
                        }
                    }

                    // 만약 구글 응답에 예외가 생겨 요일을 못 찾으면 일단 7일 전체를 던져줌
                    if (!isFound) {
                        details[2] = weekdayText.get(0).asText();
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Display Details 실시간 통신 에러 (" + placeId + "): " + e.getMessage());
        }

        return details;
    }
}