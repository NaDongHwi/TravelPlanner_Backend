package com.travel.planner.controller;

import com.travel.planner.entity.Place;
import com.travel.planner.entity.Region;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.service.AdminAsyncService;
import com.travel.planner.service.GoogleMapsService;
import com.travel.planner.service.RegionCollectService;
import com.travel.planner.util.CollectRegions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "🔒 관리자 전용 데이터 정제 API", description = "오프라인 AI 데이터 파이프라인 인리치먼트 엔진 (담당: 나동휘)")
public class AdminController {

    private final PlaceRepository placeRepository;
    private final GoogleMapsService googleMapsService;
    private final AdminAsyncService adminAsyncService; // 비동기 서비스 주입
    private final RegionCollectService regionCollectService;

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

    @PostMapping("/enrich-summaries")
    @Operation(summary = "장소 세부 유형·한 줄 소개 채우기 (백그라운드)",
            description = "소개가 비어 있는 장소를 30건씩 AI 에 보내 '세부 유형(라멘·신사·공원 등)'과 '한 줄 소개'를 채웁니다. "
                    + "기본은 DB 에 있는 정보만 사용해 구글 호출이 없습니다. withReviews=true 면 장소마다 구글 리뷰를 조회해 함께 보내 정확도를 높입니다(Places 호출 비용 발생).")
    public ResponseEntity<String> enrichSummaries(@RequestParam(defaultValue = "false") boolean withReviews) {
        if (adminAsyncService.isDescribing()) {
            return ResponseEntity.badRequest().body("이미 소개 채우기 작업이 실행 중입니다.");
        }
        adminAsyncService.runSummaryEnrichment(withReviews);
        return ResponseEntity.ok("장소 소개 채우기 백그라운드 작업이 시작되었습니다. 진행 상황은 서버 콘솔의 [소개 인리치먼트] 로그로 확인하세요.");
    }

    @PostMapping("/enrich-summaries/stop")
    @Operation(summary = "실행 중인 장소 소개 채우기 작업 중지")
    public ResponseEntity<String> stopSummaries() {
        if (!adminAsyncService.isDescribing()) {
            return ResponseEntity.ok("현재 실행 중인 소개 채우기 작업이 없습니다.");
        }
        adminAsyncService.stopDescribing();
        return ResponseEntity.ok("소개 채우기 작업 중지 명령을 전송했습니다. 현재 배치가 끝나면 중지됩니다.");
    }

    @PostMapping("/collect-places")
    @Operation(summary = "구글 API 기반 특정 도시 장소 자동 대량 수집 (Viewport 기반 동적 그리드 엔진 탑재)")
    public String collectNewPlaces(
            @RequestParam String city,
            @RequestParam(required = false) String keyword
    ) {
        String formalizedCity;

        try {
            formalizedCity = googleMapsService.getFormalizedJapanCity(city);
            System.out.println("[어드민 대량 적재 엔진] 입력 지명: [" + city + "] -> 정식 행정명: [" + formalizedCity + "]");
        } catch (RuntimeException e) {
            System.out.println("[차단됨] 쓰레기 데이터 유입을 막기 위해 수집을 취소합니다. 원인: " + e.getMessage());
            return "오류 발생: 정확한 일본 지명을 찾을 수 없어 데이터 수집을 취소합니다.";
        }

        String[] searchSuite = (keyword == null || keyword.trim().isEmpty())
                // 기본 검색 세트.
                // 이전 세트는 10개 중 7개가 식음·쇼핑이라 DB 가 그쪽으로 쏠렸고, 문화·자연·야경·서브컬쳐·액티비티 테마는
                // 후보 자체가 부족했다. 12개 테마가 고르게 채워지도록 관광 계열 검색을 늘렸다.
                ? new String[]{
                "[TYPE]tourist_attraction",
                "[TYPE]museum",
                "[TYPE]park",
                "[TYPE]amusement_park",
                "[TEXT]신사 사원",
                "[TEXT]전망대 야경",
                "[TEXT]정원 자연 명소",
                "[TEXT]체험 액티비티",
                "[TEXT]애니메이션 굿즈 성지",
                "[TEXT]온천",
                "[TYPE]restaurant",
                "[TYPE]cafe",
                "[TYPE]shopping_mall",
                "[TEXT]시장",
                "[TEXT]기념품 상점",
                "[TYPE]lodging"
        }
                : new String[]{keyword};

        int totalInserted = 0;
        int totalSkipped = 0;

        List<double[]> cityGrid = googleMapsService.getCityGrid(formalizedCity);
        System.out.println("[" + city + "] 동적 그리드(Grid) 맵핑 완료. 총 " + cityGrid.size() + "개의 그물망 투척 준비.");

        for (double[] gridNode : cityGrid) {
            for (String searchItem : searchSuite) {
                List<Place> googlePlaces = googleMapsService.searchNewPlacesFromGoogle(
                        city, formalizedCity, gridNode[0], gridNode[1], gridNode[2], searchItem, false
                );

                for (Place googlePlace : googlePlaces) {
                    if (googlePlace.getPlaceId() == null || googlePlace.getPlaceId().trim().isEmpty()) {
                        continue;
                    }
                    googlePlace.setCity(city);

                    Place existing = placeRepository.findByPlaceId(googlePlace.getPlaceId()).orElse(null);
                    if (existing != null) {
                        // 이미 있는 장소라도 예전에 수집해 비어 있는 값(구조화 영업시간·주소·평점)은 이번 응답으로 채운다.
                        // 추가 API 호출 없이 기존 DB 를 보강할 수 있다.
                        if (backfill(existing, googlePlace)) placeRepository.save(existing);
                        totalSkipped++;
                        continue;
                    }
                    placeRepository.save(googlePlace);
                    totalInserted++;
                }
            }
        }
        return String.format("[%s] 대량 자동 수집 완료! -> 신규 명소 등록: %d건 / 기존 중복 패스: %d건", city, totalInserted, totalSkipped);
    }

