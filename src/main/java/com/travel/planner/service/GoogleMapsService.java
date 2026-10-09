package com.travel.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.Region;
import com.travel.planner.util.OpeningHours;
import com.travel.planner.util.PrefectureMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class GoogleMapsService {

    private static final String TEXT_SEARCH_URL = "https://places.googleapis.com/v1/places:searchText";

    /**
     * 대량 수집 시 요청하는 필드.
     * 영업시간(구조화 periods 포함)·전화번호·평점을 여기서 같이 받아 DB 에 저장해 두면,
     * 일정 생성 때 타임라인 항목마다 Place Details 를 다시 부를 필요가 없다.
     * (rating/userRatingCount 를 이미 요청하고 있어 과금 등급은 그대로일 가능성이 높지만, 콘솔의 SKU 표로 한 번 확인할 것)
     */
    private static final String COLLECT_FIELD_MASK = String.join(",",
            "places.id", "places.displayName.text", "places.formattedAddress", "places.rating",
            "places.userRatingCount", "places.location", "places.types", "places.primaryType",
            "places.nationalPhoneNumber", "places.regularOpeningHours.weekdayDescriptions",
            "places.regularOpeningHours.periods", "nextPageToken");

    private static final String DETAIL_FIELD_MASK = String.join(",",
            "id", "displayName.text", "location", "formattedAddress", "nationalPhoneNumber", "rating", "userRatingCount",
            "regularOpeningHours.weekdayDescriptions", "regularOpeningHours.periods");

    @Value("${google.maps.api-key}")
    private String googleMapsApiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 지오코딩 결과는 도시 이름이 같으면 바뀌지 않으므로 서버가 떠 있는 동안 재사용한다.
    private final Map<String, String> formalizedCityCache = new ConcurrentHashMap<>();
    private final Map<String, List<double[]>> cityGridCache = new ConcurrentHashMap<>();

    private HttpHeaders placesHeaders(String fieldMask) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Goog-Api-Key", googleMapsApiKey);
        headers.set("X-Goog-FieldMask", fieldMask);
        return headers;
    }

    /** regularOpeningHours 노드 → 표시용 문자열 + 구조화 periods JSON */
    private void applyOpeningHours(JsonNode regularOpeningHours, Place target) {
        if (regularOpeningHours == null || regularOpeningHours.isMissingNode()) return;

        JsonNode weekdayText = regularOpeningHours.path("weekdayDescriptions");
        if (weekdayText.isArray() && !weekdayText.isEmpty()) {
            List<String> hoursList = new ArrayList<>();
            for (JsonNode node : weekdayText) hoursList.add(node.asText());
            target.setOpeningHours(String.join(" | ", hoursList));
        }
        JsonNode periods = regularOpeningHours.path("periods");
        if (periods.isArray() && !periods.isEmpty()) {
            target.setOpeningPeriods(periods.toString());
        }
    }

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

            String url = "https://maps.googleapis.com/maps/api/directions/json?origin={origin}&destination={destination}&waypoints={waypoints}&key={key}&language=ko";
            String response = restTemplate.getForObject(url, String.class, origin, destination, waypoints.toString(), googleMapsApiKey);
            JsonNode rootNode = objectMapper.readTree(response);

            JsonNode legs = rootNode.path("routes").get(0).path("legs");
            StringBuilder timeInfo = new StringBuilder();

            for (int i = 0; i < legs.size(); i++) {
                String duration = legs.get(i).path("duration").path("text").asText();
                timeInfo.append("- ").append(route.get(i).getName())
                        .append(" -> ")
                        .append(route.get(i + 1).getName())
                        .append(" (실제 소요 시간: ").append(duration).append(")\n");
            }

            return timeInfo.toString();

        } catch (Exception e) {
            return "구글 맵스 연동 오류로 실제 시간 측정 불가 (직선거리로 시간 배분 요망)";
        }
    }

    /**
     * 2. 이름으로 장소 한 곳 찾기 (평점·리뷰 수 필터 없음).
     * 사용자가 직접 입력한 숙소처럼 "품질과 무관하게 반드시 찾아야 하는" 장소에 쓴다.
     * 이전에는 대량 수집용 검색(평점 4.0·리뷰 100건 필터)을 그대로 써서 사용자 숙소가 탈락하곤 했다.
     */
    public Place findPlaceByText(String query, String lang) {
        if (query == null || query.isBlank()) return null;
        String targetLang = (lang != null && !lang.trim().isEmpty()) ? lang : "ko";

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("textQuery", query);
            body.put("languageCode", targetLang);
            body.put("regionCode", "JP");
            body.put("pageSize", 1);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body,
                    placesHeaders("places.id,places.displayName.text,places.location,places.formattedAddress,places.nationalPhoneNumber"));
            String response = restTemplate.postForObject(TEXT_SEARCH_URL, request, String.class);
            JsonNode places = objectMapper.readTree(response).path("places");

            if (places.isArray() && !places.isEmpty()) {
                JsonNode node = places.get(0);
                if (node.path("location").isMissingNode()) return null;
                Place place = new Place();
                place.setPlaceId(node.path("id").asText());
                place.setName(node.path("displayName").path("text").asText(query));
                place.setLatitude(node.path("location").path("latitude").asDouble());
                place.setLongitude(node.path("location").path("longitude").asDouble());
                place.setAddress(node.path("formattedAddress").asText(null));
                place.setPhone(node.path("nationalPhoneNumber").asText(null));
                place.setLastUpdated(LocalDateTime.now());
                return place;
            }
        } catch (Exception e) {
            System.out.println("장소 검색 실패 (" + query + "): " + e.getMessage());
        }
        return null;
    }

    /**
     * 3. 고유 Place ID 로 상세 정보 조회.
     * 통신 실패 시 null, 성공 시 조회된 값만 채운 Place 를 돌려준다 (없는 값은 null).
     */
    public Place getPlaceDetailsById(String placeId, String lang) {
        if (placeId == null || placeId.isBlank() || placeId.startsWith("DUMMY_") || placeId.startsWith("AIRPORT_")) return null;

        String targetLang = (lang != null && !lang.trim().isEmpty()) ? lang : "ko";
        String url = "https://places.googleapis.com/v1/places/{placeId}?languageCode={lang}";

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Goog-Api-Key", googleMapsApiKey);
            headers.set("X-Goog-FieldMask", DETAIL_FIELD_MASK);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<Void>(headers), String.class, placeId, targetLang);
            JsonNode node = objectMapper.readTree(response.getBody());
            if (node == null || node.isMissingNode() || node.path("id").isMissingNode()) return null;

            Place result = new Place();
            result.setPlaceId(placeId);
            if (!node.path("location").isMissingNode()) {
                result.setLatitude(node.path("location").path("latitude").asDouble());
                result.setLongitude(node.path("location").path("longitude").asDouble());
            }
            result.setAddress(node.path("formattedAddress").asText(null));
            result.setPhone(node.path("nationalPhoneNumber").asText(null));
            if (node.has("rating")) result.setRating(node.path("rating").asDouble());
            if (node.has("userRatingCount")) result.setUserRatingCount(node.path("userRatingCount").asInt());
            applyOpeningHours(node.path("regularOpeningHours"), result);
            return result;
        } catch (Exception e) {
            System.out.println("Place Details (ID) 통신 에러 (" + placeId + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * 상세 조회 결과를 기존 장소에 반영한다. 좌표는 덮어쓰지 않는다.
     * (이전 갱신 배치는 "이름 검색 1순위 결과"의 좌표로 덮어써서 다른 장소로 바뀌는 일이 있었다)
     * @return 조회에 성공해 반영했으면 true
     */
    public boolean refreshPlace(Place place, String lang) {
        Place details = getPlaceDetailsById(place.getPlaceId(), lang);
        if (details == null) return false;

        if (details.getOpeningHours() != null) {
            place.setOpeningHours(details.getOpeningHours());
        } else if (OpeningHours.isUnknownText(place.getOpeningHours())) {
            place.setOpeningHours(OpeningHours.CHECKED_NO_DATA);   // 조회했지만 구글에도 없음 → 다시 조회하지 않도록 표시
        }
        if (details.getOpeningPeriods() != null) place.setOpeningPeriods(details.getOpeningPeriods());
        if (details.getAddress() != null) place.setAddress(details.getAddress());
        if (details.getPhone() != null) place.setPhone(details.getPhone());
        if (details.getRating() != null) place.setRating(details.getRating());
        if (details.getUserRatingCount() != null) place.setUserRatingCount(details.getUserRatingCount());
        place.setLastUpdated(LocalDateTime.now());
        return true;
    }

    // 4. 리뷰 수집 (Place ID 기준: 이름 검색은 동명의 다른 가게 리뷰를 가져올 수 있다)
    public String getPlaceReviewsById(String placeId) {
        if (placeId == null || placeId.isBlank()) return null;
        String url = "https://places.googleapis.com/v1/places/{placeId}?languageCode=ko";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Goog-Api-Key", googleMapsApiKey);
            headers.set("X-Goog-FieldMask", "reviews");

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<Void>(headers), String.class, placeId);
            JsonNode reviews = objectMapper.readTree(response.getBody()).path("reviews");

            if (reviews.isArray() && !reviews.isEmpty()) {
                StringBuilder reviewText = new StringBuilder();
                for (JsonNode review : reviews) {
                    String text = review.path("text").path("text").asText("");
                    if (text.length() > 300) text = text.substring(0, 300);   // 프롬프트 길이 관리
                    if (!text.isBlank()) reviewText.append(text.replace('\n', ' ')).append("\n");
                }
                return reviewText.toString();
            }
        } catch (Exception e) {
            System.out.println("[" + placeId + "] 리뷰 수집 실패: " + e.getMessage());
            return null;
        }
        return "리뷰 정보 없음";
    }

    // 구글 Viewport를 활용하여 도시 크기에 딱 맞는 5개의 그물망(Grid) 좌표와 반경을 생성합니다.
    public List<double[]> getCityGrid(String cityInput) {
        List<double[]> cached = cityGridCache.get(cityInput);
        if (cached != null) return cached;

        List<double[]> gridPoints = new ArrayList<>();
        String url = "https://maps.googleapis.com/maps/api/geocode/json?address={address}&components=country:JP&key={key}&language=ko";

        try {
            String response = restTemplate.getForObject(url, String.class, cityInput, googleMapsApiKey);
            JsonNode root = objectMapper.readTree(response);

            if ("OK".equals(root.path("status").asText())) {
                JsonNode geometry = root.path("results").get(0).path("geometry");

                double centerLat = geometry.path("location").path("lat").asDouble();
                double centerLng = geometry.path("location").path("lng").asDouble();

                JsonNode viewport = geometry.path("viewport");
                if (!viewport.isMissingNode()) {
                    double neLat = viewport.path("northeast").path("lat").asDouble();
                    double neLng = viewport.path("northeast").path("lng").asDouble();
                    double swLat = viewport.path("southwest").path("lat").asDouble();
                    double swLng = viewport.path("southwest").path("lng").asDouble();

                    double latDiff = neLat - swLat;
                    double lngDiff = neLng - swLng;
                    double maxDiff = Math.max(latDiff, lngDiff);

                    double radius = (maxDiff * 111000) / 3.0;
                    if (radius > 50000) radius = 50000;
                    if (radius < 2000) radius = 2000;

                    gridPoints.add(new double[]{centerLat, centerLng, radius});
                    double latOffset = (neLat - centerLat) / 1.5;
                    double lngOffset = (neLng - centerLng) / 1.5;

                    gridPoints.add(new double[]{centerLat + latOffset, centerLng, radius});
                    gridPoints.add(new double[]{centerLat - latOffset, centerLng, radius});
                    gridPoints.add(new double[]{centerLat, centerLng + lngOffset, radius});
                    gridPoints.add(new double[]{centerLat, centerLng - lngOffset, radius});
                } else {
                    gridPoints.add(new double[]{centerLat, centerLng, 10000});
                }
                cityGridCache.put(cityInput, gridPoints);
            }
        } catch (Exception e) {
            System.out.println("그리드 추출 실패: " + e.getMessage());
        }
        return gridPoints;
    }

    /**
     * 5. 대량 자동 수집.
     * 긴급 수집(isEmergency=true)은 일정 생성 요청 중에 도는 것이라 1페이지만 받는다.
     * (이전에는 페이지마다 2초씩 쉬면서 3페이지를 받아 요청 한 번에 수십 초가 걸렸다)
     */
    public List<Place> searchNewPlacesFromGoogle(String city, String formalizedCity, double lat, double lng, double radius, String searchItem, boolean isEmergency) {
        List<Place> fetchedPlaces = new ArrayList<>();
        int maxPages = isEmergency ? 1 : 3;

        try {
            HttpHeaders headers = placesHeaders(COLLECT_FIELD_MASK);

            Map<String, Object> body = new HashMap<>();
            body.put("languageCode", "ko");
            body.put("regionCode", "JP");

            Map<String, Object> center = new HashMap<>();
            center.put("latitude", lat);
            center.put("longitude", lng);
            Map<String, Object> circle = new HashMap<>();
            circle.put("center", center);
            circle.put("radius", radius);

            Map<String, Object> locationBias = new HashMap<>();
            locationBias.put("circle", circle);
            body.put("locationBias", locationBias);

            // TYPE 검색 시 영어 단어 강제 삽입 방지!
            if (searchItem != null && searchItem.startsWith("[TYPE]")) {
                String type = searchItem.replace("[TYPE]", "");
                body.put("includedType", type);
                body.put("textQuery", formalizedCity);
            } else {
                String text = (searchItem != null) ? searchItem.replace("[TEXT]", "") : "";
                body.put("textQuery", formalizedCity + " " + text);
            }

            String response = restTemplate.postForObject(TEXT_SEARCH_URL, new HttpEntity<>(body, headers), String.class);
            JsonNode root = objectMapper.readTree(response);

            parsePlacesFromNode(root, fetchedPlaces, city, formalizedCity, isEmergency);

            String nextToken = root.path("nextPageToken").asText(null);
            int pageCount = 1;

            while (nextToken != null && !nextToken.isEmpty() && pageCount < maxPages) {
                Thread.sleep(2000);
                System.out.println("➡️ [" + searchItem + "] 다음 페이지 토큰 발견! " + (pageCount + 1) + "페이지 수집 중...");

                body.put("pageToken", nextToken);
                String nextResponse = restTemplate.postForObject(TEXT_SEARCH_URL, new HttpEntity<>(body, headers), String.class);
                JsonNode nextRoot = objectMapper.readTree(nextResponse);

                parsePlacesFromNode(nextRoot, fetchedPlaces, city, formalizedCity, isEmergency);
                nextToken = nextRoot.path("nextPageToken").asText(null);
                pageCount++;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.out.println("구글 장소 크롤링 실패: " + e.getMessage());
        }
        return fetchedPlaces;
    }

    // 6. 수질 관리 필터
    private void parsePlacesFromNode(JsonNode root, List<Place> fetchedPlaces, String city, String formalizedCity, boolean isEmergency) {
        JsonNode results = root.path("places");
        if (results.isMissingNode() || !results.isArray()) return;

        String cleanFormalCity = formalizedCity.replace("일본", "").trim();
        String[] formalParts = cleanFormalCity.split(" ");
        // 줄바꿈 버그 해결
        String strictCityName = formalParts[formalParts.length - 1];

        for (JsonNode node : results) {
            double rating = node.path("rating").asDouble(0.0);
            int reviewCount = node.path("userRatingCount").asInt(0);
            String placeName = node.path("displayName").path("text").asText();
            String address = node.path("formattedAddress").asText("");

            if (address.isEmpty() || address.contains("대한민국") || address.contains("한국")) continue;
            if (node.path("location").isMissingNode()) continue;

            // 영문 주소 컷오프 문제 방어 (일본 텍스트 제거 후 한글 검사)
            String addressWithoutJapan = address.replace("일본", "").trim();
            boolean hasKorean = addressWithoutJapan.matches(".*[가-힣]+.*");

            if (hasKorean && !address.contains(city) && !address.contains(strictCityName)) {
                continue;
            }

            boolean isHighQuality = (rating >= 4.0 && reviewCount >= 100);
            boolean isSuperLandmark = (rating >= 3.6 && reviewCount >= 300);
            // 긴급 수집이라도 최소한의 품질은 지킨다 (이전 기준 1.5점/10건은 사실상 무필터였다)
            boolean isEmergencyPass = isEmergency && (rating >= 3.5 && reviewCount >= 30);

            if (isHighQuality || isSuperLandmark || isEmergencyPass) {
                Place place = new Place();
                place.setPlaceId(node.path("id").asText());
                place.setName(placeName);
                place.setCity(city);
                place.setLatitude(node.path("location").path("latitude").asDouble());
                place.setLongitude(node.path("location").path("longitude").asDouble());
                place.setCategory(determineCategory(node.path("primaryType").asText(""), node.path("types")));
                place.setAddress(address);
                place.setPhone(node.path("nationalPhoneNumber").asText(null));
                place.setRating(rating);
                place.setUserRatingCount(reviewCount);
                applyOpeningHours(node.path("regularOpeningHours"), place);
                // 수집 시각을 남겨야 30일 갱신 배치가 "한 번도 갱신 안 된 데이터"로 보고 전부 재조회하지 않는다.
                place.setLastUpdated(LocalDateTime.now());

                fetchedPlaces.add(place);
            }
        }
    }

    // 7. Geocoding API 기반 자체 지명 정제 엔진
    public String getFormalizedJapanCity(String cityInput) {
        String cached = formalizedCityCache.get(cityInput);
        if (cached != null) return cached;

        try {
            String url = "https://maps.googleapis.com/maps/api/geocode/json?address={address}&components=country:JP&key={key}&language=ko";
            String response = restTemplate.getForObject(url, String.class, cityInput, googleMapsApiKey);
            JsonNode root = objectMapper.readTree(response);

            String status = root.path("status").asText();

            if ("OK".equals(status)) {
                String formattedAddress = root.path("results").get(0).path("formatted_address").asText();
                Region recognizedRegion = PrefectureMapper.getRegionFromAddress(formattedAddress);
                System.out.println("[지명 검증 완료] 정식 주소: " + formattedAddress + " -> 판정 권역: " + recognizedRegion.name());
                formalizedCityCache.put(cityInput, formattedAddress);
                return formattedAddress;
            } else {
                String errorMessage = root.path("error_message").asText("이유 없음");
                System.err.println("[구글 Geocoding API 에러] 상태: " + status + " / 사유: " + errorMessage);
                throw new RuntimeException("구글 API 거부: " + status);
            }
        } catch (Exception e) {
            throw new RuntimeException("지명 정밀 검증 실패: " + e.getMessage());
        }
    }

    /**
     * 8. 자동 분류 엔진.
     * primaryType(대표 유형)을 먼저 보고, 없으면 types 배열을 본다.
     * 테마파크·놀이공원은 "테마파크"로 분류해 체류 시간(8시간)이 제대로 잡히게 한다.
     */
    private String determineCategory(String primaryType, JsonNode typesNode) {
        String byPrimary = categoryOf(primaryType == null ? "" : primaryType.toLowerCase());
        if (byPrimary != null) return byPrimary;

        if (typesNode != null && typesNode.isArray()) {
            for (JsonNode typeNode : typesNode) {
                if ("amusement_park".equals(typeNode.asText().toLowerCase())) return "테마파크";
            }
            for (JsonNode typeNode : typesNode) {
                String category = categoryOf(typeNode.asText().toLowerCase());
                if (category != null) return category;
            }
        }
        return "관광지";
    }

    private String categoryOf(String type) {
        if (type.isEmpty()) return null;
        if (type.equals("amusement_park") || type.equals("water_park")) return "테마파크";
        if (type.equals("lodging") || type.contains("hotel") || type.equals("hostel") || type.equals("guest_house") || type.equals("japanese_inn")) return "숙소";
        if (type.equals("train_station") || type.equals("transit_station") || type.equals("airport") || type.equals("international_airport")
                || type.equals("subway_station") || type.equals("bus_station")) return "교통";
        if (type.endsWith("restaurant") || type.equals("cafe") || type.equals("coffee_shop") || type.equals("food") || type.equals("bakery")
                || type.equals("bar") || type.equals("meal_takeaway") || type.equals("izakaya_restaurant")) return "식음";
        if (type.equals("shopping_mall") || type.equals("department_store") || type.equals("supermarket") || type.equals("clothing_store")
                || type.equals("store") || type.endsWith("_store") || type.equals("market")) return "쇼핑";
        if (type.equals("tourist_attraction") || type.equals("museum") || type.equals("park") || type.equals("art_gallery")
                || type.equals("zoo") || type.equals("aquarium") || type.equals("historical_landmark")) return "관광지";
        return null;
    }

    // 9. 숙소 역제안용 구글 맵스 숙소 검색기
    /**
     * 도시의 추천 숙소. 먼저 유형을 숙박 시설(lodging)로 제한해 찾고, 결과가 없거나 요청이 거부되면 제한 없이 한 번 더 찾는다.
     * (제한 없이 "○○ 유명 호텔"로만 찾으면 호텔이 아닌 장소가 섞여 올 수 있다)
     */
    public List<Place> searchRecommendedHotels(String city) {
        List<Place> hotels = searchHotels(city, true);
        if (hotels.isEmpty()) {
            System.out.println("[숙소] " + city + ": 숙박 유형 제한 검색 결과가 없어 제한 없이 다시 검색합니다.");
            hotels = searchHotels(city, false);
        }
        return hotels;
    }

    private List<Place> searchHotels(String city, boolean lodgingOnly) {
        List<Place> recommendedHotels = new ArrayList<>();

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("textQuery", city + " 유명 호텔");
            body.put("languageCode", "ko");
            body.put("regionCode", "JP");
            if (lodgingOnly) {
                body.put("includedType", "lodging");
                body.put("strictTypeFiltering", true);
            }

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body,
                    placesHeaders("places.id,places.displayName.text,places.location,places.rating,places.userRatingCount,places.formattedAddress,places.nationalPhoneNumber"));
            String response = restTemplate.postForObject(TEXT_SEARCH_URL, request, String.class);
            JsonNode places = objectMapper.readTree(response).path("places");

            if (!places.isMissingNode() && places.isArray()) {
                for (JsonNode node : places) {
                    double rating = node.path("rating").asDouble(0.0);
                    int reviewCount = node.path("userRatingCount").asInt(0);

                    if (rating >= 3.5 && reviewCount >= 50 && !node.path("location").isMissingNode()) {
                        Place hotel = new Place();
                        hotel.setPlaceId(node.path("id").asText());
                        hotel.setName(node.path("displayName").path("text").asText());
                        hotel.setCity(city);
                        hotel.setCategory("숙소");
                        hotel.setLatitude(node.path("location").path("latitude").asDouble());
                        hotel.setLongitude(node.path("location").path("longitude").asDouble());
                        hotel.setAddress(node.path("formattedAddress").asText(null));
                        hotel.setPhone(node.path("nationalPhoneNumber").asText(null));
                        hotel.setRating(rating);
                        hotel.setUserRatingCount(reviewCount);
                        hotel.setLastUpdated(LocalDateTime.now());
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
}
