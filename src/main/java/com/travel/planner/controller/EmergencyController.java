package com.travel.planner.controller;

import com.travel.planner.util.DistanceUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/emergency")
@Tag(name = "5. 긴급 안전 API", description = "GPS 기반 주변 긴급 시설 검색 및 비상연락망 제공 (보고서 DOC-003, 기획 2번)")
public class EmergencyController {

    // yml에서 가져옵니다.
    @Value("${google.maps.api-key}")
    private String googleApiKey;

    // 일본 내 대사관 및 영사관
    private static final List<Embassy> EMBASSIES = Arrays.asList(
            new Embassy("주일본 대한민국 대사관 (도쿄)", "03-3452-7611", 35.6531, 139.7335),
            new Embassy("주오사카 대한민국 총영사관", "06-4256-2345", 34.6693, 135.4993),
            new Embassy("주후쿠오카 대한민국 총영사관", "092-771-0461", 33.5898, 130.3756),
            new Embassy("주나고야 대한민국 총영사관", "052-586-9221", 35.1709, 136.8815),
            new Embassy("주삿포로 대한민국 총영사관", "011-218-0288", 43.0621, 141.3544),
            new Embassy("주요코하마 대한민국 총영사관", "045-621-4531", 35.4419, 139.6457),
            new Embassy("주센다이 대한민국 총영사관", "022-221-2751", 38.2682, 140.8694),
            new Embassy("주니가타 대한민국 총영사관", "025-255-5555", 37.9161, 139.0364),
            new Embassy("주히로시마 대한민국 총영사관", "082-568-0502", 34.3906, 132.4633),
            new Embassy("주고베 대한민국 총영사관", "078-221-4853", 34.6974, 135.1932)
    );

    // 1. 기존 구글 맵스 주변 응급실/경찰서 검색 API
    @GetMapping("/nearby")
    @Operation(summary = "5km 반경 긴급 시설 검색", description = "현재 GPS 좌표 기반으로 주변 응급실/경찰서를 검색합니다.")
    public ResponseEntity<String> getNearbyEmergency(@RequestParam double lat, @RequestParam double lng) {

        String url = String.format(
                "https://maps.googleapis.com/maps/api/place/nearbysearch/json?location=%f,%f&radius=5000&type=hospital|police&key=%s&language=ko",
                lat, lng, googleApiKey
        );

        RestTemplate restTemplate = new RestTemplate();
        String response = restTemplate.getForObject(url, String.class);

        return ResponseEntity.ok(response);
    }

    // 2. 비상연락망 제공 로직
    @GetMapping("/contact")
    @Operation(summary = "긴급 비상연락망 조회", description = "119, 110 번호 및 현재 GPS 기반 가장 가까운 대사관 3곳 반환")
    public ResponseEntity<Map<String, Object>> getEmergencyContacts(
            @RequestParam(required = false, defaultValue = "0.0") double lat,
            @RequestParam(required = false, defaultValue = "0.0") double lng) {

        Map<String, Object> response = new LinkedHashMap<>();

        // [기본 제공 정보]
        response.put("구급_소방", "119");
        response.put("일본_경찰", "110");

        // [예외] 위치 정보 제공 거부 시 (프론트에서 0.0을 보냈을 경우)
        if (lat == 0.0 && lng == 0.0) {
            response.put("대사관_안내", Collections.singletonList(
                    Map.of("name", EMBASSIES.get(0).name, "phone", EMBASSIES.get(0).phone, "note", "위치 정보 거부로 대표 대사관 1곳만 노출됩니다.")
            ));
            return ResponseEntity.ok(response);
        }

        // [기본] GPS 기반 가장 가까운 대사관 3곳 필터링
        List<Map<String, Object>> nearestEmbassies = EMBASSIES.stream()
                .sorted(Comparator.comparingDouble(e -> DistanceUtil.calculateDistance(lat, lng, e.lat, e.lng)))
                .limit(3) // 3곳 필터링 노출
                .map(e -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", e.name);
                    map.put("phone", e.phone);
                    map.put("distance_km", Math.round(DistanceUtil.calculateDistance(lat, lng, e.lat, e.lng) * 10) / 10.0);
                    return map;
                })
                .collect(Collectors.toList());

        response.put("대사관_안내", nearestEmbassies);
        return ResponseEntity.ok(response);
    }

    // 내부 클래스로 대사관 데이터 구조화
    private static class Embassy {
        String name; String phone; double lat; double lng;
        Embassy(String name, String phone, double lat, double lng) {
            this.name = name; this.phone = phone; this.lat = lat; this.lng = lng;
        }
    }
}