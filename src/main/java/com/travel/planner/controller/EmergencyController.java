package com.travel.planner.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api/v1/emergency")
@Tag(name = "5. 긴급 안전 API", description = "GPS 기반 주변 긴급 시설 검색 (보고서 DOC-003)")
public class EmergencyController {

    // yml에서 가져옵니다.
    @Value("${google.maps.api-key}")
    private String googleApiKey;

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
}