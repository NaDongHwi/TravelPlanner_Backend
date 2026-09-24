package com.travel.planner.controller;

import com.travel.planner.entity.Place;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.service.AdminAsyncService;
import com.travel.planner.service.GoogleMapsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "🔒 관리자 전용 데이터 정제 API", description = "오프라인 AI 데이터 파이프라인 인리치먼트 엔진 (담당: 나동휘)")
public class AdminController {

    private final PlaceRepository placeRepository;
    private final GoogleMapsService googleMapsService;
    private final AdminAsyncService adminAsyncService; // 비동기 서비스 주입

    @PostMapping("/enrich-themes")
    @Operation(summary = "오프라인 AI 테마 자동 분류 및 인리치먼트 구동 (백그라운드 자동화)")
    public ResponseEntity<String> enrichMissingThemes() {
        if (adminAsyncService.isEnriching()) {
            return ResponseEntity.badRequest().body("이미 테마 인리치먼트 작업이 실행 중입니다.");
        }

        adminAsyncService.runThemeEnrichment(); // 비동기 호출
        return ResponseEntity.ok("자동 테마 인리치먼트 백그라운드 작업이 시작되었습니다. 429 에러 발생 시 1분간 자동 휴식 후 재시도합니다.");
    }

    @PostMapping("/enrich-themes/stop")
    @Operation(summary = "실행 중인 테마 자동 분류 작업 강제 중지")
    public ResponseEntity<String> stopEnriching() {
        if (!adminAsyncService.isEnriching()) {
            return ResponseEntity.ok("현재 실행 중인 테마 정제 작업이 없습니다.");
        }
        adminAsyncService.stopEnriching();
        return ResponseEntity.ok("테마 정제 작업 중지 명령을 전송했습니다. 대기 시간이 끝나면 안전하게 중지됩니다.");
    }

    @PostMapping("/collect-places")
    @Operation(summary = "구글 API 기반 특정 도시 장소 자동 대량 수집 (자체 지명 검증 엔진 탑재)")
    public String collectNewPlaces(
            @RequestParam String city,
            @RequestParam(required = false) String keyword
    ) {
        String formalizedCity;

        try {
            formalizedCity = googleMapsService.getFormalizedJapanCity(city);
            System.out.println("[어드민 대량 적재 엔진] 입력 지명: [" + city + "] -> 정제 지명: [" + formalizedCity + "]");
        } catch (RuntimeException e) {
            System.out.println("[차단됨] 쓰레기 데이터 유입을 막기 위해 수집을 취소합니다. 원인: " + e.getMessage());
            return "오류 발생: 정확한 일본 지명을 찾을 수 없어 데이터 수집을 취소합니다.";
        }

        String[] keywordSuite = (keyword == null || keyword.trim().isEmpty())
                ? new String[]{"필수 명소", "유명 관광지", "랜드마크", "역사 유적지", "테마파크", "핫플레이스 맛집", "유명 카페", "대형 쇼핑몰"}
                : new String[]{keyword};

        int totalInserted = 0;
        int totalSkipped = 0;

        for (String kw : keywordSuite) {
            List<Place> googlePlaces = googleMapsService.searchNewPlacesFromGoogle(formalizedCity, kw, false);

            for (Place googlePlace : googlePlaces) {
                googlePlace.setCity(city);
                if (placeRepository.existsByPlaceId(googlePlace.getPlaceId())) {
                    totalSkipped++;
                    continue;
                }
                placeRepository.save(googlePlace);
                totalInserted++;
            }
        }

        return String.format("[%s] 대량 자동 수집 완료! -> 신규 명소 등록: %d건 / 기존 중복 패스: %d건", city, totalInserted, totalSkipped);
    }

    @PostMapping("/cleanse-categories")
    @Operation(summary = "기존 데이터 AI 카테고리 완전 자동 분류 (30건씩 백그라운드 처리)")
    public ResponseEntity<String> cleanseCategories() {
        if (adminAsyncService.isCleansing()) {
            return ResponseEntity.badRequest().body("이미 자동 정제 작업이 실행 중입니다.");
        }

        adminAsyncService.runCategoryCleansing(); // 비동기 호출
        return ResponseEntity.ok("자동 정제 백그라운드 작업이 시작되었습니다. 429 에러 발생 시 1분간 자동 휴식 루틴이 가동됩니다.");
    }

    @PostMapping("/cleanse-categories/stop")
    @Operation(summary = "실행 중인 AI 카테고리 자동 분류 작업 강제 중지")
    public ResponseEntity<String> stopCleansing() {
        if (!adminAsyncService.isCleansing()) {
            return ResponseEntity.ok("현재 실행 중인 자동 정제 작업이 없습니다.");
        }
        adminAsyncService.stopCleansing();
        return ResponseEntity.ok("정제 작업 중지 명령을 전송했습니다. 현재 사이클이 마무리되거나 대기 시간이 끝나면 안전하게 중지됩니다.");
    }
}