    /** 기존 장소에 비어 있는 필드만 새 응답 값으로 채운다. 바뀐 것이 있으면 true. (지역 수집과 같은 규칙) */
    private boolean backfill(Place existing, Place fetched) {
        return RegionCollectService.backfill(existing, fetched);
    }

    @PostMapping("/collect-region")
    @Operation(summary = "지역별 장소 수집 (기본은 미리보기, dryRun=false 로 실제 실행)",
            description = "region: KANSAI, KANTO, KYUSHU, HOKKAIDO, OKINAWA, CHUBU, CHUGOKU, SHIKOKU, TOHOKU, ALL (쉼표로 여러 개, 한국어 '간사이'도 됩니다). "
                    + "dryRun=true(기본)면 구글을 부르지 않고 수집할 도시·건너뛸 도시·예상 호출 수·예상 비용만 보여 줍니다. "
                    + "dryRun=false 면 백그라운드로 수집하고, 장소 검색 호출이 maxCalls(기본 2000번)에 닿으면 멈춥니다. 다시 실행하면 멈춘 도시부터 이어서 합니다. "
                    + "장소가 이미 넉넉한 도시(큰 도시 80곳, 작은 도시 25곳 이상)와 전에 지역 수집을 끝낸 도시는 건너뜁니다(force=true 면 다시 돕니다).")
    public ResponseEntity<?> collectRegion(
            @RequestParam String region,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "" + RegionCollectService.DEFAULT_MAX_CALLS) int maxCalls,
            @RequestParam(defaultValue = "false") boolean force
    ) {
        List<Region> regions;
        try {
            regions = CollectRegions.parse(region);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        if (maxCalls < 1) return ResponseEntity.badRequest().body("maxCalls 는 1 이상이어야 합니다.");
        if (dryRun) return ResponseEntity.ok(regionCollectService.preview(regions, maxCalls, force));
        if (regionCollectService.isRunning()) {
            return ResponseEntity.badRequest().body("이미 지역 수집이 실행 중입니다. GET /api/v1/admin/collect-region/status 로 진행 상황을 확인하세요.");
        }
        regionCollectService.run(regions, maxCalls, force);
        return ResponseEntity.ok("지역 수집을 백그라운드에서 시작했습니다 (한도 " + maxCalls + "번). "
                + "진행 상황은 GET /api/v1/admin/collect-region/status, 멈추려면 POST /api/v1/admin/collect-region/stop 을 쓰세요.");
    }

    @GetMapping("/collect-region/status")
    @Operation(summary = "지역별 장소 수집 진행 상황")
    public Map<String, Object> collectRegionStatus() {
        return regionCollectService.status();
    }

    @PostMapping("/collect-region/stop")
    @Operation(summary = "실행 중인 지역별 장소 수집 중지 (지금 검색이 끝나면 멈춥니다)")
    public ResponseEntity<String> stopCollectRegion() {
        if (!regionCollectService.isRunning()) return ResponseEntity.ok("현재 실행 중인 지역 수집이 없습니다.");
        regionCollectService.stop();
        return ResponseEntity.ok("지역 수집 중지 명령을 보냈습니다. 지금 하던 검색이 끝나면 멈춥니다. 다시 실행하면 멈춘 도시부터 이어서 합니다.");
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
